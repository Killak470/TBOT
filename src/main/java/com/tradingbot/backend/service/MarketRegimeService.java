package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class MarketRegimeService {
    
    private static final Logger logger = LoggerFactory.getLogger(MarketRegimeService.class);
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    // Market regime detection thresholds
    private static final double TREND_THRESHOLD = 0.05; // 5% price movement for trend
    private static final double VOLATILITY_HIGH_THRESHOLD = 0.04; // 4% daily volatility
    private static final double VOLATILITY_LOW_THRESHOLD = 0.015; // 1.5% daily volatility
    private static final double VOLUME_HIGH_THRESHOLD = 1.5; // 150% of average volume
    private static final double VOLUME_LOW_THRESHOLD = 0.7; // 70% of average volume
    
    public enum MarketRegime {
        BULL_MARKET,        // Strong uptrend with high momentum
        BEAR_MARKET,        // Strong downtrend with high momentum  
        SIDEWAYS_MARKET,    // Range-bound market with no clear direction
        VOLATILE_MARKET,    // High volatility regardless of direction
        LOW_VOLUME_MARKET,  // Low participation market
        TRANSITION_MARKET   // Market changing between regimes
    }
    
    public static class RegimeAnalysis {
        private MarketRegime regime;
        private double confidence;
        private String description;
        private Map<String, Double> regimeScores;
        private Map<String, String> recommendedStrategies;
        private double trendStrength;
        private double volatilityLevel;
        private double volumeLevel;
        
        public RegimeAnalysis() {
            this.regimeScores = new HashMap<>();
            this.recommendedStrategies = new HashMap<>();
        }
        
        // Getters and setters
        public MarketRegime getRegime() { return regime; }
        public void setRegime(MarketRegime regime) { this.regime = regime; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Double> getRegimeScores() { return regimeScores; }
        public void setRegimeScores(Map<String, Double> regimeScores) { this.regimeScores = regimeScores; }
        public Map<String, String> getRecommendedStrategies() { return recommendedStrategies; }
        public void setRecommendedStrategies(Map<String, String> recommendedStrategies) { this.recommendedStrategies = recommendedStrategies; }
        public double getTrendStrength() { return trendStrength; }
        public void setTrendStrength(double trendStrength) { this.trendStrength = trendStrength; }
        public double getVolatilityLevel() { return volatilityLevel; }
        public void setVolatilityLevel(double volatilityLevel) { this.volatilityLevel = volatilityLevel; }
        public double getVolumeLevel() { return volumeLevel; }
        public void setVolumeLevel(double volumeLevel) { this.volumeLevel = volumeLevel; }
    }
    
    /**
     * Analyze market regime for a given symbol
     */
    public RegimeAnalysis analyzeMarketRegime(String symbol, String exchange, ScanConfig.MarketType marketType) {
        RegimeAnalysis analysis = new RegimeAnalysis();
        
        try {
            logger.info("Analyzing market regime for {} on {}", symbol, exchange);
            
            // Calculate market metrics
            double trendStrength = calculateTrendStrength(symbol);
            double volatilityLevel = calculateVolatilityLevel(symbol, exchange);
            double volumeLevel = calculateVolumeLevel(symbol);
            
            analysis.setTrendStrength(trendStrength);
            analysis.setVolatilityLevel(volatilityLevel);
            analysis.setVolumeLevel(volumeLevel);
            
            // Calculate regime scores
            Map<String, Double> regimeScores = calculateRegimeScores(trendStrength, volatilityLevel, volumeLevel);
            analysis.setRegimeScores(regimeScores);
            
            // Determine dominant regime
            MarketRegime dominantRegime = determineDominantRegime(regimeScores);
            double confidence = regimeScores.get(dominantRegime.name());
            
            analysis.setRegime(dominantRegime);
            analysis.setConfidence(confidence);
            analysis.setDescription(generateRegimeDescription(dominantRegime, trendStrength, volatilityLevel, volumeLevel));
            
            // Set recommended strategies
            analysis.setRecommendedStrategies(getRecommendedStrategies(dominantRegime));
            
            logger.info("Market regime analysis for {}: {} (confidence: {:.1f}%)", 
                       symbol, dominantRegime, confidence * 100);
            
        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON during market regime analysis for {}: {}. This likely occurred during volatility calculation.", symbol, e.getMessage(), e);
            analysis.setRegime(MarketRegime.SIDEWAYS_MARKET);
            analysis.setConfidence(0.5);
            analysis.setDescription("Default regime due to error in volatility calculation (JSON processing).");
        } catch (Exception e) {
            logger.error("Error analyzing market regime for {}: {}", symbol, e.getMessage(), e);
            analysis.setRegime(MarketRegime.SIDEWAYS_MARKET);
            analysis.setConfidence(0.5);
            analysis.setDescription("Default regime due to general analysis error.");
        }
        
        return analysis;
    }
    
    /**
     * Calculate trend strength (-1.0 to 1.0, negative = downtrend, positive = uptrend)
     */
    private double calculateTrendStrength(String symbol) {
        try {
            // This would ideally analyze multiple timeframes and moving averages
            // For now, use a simplified calculation
            
            // Get recent price data (mock implementation)
            List<BigDecimal> prices = getMockPriceData(symbol);
            if (prices.size() < 20) {
                return 0.0; // Not enough data
            }
            
            // Calculate simple trend using first and last prices
            BigDecimal firstPrice = prices.get(0);
            BigDecimal lastPrice = prices.get(prices.size() - 1);
            
            double priceChange = lastPrice.subtract(firstPrice)
                .divide(firstPrice, 6, RoundingMode.HALF_UP)
                .doubleValue();
            
            // Normalize to -1.0 to 1.0 range
            return Math.max(-1.0, Math.min(1.0, priceChange / TREND_THRESHOLD));
            
        } catch (Exception e) {
            logger.error("Error calculating trend strength for {}: {}", symbol, e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * Calculate volatility level (0.0 to 1.0+)
     */
    private double calculateVolatilityLevel(String symbol, String exchange) throws JsonProcessingException {
        try {
            // Use RiskManagementService to get volatility
            BigDecimal volatility = riskManagementService.calculateVolatility(symbol, exchange);
            return volatility.doubleValue();
            
        } catch (JsonProcessingException e) {
            // Re-throw JsonProcessingException to be handled by the caller (analyzeMarketRegime)
            logger.warn("JsonProcessingException while calculating volatility level for {} on exchange {}: {}. This will be handled by the caller.", symbol, exchange, e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error calculating volatility level for {} on exchange {}: {}", symbol, exchange, e.getMessage(), e);
            // Fallback to a default volatility in case of unexpected errors other than JsonProcessingException
            return 0.02; // Default 2% volatility
        }
    }
    
    /**
     * Calculate volume level (ratio compared to average)
     */
    private double calculateVolumeLevel(String symbol) {
        try {
            // This would ideally compare recent volume to historical average
            // For now, return a reasonable default
            return 1.0; // Normal volume
            
        } catch (Exception e) {
            logger.error("Error calculating volume level for {}: {}", symbol, e.getMessage());
            return 1.0;
        }
    }
    
    /**
     * Calculate scores for each market regime
     */
    private Map<String, Double> calculateRegimeScores(double trendStrength, double volatilityLevel, double volumeLevel) {
        Map<String, Double> scores = new HashMap<>();
        
        // Bull Market Score - Strong positive trend with good volume
        double bullScore = 0.0;
        if (trendStrength > 0.3) { // Positive trend
            bullScore = trendStrength * 0.6; // Trend contribution
            if (volumeLevel > 1.0) {
                bullScore += Math.min(volumeLevel - 1.0, 0.3) * 0.3; // Volume boost
            }
            if (volatilityLevel < VOLATILITY_HIGH_THRESHOLD) {
                bullScore += 0.1; // Stability bonus
            }
        }
        scores.put(MarketRegime.BULL_MARKET.name(), Math.min(1.0, bullScore));
        
        // Bear Market Score - Strong negative trend with good volume
        double bearScore = 0.0;
        if (trendStrength < -0.3) { // Negative trend
            bearScore = Math.abs(trendStrength) * 0.6; // Trend contribution
            if (volumeLevel > 1.0) {
                bearScore += Math.min(volumeLevel - 1.0, 0.3) * 0.3; // Volume boost
            }
            if (volatilityLevel < VOLATILITY_HIGH_THRESHOLD) {
                bearScore += 0.1; // Stability bonus
            }
        }
        scores.put(MarketRegime.BEAR_MARKET.name(), Math.min(1.0, bearScore));
        
        // Sideways Market Score - Low trend strength with normal volatility
        double sidewaysScore = 0.0;
        if (Math.abs(trendStrength) < 0.3) { // Low trend
            sidewaysScore = 0.5 - Math.abs(trendStrength); // Higher score for lower trend
            if (volatilityLevel < VOLATILITY_HIGH_THRESHOLD && volatilityLevel > VOLATILITY_LOW_THRESHOLD) {
                sidewaysScore += 0.3; // Normal volatility bonus
            }
        }
        scores.put(MarketRegime.SIDEWAYS_MARKET.name(), Math.min(1.0, sidewaysScore));
        
        // Volatile Market Score - High volatility regardless of trend
        double volatileScore = 0.0;
        if (volatilityLevel > VOLATILITY_HIGH_THRESHOLD) {
            volatileScore = Math.min(1.0, volatilityLevel / VOLATILITY_HIGH_THRESHOLD) * 0.8;
            if (volumeLevel > VOLUME_HIGH_THRESHOLD) {
                volatileScore += 0.2; // Volume confirmation
            }
        }
        scores.put(MarketRegime.VOLATILE_MARKET.name(), Math.min(1.0, volatileScore));
        
        // Low Volume Market Score
        double lowVolumeScore = 0.0;
        if (volumeLevel < VOLUME_LOW_THRESHOLD) {
            lowVolumeScore = (VOLUME_LOW_THRESHOLD - volumeLevel) / VOLUME_LOW_THRESHOLD * 0.7;
            if (volatilityLevel < VOLATILITY_LOW_THRESHOLD) {
                lowVolumeScore += 0.3; // Low volatility confirmation
            }
        }
        scores.put(MarketRegime.LOW_VOLUME_MARKET.name(), Math.min(1.0, lowVolumeScore));
        
        // Transition Market Score - Mixed signals
        double transitionScore = 0.5 - (scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0));
        scores.put(MarketRegime.TRANSITION_MARKET.name(), Math.max(0.0, transitionScore));
        
        return scores;
    }
    
    /**
     * Determine dominant regime from scores
     */
    private MarketRegime determineDominantRegime(Map<String, Double> regimeScores) {
        return regimeScores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(entry -> MarketRegime.valueOf(entry.getKey()))
            .orElse(MarketRegime.SIDEWAYS_MARKET);
    }
    
    /**
     * Generate human-readable regime description
     */
    private String generateRegimeDescription(MarketRegime regime, double trendStrength, double volatilityLevel, double volumeLevel) {
        switch (regime) {
            case BULL_MARKET:
                return String.format("Strong bullish trend (%.1f%% strength) with %s volatility and %s volume", 
                    trendStrength * 100, 
                    volatilityLevel > VOLATILITY_HIGH_THRESHOLD ? "high" : "normal",
                    volumeLevel > VOLUME_HIGH_THRESHOLD ? "high" : "normal");
                    
            case BEAR_MARKET:
                return String.format("Strong bearish trend (%.1f%% strength) with %s volatility and %s volume", 
                    Math.abs(trendStrength) * 100,
                    volatilityLevel > VOLATILITY_HIGH_THRESHOLD ? "high" : "normal",
                    volumeLevel > VOLUME_HIGH_THRESHOLD ? "high" : "normal");
                    
            case SIDEWAYS_MARKET:
                return String.format("Range-bound market with %.1f%% volatility and %s volume", 
                    volatilityLevel * 100,
                    volumeLevel > VOLUME_HIGH_THRESHOLD ? "high" : volumeLevel < VOLUME_LOW_THRESHOLD ? "low" : "normal");
                    
            case VOLATILE_MARKET:
                return String.format("High volatility market (%.1f%%) with %s trend strength", 
                    volatilityLevel * 100,
                    Math.abs(trendStrength) > 0.3 ? "strong" : "weak");
                    
            case LOW_VOLUME_MARKET:
                return String.format("Low participation market (%.0f%% of average volume) with %.1f%% volatility", 
                    volumeLevel * 100, volatilityLevel * 100);
                    
            case TRANSITION_MARKET:
                return "Market in transition with mixed signals - proceed with caution";
                
            default:
                return "Unknown market regime";
        }
    }
    
    /**
     * Get recommended strategies for each regime
     */
    private Map<String, String> getRecommendedStrategies(MarketRegime regime) {
        Map<String, String> strategies = new HashMap<>();
        
        switch (regime) {
            case BULL_MARKET:
                strategies.put("primary", "Momentum-following strategies");
                strategies.put("technical", "Moving average crossovers, RSI pullbacks, breakout strategies");
                strategies.put("riskManagement", "Trailing stops, position scaling up on strength");
                strategies.put("signalBias", "Favor BUY signals, filter SELL signals more strictly");
                break;
                
            case BEAR_MARKET:
                strategies.put("primary", "Reversal and short-selling strategies");
                strategies.put("technical", "Resistance levels, bearish divergences, breakdown strategies");
                strategies.put("riskManagement", "Tight stops, quick profits, smaller position sizes");
                strategies.put("signalBias", "Favor SELL signals, be cautious with BUY signals");
                break;
                
            case SIDEWAYS_MARKET:
                strategies.put("primary", "Range-bound and mean reversion strategies");
                strategies.put("technical", "Support/resistance trading, oscillator signals");
                strategies.put("riskManagement", "Take profits at range boundaries, avoid breakout trades");
                strategies.put("signalBias", "Equal weight to BUY/SELL, focus on range boundaries");
                break;
                
            case VOLATILE_MARKET:
                strategies.put("primary", "Breakout and volatility strategies");
                strategies.put("technical", "Bollinger Band expansions, ATR-based signals");
                strategies.put("riskManagement", "Wider stops, smaller positions, quick scalping");
                strategies.put("signalBias", "Increase AI weight, reduce technical weight");
                break;
                
            case LOW_VOLUME_MARKET:
                strategies.put("primary", "Conservative strategies with wider stops");
                strategies.put("technical", "Avoid breakout signals, focus on strong momentum");
                strategies.put("riskManagement", "Reduce position sizes, avoid over-trading");
                strategies.put("signalBias", "Higher confidence threshold for all signals");
                break;
                
            case TRANSITION_MARKET:
                strategies.put("primary", "Wait for clarity or use very conservative strategies");
                strategies.put("technical", "Multi-timeframe confirmation required");
                strategies.put("riskManagement", "Minimum position sizes, maximum diversification");
                strategies.put("signalBias", "Require confluence from multiple sources");
                break;
        }
        
        return strategies;
    }
    
    /**
     * Adjust signal weights based on market regime
     */
    public Map<String, Double> adjustWeightsForRegime(Map<String, Double> baseWeights, MarketRegime regime) {
        Map<String, Double> adjustedWeights = new HashMap<>(baseWeights);

        switch (regime) {
            case BULL_MARKET:
                adjustedWeights.compute("technical", (k, v) -> v == null ? 0.50 : v * 1.25); // Increase technical weight
                adjustedWeights.compute("sentiment", (k, v) -> v == null ? 0.30 : v * 1.10); // Slightly increase sentiment
                adjustedWeights.compute("ai", (k, v) -> v == null ? 0.20 : v * 0.80);      // Slightly decrease AI
                break;
            case BEAR_MARKET:
                adjustedWeights.compute("technical", (k, v) -> v == null ? 0.50 : v * 1.25);
                adjustedWeights.compute("sentiment", (k, v) -> v == null ? 0.20 : v * 0.90);
                adjustedWeights.compute("ai", (k, v) -> v == null ? 0.30 : v * 1.0); // Keep AI neutral
                break;
            case SIDEWAYS_MARKET:
                adjustedWeights.compute("technical", (k, v) -> v == null ? 0.25 : v * 0.80); // Decrease technical
                adjustedWeights.compute("ai", (k, v) -> v == null ? 0.50 : v * 1.25);      // Increase AI for pattern recognition
                adjustedWeights.compute("sentiment", (k, v) -> v == null ? 0.25 : v * 1.0);
                break;
            case VOLATILE_MARKET:
                adjustedWeights.compute("technical", (k, v) -> v == null ? 0.20 : v * 0.70); // Significantly decrease technical
                adjustedWeights.compute("ai", (k, v) -> v == null ? 0.60 : v * 1.50);      // Heavily favor AI
                adjustedWeights.compute("sentiment", (k, v) -> v == null ? 0.20 : v * 1.0);
                break;
            case LOW_VOLUME_MARKET:
                // In low volume, all signals are less reliable. Reduce all weights.
                adjustedWeights.replaceAll((k, v) -> v == null ? 0.33 : v * 0.90);
                break;
            case TRANSITION_MARKET:
                // Balance weights during transitions
                adjustedWeights.replaceAll((k, v) -> v == null ? 0.33 : v * 1.0);
                break;
        }

        // Normalize weights to ensure they sum to 1.0
        double totalWeight = adjustedWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            final double normalizer = totalWeight; // effectively final for lambda
            adjustedWeights.replaceAll((k, v) -> v / normalizer);
        }

        logger.debug("Adjusted weights for regime {}: {}", regime, adjustedWeights);
        return adjustedWeights;
    }
    
    /**
     * Mock price data for testing - replace with real price history
     */
    private List<BigDecimal> getMockPriceData(String symbol) {
        List<BigDecimal> prices = new ArrayList<>();
        
        // Generate some mock price data for testing
        BigDecimal basePrice = BigDecimal.valueOf(100.0);
        Random random = new Random();
        
        for (int i = 0; i < 30; i++) {
            double change = (random.nextDouble() - 0.5) * 0.02; // +/- 1% change
            basePrice = basePrice.multiply(BigDecimal.valueOf(1.0 + change));
            prices.add(basePrice);
        }
        
        return prices;
    }
} 