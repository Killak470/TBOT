package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.MexcApiConfig;
import com.tradingbot.backend.service.client.HttpClientService;
import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.util.SignatureUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class MexcFuturesApiService {

    private final MexcApiConfig mexcApiConfig;
    private final HttpClientService httpClientService;
    private final MexcApiClientService mexcApiClient;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(MexcFuturesApiService.class);
    
    // Rate limiting
    private volatile long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL = 100; // 100ms between requests

    public MexcFuturesApiService(MexcApiConfig mexcApiConfig, HttpClientService httpClientService, MexcApiClientService mexcApiClient) {
        this.mexcApiConfig = mexcApiConfig;
        this.httpClientService = httpClientService;
        this.mexcApiClient = mexcApiClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get futures server time
     */
    public ResponseEntity<String> getFuturesServerTime() {
        String url = mexcApiConfig.getFuturesApiV1Path("/ping");
        HttpHeaders headers = createBasicHeaders();
        
        return executeWithRetry(() -> httpClientService.get(url, headers, String.class, null), "getFuturesServerTime");
    }

    /**
     * Get futures contract details
     */
    public ResponseEntity<String> getFuturesContractDetail(String symbol) {
        String path = "/detail";
        Map<String, String> params = new HashMap<>();
        
        if (symbol != null && !symbol.isEmpty()) {
            String formattedSymbol = formatFuturesSymbol(symbol);
            params.put("symbol", formattedSymbol);
            logger.debug("Requesting futures contract detail for symbol: {} -> {}", symbol, formattedSymbol);
        }
        
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getFuturesApiV1Path(path) + (queryString.isEmpty() ? "" : "?" + queryString);
        
        HttpHeaders headers = mexcApiConfig.shouldUseAuth() ? 
            createAuthenticatedHeaders(queryString) : createBasicHeaders();
        
        return executeWithRetry(() -> httpClientService.get(url, headers, String.class, null), 
                              "getFuturesContractDetail for " + symbol);
    }

    /**
     * Get futures klines with improved error handling
     */
    public ResponseEntity<String> getFuturesKlines(String symbol, String interval, Long start, Long end) {
        try {
            // Format symbol and validate interval
            String formattedSymbol = formatFuturesSymbol(symbol);
            String validInterval = validateAndFormatFuturesInterval(interval);
            
            // Use the symbol in the path as per MEXC API documentation
            String path = "/kline/" + formattedSymbol;
            Map<String, String> params = new HashMap<>();
            
            params.put("interval", validInterval);
            
            if (start != null) params.put("start", String.valueOf(start));
            if (end != null) params.put("end", String.valueOf(end));
            
            logger.debug("Requesting futures klines: symbol={} -> {}, interval={} -> {}", 
                        symbol, formattedSymbol, interval, validInterval);
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = mexcApiConfig.getFuturesApiV1Path(path) + (queryString.isEmpty() ? "" : "?" + queryString);
            
            HttpHeaders headers = createBasicHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            ResponseEntity<String> response = executeWithRetry(() -> httpClientService.get(url, headers, String.class, null), 
                                  "getFuturesKlines for " + formattedSymbol);
            
            if (response == null) {
                logger.error("Null response from executeWithRetry for futures klines");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Failed to fetch futures klines data - null response\"}");
            }
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response.getBody());
            }
            
            logger.error("Non-OK status code from futures klines: {}", response.getStatusCode());
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody() != null ? response.getBody() : 
                         "{\"error\":\"Failed to fetch futures klines data - status " + response.getStatusCode() + "\"}");
                         
        } catch (Exception e) {
            logger.error("Error fetching futures klines for {}: {}", symbol, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Failed to fetch futures klines data: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Place a futures order
     */
    public ResponseEntity<String> placeFuturesOrder(String symbol, String side, String type, 
                                                  String quantity, String price) {
        // Validate required parameters
        if (symbol == null || side == null || type == null) {
            throw new IllegalArgumentException("Symbol, side, and type are required for futures orders");
        }
        
        // Format symbol for futures API (ensure underscore format)
        String futuresSymbol = formatFuturesSymbol(symbol);
        
        logger.warn("⚠️  MEXC Futures Order Endpoints Under Maintenance Since 2022-07-25");
        logger.info("📋 Manual Order Details for {}:", futuresSymbol);
        logger.info("   📊 Symbol: {}", futuresSymbol);
        logger.info("   📈 Side: {}", side.toUpperCase());
        logger.info("   🎯 Type: {}", type.toUpperCase());
        logger.info("   💰 Quantity: {}", quantity);
        logger.info("   💲 Price: {}", price != null ? price : "MARKET");
        logger.info("   🔗 Execute manually at: https://futures.mexc.com/exchange/{}?_from=contract", futuresSymbol);
        
        // Return a structured response indicating manual execution needed
        String manualOrderResponse = String.format(
            "{\"success\":false,\"code\":503,\"message\":\"MEXC Futures order endpoints under maintenance since 2022-07-25. Manual execution required.\",\"data\":{\"symbol\":\"%s\",\"side\":\"%s\",\"type\":\"%s\",\"quantity\":\"%s\",\"price\":\"%s\",\"manualTradeUrl\":\"https://futures.mexc.com/exchange/%s?_from=contract\",\"status\":\"MANUAL_EXECUTION_REQUIRED\"}}",
            futuresSymbol, side.toUpperCase(), type.toUpperCase(), 
            quantity != null ? quantity : "MARKET", 
            price != null ? price : "MARKET", 
            futuresSymbol
        );
        
        return ResponseEntity.status(503) // Service Unavailable
                .body(manualOrderResponse);
    }

    /**
     * Cancel a futures order
     */
    public ResponseEntity<String> cancelFuturesOrder(String symbol, String orderId) {
        String futuresSymbol = formatFuturesSymbol(symbol);
        
        Map<String, String> params = new HashMap<>();
        params.put("symbol", futuresSymbol);
        params.put("orderId", orderId);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/private/order/cancel");
        HttpHeaders headers = createSignedHeaders(params);
        
        return executeWithRetry(() -> httpClientService.post(url, headers, params, String.class), 
                               "cancelFuturesOrder");
    }

    /**
     * Get futures positions
     */
    public ResponseEntity<String> getFuturesPositions(String symbol) {
        Map<String, String> params = new HashMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", formatFuturesSymbol(symbol));
        }
        // This existing method fetches HISTORY positions, not suitable for live open positions.
        // Keeping it for now, but we need a new one for open positions.
        // params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        // String url = mexcApiConfig.getFuturesApiV1Path("/private/position/list/history_positions");
        // HttpHeaders headers = createSignedHeaders(params);
        // return executeWithRetry(() -> httpClientService.get(url, headers, String.class, params), 
        //                        "getFuturesPositions");
        logger.warn("getFuturesPositions(String symbol) in MexcFuturesApiService currently points to history_positions. For open positions, use getOpenFuturesPositions().");
        // Temporarily return an empty list or error to avoid misuse until fully refactored.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body("{\"error\":\"Use getOpenFuturesPositions() for active positions.\"}");
    }

    /**
     * Get all open futures positions from MEXC.
     * Endpoint: /api/v1/private/position/open_positions
     * @return ResponseEntity containing the API response.
     */
    public ResponseEntity<String> getOpenFuturesPositions() {
        String path = "/private/position/open_positions";
        // This endpoint does not take a symbol; it returns all open positions.
        // It requires authentication (timestamp + signature).
        Map<String, String> params = new HashMap<>(); 
        // No specific query parameters are usually needed for "get all open positions" type of endpoints,
        // but timestamp is needed for signature generation.
        // The createSignedHeaders method should handle adding the timestamp to the signature process.
        
        String url = mexcApiConfig.getFuturesApiV1Path(path);
        // The params map here is for the signature generation, not necessarily for the query string if the endpoint doesn't take query params.
        // createSignedHeaders should correctly build the signature based on an empty body or specific requirements.
        HttpHeaders headers = createSignedHeaders(params); // Assuming createSignedHeaders can handle empty params for GET if needed for sig.
        
        logger.debug("Requesting MEXC open futures positions from: {}", url);
        return executeWithRetry(() -> httpClientService.get(url, headers, String.class, params), // Pass params for sig, GET might not use them in URL
                               "getOpenFuturesPositions");
    }

    /**
     * Set leverage for a symbol
     */
    public boolean setLeverage(String symbol, BigDecimal leverage) {
        try {
            String response = mexcApiClient.setFuturesLeverage(symbol, leverage.toString());
            JsonNode leverageData = objectMapper.readTree(response);
            return leverageData.has("code") && leverageData.get("code").asInt() == 0;
        } catch (Exception e) {
            logger.error("Error setting leverage on MEXC for {}: {}", symbol, e.getMessage());
            return false;
        }
    }

    /**
     * Get available balance for futures trading
     */
    public BigDecimal getAvailableBalance() {
        try {
            // For futures on a UNIFIED account, we query the assets for the collateral (USDT).
            String responseBody = mexcApiClient.getFuturesWalletBalance("USDT");
            if (responseBody != null && !responseBody.isEmpty()) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode data = root.path("data");

                // MEXC /private/account/assets returns a list of assets. We need to find USDT.
                if (data.isArray()) {
                    for (JsonNode asset : data) {
                        if ("USDT".equalsIgnoreCase(asset.path("currency").asText())) {
                            if (asset.has("availableBalance")) {
                                String balanceStr = asset.path("availableBalance").asText(null);
                                if (balanceStr != null && !balanceStr.isEmpty()) {
                                    logger.info("Fetched futures available balance (USDT): {}", balanceStr);
                                    return new BigDecimal(balanceStr);
                                }
                            }
                        }
                    }
                }
                
                // Fallback to old parsing logic just in case the response structure is unexpected
                JsonNode list = root.path("data").path("list");
                if (list.isArray() && !list.isEmpty()) {
                    JsonNode accountInfo = list.get(0);
                    if (accountInfo.has("totalEquity")) {
                         String balanceStr = accountInfo.path("totalEquity").asText(null);
                         if (balanceStr != null && !balanceStr.isEmpty()){
                            logger.info("Fetched UNIFIED account totalEquity as fallback: {}", balanceStr);
                            return new BigDecimal(balanceStr);
                         }
                    }
                }
            }
             logger.warn("Could not parse available balance from MEXC futures wallet response.");
        } catch (Exception e) {
            logger.error("Error getting available balance from MEXC UNIFIED account: {}", e.getMessage(), e);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * Get maximum leverage allowed for a symbol from its contract details.
     */
    public BigDecimal getMaxLeverage(String symbol) {
        try {
            JsonNode contractDetails = getContractDetails(symbol);
            if (contractDetails != null && contractDetails.has("maxLeverage")) {
                return new BigDecimal(contractDetails.get("maxLeverage").asText());
            }
            logger.warn("Could not find 'maxLeverage' in contract details for {}. Defaulting to 1.", symbol);
            return BigDecimal.ONE;
        } catch (Exception e) {
            logger.error("Error getting max leverage from MEXC for {}: {}", symbol, e.getMessage());
            return BigDecimal.ONE;
        }
    }

    /**
     * Place a futures order
     */
    public String placeOrder(String symbol, String side, String type, BigDecimal quantity, 
                           BigDecimal price, BigDecimal stopLoss, BigDecimal takeProfit) throws JsonProcessingException {
        try {
            String response = mexcApiClient.placeFuturesOrder(
                symbol,
                side,
                type,
                quantity.toString(),
                price != null ? price.toString() : null,
                stopLoss != null ? stopLoss.toString() : null,
                takeProfit != null ? takeProfit.toString() : null
            );
            JsonNode orderData = objectMapper.readTree(response);
            
            if (orderData.has("data") && orderData.get("data").has("orderId")) {
                return orderData.get("data").get("orderId").asText();
            }
            
            logger.error("Failed to place futures order on MEXC: {}", response);
            throw new RuntimeException("Failed to place futures order");
        } catch (Exception e) {
            logger.error("Error placing futures order on MEXC: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Get current open orders
     */
    public ResponseEntity<String> getCurrentOpenOrders(String symbol) {
        Map<String, String> params = new HashMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", formatFuturesSymbol(symbol));
        }
        // Timestamp is crucial for signed private endpoints
        params.put("timestamp", String.valueOf(System.currentTimeMillis())); 
        
        // Using a more standard path for private open orders endpoint for MEXC Futures API v1
        String url = mexcApiConfig.getFuturesApiV1Path("/private/order/list/open_orders"); 
        
        HttpHeaders headers = createSignedHeaders(params);
        
        logger.debug("Fetching MEXC futures open orders. URL: {}, Params: {}", url, params);
        
        return executeWithRetry(() -> httpClientService.get(url, headers, String.class, params), 
                               "getCurrentOpenOrders for " + (symbol != null ? symbol : "all symbols"));
    }

    /**
     * Get account information
     */
    public ResponseEntity<String> getAccountInformation() {
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/private/account/info");
        HttpHeaders headers = createSignedHeaders(params);
        
        return executeWithRetry(() -> httpClientService.get(url, headers, String.class, params), 
                               "getAccountInformation");
    }

    /**
     * Get latest price for a symbol
     */
    public ResponseEntity<String> getLatestPrice(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", formatFuturesSymbol(symbol));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/market/price");
        HttpHeaders headers = createBasicHeaders();
        
        return executeWithRetry(() -> httpClientService.get(url, headers, String.class, params), 
                               "getLatestPrice");
    }

    /**
     * Creates basic headers for public endpoints
     */
    private HttpHeaders createBasicHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("User-Agent", "MEXC-Trading-Bot/1.0");
        headers.add("Accept", "application/json");
        headers.add("Connection", "keep-alive");
        headers.add("Cache-Control", "no-cache");
        return headers;
    }

    /**
     * Creates authenticated headers for private endpoints
     */
    private HttpHeaders createAuthenticatedHeaders(String requestBodyOrParams) {
        HttpHeaders headers = createBasicHeaders();
        
        if (mexcApiConfig.shouldUseAuth()) {
            String timestamp = String.valueOf(System.currentTimeMillis());
            headers.set("ApiKey", mexcApiConfig.getApiKey());
            headers.set("Request-Time", timestamp);
            headers.set("Signature", SignatureUtil.generateContractV1Signature(
                mexcApiConfig.getSecretKey(), mexcApiConfig.getApiKey(), timestamp, requestBodyOrParams));
        }
        
        return headers;
    }

    /**
     * Creates signed headers for private endpoints with parameters
     */
    private HttpHeaders createSignedHeaders(Map<String, String> params) {
        HttpHeaders headers = createBasicHeaders();
        
        if (mexcApiConfig.shouldUseAuth()) {
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(System.currentTimeMillis());
            headers.set("ApiKey", mexcApiConfig.getApiKey());
            headers.set("Request-Time", timestamp);
            headers.set("Signature", SignatureUtil.generateContractV1Signature(
                mexcApiConfig.getSecretKey(), mexcApiConfig.getApiKey(), timestamp, queryString));
        }
        
        return headers;
    }

    /**
     * Executes API call with retry logic and rate limiting
     */
    private ResponseEntity<String> executeWithRetry(ApiCall apiCall, String operation) {
        // Rate limiting
        rateLimit();
        
        int maxRetries = 3;
        long backoffMs = 1000; // Start with 1 second
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                logger.debug("Executing {} (attempt {}/{})", operation, attempt, maxRetries);
                ResponseEntity<String> response = apiCall.execute();
                
                if (response == null) {
                    logger.warn("Attempt {}/{} returned null for {}", attempt, maxRetries, operation);
                    continue;
                }
                
                return response;
                
            } catch (Exception e) {
                lastException = e;
                logger.warn("Attempt {}/{} failed for {}: {}", attempt, maxRetries, operation, e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("Interrupted during retry backoff", ie);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"error\":\"Operation interrupted: " + operation + "\"}");
                    }
                }
            }
        }
        
        logger.error("All attempts failed for {}", operation, lastException);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Failed to execute " + operation + " after " + maxRetries + " attempts: " + 
                      (lastException != null ? lastException.getMessage() : "Unknown error") + "\"}");
    }

    /**
     * Simple rate limiting
     */
    private void rateLimit() {
        long now = System.currentTimeMillis();
        long timeSinceLastRequest = now - lastRequestTime;
        
        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL - timeSinceLastRequest);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Rate limiting interrupted");
            }
        }
        
        lastRequestTime = System.currentTimeMillis();
    }

    /**
     * Validates and formats interval for MEXC Futures API
     */
    private String validateAndFormatFuturesInterval(String interval) {
        if (interval == null || interval.isEmpty()) {
            return "Day1";
        }
        
        String normalizedInterval = interval.trim().toLowerCase();
        
        switch (normalizedInterval) {
            // 1 minute
            case "min1": case "1m": case "1min": case "1": return "Min1";
            
            // 5 minutes
            case "min5": case "5m": case "5min": case "5": return "Min5";
            
            // 15 minutes
            case "min15": case "15m": case "15min": case "15": return "Min15";
            
            // 30 minutes
            case "min30": case "30m": case "30min": case "30": return "Min30";
            
            // 60 minutes / 1 hour
            case "min60": case "60m": case "1h": case "hour1": case "1hour": case "60": return "Min60";
            
            // 4 hours
            case "hour4": case "4h": case "4hour": case "240": case "240m": return "Hour4";
            
            // 8 hours
            case "hour8": case "8h": case "8hour": case "480": case "480m": return "Hour8";
            
            // 1 day
            case "day1": case "1d": case "d": case "day": case "daily": case "1440": case "1440m": return "Day1";
            
            // 1 week
            case "week1": case "1w": case "w": case "week": case "weekly": return "Week1";
            
            // 1 month
            case "month1": case "1mon": case "month": case "monthly": return "Month1";
            
            default:
                logger.warn("Unrecognized futures interval '{}', defaulting to Min15", interval);
                return "Min15";  // Changed default to Min15 for better charts
        }
    }

    /**
     * Formats symbol for futures contracts
     */
    private String formatFuturesSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return "";
        }
        
        if (symbol.contains("_")) {
            return symbol;
        }
        
        String[] quoteCurrencies = {"USDT", "USDC", "USD", "BTC", "ETH", "BUSD"};
        
        for (String quote : quoteCurrencies) {
            if (symbol.endsWith(quote)) {
                String base = symbol.substring(0, symbol.length() - quote.length());
                if (!base.isEmpty()) {
                    return base + "_" + quote;
                }
            }
        }
        
        logger.warn("Could not format futures symbol: {}. Using as-is.", symbol);
        return symbol;
    }

    /**
     * Get futures contract details for a single symbol.
     * @return JsonNode for the symbol's data, or null if not found.
     */
    public JsonNode getContractDetails(String symbol) {
        String formattedSymbol = formatFuturesSymbol(symbol);
        String path = "/detail/" + formattedSymbol;

        try {
            String url = mexcApiConfig.getFuturesApiV1Path(path);
            HttpHeaders headers = createBasicHeaders(); 
            
            ResponseEntity<String> response = executeWithRetry(
                () -> httpClientService.get(url, headers, String.class, null),
                "getContractDetails for " + formattedSymbol
            );

            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("data")) {
                    return root.get("data");
                } else {
                    logger.warn("MEXC getContractDetails response for {} did not contain 'data' field. Body: {}", formattedSymbol, response.getBody());
                }
            } else {
                 logger.error("Failed to get contract details for {} from MEXC. Status: {}, Body: {}", 
                    formattedSymbol, response != null ? response.getStatusCode() : "N/A", response != null ? response.getBody() : "null");
            }
        } catch (Exception e) {
            logger.error("Exception getting MEXC contract details for {}: {}", formattedSymbol, e.getMessage(), e);
        }
        return null;
    }

    @FunctionalInterface
    private interface ApiCall {
        ResponseEntity<String> execute() throws Exception;
    }
} 