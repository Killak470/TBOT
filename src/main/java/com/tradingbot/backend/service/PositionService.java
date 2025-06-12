package com.tradingbot.backend.service;

import com.tradingbot.backend.model.*;
import com.tradingbot.backend.service.analysis.TradeAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.ArrayList;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for handling position-related operations specifically for WebSocket updates
 */
@Service
public class PositionService {

    private final OrderManagementService orderManagementService;
    private final TradeAnalysisService tradeAnalysisService;
    private final BybitApiClientService bybitApiClient;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(PositionService.class);
    
    @Autowired
    public PositionService(OrderManagementService orderManagementService,
                          TradeAnalysisService tradeAnalysisService,
                          BybitApiClientService bybitApiClient,
                          ObjectMapper objectMapper) {
        this.orderManagementService = orderManagementService;
        this.tradeAnalysisService = tradeAnalysisService;
        this.bybitApiClient = bybitApiClient;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Get all positions
     * 
     * @return List of all positions
     */
    public List<Position> getAllPositions() {
        return orderManagementService.getPositions(null, null);
    }
    
    /**
     * Get all open positions
     * 
     * @return List of open positions
     */
    public List<Position> getOpenPositions() {
        return orderManagementService.getPositions("OPEN", null);
    }
    
    /**
     * Get positions by symbol
     * 
     * @param symbol The trading pair
     * @return List of positions for the symbol
     */
    public List<Position> getPositionsBySymbol(String symbol) {
        return orderManagementService.getPositionsBySymbol(symbol, null, null);
    }

    public PositionMonitoringResult monitorPosition(Position position) {
        try {
            // Get real-time orderbook data
            String marketType = position.getMarketType() != null ? position.getMarketType().toLowerCase() : "spot";
            // Ensure marketType is one of the valid values for bybitApiClient.getOrderbook, e.g., "spot", "linear", "inverse"
            // The getOrderbook method might need its own validation for the category passed to Bybit.
            // Bybit's /v5/market/orderbook endpoint uses "category" which can be spot, linear, inverse, option.
            if (!("spot".equals(marketType) || "linear".equals(marketType) || "inverse".equals(marketType) || "option".equals(marketType))) {
                // Fallback or throw error if market type is not directly usable as a category
                logger.warn("Unsupported marketType '{}' for fetching orderbook via Bybit API. Defaulting to spot or consider mapping.", marketType);
                marketType = "spot"; // Defaulting, but this might be incorrect for non-spot symbols
            }

            ResponseEntity<String> response = bybitApiClient.getOrderbook(position.getSymbol(), marketType, 50);
            JsonNode orderbook = null;
            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                 orderbook = objectMapper.readTree(response.getBody());
            } else {
                logger.error("Failed to fetch orderbook for {} {}. Status: {}, Body: {}", 
                    position.getSymbol(), marketType, 
                    response != null ? response.getStatusCode() : "N/A", 
                    response != null ? response.getBody() : "N/A");
                // Depending on requirements, either throw an exception or allow analysis that can proceed without orderbook
            }
            
            // Perform AI-powered analysis
            TradeAnalysis analysis = tradeAnalysisService.analyzePosition(position, orderbook);
            
            // Generate monitoring result
            return PositionMonitoringResult.builder()
                .position(position)
                .analysis(analysis)
                .riskMetrics(calculateRiskMetrics(position))
                .adjustmentRecommendations(generateRecommendations(position, analysis))
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to monitor position: " + e.getMessage(), e);
        }
    }

    private RiskMetrics calculateRiskMetrics(Position position) {
        return RiskMetrics.builder()
            .currentRisk(calculateCurrentRisk(position))
            .riskRewardRatio(calculateRiskRewardRatio(position))
            .maxDrawdown(calculateMaxDrawdown(position))
            .build();
    }

    private List<AdjustmentRecommendation> generateRecommendations(
            Position position, TradeAnalysis analysis) {
        List<AdjustmentRecommendation> recommendations = new ArrayList<>();
        
        // Add position size adjustment recommendations
        if (shouldAdjustPositionSize(position, analysis)) {
            recommendations.add(createSizeAdjustmentRecommendation(position, analysis));
        }
        
        // Add stop loss adjustment recommendations
        if (shouldAdjustStopLoss(position, analysis)) {
            recommendations.add(createStopLossAdjustmentRecommendation(position, analysis));
        }
        
        // Add take profit adjustment recommendations
        if (shouldAdjustTakeProfit(position, analysis)) {
            recommendations.add(createTakeProfitAdjustmentRecommendation(position, analysis));
        }
        
        return recommendations;
    }

    private BigDecimal calculateCurrentRisk(Position position) {
        // Calculate current risk based on position size and market conditions
        BigDecimal positionSize = position.getSize();
        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal currentPrice = position.getCurrentPrice();
        BigDecimal stopLoss = position.getStopLoss();
        
        if (stopLoss == null || stopLoss.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        
        // Calculate potential loss
        BigDecimal potentialLoss = entryPrice.subtract(stopLoss).abs()
            .multiply(positionSize);
            
        // Calculate risk as a percentage of position value
        BigDecimal positionValue = positionSize.multiply(currentPrice);
        return potentialLoss.divide(positionValue, 4, BigDecimal.ROUND_HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    private BigDecimal calculateRiskRewardRatio(Position position) {
        BigDecimal stopLoss = position.getStopLoss();
        BigDecimal takeProfit = position.getTakeProfit();
        BigDecimal entryPrice = position.getEntryPrice();
        
        if (stopLoss == null || takeProfit == null || 
            stopLoss.equals(BigDecimal.ZERO) || takeProfit.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        BigDecimal reward = takeProfit.subtract(entryPrice).abs();
        
        if (risk.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        
        return reward.divide(risk, 2, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal calculateMaxDrawdown(Position position) {
        // Calculate maximum drawdown based on historical price data
        BigDecimal positionSize = position.getSize();
        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal margin = position.getMargin();
        
        if (margin == null || margin.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        
        // Calculate maximum possible loss (100% of margin)
        return margin.divide(positionSize.multiply(entryPrice), 4, BigDecimal.ROUND_HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    private boolean shouldAdjustPositionSize(Position position, TradeAnalysis analysis) {
        // Determine if position size should be adjusted based on analysis
        return false; // Implementation needed
    }

    private boolean shouldAdjustStopLoss(Position position, TradeAnalysis analysis) {
        // Determine if stop loss should be adjusted based on analysis
        return false; // Implementation needed
    }

    private boolean shouldAdjustTakeProfit(Position position, TradeAnalysis analysis) {
        // Determine if take profit should be adjusted based on analysis
        return false; // Implementation needed
    }

    private AdjustmentRecommendation createSizeAdjustmentRecommendation(Position position, TradeAnalysis analysis) {
        // Create position size adjustment recommendation
        return null; // Implementation needed
    }

    private AdjustmentRecommendation createStopLossAdjustmentRecommendation(Position position, TradeAnalysis analysis) {
        // Create stop loss adjustment recommendation
        return null; // Implementation needed
    }

    private AdjustmentRecommendation createTakeProfitAdjustmentRecommendation(Position position, TradeAnalysis analysis) {
        // Create take profit adjustment recommendation
        return null; // Implementation needed
    }
} 