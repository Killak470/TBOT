package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.MexcApiConfig;
import com.tradingbot.backend.service.client.HttpClientService;
import com.tradingbot.backend.service.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
public class MexcApiClientService implements ExchangeApiClientService {

    private static final Logger logger = LoggerFactory.getLogger(MexcApiClientService.class);
    private final MexcApiConfig mexcApiConfig;
    private final HttpClientService httpClientService;
    private final ObjectMapper objectMapper;
    
    // Server time synchronization cache
    private volatile long cachedServerTimeOffset = 0;
    private volatile long lastServerTimeUpdate = 0;
    private static final long SERVER_TIME_CACHE_DURATION = 300000; // 5 minutes

    public MexcApiClientService(MexcApiConfig mexcApiConfig, HttpClientService httpClientService, ObjectMapper objectMapper) {
        this.mexcApiConfig = mexcApiConfig;
        this.httpClientService = httpClientService;
        this.objectMapper = objectMapper;
    }

    // --- SPOT API V3 --- //

    private HttpHeaders createSpotV3Headers(String timestamp, String queryString) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Only add authentication headers if configured to use auth
        if (mexcApiConfig.shouldUseAuth()) {
            headers.set("X-MEXC-APIKEY", mexcApiConfig.getApiKey());
            // Generate signature and add it to the query string
            String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), timestamp, queryString);
            headers.set("X-MEXC-SIGNATURE", signature);
            headers.set("X-MEXC-TIMESTAMP", timestamp);
        }
        
        return headers;
    }

    public ResponseEntity<String> getSpotServerTime() {
        String url = mexcApiConfig.getSpotApiV3Path("/time");
        return httpClientService.get(url, new HttpHeaders(), String.class, null);
    }

    public ResponseEntity<String> getSpotExchangeInfo(String symbol) {
        String path = "/exchangeInfo";
        Map<String, String> params = new HashMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", symbol);
        }
        
        // For MEXC API authentication, we need to:
        String queryString = SignatureUtil.buildQueryString(params);
        
        // 2. Add timestamp for authentication if needed
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, String> fullParams = new HashMap<>(params);
        
        if (mexcApiConfig.shouldUseAuth()) {
            fullParams.put("timestamp", timestamp);
            
            // 3. Rebuild query string with timestamp
            String fullQueryString = SignatureUtil.buildQueryString(fullParams);
            
            // 4. Generate signature
            String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), timestamp, fullQueryString);
            
            // 5. Add signature to params
            fullParams.put("signature", signature);
            
            // 6. Build final query string with signature
            String finalQueryString = SignatureUtil.buildQueryString(fullParams);
            
            // 7. Create URL with authentication parameters
            String url = mexcApiConfig.getSpotApiV3Path(path);
            if (!finalQueryString.isEmpty()) {
                url += "?" + finalQueryString;
            }
            
            // 8. Use unauthenticated headers as signature is in URL
            return httpClientService.get(url, new HttpHeaders(), String.class, null);
        } else {
            // Public endpoint fallback (no authentication)
            String url = mexcApiConfig.getSpotApiV3Path(path);
            if (!queryString.isEmpty()) {
                url += "?" + queryString;
            }
            return httpClientService.get(url, new HttpHeaders(), String.class, null);
        }
    }

    public ResponseEntity<String> getSpotKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        String path = "/klines";
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        
        // Fix for invalid interval - Validate and convert interval to proper format
        String validInterval = validateAndFormatInterval(interval);
        params.put("interval", validInterval);
        
        if (startTime != null) params.put("startTime", String.valueOf(startTime));
        if (endTime != null) params.put("endTime", String.valueOf(endTime));
        if (limit != null) params.put("limit", String.valueOf(limit));

        // Per MEXC documentation, klines is a public endpoint that doesn't require authentication
        // https://mexcdevelop.github.io/apidocs/spot_v3_en/#kline-candlestick-data
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path) + "?" + queryString;
        
        // Create headers with appropriate content type
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Add CORS headers directly to ensure they are present
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "*");
        
        return httpClientService.get(url, headers, String.class, null);
    }
    
    /**
     * Validates and formats the interval parameter for MEXC API
     * Valid intervals: 1m, 5m, 15m, 30m, 60m, 4h, 1d, 1M
     * @param interval the input interval
     * @return a valid MEXC API interval
     */
    private String validateAndFormatInterval(String interval) {
        // Handle common interval formats and convert to MEXC expected format
        if (interval == null || interval.isEmpty()) {
            return "1d"; // Default to 1 day if not specified
        }
        
        interval = interval.toLowerCase();
        
        // According to MEXC API docs, valid intervals are:
        switch (interval) {
            case "1m":
            case "5m":
            case "15m":
            case "30m":
            case "60m":
            case "4h":
            case "1d":
            case "1w":
            case "1M":
                return interval;
            case "1h":
                return "60m";
            case "hour":
            case "h":
            case "1":
                return "60m";
            case "day":
            case "d":
            case "daily":
                return "1d";
            case "week":
            case "w":
            case "weekly":
                return "1w";
            case "month":
            case "m":
            case "monthly":
                return "1M";
            default:
                return "1d"; // Default for unrecognized values
        }
    }

    // --- FUTURES API V1 --- //

    private HttpHeaders createFuturesV1Headers(String requestBodyOrParams) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // Only add authentication headers if configured to use auth
        if (mexcApiConfig.shouldUseAuth()) {
            String timestamp = String.valueOf(System.currentTimeMillis());
            headers.set("ApiKey", mexcApiConfig.getApiKey());
            headers.set("Request-Time", timestamp);
            headers.set("Signature", SignatureUtil.generateContractV1Signature(mexcApiConfig.getSecretKey(), mexcApiConfig.getApiKey(), timestamp, requestBodyOrParams));
        }
        
        return headers;
    }

    public ResponseEntity<String> getFuturesServerTime() {
        String url = mexcApiConfig.getFuturesApiV1Path("/time");
        // Public endpoint, no auth needed
        return httpClientService.get(url, new HttpHeaders(), String.class, null);
    }

    public ResponseEntity<String> getFuturesContractDetail(String symbol) {
        String path = "/detail";
        Map<String, String> params = new HashMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            // Format the symbol correctly for futures contracts
            String formattedSymbol = formatFuturesSymbol(symbol);
            params.put("symbol", formattedSymbol);
        }
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getFuturesApiV1Path(path) + (queryString.isEmpty() ? "" : "?" + queryString);
        
        // Futures API authentication is different from Spot API
        // It uses headers for authentication rather than query parameters
        if (mexcApiConfig.shouldUseAuth()) {
            HttpHeaders headers = createFuturesV1Headers(queryString);
            return httpClientService.get(url, headers, String.class, null);
        } else {
            return httpClientService.get(url, new HttpHeaders(), String.class, null);
        }
    }

    public ResponseEntity<String> getFuturesKlines(String symbol, String interval, Long start, Long end) {
        String path = "/kline";
        Map<String, String> params = new HashMap<>();
        
        // Format the symbol correctly for futures contracts (add underscore)
        String formattedSymbol = formatFuturesSymbol(symbol);
        params.put("symbol", formattedSymbol);
        
        // Fix for invalid interval - Validate and convert interval to proper format for futures
        String validInterval = validateAndFormatFuturesInterval(interval);
        params.put("interval", validInterval);
        
        if (start != null) {
            params.put("start", String.valueOf(start));
        }
        if (end != null) {
            params.put("end", String.valueOf(end));
        }
        
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getFuturesApiV1Path(path) + "?" + queryString;
        
        // Create minimal headers to avoid potential issues
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("User-Agent", "MEXC-API-Client");
        
        // Add exponential backoff for rate limiting issues
        try {
            return httpClientService.get(url, headers, String.class, null);
        } catch (Exception e) {
            logger.warn("First attempt failed for futures klines: {}, retrying with delay", e.getMessage());
            try {
                Thread.sleep(1000); // 1 second delay
                return httpClientService.get(url, headers, String.class, null);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.error("Thread interrupted while retrying futures klines");
                throw new RuntimeException("Request interrupted", ie);
            } catch (Exception retryException) {
                logger.error("Failed to fetch futures klines after retry: {}", retryException.getMessage());
                throw retryException;
            }
        }
    }

    /**
     * Validates and formats the interval parameter for MEXC Futures API
     * Based on official MEXC Futures API documentation
     * Valid intervals: Min1, Min5, Min15, Min30, Min60, Hour4, Hour8, Day1, Week1, Month1
     * @param interval the input interval
     * @return a valid MEXC Futures API interval
     */
    private String validateAndFormatFuturesInterval(String interval) {
        if (interval == null || interval.isEmpty()) {
            return "Day1"; // Default to 1 day if not specified
        }
        
        // Normalize input
        String normalizedInterval = interval.trim().toLowerCase();
        
        // Convert common formats to MEXC Futures expected format
        switch (normalizedInterval) {
            // Direct matches from MEXC API
            case "min1":
            case "1m":
            case "1min":
                return "Min1";
            case "min5":
            case "5m":
            case "5min":
                return "Min5";
            case "min15":
            case "15m":
            case "15min":
                return "Min15";
            case "min30":
            case "30m":
            case "30min":
                return "Min30";
            case "min60":
            case "60m":
            case "1h":
            case "hour1":
            case "1hour":
                return "Min60";
            case "hour4":
            case "4h":
            case "4hour":
            case "240":
            case "240m":
                return "Hour4";
            case "hour8":
            case "8h":
            case "8hour":
                return "Hour8";
            case "day1":
            case "1d":
            case "d":
            case "day":
            case "daily":
                return "Day1";
            case "week1":
            case "1w":
            case "w":
            case "week":
            case "weekly":
                return "Week1";
            case "month1":
            case "1mon":
            case "month":
            case "monthly":
                return "Month1";
            default:
                logger.warn("Unrecognized futures interval '{}', defaulting to Day1", interval);
                return "Day1";
        }
    }

    /**
     * Formats a symbol for futures contracts by inserting an underscore between base and quote currencies
     * Example: "BTCUSDT" becomes "BTC_USDT"
     * @param symbol the input symbol
     * @return properly formatted futures contract symbol
     */
    private String formatFuturesSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return "";
        }
        
        // If already formatted with underscore, return as is
        if (symbol.contains("_")) {
            return symbol;
        }
        
        // Common quote currencies in futures markets (ordered by priority)
        String[] quoteCurrencies = {"USDT", "USDC", "USD", "BTC", "ETH", "BUSD"};
        
        for (String quote : quoteCurrencies) {
            if (symbol.endsWith(quote)) {
                String base = symbol.substring(0, symbol.length() - quote.length());
                if (!base.isEmpty()) {
                    return base + "_" + quote;
                }
            }
        }
        
        // If no known quote currency is found, try common patterns
        // Handle cases like "BTCUSD" -> "BTC_USD"
        if (symbol.length() >= 6) {
            // Try 4-letter quote currencies first (USDT, USDC, BUSD)
            if (symbol.length() >= 7) {
                String potentialQuote4 = symbol.substring(symbol.length() - 4);
                if (potentialQuote4.matches("USDT|USDC|BUSD")) {
                    String base = symbol.substring(0, symbol.length() - 4);
                    return base + "_" + potentialQuote4;
                }
            }
            
            // Try 3-letter quote currencies (USD, BTC, ETH)
            String potentialQuote3 = symbol.substring(symbol.length() - 3);
            if (potentialQuote3.matches("USD|BTC|ETH|BNB")) {
                String base = symbol.substring(0, symbol.length() - 3);
                return base + "_" + potentialQuote3;
            }
        }
        
        logger.warn("Could not format futures symbol: {}. Using as-is.", symbol);
        return symbol;
    }

    // TODO: Implement other Spot and Futures endpoints (Order placement, account info, etc.)

    // --- SPOT ORDER MANAGEMENT --- //
    
    /**
     * Places a new spot order
     * 
     * @param symbol Trading pair
     * @param side BUY or SELL
     * @param type LIMIT, MARKET, etc.
     * @param timeInForce Time in force (GTC, IOC, FOK)
     * @param quantity Order quantity
     * @param price Limit price (not used for MARKET orders)
     * @param newClientOrderId Optional client order ID
     * @return Response containing the order details
     */
    public ResponseEntity<String> placeSpotOrder(String symbol, String side, String type, 
                                                 String timeInForce, String quantity, 
                                                 String price, String newClientOrderId) {
        // This endpoint requires authentication
        if (!mexcApiConfig.shouldUseAuth()) {
            throw new IllegalStateException("API authentication is required for order placement");
        }
        
        String path = "/order";
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", type);
        
        // Add conditional parameters
        if (timeInForce != null && !timeInForce.isEmpty()) params.put("timeInForce", timeInForce);
        if (quantity != null && !quantity.isEmpty()) params.put("quantity", quantity);
        if (price != null && !price.isEmpty()) params.put("price", price);
        if (newClientOrderId != null && !newClientOrderId.isEmpty()) params.put("newClientOrderId", newClientOrderId);
        
        // Get server time first to avoid timestamp issues
        long serverTime = getServerTimeAsLong();
        String timestamp = String.valueOf(serverTime);
        params.put("timestamp", timestamp);
        
        // Build the query string
        String queryString = SignatureUtil.buildQueryString(params);
        
        // Generate signature
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), timestamp, queryString);
        
        // Add signature to params
        params.put("signature", signature);
        
        // Build final URL with query string
        String finalQueryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path) + "?" + finalQueryString;
        
        // Create headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-MEXC-APIKEY", mexcApiConfig.getApiKey());
        
        // Make the POST request
        return httpClientService.post(url, headers, "", String.class);
    }
    
    /**
     * Cancels an existing spot order
     * 
     * @param symbol The trading pair
     * @param orderId Order ID (either orderId or origClientOrderId must be provided)
     * @param origClientOrderId Client order ID
     * @return Response containing the cancellation details
     */
    public ResponseEntity<String> cancelSpotOrder(String symbol, Long orderId, String origClientOrderId) {
        // This endpoint requires authentication
        if (!mexcApiConfig.shouldUseAuth()) {
            throw new IllegalStateException("API authentication is required for order cancellation");
        }
        
        String path = "/order";
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        
        // Add either orderId or origClientOrderId
        if (orderId != null) params.put("orderId", orderId.toString());
        if (origClientOrderId != null && !origClientOrderId.isEmpty()) params.put("origClientOrderId", origClientOrderId);
        
        // Get server time first to avoid timestamp issues
        long serverTime = getServerTimeAsLong();
        String timestamp = String.valueOf(serverTime);
        params.put("timestamp", timestamp);
        
        // Build the query string
        String queryString = SignatureUtil.buildQueryString(params);
        
        // Generate signature
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), timestamp, queryString);
        
        // Add signature to params
        params.put("signature", signature);
        
        // Build final URL with query string
        String finalQueryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path) + "?" + finalQueryString;
        
        // Create headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-MEXC-APIKEY", mexcApiConfig.getApiKey());
        
        // Make the DELETE request
        return httpClientService.delete(url, headers, String.class, null);
    }
    
    /**
     * Gets current account information including balances
     * 
     * @return Response containing account information and balances
     */
    public ResponseEntity<String> getAccountInformation() {
        // This endpoint requires authentication
        if (!mexcApiConfig.shouldUseAuth()) {
            throw new IllegalStateException("API authentication is required for account information");
        }

        String path = "/account";
        Map<String, String> params = new HashMap<>();
        
        // Get server time first to avoid timestamp issues
        long serverTime = getServerTimeAsLong();
        String timestamp = String.valueOf(serverTime);
        params.put("timestamp", timestamp);
        
        // Build the query string
        String queryString = SignatureUtil.buildQueryString(params);
        
        // Generate signature
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), timestamp, queryString);
        
        // Add signature to params
        params.put("signature", signature);
        
        // Build final URL with query string
        String finalQueryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path) + "?" + finalQueryString;
        
        // Create headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-MEXC-APIKEY", mexcApiConfig.getApiKey());
        
        // Make the GET request
        return httpClientService.get(url, headers, String.class, null);
    }
    
    /**
     * Get server time to synchronize timestamps with caching
     * 
     * @return Server timestamp in milliseconds
     */
    @Override
    public ResponseEntity<String> getServerTime() {
        String url = mexcApiConfig.getBaseUrl() + "/api/v3/time";
        return httpClientService.get(url, null, String.class, null);
    }

    /**
     * Get account information as a Map for easier use in other services
     * 
     * @return Map containing account information and balances
     * @throws Exception If there's an error fetching or parsing the account information
     */
    public Map<String, Object> getAccountInfo() throws Exception {
        ResponseEntity<String> response = getAccountInformation();
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            // Parse the JSON response
            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);
            return result;
        }
        
        throw new Exception("Failed to get account information");
    }
    
    /**
     * Get all open orders for a specific symbol
     * Note: MEXC API requires a symbol parameter - it does not support getting all orders at once
     * 
     * @param symbol Trading pair (required for MEXC API)
     * @return Response containing open orders for the specified symbol
     */
    public ResponseEntity<String> getCurrentOpenOrders(String symbol) {
        // This endpoint requires authentication
        if (!mexcApiConfig.shouldUseAuth()) {
            throw new IllegalStateException("API authentication is required for open orders");
        }

        // MEXC API requires a specific symbol - it doesn't support getting all orders
        if (symbol == null || symbol.isEmpty() || "ALL".equalsIgnoreCase(symbol)) {
            logger.warn("MEXC API requires a specific symbol for open orders - cannot get all orders at once");
            throw new IllegalArgumentException("MEXC API requires a specific symbol parameter for open orders endpoint. Use a specific trading pair like 'BTCUSDT'.");
        }

        String path = "/openOrders";
        Map<String, String> params = new HashMap<>();
        
        // Add the required symbol parameter
        params.put("symbol", symbol);
        
        // Get server time first to avoid timestamp issues
        long serverTime = getServerTimeAsLong();
        String timestamp = String.valueOf(serverTime);
        params.put("timestamp", timestamp);
        
        // Build the query string
        String queryString = SignatureUtil.buildQueryString(params);
        
        // Generate signature
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), timestamp, queryString);
        
        // Add signature to params
        params.put("signature", signature);
        
        // Build final URL with query string
        String finalQueryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path) + "?" + finalQueryString;
        
        // Create headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-MEXC-APIKEY", mexcApiConfig.getApiKey());
        
        // Make the GET request
        return httpClientService.get(url, headers, String.class, null);
    }
    
    /**
     * Get latest price for a symbol from MEXC.
     * The 'exchange' parameter from the interface is used for logging/consistency but is not strictly needed for MEXC-specific client.
     * 
     * @param symbol Trading pair
     * @param exchange The exchange name (e.g., "MEXC") - used for logging or context if needed.
     * @return Response containing the latest price
     */
    @Override
    public ResponseEntity<String> getLatestPrice(String symbol, String exchange) {
        // Log if the exchange parameter is unexpected, but proceed as this is MEXC client
        if (exchange != null && !"MEXC".equalsIgnoreCase(exchange)) {
            logger.warn("getLatestPrice called on MexcApiClientService with non-MEXC exchange parameter: {}", exchange);
        }

        String path = "/ticker/price";
        Map<String, String> params = new HashMap<>();
        
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", symbol);
        }
        
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path);
        
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        return httpClientService.get(url, headers, String.class, null);
    }

    /**
     * Get current price for a symbol in a format directly usable by strategy services
     * 
     * @param symbol Trading pair
     * @return Map containing the price information
     * @throws Exception If there's an error fetching or parsing the price
     */
    public Map<String, Object> getSymbolPrice(String symbol) throws Exception {
        ResponseEntity<String> response = getLatestPrice(symbol, null);
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            // Parse the JSON response
            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);
            return result;
        }
        
        throw new Exception("Failed to get price for " + symbol);
    }

    /**
     * Get kline/candlestick data for a symbol
     * 
     * @param symbol Trading pair
     * @param interval Kline interval (e.g., "1m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d")
     * @param limit Number of entries to return (max 1000)
     * @param startTime Optional start time in milliseconds
     * @return Response containing kline data
     */
    public ResponseEntity<String> getKlineData(String symbol, String interval, Integer limit, Long startTime) {
        String path = "/klines";
        Map<String, String> params = new HashMap<>();
        
        params.put("symbol", symbol);
        params.put("interval", interval);
        
        if (limit != null) {
            params.put("limit", String.valueOf(limit));
        }
        
        if (startTime != null) {
            params.put("startTime", String.valueOf(startTime));
        }
        
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path) + "?" + queryString;
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        // This endpoint doesn't require authentication
        return httpClientService.get(url, headers, String.class, null);
    }

    /**
     * Get futures wallet balance
     */
    public String getFuturesWalletBalance(String currency) {
        Map<String, String> params = new HashMap<>();
        params.put("currency", currency);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/private/account/assets");
        HttpHeaders headers = createFuturesV1Headers(SignatureUtil.buildQueryString(params));
        
        return httpClientService.get(url, headers, String.class, params).getBody();
    }
    
    /**
     * Get instrument info
     */
    public String getInstrumentInfo(String symbol) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", formatFuturesSymbol(symbol));
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/public/contract/detail");
        HttpHeaders headers = createBasicHeaders();
        
        return httpClientService.get(url, headers, String.class, params).getBody();
    }
    
    /**
     * Set futures leverage
     */
    public String setFuturesLeverage(String symbol, String leverage) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", formatFuturesSymbol(symbol));
        params.put("leverage", leverage);
        params.put("positionSide", "BOTH"); // For one-way mode
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/private/position/change_leverage");
        HttpHeaders headers = createFuturesV1Headers(SignatureUtil.buildQueryString(params));
        
        return httpClientService.post(url, headers, params, String.class).getBody();
    }
    
    /**
     * Place a futures order
     */
    public String placeFuturesOrder(String symbol, String side, String type, String quantity,
                                  String price, String stopLoss, String takeProfit) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", formatFuturesSymbol(symbol));
        params.put("side", side);
        params.put("type", type);
        params.put("quantity", quantity);
        
        if (price != null) {
            params.put("price", price);
        }
        
        if (stopLoss != null) {
            params.put("stopLoss", stopLoss);
        }
        
        if (takeProfit != null) {
            params.put("takeProfit", takeProfit);
        }
        
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getFuturesApiV1Path("/private/order/submit");
        HttpHeaders headers = createFuturesV1Headers(SignatureUtil.buildQueryString(params));
        
        return httpClientService.post(url, headers, params, String.class).getBody();
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
     * Get available balance for futures trading
     */
    public BigDecimal getAvailableBalance() {
        try {
            String response = getFuturesWalletBalance("USDT");
            JsonNode balanceData = objectMapper.readTree(response);
            
            if (balanceData.has("data") && balanceData.get("data").has("availableBalance")) {
                return new BigDecimal(balanceData.get("data").get("availableBalance").asText());
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.error("Error getting available balance from MEXC: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Place a spot order
     */
    public String placeOrder(String symbol, String side, String type, BigDecimal quantity, BigDecimal price) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", type);
        params.put("quantity", quantity.toPlainString());
        
        if (price != null) {
            params.put("price", price.toPlainString());
        }
        
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getSpotApiPath("/order");
        HttpHeaders headers = createFuturesV1Headers(SignatureUtil.buildQueryString(params));
        
        return httpClientService.post(url, headers, params, String.class).getBody();
    }

    @Override
    public String getExchangeName() {
        return "MEXC";
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("apiKey", mexcApiConfig.getApiKey());
        config.put("baseUrl", mexcApiConfig.getBaseUrl());
        config.put("useAuth", mexcApiConfig.shouldUseAuth());
        return config;
    }

    @Override
    public void updateConfiguration(Map<String, Object> configuration) {
        if (configuration.containsKey("apiKey")) {
            mexcApiConfig.setApiKey((String) configuration.get("apiKey"));
        }
        if (configuration.containsKey("baseUrl")) {
            mexcApiConfig.setBaseUrl((String) configuration.get("baseUrl"));
        }
        if (configuration.containsKey("useAuth")) {
            mexcApiConfig.setUseAuth((Boolean) configuration.get("useAuth"));
        }
    }

    @Override
    public ResponseEntity<String> getExchangeInfo() {
        String path = "/exchangeInfo";
        Map<String, String> params = new HashMap<>();
        String queryString = SignatureUtil.buildQueryString(params);
        String url = mexcApiConfig.getSpotApiV3Path(path);
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        return httpClientService.get(url, createPublicHeaders(), String.class, null);
    }

    @Override
    public ResponseEntity<String> getPosition(String symbol) {
        String path = "/position";
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String queryString = SignatureUtil.buildQueryString(params);
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), params.get("timestamp"), queryString);
        params.put("signature", signature);
        
        String url = mexcApiConfig.getSpotApiV3Path(path);
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        
        HttpHeaders headers = createAuthHeaders();
        return httpClientService.get(url, headers, String.class, null);
    }

    private HttpHeaders createPublicHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("User-Agent", "MEXC-Trading-Bot/1.0");
        headers.add("Accept", "application/json");
        headers.add("Connection", "keep-alive");
        headers.add("Cache-Control", "no-cache");
        return headers;
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = createPublicHeaders();
        headers.set("X-MEXC-APIKEY", mexcApiConfig.getApiKey());
        return headers;
    }

    @Override
    public ResponseEntity<String> getOpenOrders(String symbol) {
        return getCurrentOpenOrders(symbol);
    }

    @Override
    public ResponseEntity<String> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        return getKlineData(symbol, interval, limit, startTime);
    }

    @Override
    public ResponseEntity<String> getAccountBalance() {
        return getAccountInformation();
    }

    @Override
    public ResponseEntity<String> placeOrder(String symbol, String side, String type, BigDecimal quantity, 
                                           BigDecimal price, String clientOrderId) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", type);
        params.put("quantity", quantity.toPlainString());
        
        if (price != null) {
            params.put("price", price.toPlainString());
        }
        
        if (clientOrderId != null && !clientOrderId.isEmpty()) {
            params.put("newClientOrderId", clientOrderId);
        }
        
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String url = mexcApiConfig.getSpotApiPath("/order");
        HttpHeaders headers = createFuturesV1Headers(SignatureUtil.buildQueryString(params));
        
        return httpClientService.post(url, headers, params, String.class);
    }

    @Override
    public ResponseEntity<String> cancelOrder(String symbol, String orderId) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", orderId);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String queryString = SignatureUtil.buildQueryString(params);
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), params.get("timestamp"), queryString);
        params.put("signature", signature);
        
        String url = mexcApiConfig.getSpotApiV3Path("/order");
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        
        HttpHeaders headers = createAuthHeaders();
        return httpClientService.delete(url, headers, String.class, null);
    }

    @Override
    public ResponseEntity<String> getOrderStatus(String symbol, String orderId) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", orderId);
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String queryString = SignatureUtil.buildQueryString(params);
        String signature = SignatureUtil.generateSpotV3Signature(mexcApiConfig.getSecretKey(), params.get("timestamp"), queryString);
        params.put("signature", signature);
        
        String url = mexcApiConfig.getSpotApiV3Path("/order");
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        
        HttpHeaders headers = createAuthHeaders();
        return httpClientService.get(url, headers, String.class, null);
    }

    // Helper method to get server time as long
    private long getServerTimeAsLong() {
        try {
            ResponseEntity<String> response = getServerTime();
            JsonNode jsonNode = objectMapper.readTree(response.getBody());
            return jsonNode.get("serverTime").asLong();
        } catch (Exception e) {
            logger.error("Error getting server time: {}", e.getMessage());
            throw new RuntimeException("Failed to get server time", e);
        }
    }

    @Override
    public ResponseEntity<String> getOrderStatus(String symbol, String orderId, String category) {
        // MEXC API for order status typically uses the orderId and symbol.
        // The 'category' parameter might not be directly applicable or needs to be mapped
        // to MEXC's concept of order types (e.g., spot, margin, futures if supported by this client method).
        // This implementation will assume it's for spot orders as per getOrderStatus(symbol, orderId).
        // If futures order status is needed, a separate method or logic to differentiate would be required.
        logger.warn("getOrderStatus with category called on MEXC. Category parameter ('{}') is not directly used in this spot-focused implementation.", category);
        return getOrderStatus(symbol, orderId); // Delegates to the existing spot order status method.
    }
}

