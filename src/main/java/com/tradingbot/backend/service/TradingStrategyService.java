package com.tradingbot.backend.service;

import com.tradingbot.backend.service.strategy.FibonacciStrategy;
import com.tradingbot.backend.service.strategy.MovingAverageCrossoverStrategy;
import com.tradingbot.backend.service.strategy.RsiStrategy;
import com.tradingbot.backend.service.strategy.TradingStrategy;
import com.tradingbot.backend.service.strategy.SniperEntryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Arrays;

/**
 * Service for managing and evaluating different trading strategies
 */
@Service
public class TradingStrategyService {

    private static final Logger logger = LoggerFactory.getLogger(TradingStrategyService.class);
    
    private final Map<String, TradingStrategy> strategies = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    
    private final List<TradingStrategy> strategyList;

    @Autowired
    public TradingStrategyService(List<TradingStrategy> strategyList) {
        this.strategyList = strategyList;
    }
    
    /**
     * Initialize available strategies from the Spring context
     */
    @PostConstruct
    public void initStrategies() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    for (TradingStrategy strategy : strategyList) {
                        // Assuming each strategy can provide its own unique ID
                        registerStrategy(strategy.getId(), strategy);
                    }
                    initialized = true;
                    logger.info("Initialized {} trading strategies from the Spring context.", strategies.size());
                }
            }
        }
    }
    
    /**
     * Register a new trading strategy
     * 
     * @param id Unique identifier for the strategy
     * @param strategy The strategy implementation
     */
    public void registerStrategy(String id, TradingStrategy strategy) {
        if (id == null || id.isEmpty()) {
            logger.error("Strategy {} has a null or empty ID. Cannot register.", strategy.getClass().getSimpleName());
            return;
        }
        strategies.put(id, strategy);
        logger.info("Registered strategy: {} ({})", strategy.getName(), id);
    }
    
    /**
     * Get a strategy by its ID
     * 
     * @param id The strategy ID
     * @return The trading strategy or null if not found
     */
    public TradingStrategy getStrategy(String id) {
        if (!initialized) {
            initStrategies();
        }
        return strategies.get(id);
    }
    
    /**
     * Get all registered strategies
     * 
     * @return Map of strategy IDs to their implementations
     */
    public Map<String, TradingStrategy> getAllStrategies() {
        if (!initialized) {
            initStrategies();
        }
        return Collections.unmodifiableMap(strategies);
    }
    
    /**
     * Get strategies for a specific exchange
     * 
     * @param exchange The exchange name (MEXC or BYBIT)
     * @return Map of strategy IDs to their implementations for the specified exchange
     */
    public Map<String, TradingStrategy> getStrategiesForExchange(String exchange) {
        if (!initialized) {
            initStrategies();
        }
        
        Map<String, TradingStrategy> exchangeStrategies = new HashMap<>();
        String suffix = "_" + exchange.toLowerCase();
        
        for (Map.Entry<String, TradingStrategy> entry : strategies.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                exchangeStrategies.put(entry.getKey(), entry.getValue());
            }
        }
        
        return exchangeStrategies;
    }
    
    /**
     * Update a strategy's configuration
     * 
     * @param id The strategy ID
     * @param configuration New configuration parameters
     * @return true if updated successfully, false if strategy not found
     */
    public boolean updateStrategyConfig(String id, Map<String, Object> configuration) {
        TradingStrategy strategy = strategies.get(id);
        
        if (strategy != null) {
            strategy.updateConfiguration(configuration);
            logger.info("Updated configuration for strategy: {}", id);
            return true;
        }
        
        return false;
    }
    
    /**
     * Evaluate entry signals for all registered strategies
     * 
     * @param symbol The trading pair to evaluate
     * @param interval The time interval to analyze
     * @param exchange The exchange to use (MEXC or BYBIT)
     * @return Map of strategy IDs to their signal strengths (0.0 to 1.0)
     */
    public Map<String, Double> evaluateEntrySignals(String symbol, String interval, String exchange) {
        Map<String, Double> signals = new HashMap<>();
        String suffix = "_" + exchange.toLowerCase();
        
        for (Map.Entry<String, TradingStrategy> entry : strategies.entrySet()) {
            String strategyId = entry.getKey();
            
            // Only evaluate strategies for the specified exchange
            if (!strategyId.endsWith(suffix)) {
                continue;
            }
            
            TradingStrategy strategy = entry.getValue();
            
            try {
                String signal = strategy.evaluateEntry(symbol, interval);
                boolean hasSignal = "STRONG_BUY".equalsIgnoreCase(signal) || "BUY".equalsIgnoreCase(signal);
                
                // Convert boolean to signal strength (0.0 or 1.0)
                double signalStrength = hasSignal ? 1.0 : 0.0;
                
                signals.put(strategyId, signalStrength);
            } catch (Exception e) {
                logger.error("Error evaluating entry signal for strategy {}: {}", strategyId, e.getMessage());
                signals.put(strategyId, 0.0);
            }
        }
        
        return signals;
    }
    
    /**
     * Evaluate exit signals for all registered strategies
     * 
     * @param symbol The trading pair to evaluate
     * @param interval The time interval to analyze
     * @param exchange The exchange to use (MEXC or BYBIT)
     * @return Map of strategy IDs to their signal strengths (0.0 to 1.0)
     */
    public Map<String, Double> evaluateExitSignals(String symbol, String interval, String exchange) {
        Map<String, Double> signals = new HashMap<>();
        String suffix = "_" + exchange.toLowerCase();
        
        for (Map.Entry<String, TradingStrategy> entry : strategies.entrySet()) {
            String strategyId = entry.getKey();
            
            // Only evaluate strategies for the specified exchange
            if (!strategyId.endsWith(suffix)) {
                continue;
            }
            
            TradingStrategy strategy = entry.getValue();
            
            try {
                boolean hasSignal = strategy.evaluateExit(symbol, interval);
                
                // Convert boolean to signal strength (0.0 or 1.0)
                double signalStrength = hasSignal ? 1.0 : 0.0;
                
                signals.put(strategyId, signalStrength);
            } catch (Exception e) {
                logger.error("Error evaluating exit signal for strategy {}: {}", strategyId, e.getMessage());
                signals.put(strategyId, 0.0);
            }
        }
        
        return signals;
    }
    
    /**
     * Get all active symbols being traded
     */
    public List<String> getActiveSymbols() {
        // Return a predefined list of active symbols
        // In a production system, this would come from database or configuration
        return Arrays.asList("BTCUSDT", "ETHUSDT", "KSMUSDT", "SOLUSDT", "NXPCUSDT", "MOVRUSDT", "AVAXUSDT", "WALUSDT", "TRUMPUSDT", "SUIUSDT", "HYPEUSDT", "VIRTUALUSDT", "GRASSUSDT", "PEOPLEUSDT", "PEPEUSDT");
    }
} 