package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.repository.BotSignalRepository;
import com.tradingbot.backend.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PerformanceLearningService {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceLearningService.class);
    
    @Autowired
    private BotSignalRepository botSignalRepository;
    
    @Autowired
    private PositionRepository positionRepository;
    
    @Autowired
    private SignalWeightingService signalWeightingService;
    
    // Performance tracking cache
    private Map<String, PerformanceMetrics> performanceCache = new HashMap<>();
    
    // Learning parameters
    private static final double LEARNING_RATE = 0.1; // How fast to adjust weights
    private static final int MIN_SAMPLES_FOR_LEARNING = 10; // Minimum trades before adjusting
    private static final int LOOKBACK_DAYS = 30; // Days to look back for performance
    
    public static class PerformanceMetrics {
        private String strategyType; // e.g., "technical", "ai", "sentiment"
        private String symbol;
        private String timeframe;
        private int totalTrades;
        private int winningTrades;
        private int losingTrades;
        private double winRate;
        private BigDecimal totalProfit;
        private BigDecimal avgWin;
        private BigDecimal avgLoss;
        private double sharpeRatio;
        private double maxDrawdown;
        private LocalDateTime lastUpdated;
        private double currentWeight;
        private double suggestedWeight;
        
        public PerformanceMetrics() {
            this.lastUpdated = LocalDateTime.now();
        }
        
        // Getters and setters
        public String getStrategyType() { return strategyType; }
        public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public String getTimeframe() { return timeframe; }
        public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
        public int getTotalTrades() { return totalTrades; }
        public void setTotalTrades(int totalTrades) { this.totalTrades = totalTrades; }
        public int getWinningTrades() { return winningTrades; }
        public void setWinningTrades(int winningTrades) { this.winningTrades = winningTrades; }
        public int getLosingTrades() { return losingTrades; }
        public void setLosingTrades(int losingTrades) { this.losingTrades = losingTrades; }
        public double getWinRate() { return winRate; }
        public void setWinRate(double winRate) { this.winRate = winRate; }
        public BigDecimal getTotalProfit() { return totalProfit; }
        public void setTotalProfit(BigDecimal totalProfit) { this.totalProfit = totalProfit; }
        public BigDecimal getAvgWin() { return avgWin; }
        public void setAvgWin(BigDecimal avgWin) { this.avgWin = avgWin; }
        public BigDecimal getAvgLoss() { return avgLoss; }
        public void setAvgLoss(BigDecimal avgLoss) { this.avgLoss = avgLoss; }
        public double getSharpeRatio() { return sharpeRatio; }
        public void setSharpeRatio(double sharpeRatio) { this.sharpeRatio = sharpeRatio; }
        public double getMaxDrawdown() { return maxDrawdown; }
        public void setMaxDrawdown(double maxDrawdown) { this.maxDrawdown = maxDrawdown; }
        public LocalDateTime getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
        public double getCurrentWeight() { return currentWeight; }
        public void setCurrentWeight(double currentWeight) { this.currentWeight = currentWeight; }
        public double getSuggestedWeight() { return suggestedWeight; }
        public void setSuggestedWeight(double suggestedWeight) { this.suggestedWeight = suggestedWeight; }
    }
    
    public static class WeightAdjustment {
        private String strategyType;
        private String symbol;
        private String timeframe;
        private double oldWeight;
        private double newWeight;
        private String reason;
        private LocalDateTime adjustedAt;
        
        public WeightAdjustment(String strategyType, String symbol, String timeframe, 
                               double oldWeight, double newWeight, String reason) {
            this.strategyType = strategyType;
            this.symbol = symbol;
            this.timeframe = timeframe;
            this.oldWeight = oldWeight;
            this.newWeight = newWeight;
            this.reason = reason;
            this.adjustedAt = LocalDateTime.now();
        }
        
        // Getters and setters
        public String getStrategyType() { return strategyType; }
        public String getSymbol() { return symbol; }
        public String getTimeframe() { return timeframe; }
        public double getOldWeight() { return oldWeight; }
        public double getNewWeight() { return newWeight; }
        public String getReason() { return reason; }
        public LocalDateTime getAdjustedAt() { return adjustedAt; }
    }
    
    /**
     * Track performance of a completed signal/trade
     */
    @Transactional
    public void trackSignalPerformance(BotSignal signal, Position position) {
        try {
            logger.info("Tracking performance for signal {} with position {}", 
                       signal.getId(), position != null ? position.getId() : "none");
            
            // Determine strategy type based on signal characteristics
            String strategyType = determineStrategyType(signal);
            
            // Calculate performance metrics
            BigDecimal profit = calculateSignalProfit(signal, position);
            boolean isWinning = profit.compareTo(BigDecimal.ZERO) > 0;
            
            // Update performance cache
            String cacheKey = buildCacheKey(strategyType, signal.getSymbol(), "1h"); // Default timeframe
            PerformanceMetrics metrics = performanceCache.computeIfAbsent(cacheKey, k -> new PerformanceMetrics());
            
            updateMetrics(metrics, strategyType, signal.getSymbol(), "1h", profit, isWinning);
            
            logger.debug("Updated performance metrics for {}: winRate={}, totalTrades={}", 
                        cacheKey, metrics.getWinRate(), metrics.getTotalTrades());
            
        } catch (Exception e) {
            logger.error("Error tracking signal performance for signal {}: {}", 
                        signal.getId(), e.getMessage());
        }
    }
    
    /**
     * Determine strategy type from signal characteristics
     */
    private String determineStrategyType(BotSignal signal) {
        // This would ideally have more sophisticated logic to determine
        // which strategy type (technical, AI, sentiment) generated the signal
        
        // For now, use a simple heuristic based on strategy name
        String strategyName = signal.getStrategyName();
        if (strategyName == null) return "technical"; // Default
        
        if (strategyName.toLowerCase().contains("ai") || 
            strategyName.toLowerCase().contains("claude") ||
            "AI_ASSISTED_SNIPER".equalsIgnoreCase(strategyName)) {
            return "ai";
        } else if (strategyName.toLowerCase().contains("sentiment") || 
                  strategyName.toLowerCase().contains("news")) {
            return "sentiment";
        } else {
            return "technical";
        }
    }
    
    /**
     * Calculate profit from a signal and its resulting position
     */
    private BigDecimal calculateSignalProfit(BotSignal signal, Position position) {
        if (position == null || position.getEntryPrice() == null || position.getCurrentPrice() == null) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal currentPrice = position.getCurrentPrice();
        BigDecimal size = position.getSize();
        
        if (size == null || size.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        // Calculate profit based on position side
        BigDecimal priceDiff;
        if ("LONG".equals(position.getSide())) {
            priceDiff = currentPrice.subtract(entryPrice);
        } else {
            priceDiff = entryPrice.subtract(currentPrice);
        }
        
        return priceDiff.multiply(size);
    }
    
    /**
     * Update performance metrics
     */
    private void updateMetrics(PerformanceMetrics metrics, String strategyType, String symbol, 
                              String timeframe, BigDecimal profit, boolean isWinning) {
        
        metrics.setStrategyType(strategyType);
        metrics.setSymbol(symbol);
        metrics.setTimeframe(timeframe);
        metrics.setTotalTrades(metrics.getTotalTrades() + 1);
        
        if (isWinning) {
            metrics.setWinningTrades(metrics.getWinningTrades() + 1);
            // Update average win
            BigDecimal currentAvgWin = metrics.getAvgWin() != null ? metrics.getAvgWin() : BigDecimal.ZERO;
            BigDecimal newAvgWin = currentAvgWin.multiply(BigDecimal.valueOf(metrics.getWinningTrades() - 1))
                .add(profit)
                .divide(BigDecimal.valueOf(metrics.getWinningTrades()), 8, RoundingMode.HALF_UP);
            metrics.setAvgWin(newAvgWin);
        } else {
            metrics.setLosingTrades(metrics.getLosingTrades() + 1);
            // Update average loss
            BigDecimal currentAvgLoss = metrics.getAvgLoss() != null ? metrics.getAvgLoss() : BigDecimal.ZERO;
            BigDecimal newAvgLoss = currentAvgLoss.multiply(BigDecimal.valueOf(metrics.getLosingTrades() - 1))
                .add(profit.abs())
                .divide(BigDecimal.valueOf(metrics.getLosingTrades()), 8, RoundingMode.HALF_UP);
            metrics.setAvgLoss(newAvgLoss);
        }
        
        // Update win rate
        metrics.setWinRate((double) metrics.getWinningTrades() / metrics.getTotalTrades());
        
        // Update total profit
        BigDecimal currentTotalProfit = metrics.getTotalProfit() != null ? metrics.getTotalProfit() : BigDecimal.ZERO;
        metrics.setTotalProfit(currentTotalProfit.add(profit));
        
        metrics.setLastUpdated(LocalDateTime.now());
    }
    
    /**
     * Calculate and suggest weight adjustments based on performance
     */
    @Scheduled(fixedDelay = 3600000) // Run every hour
    @Transactional(readOnly = true)
    public void calculateWeightAdjustments() {
        logger.info("Starting automated weight adjustment calculation");
        
        try {
            List<WeightAdjustment> adjustments = new ArrayList<>();
            
            for (PerformanceMetrics metrics : performanceCache.values()) {
                if (metrics.getTotalTrades() >= MIN_SAMPLES_FOR_LEARNING) {
                    WeightAdjustment adjustment = calculateSingleWeightAdjustment(metrics);
                    if (adjustment != null) {
                        adjustments.add(adjustment);
                    }
                }
            }
            
            // Apply adjustments
            for (WeightAdjustment adjustment : adjustments) {
                applyWeightAdjustment(adjustment);
            }
            
            logger.info("Completed weight adjustment calculation. Applied {} adjustments", adjustments.size());
            
        } catch (Exception e) {
            logger.error("Error in automated weight adjustment calculation: {}", e.getMessage());
        }
    }
    
    /**
     * Calculate weight adjustment for a single performance metric
     */
    private WeightAdjustment calculateSingleWeightAdjustment(PerformanceMetrics metrics) {
        try {
            // Calculate performance score (0.0 to 1.0)
            double performanceScore = calculatePerformanceScore(metrics);
            
            // Get current weight (default if not set)
            double currentWeight = metrics.getCurrentWeight();
            if (currentWeight == 0) {
                // Set default weight based on strategy type
                currentWeight = getDefaultWeight(metrics.getStrategyType());
                metrics.setCurrentWeight(currentWeight);
            }
            
            // Calculate target weight based on performance
            double targetWeight = calculateTargetWeight(performanceScore, metrics.getStrategyType());
            
            // Apply learning rate to smooth the adjustment
            double newWeight = currentWeight + (LEARNING_RATE * (targetWeight - currentWeight));
            
            // Ensure weight bounds (0.1 to 0.7)
            newWeight = Math.max(0.1, Math.min(0.7, newWeight));
            
            // Only create adjustment if change is significant (>1%)
            if (Math.abs(newWeight - currentWeight) > 0.01) {
                String reason = String.format(
                    "Performance-based adjustment: winRate=%.1f%%, profit=%s, score=%.2f", 
                    metrics.getWinRate() * 100, 
                    metrics.getTotalProfit(), 
                    performanceScore
                );
                
                return new WeightAdjustment(
                    metrics.getStrategyType(), 
                    metrics.getSymbol(), 
                    metrics.getTimeframe(), 
                    currentWeight, 
                    newWeight, 
                    reason
                );
            }
            
        } catch (Exception e) {
            logger.error("Error calculating weight adjustment for {}: {}", 
                        buildCacheKey(metrics.getStrategyType(), metrics.getSymbol(), metrics.getTimeframe()), 
                        e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Calculate overall performance score (0.0 to 1.0)
     */
    private double calculatePerformanceScore(PerformanceMetrics metrics) {
        // Weighted combination of different performance factors
        double winRateScore = metrics.getWinRate(); // 0.0 to 1.0
        
        double profitScore = 0.5; // Default neutral
        if (metrics.getTotalProfit() != null) {
            // Normalize profit score (simplified)
            double profitValue = metrics.getTotalProfit().doubleValue();
            profitScore = Math.max(0.0, Math.min(1.0, 0.5 + (profitValue / 1000.0))); // Rough normalization
        }
        
        double sharpeScore = Math.max(0.0, Math.min(1.0, (metrics.getSharpeRatio() + 1.0) / 3.0)); // Normalize Sharpe ratio
        
        // Weighted combination
        return (winRateScore * 0.4) + (profitScore * 0.4) + (sharpeScore * 0.2);
    }
    
    /**
     * Calculate target weight based on performance score
     */
    private double calculateTargetWeight(double performanceScore, String strategyType) {
        // Map performance score to weight range
        // High performance (0.8-1.0) -> 0.5-0.7 weight
        // Average performance (0.4-0.8) -> 0.2-0.5 weight  
        // Poor performance (0.0-0.4) -> 0.1-0.2 weight
        
        if (performanceScore >= 0.8) {
            return 0.5 + (performanceScore - 0.8) * 1.0; // 0.5 to 0.7
        } else if (performanceScore >= 0.4) {
            return 0.2 + (performanceScore - 0.4) * 0.75; // 0.2 to 0.5
        } else {
            return 0.1 + performanceScore * 0.25; // 0.1 to 0.2
        }
    }
    
    /**
     * Get default weight for strategy type
     */
    private double getDefaultWeight(String strategyType) {
        switch (strategyType.toLowerCase()) {
            case "technical": return 0.4;
            case "ai": return 0.4;
            case "sentiment": return 0.2;
            default: return 0.3;
        }
    }
    
    /**
     * Apply weight adjustment
     */
    private void applyWeightAdjustment(WeightAdjustment adjustment) {
        try {
            String cacheKey = buildCacheKey(adjustment.getStrategyType(), 
                                          adjustment.getSymbol(), 
                                          adjustment.getTimeframe());
            
            PerformanceMetrics metrics = performanceCache.get(cacheKey);
            if (metrics != null) {
                metrics.setCurrentWeight(adjustment.getNewWeight());
                metrics.setSuggestedWeight(adjustment.getNewWeight());
                
                logger.info("Applied weight adjustment for {}: {} -> {} ({})", 
                           cacheKey, 
                           String.format("%.3f", adjustment.getOldWeight()),
                           String.format("%.3f", adjustment.getNewWeight()),
                           adjustment.getReason());
            }
            
        } catch (Exception e) {
            logger.error("Error applying weight adjustment: {}", e.getMessage());
        }
    }
    
    /**
     * Get learned weights for a specific symbol and timeframe
     */
    public Map<String, Double> getLearnedWeights(String symbol, String timeframe) {
        Map<String, Double> learnedWeights = new HashMap<>();
        List<String> strategyTypes = Arrays.asList("technical", "ai", "sentiment");
        
        // Ensure all strategy types have at least a default weight
        for (String strategyType : strategyTypes) {
            learnedWeights.put(strategyType, getDefaultWeight(strategyType));
        }

        // Get performance-based weights for each strategy type
        for (String strategyType : strategyTypes) {
            String cacheKey = buildCacheKey(strategyType, symbol, timeframe);
            PerformanceMetrics metrics = performanceCache.get(cacheKey);
            
            if (metrics != null && metrics.getTotalTrades() >= MIN_SAMPLES_FOR_LEARNING) {
                // Overwrite default with learned weight if available
                learnedWeights.put(strategyType, metrics.getCurrentWeight());
            }
        }
        
        // Normalize weights to sum to 1.0
        double totalWeight = learnedWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            learnedWeights.replaceAll((k, v) -> v / totalWeight);
        }
        
        return learnedWeights;
    }
    
    /**
     * Get performance metrics for all strategies
     */
    public Map<String, PerformanceMetrics> getAllPerformanceMetrics() {
        return new HashMap<>(performanceCache);
    }
    
    /**
     * Build cache key for performance metrics
     */
    private String buildCacheKey(String strategyType, String symbol, String timeframe) {
        return String.format("%s_%s_%s", strategyType, symbol, timeframe);
    }
} 