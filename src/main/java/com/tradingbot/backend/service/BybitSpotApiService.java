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
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;

@Service
public class BybitSpotApiService {
    
    private static final Logger logger = LoggerFactory.getLogger(BybitSpotApiService.class);
    
    private final BybitApiClientService bybitApiClient;
    private final ObjectMapper objectMapper;
    private final BybitApiConfig bybitApiConfig;
    private final HttpClientService httpClientService;
    
    public BybitSpotApiService(BybitApiClientService bybitApiClient,
                              BybitApiConfig bybitApiConfig,
                              HttpClientService httpClientService) {
        this.bybitApiClient = bybitApiClient;
        this.objectMapper = new ObjectMapper();
        this.bybitApiConfig = bybitApiConfig;
        this.httpClientService = httpClientService;
    }
    
    /**
     * Get server time from Bybit to synchronize timestamps
     */
    private long getServerTime() {
        try {
            String url = bybitApiConfig.getBaseUrl() + "/v5/market/time";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            ResponseEntity<String> response = httpClientService.get(url, headers, String.class, null);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                if (jsonResponse.has("result") && jsonResponse.get("result").has("timeSecond")) {
                    // Bybit returns seconds, convert to milliseconds
                    return jsonResponse.get("result").get("timeSecond").asLong() * 1000;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to get Bybit server time, using local time: {}", e.getMessage());
        }
        
        // Fallback to local time if server time request fails
        return System.currentTimeMillis();
    }
    
    /**
     * Generate Bybit API signature using HMAC-SHA256
     */
    private String generateSignature(String timestamp, String queryString, String body) {
        try {
            // Bybit signature format: timestamp + apiKey + recvWindow + queryString/body
            String payload = timestamp + bybitApiConfig.getApiKey() + 
                           bybitApiConfig.getRecvWindow() + 
                           (body != null ? body : (queryString != null ? queryString : ""));
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                bybitApiConfig.getSecretKey().getBytes(), "HmacSHA256");
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(payload.getBytes());
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
        headers.set("X-BAPI-API-KEY", bybitApiConfig.getApiKey());
        headers.set("X-BAPI-TIMESTAMP", timestamp);
        headers.set("X-BAPI-SIGN", signature);
        headers.set("X-BAPI-RECV-WINDOW", String.valueOf(bybitApiConfig.getRecvWindow()));
        return headers;
    }
    
    /**
     * Format symbol for Bybit spot API
     * Bybit spot uses BTCUSDT format
     */
    private String formatSpotSymbol(String symbol) {
        // Remove underscores if present (ETH_USDT -> ETHUSDT)
        return symbol.replace("_", "");
    }
    
    /**
     * Get available balance for spot trading
     */
    public BigDecimal getAvailableBalance() {
        try {
            ResponseEntity<String> response = bybitApiClient.getWalletBalance("UNIFIED", "USDT");
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                logger.error("Failed to get wallet balance from Bybit: {}", response.getStatusCode());
                return BigDecimal.ZERO;
            }
            
            JsonNode balanceData = objectMapper.readTree(response.getBody());
            
            if (balanceData.has("retCode") && balanceData.get("retCode").asInt() == 0) {
                if (balanceData.has("result") && balanceData.get("result").has("list")) {
                    JsonNode accountList = balanceData.get("result").get("list");
                    if (accountList.isArray() && accountList.size() > 0) {
                        JsonNode account = accountList.get(0);
                        if (account.has("coin") && account.get("coin").isArray()) {
                            JsonNode coins = account.get("coin");
                            for (JsonNode coin : coins) {
                                if (coin.has("coin") && "USDT".equals(coin.get("coin").asText())) {
                                    // Use walletBalance for unified accounts
                                    return new BigDecimal(coin.get("walletBalance").asText());
                                }
                            }
                        }
                    }
                }
                logger.warn("No USDT balance found in response: {}", response.getBody());
            } else {
                String retMsg = balanceData.has("retMsg") ? balanceData.get("retMsg").asText() : "Unknown error";
                logger.error("Error response from Bybit: {}", retMsg);
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.error("Error getting available balance from Bybit: {}", e.getMessage(), e);
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * Place a spot order
     */
    public String placeOrder(String symbol, String side, String type, BigDecimal quantity, BigDecimal price) throws JsonProcessingException {
        try {
            // For market orders, don't send price and use IOC timeInForce
            String timeInForce = "MARKET".equalsIgnoreCase(type) ? "IOC" : "GTC";
            
            // Validate quantity is positive
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Order quantity must be positive");
            }
            
            // Format quantity and price according to Bybit requirements
            BigDecimal formattedQuantity = OrderUtil.formatQuantity(quantity);
            
            // Only format price for limit orders
            BigDecimal formattedPrice = null;
            if (!"MARKET".equalsIgnoreCase(type) && price != null) {
                formattedPrice = OrderUtil.formatPrice(price);
            }
            
            ResponseEntity<String> response = bybitApiClient.placeSpotOrder(
                symbol,
                side,
                type,
                formattedQuantity.toString(),
                formattedPrice != null ? formattedPrice.toString() : null,
                timeInForce
            );
            JsonNode orderData = objectMapper.readTree(response.getBody());
            
            if (orderData.has("retCode") && orderData.get("retCode").asInt() == 0 
                && orderData.has("result") && orderData.get("result").has("orderId")) {
                return orderData.get("result").get("orderId").asText();
            }
            
            logger.error("Failed to place spot order on Bybit: {}", response.getBody());
            throw new RuntimeException("Failed to place spot order: " + response.getBody());
        } catch (JsonProcessingException e) {
            logger.error("Error parsing spot order response from Bybit: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error placing spot order on Bybit: {}", e.getMessage());
            throw new RuntimeException("Failed to place spot order", e);
        }
    }
    
    /**
     * Get order status
     */
    public String getOrderStatus(String orderId) {
        try {
            String response = bybitApiClient.getSpotOrderStatus(orderId);
            JsonNode orderData = objectMapper.readTree(response);
            
            if (orderData.has("result") && orderData.get("result").has("status")) {
                return orderData.get("result").get("status").asText();
            }
            
            logger.warn("Could not parse order status from Bybit spot response");
            return "UNKNOWN";
            
        } catch (Exception e) {
            logger.error("Error getting spot order status from Bybit: {}", e.getMessage());
            return "UNKNOWN";
        }
    }
    
    /**
     * Get order details
     */
    public String getOrderDetails(String symbol, String orderId) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("category", "spot");
            params.put("symbol", formatSpotSymbol(symbol));
            params.put("orderId", orderId);
            
            String queryString = SignatureUtil.buildQueryString(params);
            String timestamp = String.valueOf(getServerTime());
            String signature = generateSignature(timestamp, queryString, null);
            HttpHeaders headers = createAuthHeaders(signature, timestamp);
            
            String url = bybitApiConfig.getSpotApiUrl() + "/order/realtime?" + queryString;
            
            logger.debug("Getting Bybit spot order details: {}", url);
            ResponseEntity<String> response = httpClientService.get(url, headers, String.class, null);
            return response.getBody();
            
        } catch (Exception e) {
            logger.error("Error getting Bybit spot order details: {}", e.getMessage());
            throw new RuntimeException("Failed to get order details", e);
        }
    }
} 