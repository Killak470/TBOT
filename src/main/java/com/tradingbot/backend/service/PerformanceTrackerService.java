package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.SignalPerformance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for tracking signal performance and learning from results
 */
@Service
public class PerformanceTrackerService {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTrackerService.class);
    
    @Autowired
    private SignalGenerationService signalGenerationService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    // In-memory storage for performance data (would be database in production)
    private final Map<String, List<SignalPerformance>> performanceHistory = new ConcurrentHashMap<>();
    private final Map<String, Double> strategyPerformance = new ConcurrentHashMap<>();
    private final Map<String, Integer> strategyUsageCount = new ConcurrentHashMap<>();
    
    /**
     * Track signal performance when a position is closed
     */
    public void trackSignalPerformance(BotSignal signal, BigDecimal finalPrice, String outcome) {
        try {
            SignalPerformance performance = createPerformanceRecord(signal, finalPrice, outcome);
            
            // Store performance data
            String symbol = signal.getSymbol();
            List<SignalPerformance> history = performanceHistory.computeIfAbsent(
                symbol, k -> new ArrayList<>());
            history.add(performance);
            
            // Keep only last 100 performances per symbol
            if (history.size() > 100) {
                history.remove(0);
            }
            
            // Update strategy performance metrics
            updateStrategyMetrics(signal, performance);
            
            // Learn from the performance to adjust weights
            learnFromPerformance(performance);
            
            logger.info("Tracked performance for signal {}: {} ({}% return)", 
                       signal.getId(), outcome, performance.getActualReturn());
            
        } catch (Exception e) {
            logger.error("Error tracking signal performance: {}", e.getMessage());
        }
    }
    
    /**
     * Create a performance record from signal and outcome
     */
    private SignalPerformance createPerformanceRecord(BotSignal signal, BigDecimal finalPrice, String outcome) {
        SignalPerformance performance = new SignalPerformance(signal.getId(), signal.getSymbol());
        
        performance.setInitialConfidence(signal.getConfidence());
        performance.setActualOutcome(outcome);
        
        // Calculate returns
        BigDecimal expectedReturn = calculateExpectedReturn(signal);
        BigDecimal actualReturn = calculateActualReturn(signal, finalPrice);
        performance.setExpectedReturn(expectedReturn);
        performance.setActualReturn(actualReturn);
        
        // Get market context at the time
        performance.setMarketRegime(signalGenerationService.detectMarketRegime(signal.getSymbol()));
        
        try {
            BigDecimal volatility = riskManagementService.calculateVolatility(signal.getSymbol(), "BYBIT");
            performance.setVolatilityAtEntry(volatility);
        } catch (Exception e) {
            logger.warn("Could not get volatility for {}: {}", signal.getSymbol(), e.getMessage());
        }
        
        // Analyze component accuracy
        analyzeComponentAccuracy(performance, signal, outcome);
        
        performance.setClosedAt(LocalDateTime.now());
        
        return performance;
    }
    
    /**
     * Analyze how well each signal component performed
     */
    private void analyzeComponentAccuracy(SignalPerformance performance, BotSignal signal, String outcome) {
        // This would analyze the original signal components vs actual outcome
        // For now, we'll use simplified heuristics
        
        String strategyName = signal.getStrategyName();
        boolean wasCorrect = "WIN".equals(outcome);
        
        if (strategyName.contains("Technical")) {
            performance.setTechnicalAccuracy(BigDecimal.valueOf(wasCorrect ? 0.8 : 0.3));
            performance.setAiAccuracy(BigDecimal.valueOf(0.5)); // Neutral
            performance.setSentimentAccuracy(BigDecimal.valueOf(0.5)); // Neutral
        } else if (strategyName.contains("AI")) {
            performance.setAiAccuracy(BigDecimal.valueOf(wasCorrect ? 0.85 : 0.2));
            performance.setTechnicalAccuracy(BigDecimal.valueOf(0.5)); // Neutral
            performance.setSentimentAccuracy(BigDecimal.valueOf(0.5)); // Neutral
        } else if (strategyName.contains("Sentiment")) {
            performance.setSentimentAccuracy(BigDecimal.valueOf(wasCorrect ? 0.75 : 0.25));
            performance.setTechnicalAccuracy(BigDecimal.valueOf(0.5)); // Neutral
            performance.setAiAccuracy(BigDecimal.valueOf(0.5)); // Neutral
        } else {
            // Multi-factor strategy - distribute accuracy
            double baseAccuracy = wasCorrect ? 0.7 : 0.3;
            performance.setTechnicalAccuracy(BigDecimal.valueOf(baseAccuracy));
            performance.setAiAccuracy(BigDecimal.valueOf(baseAccuracy));
            performance.setSentimentAccuracy(BigDecimal.valueOf(baseAccuracy));
        }
    }
    
    /**
     * Update strategy performance metrics
     */
    private void updateStrategyMetrics(BotSignal signal, SignalPerformance performance) {
        String strategyKey = signal.getStrategyName() + "_" + signal.getSymbol();
        
        // Update usage count
        strategyUsageCount.merge(strategyKey, 1, Integer::sum);
        
        // Update performance (simple moving average)
        double currentPerf = strategyPerformance.getOrDefault(strategyKey, 0.0);
        double newReturn = performance.getActualReturn() != null ? 
            performance.getActualReturn().doubleValue() : 0.0;
        
        int count = strategyUsageCount.get(strategyKey);
        double updatedPerf = (currentPerf * (count - 1) + newReturn) / count;
        
        strategyPerformance.put(strategyKey, updatedPerf);
        
        logger.debug("Updated strategy performance for {}: {} (count: {})", 
                    strategyKey, updatedPerf, count);
    }
    
    /**
     * Learn from performance to adjust signal generation weights
     */
    private void learnFromPerformance(SignalPerformance performance) {
        try {
            Map<String, Double> currentWeights = signalGenerationService.getSignalWeights();
            Map<String, Double> adjustments = new HashMap<>();
            
            // Analyze which components performed well
            double aiAccuracy = performance.getAiAccuracy() != null ? 
                performance.getAiAccuracy().doubleValue() : 0.5;
            double techAccuracy = performance.getTechnicalAccuracy() != null ? 
                performance.getTechnicalAccuracy().doubleValue() : 0.5;
            double sentimentAccuracy = performance.getSentimentAccuracy() != null ? 
                performance.getSentimentAccuracy().doubleValue() : 0.5;
            
            // Calculate weight adjustments based on accuracy
            double learningRate = 0.01; // Small learning rate for stability
            
            if (aiAccuracy > 0.7) {
                adjustments.put("ai", learningRate);
            } else if (aiAccuracy < 0.3) {
                adjustments.put("ai", -learningRate);
            }
            
            if (techAccuracy > 0.7) {
                adjustments.put("technical", learningRate);
            } else if (techAccuracy < 0.3) {
                adjustments.put("technical", -learningRate);
            }
            
            if (sentimentAccuracy > 0.7) {
                adjustments.put("sentiment", learningRate);
            } else if (sentimentAccuracy < 0.3) {
                adjustments.put("sentiment", -learningRate);
            }
            
            // Apply adjustments if any
            if (!adjustments.isEmpty()) {
                Map<String, Double> newWeights = applyWeightAdjustments(currentWeights, adjustments);
                signalGenerationService.updateSignalWeights(newWeights);
                
                logger.debug("Applied weight adjustments: {}", adjustments);
            }
            
        } catch (Exception e) {
            logger.warn("Error learning from performance: {}", e.getMessage());
        }
    }
    
    /**
     * Apply weight adjustments and normalize
     */
    private Map<String, Double> applyWeightAdjustments(
            Map<String, Double> currentWeights, 
            Map<String, Double> adjustments) {
        
        Map<String, Double> newWeights = new HashMap<>(currentWeights);
        
        // Apply adjustments
        for (Map.Entry<String, Double> adjustment : adjustments.entrySet()) {
            String component = adjustment.getKey();
            double delta = adjustment.getValue();
            
            double currentWeight = newWeights.getOrDefault(component, 0.0);
            double newWeight = Math.max(0.05, Math.min(0.8, currentWeight + delta));
            newWeights.put(component, newWeight);
        }
        
        // Normalize weights to sum to 1.0
        double totalWeight = newWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            newWeights.replaceAll((k, v) -> v / totalWeight);
        }
        
        return newWeights;
    }
    
    /**
     * Get performance statistics for a symbol
     */
    public Map<String, Object> getPerformanceStats(String symbol) {
        List<SignalPerformance> history = performanceHistory.getOrDefault(symbol, new ArrayList<>());
        
        if (history.isEmpty()) {
            return Map.of("status", "No performance data available");
        }
        
        // Calculate statistics
        long winCount = history.stream()
            .filter(p -> "WIN".equals(p.getActualOutcome()))
            .count();
        
        double winRate = (double) winCount / history.size();
        
        double avgReturn = history.stream()
            .filter(p -> p.getActualReturn() != null)
            .mapToDouble(p -> p.getActualReturn().doubleValue())
            .average()
            .orElse(0.0);
        
        double avgWin = history.stream()
            .filter(p -> "WIN".equals(p.getActualOutcome()) && p.getActualReturn() != null)
            .mapToDouble(p -> p.getActualReturn().doubleValue())
            .average()
            .orElse(0.0);
        
        double avgLoss = history.stream()
            .filter(p -> "LOSS".equals(p.getActualOutcome()) && p.getActualReturn() != null)
            .mapToDouble(p -> Math.abs(p.getActualReturn().doubleValue()))
            .average()
            .orElse(0.0);
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("symbol", symbol);
        stats.put("totalSignals", history.size());
        stats.put("winRate", winRate);
        stats.put("avgReturn", avgReturn);
        stats.put("avgWin", avgWin);
        stats.put("avgLoss", avgLoss);
        stats.put("profitFactor", avgLoss > 0 ? (avgWin * winRate) / (avgLoss * (1 - winRate)) : 0);
        
        return stats;
    }
    
    /**
     * Get best performing strategies
     */
    public List<Map<String, Object>> getBestStrategies(int limit) {
        return strategyPerformance.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(limit)
            .map(entry -> {
                Map<String, Object> result = new HashMap<>();
                result.put("strategy", entry.getKey());
                result.put("performance", entry.getValue());
                result.put("usageCount", strategyUsageCount.getOrDefault(entry.getKey(), 0));
                return result;
            })
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * Helper methods for return calculations
     */
    private BigDecimal calculateExpectedReturn(BotSignal signal) {
        if (signal.getTakeProfit() != null && signal.getEntryPrice() != null) {
            return signal.getTakeProfit().subtract(signal.getEntryPrice())
                .divide(signal.getEntryPrice(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
    
    private BigDecimal calculateActualReturn(BotSignal signal, BigDecimal finalPrice) {
        if (signal.getEntryPrice() != null && finalPrice != null) {
            return finalPrice.subtract(signal.getEntryPrice())
                .divide(signal.getEntryPrice(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
} 