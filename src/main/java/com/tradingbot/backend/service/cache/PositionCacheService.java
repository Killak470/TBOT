package com.tradingbot.backend.service.cache;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PositionCacheService {

    private static final Logger logger = LoggerFactory.getLogger(PositionCacheService.class);
    private final Map<String, PositionUpdateData> positionCache = new ConcurrentHashMap<>();

    // Static inner DTO to avoid issues with separate file and Lombok for now
    public static class PositionUpdateData {
        private String symbol;
        private String side; // "Buy" (Long), "Sell" (Short), or "None"
        private BigDecimal size;
        private BigDecimal entryPrice; // Average entry price
        private BigDecimal markPrice;
        private BigDecimal positionValue;
        private BigDecimal unrealisedPnl;
        private BigDecimal realisedPnl; // Cumulative realised PnL for this position session
        private String stopLoss; // Stop loss price as string from Bybit
        private String takeProfit; // Take profit price as string from Bybit
        private String positionStatus; // e.g., "Normal", "Liq", "Adl"
        private long updatedTimestamp; // Timestamp of the data from Bybit
        private String exchange; // Added: e.g., "BYBIT", "MEXC"
        private Integer leverage; // Added for futures trading
        private Integer positionIdx; // Added: Position index from Bybit
        private BigDecimal liqPrice; // Added for liquidation price tracking

        // Fields strategy needs to track, potentially derived or enriched
        private BigDecimal initialQuantity;
        private BigDecimal highestPriceSinceEntry;
        private BigDecimal lowestPriceSinceEntry;
        private BigDecimal strategyStopLossPrice;
        private boolean firstProfitTargetTaken;
        private boolean isSecureProfitSlApplied; // Added
        private BigDecimal initialMargin; // Added for initial margin tracking

        // Manual Constructor
        public PositionUpdateData(String symbol, String side, BigDecimal size, BigDecimal entryPrice,
                                  BigDecimal markPrice, BigDecimal positionValue, BigDecimal unrealisedPnl,
                                  BigDecimal realisedPnl, String stopLoss, String takeProfit,
                                  String positionStatus, long updatedTimestamp, String exchange,
                                  BigDecimal liqPrice) {
            this.symbol = symbol;
            this.side = side;
            this.size = size;
            this.entryPrice = entryPrice;
            this.markPrice = markPrice;
            this.positionValue = positionValue;
            this.unrealisedPnl = unrealisedPnl;
            this.realisedPnl = realisedPnl;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.positionStatus = positionStatus;
            this.updatedTimestamp = updatedTimestamp;
            this.exchange = exchange;
            this.liqPrice = liqPrice;
            // positionIdx will be set separately if available from source
            // Initialize strategy-tracked fields
            this.initialQuantity = BigDecimal.ZERO; // Will be set on first update typically
            this.firstProfitTargetTaken = false;
            this.isSecureProfitSlApplied = false; // Initialized
        }
        
        // Manual Getters & Setters (selected examples, add all as needed)
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public BigDecimal getEntryPrice() { return entryPrice; }
        public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
        public BigDecimal getSize() { return size; }
        public void setSize(BigDecimal size) { this.size = size; }
        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }
        public BigDecimal getMarkPrice() { return markPrice; }
        public void setMarkPrice(BigDecimal markPrice) { this.markPrice = markPrice; }
        public BigDecimal getPositionValue() { return positionValue; }
        public void setPositionValue(BigDecimal positionValue) { this.positionValue = positionValue; }
        public BigDecimal getUnrealisedPnl() { return unrealisedPnl; }
        public void setUnrealisedPnl(BigDecimal unrealisedPnl) { this.unrealisedPnl = unrealisedPnl; }
        public BigDecimal getRealisedPnl() { return realisedPnl; }
        public void setRealisedPnl(BigDecimal realisedPnl) { this.realisedPnl = realisedPnl; }
        public String getStopLoss() { return stopLoss; }
        public void setStopLoss(String stopLoss) { this.stopLoss = stopLoss; }
        public String getTakeProfit() { return takeProfit; }
        public void setTakeProfit(String takeProfit) { this.takeProfit = takeProfit; }
        public String getPositionStatus() { return positionStatus; }
        public void setPositionStatus(String positionStatus) { this.positionStatus = positionStatus; }
        public long getUpdatedTimestamp() { return updatedTimestamp; }
        public void setUpdatedTimestamp(long updatedTimestamp) { this.updatedTimestamp = updatedTimestamp; }
        public String getExchange() { return exchange; }
        public void setExchange(String exchange) { this.exchange = exchange; }
        public BigDecimal getInitialQuantity() { return initialQuantity; }
        public void setInitialQuantity(BigDecimal initialQuantity) { this.initialQuantity = initialQuantity; }
        public BigDecimal getHighestPriceSinceEntry() { return highestPriceSinceEntry; }
        public void setHighestPriceSinceEntry(BigDecimal highestPriceSinceEntry) { this.highestPriceSinceEntry = highestPriceSinceEntry; }
        public BigDecimal getLowestPriceSinceEntry() { return lowestPriceSinceEntry; }
        public void setLowestPriceSinceEntry(BigDecimal lowestPriceSinceEntry) { this.lowestPriceSinceEntry = lowestPriceSinceEntry; }
        public BigDecimal getStrategyStopLossPrice() { return strategyStopLossPrice; }
        public void setStrategyStopLossPrice(BigDecimal strategyStopLossPrice) { this.strategyStopLossPrice = strategyStopLossPrice; }
        public boolean isFirstProfitTargetTaken() { return firstProfitTargetTaken; }
        public void setFirstProfitTargetTaken(boolean firstProfitTargetTaken) { this.firstProfitTargetTaken = firstProfitTargetTaken; }
        
        public Integer getLeverage() { return leverage; }
        public void setLeverage(Integer leverage) { this.leverage = leverage; }
        public boolean isSecureProfitSlApplied() { return isSecureProfitSlApplied; } // Added
        public void setSecureProfitSlApplied(boolean isSecureProfitSlApplied) { this.isSecureProfitSlApplied = isSecureProfitSlApplied; } // Added
        public Integer getPositionIdx() { return positionIdx; }
        public void setPositionIdx(Integer positionIdx) { this.positionIdx = positionIdx; }
        public BigDecimal getInitialMargin() { return initialMargin; } // Added getter
        public void setInitialMargin(BigDecimal initialMargin) { this.initialMargin = initialMargin; } // Added setter
        public BigDecimal getLiqPrice() { return liqPrice; } // Added getter
        public void setLiqPrice(BigDecimal liqPrice) { this.liqPrice = liqPrice; } // Added setter
    }

    public void updatePosition(JsonNode positionData, String exchangeName) {
        String symbol = positionData.path("symbol").asText();
        if (symbol == null || symbol.isEmpty()) {
            logger.warn("Received position update without a symbol: {}", positionData.toString());
            return;
        }

        if ("TRUMPUSDT".equals(symbol)) {
            logger.info("PCS_RAW_DATA [TRUMPUSDT]: {}", positionData.toString());
        }

        // String exchangeName = "BYBIT"; // Removed hardcoding

        // Extract data from JsonNode, with defaults for safety
        String side = positionData.path("side").asText("None");
        BigDecimal size = safeGetBigDecimal(positionData, "size", "0");
        BigDecimal entryPrice = safeGetBigDecimal(positionData, "avgPrice", "0"); // Bybit uses avgPrice for entry
        BigDecimal markPrice = safeGetBigDecimal(positionData, "markPrice", "0");
        BigDecimal positionValue = safeGetBigDecimal(positionData, "positionValue", "0");
        BigDecimal unrealisedPnl = safeGetBigDecimal(positionData, "unrealisedPnl", "0"); // Restored unrealisedPnl extraction
        BigDecimal realisedPnl = safeGetBigDecimal(positionData, "curRealisedPnl", "0"); // current realised PnL
        String stopLoss = positionData.path("stopLoss").asText("");
        String takeProfit = positionData.path("takeProfit").asText("");
        String positionStatus = positionData.path("positionStatus").asText("Unknown");
        long updatedTimestamp = positionData.path("updatedTime").asLong(System.currentTimeMillis());
        Integer leverage = positionData.hasNonNull("leverage") ? positionData.path("leverage").asInt(1) : null; // Fetch leverage
        Integer positionIdx = positionData.hasNonNull("positionIdx") ? positionData.path("positionIdx").asInt() : null; // Fetch positionIdx
        BigDecimal initialMargin = safeGetBigDecimal(positionData, "positionIM", null); // Added: Bybit uses positionIM
        BigDecimal liqPrice = safeGetBigDecimal(positionData, "liqPrice", null); // Added: Extract liqPrice

        positionCache.compute(symbol, (key, existingData) -> {
            if (existingData == null) {
                // New position or first update for this symbol
                logger.info("Caching new position data for symbol: {} on exchange: {}", symbol, exchangeName);
                PositionUpdateData newData = new PositionUpdateData(symbol, side, size, entryPrice, markPrice,
                        positionValue, unrealisedPnl, realisedPnl, stopLoss, takeProfit,
                        positionStatus, updatedTimestamp, exchangeName, liqPrice);
                newData.setLeverage(leverage); // Set leverage
                newData.setPositionIdx(positionIdx); // Set positionIdx
                newData.setInitialMargin(initialMargin); // Added: Set initial margin
                newData.setLiqPrice(liqPrice); // Added: Update liqPrice
                if (size.compareTo(BigDecimal.ZERO) > 0) { // If there's an actual position size
                    newData.setInitialQuantity(size); // Set initial quantity if this is the first time we see this position with size
                    // Set initial high/low based on entry or mark price
                    if ("Buy".equalsIgnoreCase(side)) { // Long
                        newData.setHighestPriceSinceEntry(entryPrice); // Or markPrice if more appropriate
                    } else if ("Sell".equalsIgnoreCase(side)) { // Short
                        newData.setLowestPriceSinceEntry(entryPrice); // Or markPrice
                    }
                }
                return newData;
            } else {
                // Update existing position data
                logger.debug("Updating cached position data for symbol: {}", symbol);
                existingData.setSide(side);
                existingData.setSize(size);
                existingData.setExchange(exchangeName);
                existingData.setEntryPrice(entryPrice); // Bybit's avgPrice can change
                existingData.setMarkPrice(markPrice);
                existingData.setPositionValue(positionValue);
                existingData.setUnrealisedPnl(unrealisedPnl); // Corrected: set unrealisedPnl
                existingData.setRealisedPnl(realisedPnl); // Update cumulative PnL
                existingData.setStopLoss(stopLoss); // Update exchange SL/TP
                existingData.setTakeProfit(takeProfit);
                existingData.setPositionStatus(positionStatus);
                existingData.setUpdatedTimestamp(updatedTimestamp);
                existingData.setLeverage(leverage); // Update leverage
                existingData.setPositionIdx(positionIdx); // Update positionIdx
                existingData.setInitialMargin(initialMargin); // Added: Update initial margin
                existingData.setLiqPrice(liqPrice); // Added: Update liqPrice

                // Logic to update highest/lowest price since entry needs market data, 
                // but mark price can be a proxy if position just opened.
                if (size.compareTo(BigDecimal.ZERO) > 0) {
                     if (existingData.getInitialQuantity() == null || existingData.getInitialQuantity().compareTo(BigDecimal.ZERO) == 0) {
                        existingData.setInitialQuantity(size); // If it became non-zero
                    }
                    if ("Buy".equalsIgnoreCase(side)) { // Long
                        if (existingData.getHighestPriceSinceEntry() == null || markPrice.compareTo(existingData.getHighestPriceSinceEntry()) > 0) {
                            existingData.setHighestPriceSinceEntry(markPrice);
                        }
                    } else if ("Sell".equalsIgnoreCase(side)) { // Short
                        if (existingData.getLowestPriceSinceEntry() == null || markPrice.compareTo(existingData.getLowestPriceSinceEntry()) < 0) {
                            existingData.setLowestPriceSinceEntry(markPrice);
                        }
                    }
                }
                 // If size becomes zero, it might indicate a closed position. Reset some strategy fields.
                if (size.compareTo(BigDecimal.ZERO) == 0 && existingData.getSize().compareTo(BigDecimal.ZERO) > 0) {
                    logger.info("Position size for {} is now zero. Resetting strategy-specific fields.", symbol);
                    existingData.setFirstProfitTargetTaken(false); // Reset for potential new trades
                    existingData.setSecureProfitSlApplied(false); // Reset here
                    existingData.setInitialQuantity(BigDecimal.ZERO);
                    // highest/lowest can be kept for analysis or reset too
                }

                return existingData;
            }
        });
    }

    public PositionUpdateData getPosition(String symbol) {
        return positionCache.get(symbol);
    }

    public List<PositionUpdateData> getAllPositions() {
        return new ArrayList<>(positionCache.values());
    }

    // New overloaded method to accept PositionUpdateData object
    public void updatePosition(String symbol, PositionUpdateData newData) {
        positionCache.compute(symbol, (key, existingData) -> {
            if (existingData == null) {
                logger.info("Caching new position data for symbol: {} (via PositionUpdateData object)", symbol);
                // If it's new, and size is > 0, set initial fields
                if (newData.getSize() != null && newData.getSize().compareTo(BigDecimal.ZERO) > 0) {
                    newData.setInitialQuantity(newData.getSize());
                    if ("Buy".equalsIgnoreCase(newData.getSide())) {
                        newData.setHighestPriceSinceEntry(newData.getEntryPrice() != null ? newData.getEntryPrice() : newData.getMarkPrice());
                    } else if ("Sell".equalsIgnoreCase(newData.getSide())) {
                        newData.setLowestPriceSinceEntry(newData.getEntryPrice() != null ? newData.getEntryPrice() : newData.getMarkPrice());
                    }
                }
                return newData;
            } else {
                // Update existing position data
                logger.debug("Updating cached position data for symbol: {} (via PositionUpdateData object)", symbol);
                existingData.setSide(newData.getSide());
                existingData.setSize(newData.getSize());
                existingData.setExchange(newData.getExchange()); // Ensure exchange is updated
                if (newData.getEntryPrice() != null) existingData.setEntryPrice(newData.getEntryPrice());
                if (newData.getMarkPrice() != null) existingData.setMarkPrice(newData.getMarkPrice());
                if (newData.getPositionValue() != null) existingData.setPositionValue(newData.getPositionValue());
                if (newData.getUnrealisedPnl() != null) existingData.setUnrealisedPnl(newData.getUnrealisedPnl());
                if (newData.getRealisedPnl() != null) existingData.setRealisedPnl(newData.getRealisedPnl());
                if (newData.getStopLoss() != null) existingData.setStopLoss(newData.getStopLoss());
                if (newData.getTakeProfit() != null) existingData.setTakeProfit(newData.getTakeProfit());
                if (newData.getPositionStatus() != null) existingData.setPositionStatus(newData.getPositionStatus()); // Update status
                existingData.setUpdatedTimestamp(newData.getUpdatedTimestamp()); // Always update timestamp
                if (newData.getLeverage() != null) existingData.setLeverage(newData.getLeverage());
                if (newData.getPositionIdx() != null) existingData.setPositionIdx(newData.getPositionIdx()); // Set positionIdx if available in newData
                if (newData.getInitialMargin() != null) existingData.setInitialMargin(newData.getInitialMargin());
                if (newData.getLiqPrice() != null) existingData.setLiqPrice(newData.getLiqPrice()); // Added

                // Logic to update highest/lowest price
                if (newData.getSize() != null && newData.getSize().compareTo(BigDecimal.ZERO) > 0) {
                    if (existingData.getInitialQuantity() == null || existingData.getInitialQuantity().compareTo(BigDecimal.ZERO) == 0) {
                        existingData.setInitialQuantity(newData.getSize()); // If it became non-zero
                    }
                    // Use markPrice from newData if available for high/low, otherwise entryPrice
                    BigDecimal priceToCompare = newData.getMarkPrice() != null ? newData.getMarkPrice() : newData.getEntryPrice();

                    if (priceToCompare != null) { // Only update if we have a valid price
                        if ("Buy".equalsIgnoreCase(existingData.getSide())) { // Long
                            if (existingData.getHighestPriceSinceEntry() == null || priceToCompare.compareTo(existingData.getHighestPriceSinceEntry()) > 0) {
                                existingData.setHighestPriceSinceEntry(priceToCompare);
                            }
                        } else if ("Sell".equalsIgnoreCase(existingData.getSide())) { // Short
                            if (existingData.getLowestPriceSinceEntry() == null || priceToCompare.compareTo(existingData.getLowestPriceSinceEntry()) < 0) {
                                existingData.setLowestPriceSinceEntry(priceToCompare);
                            }
                        }
                    }
                }
                // If size becomes zero, it might indicate a closed position. Reset some strategy fields.
                // This check should compare newData's size to existingData's size to detect transition to zero.
                if (newData.getSize() != null && newData.getSize().compareTo(BigDecimal.ZERO) == 0 &&
                    existingData.getSize() != null && existingData.getSize().compareTo(BigDecimal.ZERO) > 0) {
                    logger.info("Position size for {} is now zero (via PositionUpdateData object). Resetting strategy-specific fields.", symbol);
                    existingData.setFirstProfitTargetTaken(false);
                    existingData.setSecureProfitSlApplied(false);
                    existingData.setInitialQuantity(BigDecimal.ZERO);
                    // Consider if HighestPriceSinceEntry/LowestPriceSinceEntry should be reset or kept for analysis
                }
                return existingData;
            }
        });
    }

    // Helper to safely parse BigDecimal from JsonNode
    private BigDecimal safeGetBigDecimal(JsonNode node, String fieldName, String defaultValue) {
        JsonNode fieldNode = node.path(fieldName);
        if (fieldNode.isMissingNode() || fieldNode.isNull() || fieldNode.asText().isEmpty()) {
            return new BigDecimal(defaultValue);
        }
        try {
            return new BigDecimal(fieldNode.asText());
        } catch (NumberFormatException e) {
            logger.warn("Could not parse BigDecimal for field '{}' with value '{}'. Using default value '{}'.", fieldName, fieldNode.asText(), defaultValue, e);
            return new BigDecimal(defaultValue);
        }
    }
    
    // Method for strategy to update its specific fields, like firstProfitTargetTaken
    public void updateStrategyPositionInfo(String symbol, BigDecimal strategyStopLoss, boolean firstProfitTargetTaken) {
        positionCache.computeIfPresent(symbol, (key, data) -> {
            if (strategyStopLoss != null) { // Only update if provided
                data.setStrategyStopLossPrice(strategyStopLoss);
            }
            data.setFirstProfitTargetTaken(firstProfitTargetTaken);
            logger.debug("Updated strategy-specific info for {}: SL={}, PT1Taken={}", 
                symbol, data.getStrategyStopLossPrice(), firstProfitTargetTaken);
            return data;
        });
    }

    // Overloaded method to include isSecureProfitSlApplied
    public void updateStrategyPositionInfo(String symbol, BigDecimal strategyStopLoss, boolean firstProfitTargetTaken, boolean isSecureProfitSlApplied) {
        PositionUpdateData position = positionCache.get(symbol);
        if (position != null) {
            position.setStrategyStopLossPrice(strategyStopLoss);
            position.setFirstProfitTargetTaken(firstProfitTargetTaken);
            position.setSecureProfitSlApplied(isSecureProfitSlApplied); // Set the new flag
            logger.debug("Updated strategy info for {}: SL={}, PT1Taken={}, SecureSLApplied={}", symbol, strategyStopLoss, firstProfitTargetTaken, isSecureProfitSlApplied);
        } else {
            logger.warn("Attempted to update strategy info (with secure SL flag) for non-existent position: {}", symbol);
        }
    }

    public void removePosition(String symbol) {
        PositionUpdateData removed = positionCache.remove(symbol);
        if (removed != null) {
            logger.info("Removed position from cache: {}", symbol);
        } else {
            logger.warn("Attempted to remove non-existent position from cache: {}", symbol);
        }
    }
} 