package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.BybitApiConfig;
import com.tradingbot.backend.service.client.HttpClientService;
import com.tradingbot.backend.service.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

import java.math.BigDecimal;

@Service
public class BybitApiClientService implements ExchangeApiClientService {
    
    private static final Logger logger = LoggerFactory.getLogger(BybitApiClientService.class);
    
    private final BybitApiConfig bybitApiConfig;
    private final HttpClientService httpClientService;
    private final ObjectMapper objectMapper;
    
    private static final long SERVER_TIME_CACHE_DURATION = 10000; // 10 seconds
    private Long cachedServerTime = null;
    private long lastServerTimeUpdate = 0;
    
    public BybitApiClientService(BybitApiConfig bybitApiConfig, 
                                HttpClientService httpClientService,
                                ObjectMapper objectMapper) {
        this.bybitApiConfig = bybitApiConfig;
        this.httpClientService = httpClientService;
        this.objectMapper = objectMapper;
    }

    /**
     * Get current timestamp in milliseconds for Bybit API
     */
    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("apiKey", bybitApiConfig.getApiKey());
        config.put("baseUrl", bybitApiConfig.getBaseUrl());
        config.put("recvWindow", bybitApiConfig.getRecvWindow());
        return config;
    }

    @Override
    public void updateConfiguration(Map<String, Object> configuration) {
        if (configuration.containsKey("apiKey")) {
            bybitApiConfig.setApiKey((String) configuration.get("apiKey"));
        }
        if (configuration.containsKey("baseUrl")) {
            bybitApiConfig.setBaseUrl((String) configuration.get("baseUrl"));
        }
        if (configuration.containsKey("recvWindow")) {
            bybitApiConfig.setRecvWindow((Integer) configuration.get("recvWindow"));
        }
    }
    
    @Override
    public ResponseEntity<String> getServerTime() {
        String url = bybitApiConfig.getBaseUrl() + "/v5/market/time";
        return httpClientService.get(url, createPublicHeaders(), String.class, null);
    }
    
    // === AUTHENTICATION METHODS ===
    
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
        
        // Log headers for debugging
        if (logger.isDebugEnabled()) {
            logger.debug("Request Headers: API-KEY={}, TIMESTAMP={}, RECV-WINDOW={}", 
                headers.get("X-BAPI-API-KEY"),
                headers.get("X-BAPI-TIMESTAMP"),
                headers.get("X-BAPI-RECV-WINDOW")
            );
        }
        
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
    
    /**
     * Create authenticated headers with URL and params (for simplified API calls)
     */
    private HttpHeaders createAuthHeaders(String url, Map<String, String> params) {
        try {
            String timestamp = String.valueOf(getCurrentTimestamp());
            String queryString = SignatureUtil.buildQueryString(params);
            String body = objectMapper.writeValueAsString(params);
            String signature = generateSignature(timestamp, queryString, body);
            return createAuthHeaders(signature, timestamp);
        } catch (Exception e) {
            logger.error("Error creating auth headers: {}", e.getMessage());
            throw new RuntimeException("Failed to create auth headers", e);
        }
    }
    
    // === MARKET DATA METHODS ===
    
    /**
     * Get kline/candlestick data
     * Reference: https://bybit-exchange.github.io/docs/v5/market/kline
     */
    public ResponseEntity<String> getKlineData(String symbol, String interval,
                                             Integer limit, Long startTime, Long endTime, String category) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", category != null ? category : "spot");
            params.put("symbol", symbol);
            params.put("interval", convertIntervalToBybitFormat(interval));
            
            if (limit != null) params.put("limit", String.valueOf(limit));
            if (startTime != null) params.put("start", String.valueOf(startTime));
            if (endTime != null) params.put("end", String.valueOf(endTime));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getBaseUrl() + "/v5/market/kline?" + queryString;
            
            logger.debug("Bybit kline request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit kline data for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get kline data", e);
        }
    }
    
    /**
     * Get mark price kline data
     * Reference: https://bybit-exchange.github.io/docs/v5/market/mark-kline
     */
    public ResponseEntity<String> getMarkPriceKline(String symbol, String interval, 
                                                   Integer limit, Long startTime, Long endTime) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear"); // Mark price is only for derivatives
            params.put("symbol", symbol);
            params.put("interval", interval);
            
            if (limit != null) params.put("limit", String.valueOf(limit));
            if (startTime != null) params.put("start", String.valueOf(startTime));
            if (endTime != null) params.put("end", String.valueOf(endTime));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getSpotApiUrl() + "/market/mark-price-kline?" + queryString;
            
            logger.debug("Bybit mark price kline request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit mark price kline for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get mark price kline", e);
        }
    }
    
    /**
     * Get index price kline data
     * Reference: https://bybit-exchange.github.io/docs/v5/market/index-kline
     */
    public ResponseEntity<String> getIndexPriceKline(String symbol, String interval, 
                                                    Integer limit, Long startTime, Long endTime) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear"); // Index price is only for derivatives
            params.put("symbol", symbol);
            params.put("interval", interval);
            
            if (limit != null) params.put("limit", String.valueOf(limit));
            if (startTime != null) params.put("start", String.valueOf(startTime));
            if (endTime != null) params.put("end", String.valueOf(endTime));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getSpotApiUrl() + "/market/index-price-kline?" + queryString;
            
            logger.debug("Bybit index price kline request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit index price kline for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get index price kline", e);
        }
    }
    
    /**
     * Get premium index price kline data
     * Reference: https://bybit-exchange.github.io/docs/v5/market/premium-index-kline
     */
    public ResponseEntity<String> getPremiumIndexPriceKline(String symbol, String interval, 
                                                           Integer limit, Long startTime, Long endTime) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear"); // Premium index price is only for derivatives
            params.put("symbol", symbol);
            params.put("interval", interval);
            
            if (limit != null) params.put("limit", String.valueOf(limit));
            if (startTime != null) params.put("start", String.valueOf(startTime));
            if (endTime != null) params.put("end", String.valueOf(endTime));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getSpotApiUrl() + "/market/premium-index-price-kline?" + queryString;
            
            logger.debug("Bybit premium index price kline request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit premium index price kline for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get premium index price kline", e);
        }
    }
    
    /**
     * Get instruments info (trading rules, symbol details)
     * Reference: https://bybit-exchange.github.io/docs/v5/market/instrument
     * @param category Category type. "spot", "linear", "inverse", "option"
     * @param symbol Symbol name. Optional. If not passed, return all symbols under category.
     * @return API response
     */
    public ResponseEntity<String> getInstrumentsInfo(String category, String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            if (category == null || category.isEmpty()) {
                throw new IllegalArgumentException("Category is required for getting instruments info.");
            }
            params.put("category", category);
            
            if (symbol != null && !symbol.isEmpty()) {
                params.put("symbol", symbol);
            }
            // Other params like 'status' (Trading) or 'limit' could be added if needed.
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getBaseUrl() + "/v5/market/instruments-info?" + queryString;
            
            logger.debug("Bybit instruments info request: {}", url);
            // This is a public endpoint, so no authentication headers are needed if createPublicHeaders() handles that.
            // Otherwise, adjust if auth is actually required by your HttpClientService setup for all calls.
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit instruments info for category {}{}: {}", 
                         category, (symbol != null ? ", symbol " + symbol : ""), e.getMessage(), e);
            throw new RuntimeException("Failed to get instruments info", e);
        }
    }
    
    /**
     * Get orderbook data
     * Reference: https://bybit-exchange.github.io/docs/v5/market/orderbook
     */
    public ResponseEntity<String> getOrderbook(String symbol, String category, Integer limit) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", category != null ? category : "spot");
            params.put("symbol", symbol);
            if (limit != null) {
                params.put("limit", limit.toString());
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getBaseUrl() + "/v5/market/orderbook?" + queryString;
            
            logger.debug("Bybit orderbook request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit orderbook for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get orderbook", e);
        }
    }
    
    /**
     * Get tickers data
     * Reference: https://bybit-exchange.github.io/docs/v5/market/tickers
     */
    public ResponseEntity<String> getTickers(String symbol, String category) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", category != null ? category : "spot");
            
            if (symbol != null && !symbol.isEmpty()) {
                params.put("symbol", symbol);
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getSpotApiUrl() + "/market/tickers?" + queryString;
            
            logger.debug("Bybit tickers request: {}", url);
            return httpClientService.get(url, createPublicHeaders(), String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit tickers for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to get tickers", e);
        }
    }
    
    /**
     * Get latest price for a symbol (alias for getTickers)
     */
    public ResponseEntity<String> getLatestPrice(String symbol) {
        String url = bybitApiConfig.getBaseUrl() + "/v5/market/tickers?category=spot&symbol=" + symbol;
        return httpClientService.get(url, createPublicHeaders(), String.class, null);
    }
    
    // === ORDER MANAGEMENT METHODS ===
    
    /**
     * Create an order
     * Reference: https://bybit-exchange.github.io/docs/v5/order/create-order
     * @param category "spot", "linear" (for USDT perpetuals), "inverse"
     * @param stopLoss Optional: Stop loss price
     * @param takeProfit Optional: Take profit price
     * @param tpslMode Optional: "Full" or "Partial" (for TP/SL). For Sniper, likely "Full" on initial order.
     * @param slTriggerBy Optional: "LastPrice", "IndexPrice", "MarkPrice"
     * @param tpTriggerBy Optional: "LastPrice", "IndexPrice", "MarkPrice"
     */
    public ResponseEntity<String> createOrder(String category, String symbol, String side, 
                                            String orderType, String quantity, String price, 
                                            String timeInForce, String orderLinkId,
                                            String stopLoss, String takeProfit, String tpslMode,
                                            String slTriggerBy, String tpTriggerBy) {
        try {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("category", category);
            orderData.put("symbol", symbol);
            orderData.put("side", side);
            orderData.put("orderType", orderType.toUpperCase());
            orderData.put("qty", quantity);
            
            if (timeInForce != null && !timeInForce.isEmpty()) {
                orderData.put("timeInForce", timeInForce);
            } else {
                orderData.put("timeInForce", "GTC"); // Good-Till-Cancelled is a common default
            }
            
            if (price != null && !"MARKET".equals(orderType.toUpperCase())) {
                orderData.put("price", price);
            }
            
            if (orderLinkId != null && !orderLinkId.isEmpty()) {
                orderData.put("orderLinkId", orderLinkId);
            }

            // Add TP/SL parameters if provided (primarily for linear/inverse futures)
            if (("linear".equalsIgnoreCase(category) || "inverse".equalsIgnoreCase(category))) {
                if (takeProfit != null && !takeProfit.isEmpty()) {
                    orderData.put("takeProfit", takeProfit);
                }
                if (stopLoss != null && !stopLoss.isEmpty()) {
                    orderData.put("stopLoss", stopLoss);
                }
                if (tpslMode != null && !tpslMode.isEmpty()) { // e.g., "Full"
                    orderData.put("tpslMode", tpslMode);
                }
                if (tpTriggerBy != null && !tpTriggerBy.isEmpty()) { // e.g., "MarkPrice", "LastPrice"
                    orderData.put("tpTriggerBy", tpTriggerBy);
                }
                if (slTriggerBy != null && !slTriggerBy.isEmpty()) { // e.g., "MarkPrice", "LastPrice"
                    orderData.put("slTriggerBy", slTriggerBy);
                }
            }
            
            // positionIdx: 0-one-way, 1-buy side of hedge mode, 2-sell side of hedge mode
            // Required for linear and inverse contracts under Hedge Mode.
            // The error 10001 ("position idx not match position mode") when positionIdx is omitted
            // strongly suggests the account is in Hedge Mode on Bybit for this category.
            if ("linear".equalsIgnoreCase(category) || "inverse".equalsIgnoreCase(category)) {
                if ("Buy".equalsIgnoreCase(side)) {
                    orderData.put("positionIdx", 1); // Hedge mode Buy side
                    logger.info("[POS_IDX_API_CLIENT] Side: Buy, Category: {}. Setting positionIdx to 1 for symbol {}", category, symbol);
                } else if ("Sell".equalsIgnoreCase(side)) {
                    orderData.put("positionIdx", 2); // Hedge mode Sell side
                    logger.info("[POS_IDX_API_CLIENT] Side: Sell, Category: {}. Setting positionIdx to 2 for symbol {}", category, symbol);
                } else {
                    // This case should ideally not be reached for standard orders.
                    logger.error("[POS_IDX_API_CLIENT] Invalid side '{}' for setting positionIdx in Hedge Mode for symbol {}. Order will likely fail if Bybit is in Hedge Mode.", side, symbol);
                }
            }
            
            String body = objectMapper.writeValueAsString(orderData);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            // Use v5 endpoint for consistency
            String url = bybitApiConfig.getBaseUrl() + "/v5/order/create";
            
            logger.info("Creating Bybit order: Category={}, Symbol={}, Side={}, Type={}, Qty={}, Price={}, SL={}, TP={}, Body={}", 
                       category, symbol, side, orderType, quantity, price != null ? price : "MARKET", stopLoss, takeProfit, body);
            ResponseEntity<String> response = httpClientService.post(url, headers, body, String.class);
            logger.info("Bybit create order response for {}: {}", symbol, response.getBody());
            return response;
            
        } catch (Exception e) {
            logger.error("Error creating Bybit order for {}: {}", symbol, e.getMessage(), e);
            throw new RuntimeException("Failed to create order for " + symbol, e);
        }
    }

    // Overload for existing spot order calls that don't have TP/SL at creation
    public ResponseEntity<String> placeSpotOrder(String symbol, String side, String orderType,
                                               String quantity, String price, String timeInForce) {
        return createOrder("spot", symbol, side, orderType, quantity, price, timeInForce, null, null, null, null, null, null);
    }
    
    /**
     * Amend an order
     * Reference: https://bybit-exchange.github.io/docs/v5/order/amend-order
     */
    public ResponseEntity<String> amendOrder(String category, String symbol, String orderId, 
                                           String orderLinkId, String quantity, String price) {
        try {
            Map<String, Object> amendData = new HashMap<>();
            amendData.put("category", category);
            amendData.put("symbol", symbol);
            
            if (orderId != null) amendData.put("orderId", orderId);
            if (orderLinkId != null) amendData.put("orderLinkId", orderLinkId);
            if (quantity != null) amendData.put("qty", quantity);
            if (price != null) amendData.put("price", price);
            
            String body = objectMapper.writeValueAsString(amendData);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getSpotApiUrl() + "/order/amend";
            
            logger.info("Amending Bybit order: {} for {}", orderId != null ? orderId : orderLinkId, symbol);
            return httpClientService.post(url, headers, body, String.class);
            
        } catch (Exception e) {
            logger.error("Error amending Bybit order: {}", e.getMessage());
            throw new RuntimeException("Failed to amend order", e);
        }
    }
    
    /**
     * Cancel an order
     * Reference: https://bybit-exchange.github.io/docs/v5/order/cancel-order
     */
    public ResponseEntity<String> cancelOrder(String category, String symbol, String orderId, String orderLinkId) {
        try {
            Map<String, Object> cancelData = new HashMap<>();
            cancelData.put("category", category);
            cancelData.put("symbol", symbol);
            
            if (orderId != null) cancelData.put("orderId", orderId);
            if (orderLinkId != null) cancelData.put("orderLinkId", orderLinkId);
            
            String body = objectMapper.writeValueAsString(cancelData);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getSpotApiUrl() + "/order/cancel";
            
            logger.info("Cancelling Bybit order: {} for {}", orderId != null ? orderId : orderLinkId, symbol);
            return httpClientService.post(url, headers, body, String.class);
            
        } catch (Exception e) {
            logger.error("Error cancelling Bybit order: {}", e.getMessage());
            throw new RuntimeException("Failed to cancel order", e);
        }
    }
    
    /**
     * Cancel spot order (convenience method)
     */
    public ResponseEntity<String> cancelSpotOrder(String symbol, String orderId) {
        return cancelOrder("spot", symbol, orderId, null);
    }
    
    /**
     * Get open orders
     */
    public ResponseEntity<String> getCurrentOpenOrders(String symbol, String category) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", category != null ? category : "spot");
            
            if (symbol != null && !symbol.isEmpty()) {
                params.put("symbol", symbol);
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getSpotApiUrl() + "/order/realtime?" + queryString;
            
            logger.debug("Bybit open orders request: {}", url);
            return httpClientService.get(url, headers, String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit open orders: {}", e.getMessage());
            throw new RuntimeException("Failed to get open orders", e);
        }
    }
    
    /**
     * Get open orders for spot (convenience method)
     */
    public ResponseEntity<String> getCurrentOpenOrders(String symbol) {
        return getCurrentOpenOrders(symbol, "spot");
    }
    
    // === ACCOUNT METHODS ===
    
    /**
     * Get account information
     * Reference: https://bybit-exchange.github.io/docs/v5/account/account-info
     */
    public ResponseEntity<String> getAccountInfo() {
        try {
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/account/info";
            
            logger.debug("Bybit account info request: {}", url);
            return httpClientService.get(url, headers, String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit account info: {}", e.getMessage());
            throw new RuntimeException("Failed to get account info", e);
        }
    }

    /**
     * Upgrade account to UTA2.0 Pro
     * Reference: https://bybit-exchange.github.io/docs/v5/account/upgrade-unified-account
     */
    public ResponseEntity<String> upgradeToUnifiedAccount() {
        try {
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/account/upgrade-unified-account";
            
            logger.debug("Bybit upgrade account request: {}", url);
            return httpClientService.post(url, headers, String.class, null);
            
        } catch (Exception e) {
            logger.error("Error upgrading Bybit account: {}", e.getMessage());
            throw new RuntimeException("Failed to upgrade Bybit account", e);
        }
    }

    /**
     * Get wallet balance for a specific coin and account type
     */
    public ResponseEntity<String> getWalletBalance(String accountType, String coin) {
        try {
            String timestamp = String.valueOf(getCurrentTimestamp());
            Map<String, String> params = new HashMap<>();
            params.put("accountType", accountType);
            if (coin != null) {
                params.put("coin", coin);
            }
            
            String queryString = SignatureUtil.buildQueryString(params);
            String signature = generateSignature(timestamp, queryString, null);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-BAPI-API-KEY", bybitApiConfig.getApiKey().trim());
            headers.set("X-BAPI-TIMESTAMP", timestamp);
            headers.set("X-BAPI-SIGN", signature);
            headers.set("X-BAPI-RECV-WINDOW", String.valueOf(bybitApiConfig.getRecvWindow()));
            
            logger.debug("Request Headers: API-KEY=[{}], TIMESTAMP=[{}], RECV-WINDOW=[{}]", 
                bybitApiConfig.getApiKey().substring(0, 15) + "...", timestamp, bybitApiConfig.getRecvWindow());
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/account/wallet-balance" + (queryString.isEmpty() ? "" : "?" + queryString);
            logger.debug("Bybit wallet balance request: {}", url);
            
            return httpClientService.get(url, headers, String.class, null);
        } catch (Exception e) {
            logger.error("Error getting wallet balance: {}", e.getMessage());
            throw new RuntimeException("Failed to get wallet balance", e);
        }
    }
    
    // === UTILITY METHODS ===
    
    /**
     * Format symbol for Bybit API
     * Bybit uses BTCUSDT format for both spot and futures
     */
    public String formatSymbolForSpot(String symbol) {
        // Remove underscores if present (ETH_USDT -> ETHUSDT)
        return symbol.replace("_", "");
    }
    
    /**
     * Format symbol for futures
     */
    public String formatSymbolForFutures(String symbol) {
        // Bybit futures also uses BTCUSDT format
        return formatSymbolForSpot(symbol);
    }
    
    /**
     * Convert interval format to Bybit-compatible format
     * Bybit V5 API uses: 1,3,5,15,30,60,120,240,360,720,D,W,M
     */
    private String convertIntervalToBybitFormat(String interval) {
        if (interval == null) return "60"; // Default to 1 hour
        
        String lowerInterval = interval.toLowerCase(); // Use toLowerCase for consistent case handling in switch

        switch (lowerInterval) {
            case "1m":
                // This case is hit if the original interval was "1m" or "1M" (both lowercase to "1m")
                if (interval.equals("1M")) { // Check the original interval string for "1M"
                    return "M"; // Bybit V5 API uses 'M' for Month
                }
                return "1"; // Otherwise, it's 1 minute
            case "3m": return "3";
            case "5m": return "5";
            case "15m": return "15";
            case "30m": return "30";
            case "1h": return "60";
            case "2h": return "120";
            case "4h": return "240";
            case "6h": return "360";
            case "12h": return "720";
            case "d":             // Handles original "D" or "d"
            case "1d":            // Handles original "1D" or "1d"
                return "D";     // Bybit V5 API uses 'D' for Day
            case "w":             // Handles original "W" or "w"
            case "1w":            // Handles original "1W" or "1w"
                return "W";     // Bybit V5 API uses 'W' for Week
            case "m":             // Handles original "M" or "m" (Month)
                return "M";     // Bybit V5 API uses 'M' for Month
            default: 
                logger.warn("Unknown interval format: {}, using 1 hour as default for Bybit API.", interval);
                return "60"; // Default to 1 hour (60 minutes) if format is unrecognized
        }
    }
    
    /**
     * Get API configuration for external access
     */
    public BybitApiConfig getConfig() {
        return bybitApiConfig;
    }

    /**
     * Get current public IP address using a public IP API
     * @return ResponseEntity containing the IP address information
     */
    public ResponseEntity<String> getPublicIp() {
        try {
            String url = "https://api.ipify.org?format=json";
            HttpHeaders headers = createPublicHeaders();
            
            logger.debug("Getting public IP address");
            return httpClientService.get(url, headers, String.class, null);
            
        } catch (Exception e) {
            logger.error("Error getting public IP: {}", e.getMessage());
            throw new RuntimeException("Failed to get public IP", e);
        }
    }
    
    /**
     * Get spot wallet balance
     */
    public String getSpotWalletBalance(String coin) {
        try {
            ResponseEntity<String> response = getWalletBalance("UNIFIED", coin);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw new RuntimeException("Failed to get spot wallet balance: " + response.getStatusCode());
        } catch (Exception e) {
            logger.error("Error getting spot wallet balance: {}", e.getMessage());
            throw new RuntimeException("Failed to get spot wallet balance", e);
        }
    }
    
    /**
     * Get futures wallet balance
     */
    public String getFuturesWalletBalance(String coin) {
        try {
            ResponseEntity<String> response = getWalletBalance("CONTRACT", coin);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            throw new RuntimeException("Failed to get futures wallet balance: " + response.getStatusCode());
        } catch (Exception e) {
            logger.error("Error getting futures wallet balance: {}", e.getMessage());
            throw new RuntimeException("Failed to get futures wallet balance", e);
        }
    }
    
    /**
     * Get instrument info (including leverage settings)
     */
    public String getInstrumentInfo(String symbol) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "linear");
            params.put("symbol", formatSymbolForFutures(symbol));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String url = bybitApiConfig.getSpotApiUrl() + "/market/instruments-info?" + queryString;
            
            logger.debug("Bybit instrument info request: {}", url);
            ResponseEntity<String> response = httpClientService.get(url, createPublicHeaders(), String.class, null);
            return response.getBody();
        } catch (Exception e) {
            logger.error("Error getting instrument info: {}", e.getMessage());
            throw new RuntimeException("Failed to get instrument info", e);
        }
    }
    
    /**
     * Get spot order status
     */
    public String getSpotOrderStatus(String orderId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "spot");
            params.put("orderId", orderId);
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getSpotApiUrl() + "/order/realtime?" + queryString;
            ResponseEntity<String> response = httpClientService.get(url, headers, String.class, null);
            return response.getBody();
        } catch (Exception e) {
            logger.error("Error getting spot order status: {}", e.getMessage());
            throw new RuntimeException("Failed to get spot order status", e);
        }
    }
    
    /**
     * Set leverage for a symbol
     * @param symbol The trading pair (e.g., BTCUSDT for linear perpetuals)
     * @param leverage The leverage value (e.g., "10" for 10x)
     * @param tradeMode Optional: 0 for Cross Margin, 1 for Isolated Margin (for linear category)
     * @return API response body as String
     */
    public String setLeverage(String symbol, String leverage, Integer tradeMode) {
        try {
            Map<String, Object> leverageData = new HashMap<>();
            leverageData.put("category", "linear"); // Sniper strategy is for futures, assuming linear
            leverageData.put("symbol", formatSymbolForFutures(symbol));
            leverageData.put("buyLeverage", leverage);
            leverageData.put("sellLeverage", leverage);

            if (tradeMode != null) {
                leverageData.put("tradeMode", tradeMode); // 0 for cross, 1 for isolated
            }
            
            String body = objectMapper.writeValueAsString(leverageData);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getSpotApiUrl() + "/position/set-leverage"; // Ensure this path is correct for v5 futures
            // The path might be bybitApiConfig.getBaseUrl() + "/v5/position/set-leverage"
            // Assuming getSpotApiUrl() is actually the base for v5 private APIs or is configured appropriately.
            // Let's change to getBaseUrl() for consistency with other v5 calls like getOrderbook or getKlineData
            url = bybitApiConfig.getBaseUrl() + "/v5/position/set-leverage";

            logger.info("Setting Bybit leverage: Symbol={}, Leverage={}, TradeMode={}, Body={}", symbol, leverage, tradeMode, body);
            ResponseEntity<String> response = httpClientService.post(url, headers, body, String.class);
            logger.info("Set leverage response for {}: {}", symbol, response.getBody());
            return response.getBody();
        } catch (Exception e) {
            logger.error("Error setting Bybit leverage for {}: {}", symbol, e.getMessage(), e);
            throw new RuntimeException("Failed to set leverage for " + symbol, e);
        }
    }

    // Overload for existing calls that don't specify tradeMode (defaults to exchange default)
    public String setLeverage(String symbol, String leverage) {
        return setLeverage(symbol, leverage, null);
    }

    /**
     * Get position information
     * Reference: https://bybit-exchange.github.io/docs/v5/position
     */
    public ResponseEntity<String> getPositionInfo(String category, String symbol, String baseCoin, String settleCoin, Integer limit) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", category);
            
            if (symbol != null) params.put("symbol", symbol);
            if (baseCoin != null) params.put("baseCoin", baseCoin);
            if (settleCoin != null) params.put("settleCoin", settleCoin);
            if (limit != null) params.put("limit", String.valueOf(limit));
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/position/list?" + queryString;
            
            logger.debug("Getting position info: {}", url);
            return httpClientService.get(url, headers, String.class, null);
        } catch (Exception e) {
            logger.error("Error getting position info: {}", e.getMessage());
            throw new RuntimeException("Failed to get position info", e);
        }
    }

    /**
     * Set trading stop (Take Profit / Stop Loss)
     * Reference: https://bybit-exchange.github.io/docs/v5/position/trading-stop
     */
    public ResponseEntity<String> setTradingStop(String category, String symbol, String takeProfit, 
                                               String stopLoss, String trailingStop, String tpTriggerBy, 
                                               String slTriggerBy, String activePrice, String tpSize, 
                                               String slSize, Integer positionIdx) {
        try {
            Map<String, Object> stopData = new HashMap<>();
            stopData.put("category", category);
            stopData.put("symbol", symbol);
            
            if (takeProfit != null) stopData.put("takeProfit", takeProfit);
            if (stopLoss != null) stopData.put("stopLoss", stopLoss);
            if (trailingStop != null) stopData.put("trailingStop", trailingStop);
            if (tpTriggerBy != null) stopData.put("tpTriggerBy", tpTriggerBy);
            if (slTriggerBy != null) stopData.put("slTriggerBy", slTriggerBy);
            if (activePrice != null) stopData.put("activePrice", activePrice);
            if (tpSize != null) stopData.put("tpSize", tpSize);
            if (slSize != null) stopData.put("slSize", slSize);
            if (positionIdx != null) stopData.put("positionIdx", positionIdx);
            
            String body = objectMapper.writeValueAsString(stopData);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/position/trading-stop";
            
            logger.debug("Setting trading stop: {}", url);
            return httpClientService.post(url, headers, body, String.class);
        } catch (Exception e) {
            logger.error("Error setting trading stop: {}", e.getMessage());
            throw new RuntimeException("Failed to set trading stop", e);
        }
    }

    /**
     * Set auto-add margin
     * Reference: https://bybit-exchange.github.io/docs/v5/position/auto-add-margin
     */
    public ResponseEntity<String> setAutoAddMargin(String category, String symbol, Boolean autoAddMargin, Integer positionIdx) {
        try {
            Map<String, Object> marginData = new HashMap<>();
            marginData.put("category", category);
            marginData.put("symbol", symbol);
            marginData.put("autoAddMargin", autoAddMargin);
            if (positionIdx != null) marginData.put("positionIdx", positionIdx);
            
            String body = objectMapper.writeValueAsString(marginData);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, "", body);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/position/set-auto-add-margin";
            
            logger.debug("Setting auto-add margin: {}", url);
            return httpClientService.post(url, headers, body, String.class);
        } catch (Exception e) {
            logger.error("Error setting auto-add margin: {}", e.getMessage());
            throw new RuntimeException("Failed to set auto-add margin", e);
        }
    }

    /**
     * Get all wallet balances
     * Reference: https://bybit-exchange.github.io/docs/v5/asset/all-balance
     */
    public ResponseEntity<String> getAllWalletBalances(String accountType, String coin) {
        try {
            Map<String, String> params = new HashMap<>();
            if (accountType != null) params.put("accountType", accountType);
            if (coin != null) params.put("coin", coin);
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/account/wallet-balance" + 
                (queryString.isEmpty() ? "" : "?" + queryString);
            
            logger.debug("Getting all wallet balances: {}", url);
            return httpClientService.get(url, headers, String.class, null);
        } catch (Exception e) {
            logger.error("Error getting all wallet balances: {}", e.getMessage());
            throw new RuntimeException("Failed to get all wallet balances", e);
        }
    }

    @Override
    public String getExchangeName() {
        return "BYBIT";
    }

    @Override
    public ResponseEntity<String> getExchangeInfo() {
        String url = bybitApiConfig.getBaseUrl() + "/v5/market/instruments-info";
        Map<String, String> params = new HashMap<>();
        params.put("category", "spot");
        String queryString = SignatureUtil.buildQueryString(params);
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        return httpClientService.get(url, createPublicHeaders(), String.class, null);
    }

    @Override
    public ResponseEntity<String> getPosition(String symbol) {
        String url = bybitApiConfig.getBaseUrl() + "/v5/position/list";
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("category", "spot");
        String queryString = SignatureUtil.buildQueryString(params);
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        return httpClientService.get(url, createPublicHeaders(), String.class, null);
    }

    @Override
    public ResponseEntity<String> getOpenOrders(String symbol) {
        String url = bybitApiConfig.getBaseUrl() + "/v5/order/realtime";
        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("category", "spot");
        String queryString = SignatureUtil.buildQueryString(params);
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }
        return httpClientService.get(url, createPublicHeaders(), String.class, null);
    }

    @Override
    public ResponseEntity<String> placeOrder(String symbol, String side, String type, BigDecimal quantity, 
                                           BigDecimal price, String clientOrderId) {
        try {
            // Use createOrder method with proper category
            String orderType = type.toUpperCase();
            String priceStr = price != null ? price.toPlainString() : null;
            String qtyStr = quantity.toPlainString();
            
            // Default to spot trading
            return createOrder("spot", symbol, side, orderType, qtyStr, priceStr, "GTC", clientOrderId, null, null, null, null, null);
        } catch (Exception e) {
            logger.error("Error placing order: {}", e.getMessage());
            throw new RuntimeException("Failed to place order", e);
        }
    }

    @Override
    public ResponseEntity<String> getOrderStatus(String symbol, String orderId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "spot");
            params.put("symbol", symbol);
            params.put("orderId", orderId);
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getCurrentTimestamp());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getBaseUrl() + "/v5/order/realtime?" + queryString;
            
            logger.debug("Getting order status: {}", url);
            return httpClientService.get(url, headers, String.class, null);
        } catch (Exception e) {
            logger.error("Error getting order status: {}", e.getMessage());
            throw new RuntimeException("Failed to get order status", e);
        }
    }

    @Override
    public ResponseEntity<String> cancelOrder(String symbol, String orderId) {
        try {
            String url = bybitApiConfig.getBaseUrl() + "/v5/order/cancel";
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", orderId);
            
            HttpHeaders headers = createAuthHeaders(url, params);
            return httpClientService.post(url, headers, params, String.class);
        } catch (Exception e) {
            logger.error("Error cancelling order: {}", e.getMessage());
            throw new RuntimeException("Failed to cancel order", e);
        }
    }

    @Override
    public ResponseEntity<String> getAccountBalance() {
        String url = bybitApiConfig.getBaseUrl() + "/v5/account/wallet-balance";
        Map<String, String> params = new HashMap<>();
        params.put("accountType", "UNIFIED"); // Or SPOT, CONTRACT as needed

        HttpHeaders headers = createAuthHeaders(url, params);
        return httpClientService.get(url, headers, String.class, params);
    }

    @Override
    public ResponseEntity<String> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        // This is a placeholder implementation. Actual implementation would call Bybit's kline endpoint.
        // For Bybit V5, it's GET /v5/market/kline
        // Parameters: category (spot, linear, inverse), symbol, interval, start, end, limit
        logger.warn("getKlines called on BybitApiClientService but not fully implemented for all details. Using generic path from getKlineData.");
        return getKlineData(symbol, interval, limit, startTime, endTime, "spot"); // Default to spot, needs category context
    }

    /**
     * Overloaded method for convenience, matching the call from MarketDataService.
     */
    public ResponseEntity<String> getKlines(String symbol, String interval, String category, int limit) {
        return getKlineData(symbol, interval, limit, null, null, category);
    }

    /**
     * Get order status/history by orderId or orderLinkId.
     * Uses /v5/order/history endpoint.
     * Reference: https://bybit-exchange.github.io/docs/v5/order/order-history
     */
    @Override
    public ResponseEntity<String> getOrderStatus(String symbol, String orderId, String category) {
        if (category == null || category.isEmpty()) {
            // Attempt to infer category if not provided, or default/error
            // This is crucial as the endpoint requires it.
            // For simplicity, let's assume "linear" or "spot" based on symbol for now, but this is risky.
            category = isLikelyPerpetualSymbol(symbol) ? "linear" : "spot";
            logger.warn("Category not provided for getOrderStatus, inferred as {} for symbol {}", category, symbol);
        }

        Map<String, String> params = new HashMap<>();
        params.put("category", category);
        // params.put("symbol", symbol); // Endpoint docs say symbol is optional if orderId or orderLinkId is used.
        if (orderId != null && !orderId.isEmpty()) {
            params.put("orderId", orderId);
        } else {
            // Potentially add orderLinkId if orderId is not available, though the interface specifies orderId
            logger.error("getOrderStatus called without a valid orderId for symbol {}", symbol);
            // Return an error or an empty successful response to avoid breaking flow, depending on desired strictness.
            // For now, let it proceed, Bybit will error if orderId is missing and required.
            return ResponseEntity.badRequest().body("{\"error\":\"orderId is required for getOrderStatus\"}");
        }
        // limit can be added if multiple orders with same ID (not typical) or for general history query
        // params.put("limit", "1"); 

        String queryString = SignatureUtil.buildQueryString(params);
        String url = bybitApiConfig.getBaseUrl() + "/v5/order/history?" + queryString;
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = generateSignature(timestamp, queryString, ""); // GET request, no body for signature

        HttpHeaders headers = createAuthHeaders(signature, timestamp);
        logger.debug("Bybit getOrderStatus request: URL={}, Params={}", url, params);
        return httpClientService.get(url, headers, String.class, params);
    }

    // Made public to be accessible from OrderManagementService for category inference
    public boolean isLikelyPerpetualSymbol(String symbol) {
        // Common perpetuals often don't have specific suffixes other than USDT/USDC
        // and are high-volume pairs.
        // This is a very basic check and might need refinement.
        return symbol.endsWith("USDT") || symbol.endsWith("USDC");
    }

    @Override
    public ResponseEntity<String> getLatestPrice(String symbol, String exchange) {
        // The 'exchange' parameter is ignored here as this service is Bybit-specific.
        return getLatestPrice(symbol);
    }

    /**
     * Parses the wallet balance response and returns the available balance for the primary coin (e.g., USDT).
     * This is a convenience method for services that need a direct BigDecimal value.
     * @return The available balance as a BigDecimal, or BigDecimal.ZERO if not found or on error.
     */
    public BigDecimal getAvailableBalance() {
        try {
            // We assume the primary balance coin is USDT for spot trading. This could be made configurable.
            ResponseEntity<String> response = getWalletBalance("UNIFIED", "USDT");
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode list = root.path("result").path("list");
                if (list.isArray() && !list.isEmpty()) {
                    JsonNode accountInfo = list.get(0);
                    JsonNode coinList = accountInfo.path("coin");
                    if (coinList.isArray()) {
                        for (JsonNode coinNode : coinList) {
                            if ("USDT".equals(coinNode.path("coin").asText())) {
                                String availableBalanceStr = coinNode.path("availableToWithdraw").asText(null);
                                if (availableBalanceStr != null) {
                                    return new BigDecimal(availableBalanceStr);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing available balance from Bybit response: {}", e.getMessage(), e);
        }
        // Return zero as a safe default if balance can't be fetched or parsed
        return BigDecimal.ZERO;
    }
} 