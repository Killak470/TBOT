package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.StrategyExecutionService;
import com.tradingbot.backend.service.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller for managing trading strategies and their execution
 */
@RestController
@RequestMapping("/api/strategies")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class StrategyController {

    private static final Logger logger = LoggerFactory.getLogger(StrategyController.class);
    
    private final Map<String, TradingStrategy> strategies;
    private final StrategyExecutionService strategyExecutionService;
    
    @Autowired
    public StrategyController(
            Map<String, TradingStrategy> strategies,
            StrategyExecutionService strategyExecutionService) {
        this.strategies = strategies;
        this.strategyExecutionService = strategyExecutionService;
    }
    
    /**
     * Get all available trading strategies
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getStrategies() {
        Map<String, Object> response = new HashMap<>();
        
        Map<String, Map<String, Object>> strategiesInfo = strategies.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            TradingStrategy strategy = entry.getValue();
                            Map<String, Object> info = new HashMap<>();
                            info.put("name", strategy.getName());
                            info.put("description", strategy.getDescription());
                            info.put("configuration", strategy.getConfiguration());
                            return info;
                        }
                ));
        
        response.put("success", true);
        response.put("strategies", strategiesInfo);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get a specific strategy by ID
     */
    @GetMapping("/{strategyId}")
    public ResponseEntity<Map<String, Object>> getStrategy(@PathVariable String strategyId) {
        TradingStrategy strategy = strategies.get(strategyId);
        
        if (strategy == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Strategy not found: " + strategyId);
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("id", strategyId);
        response.put("name", strategy.getName());
        response.put("description", strategy.getDescription());
        response.put("configuration", strategy.getConfiguration());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Update a strategy's configuration
     */
    @PutMapping("/{strategyId}/config")
    public ResponseEntity<Map<String, Object>> updateStrategyConfig(
            @PathVariable String strategyId,
            @RequestBody Map<String, Object> configuration) {
        
        TradingStrategy strategy = strategies.get(strategyId);
        
        if (strategy == null) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Strategy not found: " + strategyId);
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        strategy.updateConfiguration(configuration);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Configuration updated successfully");
        response.put("id", strategyId);
        response.put("name", strategy.getName());
        response.put("configuration", strategy.getConfiguration());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get execution settings
     */
    @GetMapping("/execution/settings")
    public ResponseEntity<Map<String, Object>> getExecutionSettings() {
        Map<String, Object> settings = strategyExecutionService.getExecutionSettings();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("settings", settings);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Update execution settings
     */
    @PutMapping("/execution/settings")
    public ResponseEntity<Map<String, Object>> updateExecutionSettings(
            @RequestBody Map<String, Object> settings) {
        
        Map<String, Object> updatedSettings = strategyExecutionService.updateExecutionSettings(settings);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Execution settings updated successfully");
        response.put("settings", updatedSettings);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Start trading with a strategy
     */
    @PostMapping("/execution/start")
    public ResponseEntity<Map<String, Object>> startTrading(
            @RequestParam String strategyId,
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "1h") String interval) {
        
        Map<String, Object> result = strategyExecutionService.startTrading(strategyId, symbol, interval);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * Stop trading a symbol
     */
    @PostMapping("/execution/stop")
    public ResponseEntity<Map<String, Object>> stopTrading(@RequestParam String symbol) {
        Map<String, Object> result = strategyExecutionService.stopTrading(symbol);
        
        return ResponseEntity.ok(result);
    }
    
    /**
     * Get active trading strategies
     */
    @GetMapping("/execution/active")
    public ResponseEntity<Map<String, Object>> getActiveStrategies() {
        Map<String, String> activeStrategies = strategyExecutionService.getActiveStrategies();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("activeStrategies", activeStrategies);
        response.put("count", activeStrategies.size());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Start the Sniper strategy's scheduled execution.
     */
    @PostMapping("/execution/sniper/start")
    public ResponseEntity<Map<String, Object>> startSniperStrategyExecution() {
        logger.info("Received request to START Sniper strategy execution.");
        strategyExecutionService.startSniperStrategy();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Sniper strategy execution initiated.");
        return ResponseEntity.ok(response);
    }

    /**
     * Stop the Sniper strategy's scheduled execution.
     */
    @PostMapping("/execution/sniper/stop")
    public ResponseEntity<Map<String, Object>> stopSniperStrategyExecution() {
        logger.info("Received request to STOP Sniper strategy execution.");
        strategyExecutionService.stopSniperStrategy();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Sniper strategy execution stopped.");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/execution/sniper/status")
    public ResponseEntity<Map<String, Object>> getSniperStrategyStatus() {
        try {
            boolean isActive = strategyExecutionService.isSniperStrategyActive();
            logger.debug("Fetching sniper strategy status via API. Active: {}", isActive);
            return ResponseEntity.ok(Map.of("isActive", isActive));
        } catch (Exception e) {
            logger.error("Error fetching sniper strategy status via API", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error fetching sniper strategy status: " + e.getMessage()));
        }
    }
} 