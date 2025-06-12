package com.tradingbot.backend.service;

import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SignalWeightingService {
    
    private static final Logger logger = LoggerFactory.getLogger(SignalWeightingService.class);
    
    // Removed circular dependency - will use direct data instead of scanner service
    
    // Default weights
    private static final double DEFAULT_TECHNICAL_WEIGHT = 0.4;
    private static final double DEFAULT_AI_WEIGHT = 0.4;
    private static final double DEFAULT_SENTIMENT_WEIGHT = 0.2;
    
    public enum MarketCondition {
        TRENDING_UP,
        TRENDING_DOWN,
        RANGING,
        VOLATILE,
        LOW_VOLUME,
        HIGH_VOLUME
    }
    
    /**
     * Calculate adaptive weights based on current market conditions
     */
    public Map<String, Double> calculateAdaptiveWeights(String symbol, String interval) {
        Map<String, Double> weights = new HashMap<>();
        
        try {
            // Analyze current market conditions
            MarketCondition condition = analyzeMarketCondition(symbol, interval);
            
            // Adjust weights based on market condition
            switch (condition) {
                case TRENDING_UP:
                case TRENDING_DOWN:
                    // In trending markets, technical analysis is more reliable
                    weights.put("technical", 0.5);
                    weights.put("ai", 0.3);
                    weights.put("sentiment", 0.2);
                    logger.debug("Trending market detected for {}: increased technical weight", symbol);
                    break;
                    
                case RANGING:
                    // In ranging markets, AI analysis is more valuable
                    weights.put("technical", 0.3);
                    weights.put("ai", 0.5);
                    weights.put("sentiment", 0.2);
                    logger.debug("Ranging market detected for {}: increased AI weight", symbol);
                    break;
                    
                case VOLATILE:
                    // In volatile markets, AI can better adapt to rapid changes
                    weights.put("technical", 0.25);
                    weights.put("ai", 0.55);
                    weights.put("sentiment", 0.2);
                    logger.debug("Volatile market detected for {}: heavily increased AI weight", symbol);
                    break;
                    
                case LOW_VOLUME:
                    // Low volume means less reliable signals, be conservative
                    weights.put("technical", 0.35);
                    weights.put("ai", 0.35);
                    weights.put("sentiment", 0.3);
                    logger.debug("Low volume detected for {}: balanced conservative weights", symbol);
                    break;
                    
                case HIGH_VOLUME:
                    // High volume confirms moves, trust technical more
                    weights.put("technical", 0.45);
                    weights.put("ai", 0.35);
                    weights.put("sentiment", 0.2);
                    logger.debug("High volume detected for {}: increased technical weight", symbol);
                    break;
                    
                default:
                    // Default balanced weights
                    weights.put("technical", DEFAULT_TECHNICAL_WEIGHT);
                    weights.put("ai", DEFAULT_AI_WEIGHT);
                    weights.put("sentiment", DEFAULT_SENTIMENT_WEIGHT);
                    break;
            }
            
        } catch (Exception e) {
            logger.error("Error calculating adaptive weights for {}: {}", symbol, e.getMessage());
            // Fallback to default weights
            weights.put("technical", DEFAULT_TECHNICAL_WEIGHT);
            weights.put("ai", DEFAULT_AI_WEIGHT);
            weights.put("sentiment", DEFAULT_SENTIMENT_WEIGHT);
        }
        
        return weights;
    }
    
    /**
     * Analyze current market condition for a symbol
     */
    private MarketCondition analyzeMarketCondition(String symbol, String interval) {
        try {
            // Get recent market data through MarketScannerService
            // For now, we'll use a simplified analysis
            
            // Calculate volatility and volume indicators
            double volatility = calculateRecentVolatility(symbol);
            double volumeRatio = calculateVolumeRatio(symbol);
            String trendDirection = analyzeTrendDirection(symbol);
            
            // Determine market condition based on multiple factors
            if (volatility > 0.05) { // >5% volatility
                return MarketCondition.VOLATILE;
            } else if (volumeRatio < 0.7) { // Volume 30% below average
                return MarketCondition.LOW_VOLUME;
            } else if (volumeRatio > 1.5) { // Volume 50% above average
                return MarketCondition.HIGH_VOLUME;
            } else if ("UP".equals(trendDirection)) {
                return MarketCondition.TRENDING_UP;
            } else if ("DOWN".equals(trendDirection)) {
                return MarketCondition.TRENDING_DOWN;
            } else {
                return MarketCondition.RANGING;
            }
            
        } catch (Exception e) {
            logger.error("Error analyzing market condition for {}: {}", symbol, e.getMessage());
            return MarketCondition.RANGING; // Default fallback
        }
    }
    
    /**
     * Calculate recent volatility for market condition analysis
     */
    private double calculateRecentVolatility(String symbol) {
        try {
            // This would ideally get recent price data and calculate volatility
            // For now, return a simplified calculation
            
            // Get some sample volatility based on symbol type
            if (symbol.startsWith("BTC")) {
                return 0.03; // 3% for BTC
            } else if (symbol.startsWith("ETH")) {
                return 0.035; // 3.5% for ETH
            } else {
                return 0.04; // 4% for altcoins
            }
            
        } catch (Exception e) {
            logger.error("Error calculating volatility for {}: {}", symbol, e.getMessage());
            return 0.03; // Default volatility
        }
    }
    
    /**
     * Calculate volume ratio (current vs average)
     */
    private double calculateVolumeRatio(String symbol) {
        try {
            // This would ideally compare recent volume to historical average
            // For now, return a reasonable default
            return 1.0; // Normal volume
            
        } catch (Exception e) {
            logger.error("Error calculating volume ratio for {}: {}", symbol, e.getMessage());
            return 1.0; // Default ratio
        }
    }
    
    /**
     * Analyze trend direction
     */
    private String analyzeTrendDirection(String symbol) {
        try {
            // This would ideally analyze moving averages and price action
            // For now, return a simplified analysis
            
            // Could integrate with existing technical indicators
            return "NEUTRAL"; // Default
            
        } catch (Exception e) {
            logger.error("Error analyzing trend for {}: {}", symbol, e.getMessage());
            return "NEUTRAL"; // Default
        }
    }
    
    /**
     * Apply adaptive weights to generate a weighted signal score
     */
    public double calculateWeightedSignalScore(
            double technicalScore, 
            double aiScore, 
            double sentimentScore, 
            String symbol, 
            String interval) {
        
        Map<String, Double> weights = calculateAdaptiveWeights(symbol, interval);
        
        double weightedScore = (technicalScore * weights.get("technical")) +
                              (aiScore * weights.get("ai")) +
                              (sentimentScore * weights.get("sentiment"));
        
        logger.debug("Weighted signal score for {}: {} (tech: {}, ai: {}, sentiment: {})", 
                    symbol, weightedScore, technicalScore, aiScore, sentimentScore);
        
        return weightedScore;
    }
    
    /**
     * Convert technical indicators to a numerical score
     */
    public double convertTechnicalToScore(Map<String, TechnicalIndicator> indicators) {
        if (indicators == null || indicators.isEmpty()) {
            return 0.5; // Neutral score
        }
        
        double totalScore = 0.0;
        int indicatorCount = 0;
        
        for (TechnicalIndicator indicator : indicators.values()) {
            String signal = indicator.getSignal();
            double score = convertSignalToScore(signal);
            totalScore += score;
            indicatorCount++;
        }
        
        return indicatorCount > 0 ? totalScore / indicatorCount : 0.5;
    }
    
    /**
     * Convert AI analysis to a numerical score
     */
    public double convertAiToScore(String aiAnalysis) {
        if (aiAnalysis == null || aiAnalysis.trim().isEmpty()) {
            return 0.5; // Neutral score
        }
        
        // Simple sentiment analysis of AI text
        String analysis = aiAnalysis.toLowerCase();
        
        if (analysis.contains("strong buy") || analysis.contains("very bullish")) {
            return 0.9;
        } else if (analysis.contains("buy") || analysis.contains("bullish")) {
            return 0.7;
        } else if (analysis.contains("strong sell") || analysis.contains("very bearish")) {
            return 0.1;
        } else if (analysis.contains("sell") || analysis.contains("bearish")) {
            return 0.3;
        } else {
            return 0.5; // Neutral
        }
    }
    
    /**
     * Convert sentiment to numerical score
     */
    public double convertSentimentToScore(String sentiment) {
        if (sentiment == null) return 0.5;
        
        switch (sentiment.toUpperCase()) {
            case "VERY_POSITIVE": return 0.9;
            case "POSITIVE": return 0.7;
            case "NEUTRAL": return 0.5;
            case "NEGATIVE": return 0.3;
            case "VERY_NEGATIVE": return 0.1;
            default: return 0.5;
        }
    }
    
    /**
     * Helper method to convert signal strings to numerical scores
     */
    private double convertSignalToScore(String signal) {
        if (signal == null) return 0.5;
        
        switch (signal.toUpperCase()) {
            case "STRONG_BULLISH":
            case "STRONG_BUY":
            case "OVERSOLD":
                return 0.9;
            case "BULLISH":
            case "BUY":
                return 0.7;
            case "STRONG_BEARISH":
            case "STRONG_SELL":
            case "OVERBOUGHT":
                return 0.1;
            case "BEARISH":
            case "SELL":
                return 0.3;
            case "NEUTRAL":
            default:
                return 0.5;
        }
    }
} 