package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.BybitApiConfig;
import com.tradingbot.backend.service.client.HttpClientService;
import com.tradingbot.backend.service.BybitApiClientService;
import com.tradingbot.backend.service.util.OrderUtil;
import com.tradingbot.backend.service.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

@Service
public class BybitFuturesApiService {
    
    private static final Logger logger = LoggerFactory.getLogger(BybitFuturesApiService.class);
    
    private final BybitApiConfig bybitApiConfig;
    private final HttpClientService httpClientService;
    private final ObjectMapper objectMapper;
    private final BybitApiClientService bybitApiClient;
    
    public BybitFuturesApiService(BybitApiConfig bybitApiConfig, 
                                 HttpClientService httpClientService,
                                 BybitApiClientService bybitApiClient) {
        this.bybitApiConfig = bybitApiConfig;
        this.httpClientService = httpClientService;
        this.objectMapper = new ObjectMapper();
        this.bybitApiClient = bybitApiClient;
    }
    
    // === AUTHENTICATION METHODS ===
    
    /**
     * Get server time from Bybit to synchronize timestamps
     */
    private long getServerTime() {
        // Use system time in milliseconds for consistency
        // This avoids issues with server time sync and signature generation
        return System.currentTimeMillis();
    }
    
    /**
     * Generate Bybit API signature using HMAC-SHA256
     */
    private String generateSignature(String timestamp, String queryString, String body) {
        try {
            // Bybit V5 signature format: timestamp + apiKey + recvWindow + (queryString or body)
            StringBuilder payload = new StringBuilder()
                .append(timestamp)
                .append(bybitApiConfig.getApiKey().trim())
                .append(bybitApiConfig.getRecvWindow());
            
            if (body != null && !body.isEmpty()) {
                payload.append(body);
            } else if (queryString != null && !queryString.isEmpty()) {
                payload.append(queryString);
            }
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                bybitApiConfig.getSecretKey().trim().getBytes(StandardCharsets.UTF_8), 
                "HmacSHA256"
            );
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).toLowerCase();
            
        } catch (Exception e) {
            logger.error("Error generating Bybit signature: {}", e.getMessage());
            throw new RuntimeException("Error generating Bybit signature", e);
        }
    }
    
    /**
     * Create authenticated headers for Bybit API
     */
    private HttpHeaders createAuthHeaders(String signature, String timestamp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-BAPI-API-KEY", bybitApiConfig.getApiKey().trim());
        headers.set("X-BAPI-TIMESTAMP", timestamp);
        headers.set("X-BAPI-SIGN", signature);
        headers.set("X-BAPI-RECV-WINDOW", String.valueOf(bybitApiConfig.getRecvWindow()));
        return headers;
    }
    
    /**
     * Create headers for public endpoints (no authentication)
     */
    private HttpHeaders createPublicHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
    
    // === FUTURES TRADING METHODS ===
    
    /**
     * Validate and format quantity based on instrument info
     */
    private String validateAndFormatQuantity(String symbol, BigDecimal quantity) throws Exception {
        // Get instrument info to check quantity rules
        JsonNode instrumentData = getInstrumentInfo(symbol);
        
        logger.debug("Instrument info for {}: {}", symbol, instrumentData.toPrettyString());
        
        if (instrumentData != null) {
            JsonNode lotSizeFilter = instrumentData.get("lotSizeFilter");
            
            if (lotSizeFilter != null) {
                String minOrderQty = lotSizeFilter.get("minOrderQty").asText();
                String maxOrderQty = lotSizeFilter.get("maxOrderQty").asText();
                String qtyStep = lotSizeFilter.get("qtyStep").asText();
                
                BigDecimal minQty = new BigDecimal(minOrderQty);
                BigDecimal maxQty = new BigDecimal(maxOrderQty);
                BigDecimal step = new BigDecimal(qtyStep);
                
                logger.info("Quantity validation for {}: min={}, max={}, step={}, requested={}", 
                           symbol, minQty, maxQty, step, quantity);
                
                // Check if quantity is less than minimum
                if (quantity.compareTo(minQty) < 0) {
                    throw new IllegalArgumentException(String.format(
                        "Order quantity %s is less than minimum %s for symbol %s",
                        quantity, minQty, symbol));
                }
                
                // Check if quantity is greater than maximum
                if (quantity.compareTo(maxQty) > 0) {
                    throw new IllegalArgumentException(String.format(
                        "Order quantity %s is greater than maximum %s for symbol %s",
                        quantity, maxQty, symbol));
                }
                
                // Round quantity to the nearest valid step
                BigDecimal[] divideAndRemainder = quantity.divideAndRemainder(step);
                if (divideAndRemainder[1].compareTo(BigDecimal.ZERO) != 0) {
                    // Round down to nearest step
                    quantity = divideAndRemainder[0].multiply(step);
                    logger.info("Adjusted quantity to {} to match step size {} for {}", 
                               quantity, step, symbol);
                }
                
                // Format with appropriate decimal places based on step size
                int scale = step.scale();
                String formattedQty = quantity.setScale(scale, RoundingMode.DOWN).toPlainString();
                logger.info("Final formatted quantity for {}: {}", symbol, formattedQty);
                return formattedQty;
            }
        }
        
        // Fallback to default formatting if instrument info not available
        logger.warn("Could not get instrument info for {}, using default formatting", symbol);
        return OrderUtil.formatFuturesQuantity(quantity).toString();
    }
    
    /**
     * Place a futures order
     */
    public String placeFuturesOrder(String symbol, String side, String type, String quantity,
                                  String price, String stopLoss, String takeProfit,
                                  String timeInForce, String orderLinkId, String triggerPrice) {
        try {
            // Convert quantity and price to BigDecimal for formatting
            BigDecimal quantityBd = new BigDecimal(quantity);
            BigDecimal priceBd = price != null ? new BigDecimal(price) : null;
            BigDecimal stopLossBd = stopLoss != null ? new BigDecimal(stopLoss) : null;
            BigDecimal takeProfitBd = takeProfit != null ? new BigDecimal(takeProfit) : null;
            
            // Validate quantity is positive
            if (quantityBd.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Order quantity must be positive");
            }
            
            // Format values according to Bybit requirements
            String formattedQuantity = validateAndFormatQuantity(symbol, quantityBd);
            
            // For market orders, don't send price and use IOC timeInForce
            // For futures, market orders are converted to IOC limit orders by Bybit
            String effectiveTimeInForce = "MARKET".equalsIgnoreCase(type) ? "IOC" : (timeInForce != null ? timeInForce : "GTC");
            String formattedPrice = null;
            if (!"MARKET".equalsIgnoreCase(type) && priceBd != null) {
                formattedPrice = OrderUtil.formatPrice(priceBd).toString();
            }
            
            String formattedStopLoss = stopLossBd != null ? OrderUtil.formatPrice(stopLossBd).toString() : null;
            String formattedTakeProfit = takeProfitBd != null ? OrderUtil.formatPrice(takeProfitBd).toString() : null;
            
            // Create order parameters
            Map<String, Object> orderParams = new HashMap<>();
            orderParams.put("category", bybitApiConfig.getCategoryForMarketType("linear"));
            orderParams.put("symbol", symbol);
            
            // Format side with proper case for Bybit - "Buy" or "Sell"
            String formattedSide;
            if ("buy".equalsIgnoreCase(side)) {
                formattedSide = "Buy";
            } else if ("sell".equalsIgnoreCase(side)) {
                formattedSide = "Sell";
            } else {
                formattedSide = side; // Use as-is if already formatted
            }
            orderParams.put("side", formattedSide);
            
            // positionIdx: 0-one-way, 1-buy side of hedge mode, 2-sell side of hedge mode
            // Required for linear and inverse contracts under Hedge Mode.
            // The error 10001 ("position idx not match position mode") when positionIdx is omitted
            // strongly suggests the account is in Hedge Mode on Bybit for this category.
            String category = orderParams.get("category").toString();
            if ("linear".equalsIgnoreCase(category) || "inverse".equalsIgnoreCase(category)) {
                if ("Buy".equalsIgnoreCase(formattedSide)) {
                    orderParams.put("positionIdx", 1); // Hedge mode Buy side
                    logger.info("[POS_IDX] Side: Buy, Category: {}. Setting positionIdx to 1 for symbol {}", category, symbol);
                } else if ("Sell".equalsIgnoreCase(formattedSide)) {
                    orderParams.put("positionIdx", 2); // Hedge mode Sell side
                    logger.info("[POS_IDX] Side: Sell, Category: {}. Setting positionIdx to 2 for symbol {}", category, symbol);
                } else {
                    // This should not happen for well-formed orders.
                    logger.error("[POS_IDX] Invalid side '{}' for setting positionIdx in Hedge Mode for symbol {}", formattedSide, symbol);
                    // If this case is reached, the order will likely fail if Bybit is in Hedge Mode,
                    // as positionIdx 0 or omitted would be incorrect.
                }
            }
            
            // Format orderType with proper case for Bybit
            String formattedOrderType;
            if ("MARKET".equalsIgnoreCase(type)) {
                formattedOrderType = "Market";
            } else if ("LIMIT".equalsIgnoreCase(type)) {
                formattedOrderType = "Limit";
            } else {
                formattedOrderType = type; // Use as-is for other types
            }
            orderParams.put("orderType", formattedOrderType);
            orderParams.put("qty", formattedQuantity);
            orderParams.put("timeInForce", effectiveTimeInForce);
            
            if (formattedPrice != null) {
                orderParams.put("price", formattedPrice);
            }
            if (formattedStopLoss != null) {
                orderParams.put("stopLoss", formattedStopLoss);
            }
            if (formattedTakeProfit != null) {
                orderParams.put("takeProfit", formattedTakeProfit);
            }
            if (orderLinkId != null) {
                orderParams.put("orderLinkId", orderLinkId);
            }
            if (triggerPrice != null) {
                orderParams.put("triggerPrice", triggerPrice);
                // When triggerPrice is set, it becomes a conditional order
                orderParams.put("orderType", "Limit");
            }
            
            String orderJson = objectMapper.writeValueAsString(orderParams);
            
            // Get server time and generate signature
            String timestamp = String.valueOf(getServerTime());
            String signature = generateSignature(timestamp, "", orderJson);
            
            // Create headers
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            // Send request
            String url = bybitApiConfig.getBaseUrl() + "/v5/order/create";
            ResponseEntity<String> response = httpClientService.post(url, headers, orderJson, String.class);
            
            logger.info("Bybit create order response for {}: {}", symbol, response.getBody());
            
            // Parse response
            JsonNode responseData = objectMapper.readTree(response.getBody());
            
            if (responseData.has("retCode") && responseData.get("retCode").asInt() == 0
                && responseData.has("result") && responseData.get("result").has("orderId")) {
                return responseData.get("result").get("orderId").asText();
            }
            
            logger.error("Failed to place futures order on Bybit: {}", response.getBody());
            throw new RuntimeException("Failed to place futures order: " + response.getBody());
            
        } catch (Exception e) {
            logger.error("Error placing futures order on Bybit: {}", e.getMessage());
            throw new RuntimeException("Failed to place futures order", e);
        }
    }
    
    /**
     * Validate order quantity limit for a symbol
     */
    private void validateOrderLimit(String symbol) throws Exception {
        ResponseEntity<String> response = getOpenFuturesOrders(symbol);
        JsonNode ordersData = objectMapper.readTree(response.getBody());
        
        if (ordersData.has("result") && ordersData.get("result").has("list")) {
            int activeOrders = ordersData.get("result").get("list").size();
            if (activeOrders >= 500) {
                throw new IllegalStateException("Maximum order limit reached for symbol " + symbol + 
                    " (500 active orders per symbol)");
            }
        }
    }
    
    /**
     * Validate price tick size based on instrument info
     */
    private void validatePriceTickSize(String symbol, BigDecimal price) throws Exception {
        // Get instrument info to check tick size
        JsonNode instrumentData = getInstrumentInfo(symbol);
        
        if (instrumentData != null) {
            JsonNode priceFilter = instrumentData.get("priceFilter");
            if (priceFilter != null) {
                String tickSize = priceFilter.get("tickSize").asText();
                BigDecimal minPriceIncrement = new BigDecimal(tickSize);
                
                // Check if price is divisible by tick size
                BigDecimal remainder = price.remainder(minPriceIncrement);
                if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                    throw new IllegalArgumentException("Price must be a multiple of tick size " + tickSize + 
                        " for symbol " + symbol);
                }
            }
        }
    }
    
    /**
     * Get futures positions
     */
    public ResponseEntity<String> getFuturesPositions(String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            
            if (symbol != null && !symbol.isEmpty()) {
                params.put("symbol", formatFuturesSymbol(symbol));
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getServerTime());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getFuturesApiUrl() + "/position/list?" + queryString;
            
            logger.debug("Bybit futures positions request: {}", url);
            return httpClientService.get(url, headers, String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit futures positions: {}", e.getMessage());
            throw new RuntimeException("Failed to get futures positions", e);
        }
    }
    
    /**
     * Get futures kline data
     */
    public ResponseEntity<String> getFuturesKlineData(String symbol, String interval, 
                                                     Integer limit, Long startTime, Long endTime) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            params.put("symbol", formatFuturesSymbol(symbol));
            params.put("interval", interval);
            
            if (limit != null) params.put("limit", String.valueOf(limit));
            if (startTime != null) params.put("start", String.valueOf(startTime));
            if (endTime != null) params.put("end", String.valueOf(endTime));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getFuturesApiUrl() + "/market/kline?" + queryString;
            
            logger.debug("Bybit futures kline request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit futures kline data for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get futures kline data", e);
        }
    }
    
    /**
     * Get futures tickers
     */
    public ResponseEntity<String> getFuturesTickers(String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            
            if (symbol != null && !symbol.isEmpty()) {
                params.put("symbol", formatFuturesSymbol(symbol));
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getFuturesApiUrl() + "/market/tickers?" + queryString;
            
            logger.debug("Bybit futures tickers request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit futures tickers for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get futures tickers", e);
        }
    }
    
    /**
     * Cancel futures order
     */
    public ResponseEntity<String> cancelFuturesOrder(String symbol, String orderId, String orderLinkId) {
        try {
            Map<String, Object> cancelData = new HashMap<>();
            cancelData.put("category", "linear");
            cancelData.put("symbol", formatFuturesSymbol(symbol));
            
            if (orderId != null) cancelData.put("orderId", orderId);
            if (orderLinkId != null) cancelData.put("orderLinkId", orderLinkId);
            
            String body = objectMapper.writeValueAsString(cancelData);
            String timestamp = String.valueOf(getServerTime());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getFuturesApiUrl() + "/order/cancel";
            
            logger.info("Cancelling Bybit futures order: {} for {}", 
                       orderId != null ? orderId : orderLinkId, symbol);
            return httpClientService.post(url, headers, body, String.class);
            
        } catch (Exception e) {
            logger.error("Error cancelling Bybit futures order: {}", e.getMessage());
            throw new RuntimeException("Failed to cancel futures order", e);
        }
    }
    
    /**
     * Get open futures orders
     */
    public ResponseEntity<String> getOpenFuturesOrders(String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            
            if (symbol != null && !symbol.isEmpty()) {
                params.put("symbol", formatFuturesSymbol(symbol));
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getServerTime());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getFuturesApiUrl() + "/order/realtime?" + queryString;
            
            logger.debug("Bybit open futures orders request: {}", url);
            return httpClientService.get(url, headers, String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit open futures orders: {}", e.getMessage());
            throw new RuntimeException("Failed to get open futures orders", e);
        }
    }
    
    /**
     * Get latest price for a symbol
     */
    public ResponseEntity<String> getLatestPrice(String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            params.put("symbol", formatFuturesSymbol(symbol));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getFuturesApiUrl() + "/market/tickers?" + queryString;
            
            logger.debug("Bybit futures latest price request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting latest price for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get latest price", e);
        }
    }
    
    /**
     * Get order details
     */
    public String getOrderDetails(String symbol, String orderId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            params.put("symbol", formatFuturesSymbol(symbol));
            params.put("orderId", orderId);
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getServerTime());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getFuturesApiUrl() + "/order/realtime?" + queryString;
            
            logger.debug("Getting Bybit futures order details: {}", url);
            ResponseEntity<String> response = httpClientService.get(url, headers, String.class, null);
            return response.getBody();
            
        } catch (Exception e) {
            logger.error("Error getting Bybit futures order details: {}", e.getMessage());
            throw new RuntimeException("Failed to get order details", e);
        }
    }
    
    // === UTILITY METHODS ===
    
    /**
     * Format symbol for Bybit futures API
     * Bybit futures uses BTCUSDT format
     */
    private String formatFuturesSymbol(String symbol) {
        // Remove underscores if present (ETH_USDT -> ETHUSDT)
        return symbol.replace("_", "");
    }
    
    /**
     * Get API configuration for external access
     */
    public BybitApiConfig getConfig() {
        return bybitApiConfig;
    }
    
    /**
     * Get available balance for futures trading on a UNIFIED account.
     */
    public BigDecimal getAvailableBalance() {
        try {
            // For futures on a UNIFIED account, we query the UNIFIED account type for the collateral (USDT).
            ResponseEntity<String> response = bybitApiClient.getWalletBalance("UNIFIED", "USDT");
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode list = root.path("result").path("list");

                if (list.isArray() && !list.isEmpty()) {
                    JsonNode accountInfo = list.get(0);
                    // For UNIFIED account, the total equity is a good representation of available "balance"
                    if (accountInfo.has("totalEquity")) {
                         String balanceStr = accountInfo.path("totalEquity").asText(null);
                         if (balanceStr != null && !balanceStr.isEmpty()){
                            logger.info("Fetched UNIFIED account totalEquity: {}", balanceStr);
                            return new BigDecimal(balanceStr);
                         }
                    }
                }
            }
             logger.warn("Could not parse UNIFIED wallet balance from Bybit response.");
        } catch (Exception e) {
            logger.error("Error getting available balance from Bybit UNIFIED account: {}", e.getMessage(), e);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * Get maximum leverage allowed for a symbol
     */
    public int getMaxLeverage(String symbol) {
        try {
            JsonNode instrumentData = getInstrumentInfo(symbol);
            if (instrumentData != null && instrumentData.has("leverageFilter")) {
                return instrumentData.get("leverageFilter").get("maxLeverage").asInt();
            }
            return 1; // Default to 1x if unable to get max leverage
        } catch (Exception e) {
            logger.error("Error getting max leverage from Bybit for {}: {}", symbol, e.getMessage());
            return 1;
        }
    }
    
    /**
     * Set leverage for a symbol, assuming hedge mode for futures.
     */
    public boolean setLeverage(String symbol, int leverage) {
        try {
            // For hedge mode, Bybit API for setting leverage doesn't require a specific tradeMode parameter,
            // as it applies to both buy/sell leverage. The position is determined by positionIdx on the order itself.
            // We pass null for tradeMode, which is acceptable.
            String response = bybitApiClient.setLeverage(symbol, String.valueOf(leverage), null);
            JsonNode leverageData = objectMapper.readTree(response);
            // Check for success code from Bybit API (retCode: 0)
            return leverageData.has("retCode") && leverageData.get("retCode").asInt() == 0;
        } catch (Exception e) {
            logger.error("Error setting leverage on Bybit for {}: {}", symbol, e.getMessage());
            return false;
        }
    }
    
    /**
     * Get instrument info for a specific futures symbol.
     * @return JsonNode for the first instrument in the list, or null if not found.
     */
    public JsonNode getInstrumentInfo(String symbol) {
        try {
            ResponseEntity<String> response = bybitApiClient.getInstrumentsInfo("linear", symbol);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode list = root.path("result").path("list");
                if (list.isArray() && !list.isEmpty()) {
                    return list.get(0);
                }
            }
             logger.warn("Could not find instrument info list in response for symbol {}", symbol);
        } catch (Exception e) {
            logger.error("Error getting and parsing instrument info for {}: {}", symbol, e.getMessage(), e);
        }
        return null;
    }
} 