package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class BybitPositionService {
    
    private static final Logger logger = LoggerFactory.getLogger(BybitPositionService.class);
    
    private final BybitApiClientService bybitApiClient;
    private final ObjectMapper objectMapper;
    
    public BybitPositionService(BybitApiClientService bybitApiClient) {
        this.bybitApiClient = bybitApiClient;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Get all open positions for a category
     */
    public List<Position> getOpenPositions(String category) {
        logger.info("Fetching open Bybit positions for category: {}", category);
        try {
            String symbolParam = null;
            String baseCoinParam = null;
            String settleCoinParam = null;
            Integer limitParam = 200; // Default limit

            if ("linear".equalsIgnoreCase(category)) {
                settleCoinParam = "USDT"; // For linear, specify USDT as settleCoin to get all USDT-margined positions
                logger.info("For linear category, using settleCoin: USDT");
            } else if ("spot".equalsIgnoreCase(category)){
                // For spot, the API endpoint used by getPositionInfo might not be suitable,
                // as per previous error "category only support linear or option".
                // This call will likely fail or return an error from Bybit.
                // Consider using a different endpoint or logic for spot positions if needed.
                logger.warn("Fetching 'spot' positions via /v5/position/list. This may not be supported by Bybit or may require different parameters.");
            } else if ("option".equalsIgnoreCase(category)){
                 // For option, no specific symbol or settleCoin might be needed by default to list all, 
                 // but check Bybit docs if issues arise.
            }

            ResponseEntity<String> responseEntity = bybitApiClient.getPositionInfo(category, symbolParam, baseCoinParam, settleCoinParam, limitParam);
            String responseBody = responseEntity.getBody();
            logger.debug("Raw Bybit position response for category {}: {}", category, responseBody);

            if (responseEntity.getStatusCode().is2xxSuccessful() && responseBody != null) {
                JsonNode rootNode = objectMapper.readTree(responseBody);
                if (rootNode.path("retCode").asInt() == 0) {
                    JsonNode listNode = rootNode.path("result").path("list");
                    if (listNode.isArray() && listNode.size() > 0) {
                        List<Position> positions = new ArrayList<>();
                        for (JsonNode positionNode : listNode) {
                            BigDecimal size = positionNode.hasNonNull("size") ? new BigDecimal(positionNode.get("size").asText("0")) : BigDecimal.ZERO;
                            if (size.compareTo(BigDecimal.ZERO) > 0) {
                                Position position = parsePositionFromJson(positionNode, category);
                                if (position != null) {
                                    positions.add(position);
                                }
                            } else {
                                logger.trace("Skipping position for symbol {} in category {} due to zero size.", positionNode.path("symbol").asText(), category);
                            }
                        }
                        logger.info("Successfully fetched {} open Bybit positions for category: {}", positions.size(), category);
                        return positions;
                    } else {
                        logger.info("No open Bybit positions found or list is empty for category: {}. Response listNode: {}", category, listNode.toString());
                        return Collections.emptyList();
                    }
                } else {
                    logger.error("Error from Bybit API when fetching positions for category {}. retCode: {}, retMsg: {}",
                            category, rootNode.path("retCode").asInt(), rootNode.path("retMsg").asText());
                    return Collections.emptyList();
                }
            } else {
                logger.error("Failed to fetch Bybit positions for category {}. Status: {}, Body: {}",
                        category, responseEntity.getStatusCode(), responseBody);
                return Collections.emptyList();
            }
        } catch (HttpClientErrorException e) {
            logger.error("HttpClientErrorException fetching Bybit positions for category {}: {} - Response: {}", category, e.getStatusCode(), e.getResponseBodyAsString(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Exception fetching Bybit positions for category {}: {}", category, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    private Position parsePositionFromJson(JsonNode node, String category) {
        if (node.has("symbol") && node.has("side") && node.has("size") && node.has("avgPrice") && node.has("leverage") && node.has("unrealisedPnl")) {
            Position.PositionBuilder builder = Position.builder()
                .symbol(node.get("symbol").asText())
                .side(node.get("side").asText())
                .size(new BigDecimal(node.get("size").asText()))
                .entryPrice(new BigDecimal(node.get("avgPrice").asText()))
                .leverage(new BigDecimal(node.get("leverage").asText()))
                .unrealizedPnl(new BigDecimal(node.get("unrealisedPnl").asText()));

            if (node.hasNonNull("markPrice")) {
                builder.markPrice(node.get("markPrice").asText());
            }
            if (node.hasNonNull("liqPrice")) {
                builder.liqPrice(node.get("liqPrice").asText());
            }
            if (node.hasNonNull("positionValue")) {
                builder.positionValue(node.get("positionValue").asText());
            }
            if (node.hasNonNull("positionIdx")) {
                builder.positionIdx(node.get("positionIdx").asInt());
            }
            
            return builder.build();
        }
        return null;
    }
    
    /**
     * Set take profit and stop loss for a position
     */
    public boolean setTpSl(String category, String symbol, BigDecimal takeProfit, BigDecimal stopLoss) {
        try {
            ResponseEntity<String> response = bybitApiClient.setTradingStop(
                category,
                symbol,
                takeProfit != null ? takeProfit.toString() : null,
                stopLoss != null ? stopLoss.toString() : null,
                null, // trailingStop
                "MarkPrice", // tpTriggerBy
                "MarkPrice", // slTriggerBy
                null, // activePrice
                null, // tpSize
                null, // slSize
                0 // positionIdx for one-way mode
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                return root.has("retCode") && root.get("retCode").asInt() == 0;
            }
            return false;
        } catch (Exception e) {
            logger.error("Error setting TP/SL for {}: {}", symbol, e.getMessage());
            return false;
        }
    }
    
    /**
     * Enable auto-add margin for a position
     */
    public boolean enableAutoAddMargin(String category, String symbol) {
        try {
            ResponseEntity<String> response = bybitApiClient.setAutoAddMargin(
                category,
                symbol,
                true,
                0 // positionIdx for one-way mode
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                return root.has("retCode") && root.get("retCode").asInt() == 0;
            }
            return false;
        } catch (Exception e) {
            logger.error("Error enabling auto-add margin for {}: {}", symbol, e.getMessage());
            return false;
        }
    }
    
    /**
     * Position data model
     */
    @lombok.Builder
    @lombok.Data
    public static class Position {
        private String symbol;
        private String side;
        private BigDecimal size;
        private BigDecimal entryPrice;
        private BigDecimal leverage;
        private BigDecimal unrealizedPnl;
        private String markPrice;
        private String liqPrice;
        private String positionValue;
        private Integer positionIdx;

        public String getMarkPrice() {
            return markPrice;
        }

        public void setMarkPrice(String markPrice) {
            this.markPrice = markPrice;
        }
        
        public String getLiqPrice() {
            return liqPrice;
        }

        public void setLiqPrice(String liqPrice) {
            this.liqPrice = liqPrice;
        }

        public String getPositionValue() {
            return positionValue;
        }

        public void setPositionValue(String positionValue) {
            this.positionValue = positionValue;
        }
    }
} 