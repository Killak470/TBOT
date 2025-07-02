package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class EnhancedSLTPService {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedSLTPService.class);
    
    @Autowired
    private PositionRepository positionRepository;
    
    @Autowired
    private BybitApiClientService bybitApiClientService;
    
    @Autowired
    private MexcApiClientService mexcApiClientService;
    
    public static class SLTPConfig {
        private double stopLoss;
        private double takeProfit;
        private boolean isScalpTrade;
        private String strategy;
        
        public SLTPConfig(double stopLoss, double takeProfit, boolean isScalpTrade, String strategy) {
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.isScalpTrade = isScalpTrade;
            this.strategy = strategy;
        }
        
        // Getters
        public double getStopLoss() { return stopLoss; }
        public double getTakeProfit() { return takeProfit; }
        public boolean isScalpTrade() { return isScalpTrade; }
        public String getStrategy() { return strategy; }
    }
    
    public void applySignalSpecificSLTP(Position position, BotSignal signal) {
        try {
            logger.info("Applying signal-specific SL/TP for Position {} using Signal {}", 
                position.getId(), signal.getId());
            
            SLTPConfig config = calculateSLTPFromSignal(signal, position);
            
            if (config.getStopLoss() > 0 && config.getTakeProfit() > 0) {
                boolean success = placeSLTPOrders(position, config);
                
                if (success) {
                    // Update position tracking
                    position.setSltpApplied(true);
                    position.setLastSltpCheck(LocalDateTime.now());
                    positionRepository.save(position);
                    
                    logger.info("Successfully applied SL/TP for Position {}: SL={}, TP={}, Strategy={}", 
                        position.getId(), config.getStopLoss(), config.getTakeProfit(), config.getStrategy());
                } else {
                    logger.warn("Failed to place SL/TP orders for Position {}", position.getId());
                }
            } else {
                logger.warn("Invalid SL/TP values for Position {}: SL={}, TP={}", 
                    position.getId(), config.getStopLoss(), config.getTakeProfit());
            }
            
        } catch (Exception e) {
            logger.error("Error applying signal-specific SL/TP for Position {}: {}", 
                position.getId(), e.getMessage(), e);
        }
    }
    
    private SLTPConfig calculateSLTPFromSignal(BotSignal signal, Position position) {
        try {
            double entryPrice = position.getEntryPrice();
            String side = position.getSide();
            boolean isScalpTrade = isScalpTrade(signal);
            
            double stopLoss = 0.0;
            double takeProfit = 0.0;
            
            // Use signal's SL/TP if available
            if (signal.getStopLoss() != null && signal.getStopLoss() > 0) {
                stopLoss = signal.getStopLoss();
            }
            
            if (signal.getTakeProfit() != null && signal.getTakeProfit() > 0) {
                takeProfit = signal.getTakeProfit();
            }
            
            // If signal doesn't have SL/TP, calculate based on signal characteristics
            if (stopLoss == 0.0 || takeProfit == 0.0) {
                if (isScalpTrade) {
                    // Scalp trade: tight SL/TP
                    double slPercentage = 0.5; // 0.5% stop loss
                    double tpPercentage = 0.8; // 0.8% take profit
                    
                    if ("BUY".equals(side)) {
                        stopLoss = stopLoss == 0.0 ? entryPrice * (1 - slPercentage / 100) : stopLoss;
                        takeProfit = takeProfit == 0.0 ? entryPrice * (1 + tpPercentage / 100) : takeProfit;
                    } else {
                        stopLoss = stopLoss == 0.0 ? entryPrice * (1 + slPercentage / 100) : stopLoss;
                        takeProfit = takeProfit == 0.0 ? entryPrice * (1 - tpPercentage / 100) : takeProfit;
                    }
                } else {
                    // Regular trade: wider SL/TP
                    double slPercentage = 2.0; // 2% stop loss
                    double tpPercentage = 4.0; // 4% take profit
                    
                    if ("BUY".equals(side)) {
                        stopLoss = stopLoss == 0.0 ? entryPrice * (1 - slPercentage / 100) : stopLoss;
                        takeProfit = takeProfit == 0.0 ? entryPrice * (1 + tpPercentage / 100) : takeProfit;
                    } else {
                        stopLoss = stopLoss == 0.0 ? entryPrice * (1 + slPercentage / 100) : stopLoss;
                        takeProfit = takeProfit == 0.0 ? entryPrice * (1 - tpPercentage / 100) : takeProfit;
                    }
                }
            }
            
            return new SLTPConfig(stopLoss, takeProfit, isScalpTrade, signal.getStrategy());
            
        } catch (Exception e) {
            logger.error("Error calculating SL/TP from signal: {}", e.getMessage());
            return new SLTPConfig(0.0, 0.0, false, "UNKNOWN");
        }
    }
    
    private boolean placeSLTPOrders(Position position, SLTPConfig config) {
        try {
            String exchange = position.getExchange();
            String symbol = position.getSymbol();
            String side = position.getSide();
            double quantity = Math.abs(position.getSize());
            
            boolean stopLossSuccess = false;
            boolean takeProfitSuccess = false;
            
            // Place stop loss order
            if (config.getStopLoss() > 0) {
                String stopLossOrderId = generateOrderLinkId("SL", position.getId());
                
                if ("BYBIT".equals(exchange)) {
                    stopLossSuccess = bybitApiClientService.placeStopLossOrder(
                        symbol, side, quantity, config.getStopLoss(), stopLossOrderId
                    );
                } else if ("MEXC".equals(exchange)) {
                    stopLossSuccess = mexcApiClientService.placeStopLossOrder(
                        symbol, side, quantity, config.getStopLoss(), stopLossOrderId
                    );
                }
            }
            
            // Place take profit order
            if (config.getTakeProfit() > 0) {
                String takeProfitOrderId = generateOrderLinkId("TP", position.getId());
                
                if ("BYBIT".equals(exchange)) {
                    takeProfitSuccess = bybitApiClientService.placeTakeProfitOrder(
                        symbol, side, quantity, config.getTakeProfit(), takeProfitOrderId
                    );
                } else if ("MEXC".equals(exchange)) {
                    takeProfitSuccess = mexcApiClientService.placeTakeProfitOrder(
                        symbol, side, quantity, config.getTakeProfit(), takeProfitOrderId
                    );
                }
            }
            
            return stopLossSuccess && takeProfitSuccess;
            
        } catch (Exception e) {
            logger.error("Error placing SL/TP orders: {}", e.getMessage());
            return false;
        }
    }
    
    private boolean isScalpTrade(BotSignal signal) {
        if (signal.getStrategy() != null && signal.getStrategy().toLowerCase().contains("scalp")) {
            return true;
        }
        
        // Check confidence level - high confidence might indicate scalp trade
        if (signal.getConfidence() != null && signal.getConfidence() > 80) {
            return true;
        }
        
        // Check if it's a quick trade based on targets
        if (signal.getTakeProfit() != null && signal.getStopLoss() != null && signal.getEntryPrice() != null) {
            double tpDistance = Math.abs(signal.getTakeProfit() - signal.getEntryPrice());
            double slDistance = Math.abs(signal.getEntryPrice() - signal.getStopLoss());
            double ratio = tpDistance / slDistance;
            
            // Scalp trades typically have tight risk/reward ratios
            return ratio <= 2.0;
        }
        
        return false;
    }
    
    private String generateOrderLinkId(String type, Long positionId) {
        return String.format("%s_%d_%d", type, positionId, System.currentTimeMillis());
    }
    
    public void applyMissingSLTP(Position position) {
        try {
            if (position.getSltpApplied() == null || !position.getSltpApplied()) {
                logger.info("Applying missing SL/TP for Position {}", position.getId());
                
                // Use default SL/TP calculation if no signal is linked
                SLTPConfig config = calculateDefaultSLTP(position);
                
                boolean success = placeSLTPOrders(position, config);
                
                if (success) {
                    position.setSltpApplied(true);
                    position.setLastSltpCheck(LocalDateTime.now());
                    positionRepository.save(position);
                    
                    logger.info("Successfully applied default SL/TP for Position {}", position.getId());
                }
            }
            
        } catch (Exception e) {
            logger.error("Error applying missing SL/TP for Position {}: {}", 
                position.getId(), e.getMessage());
        }
    }
    
    private SLTPConfig calculateDefaultSLTP(Position position) {
        double entryPrice = position.getEntryPrice();
        String side = position.getSide();
        
        // Default percentages
        double slPercentage = 1.5; // 1.5% stop loss
        double tpPercentage = 3.0; // 3% take profit
        
        double stopLoss, takeProfit;
        
        if ("BUY".equals(side)) {
            stopLoss = entryPrice * (1 - slPercentage / 100);
            takeProfit = entryPrice * (1 + tpPercentage / 100);
        } else {
            stopLoss = entryPrice * (1 + slPercentage / 100);
            takeProfit = entryPrice * (1 - tpPercentage / 100);
        }
        
        return new SLTPConfig(stopLoss, takeProfit, false, "DEFAULT");
    }
} 