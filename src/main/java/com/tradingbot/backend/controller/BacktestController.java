package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.BacktestingService;
import com.tradingbot.backend.service.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/backtest")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class BacktestController {

    private static final Logger logger = LoggerFactory.getLogger(BacktestController.class);
    
    private final BacktestingService backtestingService;
    private final Map<String, TradingStrategy> strategies;
    
    public BacktestController(
            BacktestingService backtestingService,
            Map<String, TradingStrategy> strategies) {
        this.backtestingService = backtestingService;
        this.strategies = strategies;
    }
    
    /**
     * Run a backtest for a specific trading strategy
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam String strategyName,
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam Long startTime,
            @RequestParam Long endTime,
            @RequestParam(required = false, defaultValue = "10000") Double initialCapital) {
        
        logger.info("Running backtest for {} on {} from {} to {}", 
                strategyName, symbol, startTime, endTime);
        
        TradingStrategy strategy = strategies.get(strategyName);
        
        if (strategy == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Strategy not found: " + strategyName);
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        Map<String, Object> results = backtestingService.runBacktest(
                strategy, symbol, interval, startTime, endTime, initialCapital);
        
        return ResponseEntity.ok(results);
    }
    
    /**
     * Get a list of all available trading strategies
     */
    @GetMapping("/strategies")
    public ResponseEntity<Map<String, Object>> getAvailableStrategies() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> strategiesInfo = new HashMap<>();
        
        for (Map.Entry<String, TradingStrategy> entry : strategies.entrySet()) {
            TradingStrategy strategy = entry.getValue();
            Map<String, Object> strategyInfo = new HashMap<>();
            
            strategyInfo.put("name", strategy.getName());
            strategyInfo.put("description", strategy.getDescription());
            strategyInfo.put("configuration", strategy.getConfiguration());
            
            strategiesInfo.put(entry.getKey(), strategyInfo);
        }
        
        response.put("success", true);
        response.put("strategies", strategiesInfo);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Update a strategy's configuration parameters
     */
    @PutMapping("/strategies/{strategyName}/config")
    public ResponseEntity<Map<String, Object>> updateStrategyConfig(
            @PathVariable String strategyName,
            @RequestBody Map<String, Object> configuration) {
        
        TradingStrategy strategy = strategies.get(strategyName);
        
        if (strategy == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Strategy not found: " + strategyName);
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        strategy.updateConfiguration(configuration);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Configuration updated successfully");
        response.put("strategy", strategyName);
        response.put("configuration", strategy.getConfiguration());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Compare multiple strategies in a backtest
     */
    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareStrategies(
            @RequestParam String[] strategyNames,
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam Long startTime,
            @RequestParam Long endTime,
            @RequestParam(required = false, defaultValue = "10000") Double initialCapital) {
        
        logger.info("Comparing {} strategies on {} from {} to {}", 
                strategyNames.length, symbol, startTime, endTime);
        
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> results = new HashMap<>();
        
        for (String strategyName : strategyNames) {
            TradingStrategy strategy = strategies.get(strategyName);
            
            if (strategy != null) {
                Map<String, Object> strategyResult = backtestingService.runBacktest(
                        strategy, symbol, interval, startTime, endTime, initialCapital);
                
                results.put(strategyName, strategyResult);
            } else {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("success", false);
                errorResult.put("message", "Strategy not found: " + strategyName);
                results.put(strategyName, errorResult);
            }
        }
        
        response.put("success", true);
        response.put("results", results);
        
        return ResponseEntity.ok(response);
    }
} 