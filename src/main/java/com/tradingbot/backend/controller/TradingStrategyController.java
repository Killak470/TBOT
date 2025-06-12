package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TradeSignal;
import com.tradingbot.backend.service.MarketScannerService;
import com.tradingbot.backend.service.strategy.TradingStrategyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/strategy")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class TradingStrategyController {

    private static final Logger logger = LoggerFactory.getLogger(TradingStrategyController.class);
    private final TradingStrategyProvider tradingStrategyProvider;
    private final MarketScannerService marketScannerService;

    public TradingStrategyController(TradingStrategyProvider tradingStrategyProvider, MarketScannerService marketScannerService) {
        this.tradingStrategyProvider = tradingStrategyProvider;
        this.marketScannerService = marketScannerService;
    }

    /**
     * Apply a strategy to the latest market scan results
     */
    @GetMapping("/apply")
    public ResponseEntity<Map<String, Object>> applyStrategy(
            @RequestParam String strategyType,
            @RequestParam(required = false) String pairs,
            @RequestParam(required = false) String interval) {
        
        logger.info("Applying strategy {} to pairs {} with interval {}", strategyType, pairs, interval);
        
        try {
            // Convert the strategy type string to enum
            TradingStrategyProvider.StrategyType type = TradingStrategyProvider.StrategyType.valueOf(strategyType.toUpperCase());
            
            // Run a market scan to get the latest data
            com.tradingbot.backend.model.ScanConfig scanConfig = new com.tradingbot.backend.model.ScanConfig();
            
            // Set interval if provided (default to "1h")
            if (interval != null && !interval.isEmpty()) {
                scanConfig.setInterval(interval);
            } else {
                scanConfig.setInterval("1h");
            }
            
            // Parse trading pairs if provided
            if (pairs != null && !pairs.isEmpty()) {
                List<String> tradingPairs = java.util.Arrays.asList(pairs.split(","));
                scanConfig.setTradingPairs(tradingPairs);
            }
            
            // Run the scan
            List<ScanResult> scanResults = marketScannerService.scanMarket(scanConfig);
            
            // Apply the strategy with default parameters
            List<TradeSignal> signals = tradingStrategyProvider.generateSignals(scanResults, type, null);
            
            // Format the response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("timestamp", System.currentTimeMillis());
            response.put("strategy", strategyType);
            response.put("signals", signals);
            response.put("count", signals.size());
            
            logger.info("Generated {} signals using {} strategy", signals.size(), strategyType);
            
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Invalid strategy type
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Invalid strategy type: " + strategyType);
            
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            // Other errors
            logger.error("Error applying strategy {}: {}", strategyType, e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Error applying strategy: " + e.getMessage());
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    /**
     * Apply a strategy with custom parameters to the latest market scan results
     */
    @PostMapping("/apply-custom")
    public ResponseEntity<Map<String, Object>> applyCustomStrategy(
            @RequestBody Map<String, Object> request) {
        
        try {
            // Extract parameters from request
            String strategyType = (String) request.get("strategyType");
            if (strategyType == null) {
                throw new IllegalArgumentException("Strategy type is required");
            }
            
            // Convert the strategy type string to enum
            TradingStrategyProvider.StrategyType type = TradingStrategyProvider.StrategyType.valueOf(strategyType.toUpperCase());
            
            // Extract other parameters
            @SuppressWarnings("unchecked")
            Map<String, Object> strategyParams = (Map<String, Object>) request.get("parameters");
            @SuppressWarnings("unchecked")
            List<String> pairs = (List<String>) request.get("tradingPairs");
            String interval = (String) request.get("interval");
            
            logger.info("Applying custom {} strategy with parameters: {}", strategyType, strategyParams);
            
            // Run a market scan to get the latest data
            com.tradingbot.backend.model.ScanConfig scanConfig = new com.tradingbot.backend.model.ScanConfig();
            
            // Set interval
            scanConfig.setInterval(interval != null ? interval : "1h");
            
            // Set trading pairs
            if (pairs != null && !pairs.isEmpty()) {
                scanConfig.setTradingPairs(pairs);
            }
            
            // Run the scan
            List<ScanResult> scanResults = marketScannerService.scanMarket(scanConfig);
            
            // Apply the strategy with custom parameters
            List<TradeSignal> signals = tradingStrategyProvider.generateSignals(scanResults, type, strategyParams);
            
            // Format the response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("timestamp", System.currentTimeMillis());
            response.put("strategy", strategyType);
            response.put("parameters", strategyParams);
            response.put("signals", signals);
            response.put("count", signals.size());
            
            logger.info("Generated {} signals using custom {} strategy", signals.size(), strategyType);
            
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Invalid parameters
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            // Other errors
            logger.error("Error applying custom strategy: {}", e.getMessage(), e);
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Error applying strategy: " + e.getMessage());
            
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    /**
     * Get available strategies and their parameters
     */
    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> getAvailableStrategies() {
        Map<String, Object> response = new HashMap<>();
        
        // List all available strategies
        response.put("strategies", java.util.Arrays.stream(TradingStrategyProvider.StrategyType.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toList()));
        
        // Add descriptions and parameter definitions for each strategy
        Map<String, Object> descriptions = new HashMap<>();
        
        descriptions.put("MOVING_AVERAGE_CROSSOVER", Map.of(
            "description", "Generates buy signals when fast MA crosses above slow MA, and sell signals when it crosses below",
            "parameters", Map.of(
                "fastPeriod", "Period for the fast moving average (default: 20)",
                "slowPeriod", "Period for the slow moving average (default: 50)"
            )
        ));
        
        descriptions.put("RSI_OVERSOLD_OVERBOUGHT", Map.of(
            "description", "Generates buy signals when RSI is oversold and sell signals when it's overbought",
            "parameters", Map.of(
                "period", "RSI period (default: 14)",
                "oversoldThreshold", "Threshold for oversold condition (default: 30)",
                "overboughtThreshold", "Threshold for overbought condition (default: 70)"
            )
        ));
        
        descriptions.put("MACD_SIGNAL_CROSS", Map.of(
            "description", "Generates signals based on MACD line crossing the signal line",
            "parameters", Map.of(
                "fastEMA", "Fast EMA period (default: 12)",
                "slowEMA", "Slow EMA period (default: 26)",
                "signalLine", "Signal line period (default: 9)"
            )
        ));
        
        descriptions.put("BOLLINGER_BAND_BREAKOUT", Map.of(
            "description", "Generates signals when price breaks out of Bollinger Bands",
            "parameters", Map.of(
                "period", "Period for the middle band calculation (default: 20)",
                "stdDev", "Standard deviation multiplier (default: 2.0)"
            )
        ));
        
        descriptions.put("VOLUME_PRICE_CONFIRMATION", Map.of(
            "description", "Generates signals based on price movements confirmed by volume",
            "parameters", Map.of(
                "volumeThreshold", "Volume increase threshold (default: 1.5)",
                "priceChangeThreshold", "Minimum price change percentage (default: 1.0)"
            )
        ));
        
        descriptions.put("FIBONACCI_RETRACEMENT", Map.of(
            "description", "Generates signals based on price reaching Fibonacci retracement levels",
            "parameters", Map.of(
                "lookbackPeriods", "Number of periods to look back for high/low (default: 100)"
            )
        ));
        
        descriptions.put("COMBINED_TECHNICAL", Map.of(
            "description", "Uses multiple technical indicators for a more robust signal",
            "parameters", Map.of(
                "minimumIndicators", "Minimum number of indicators required for a signal (default: 3)",
                "minimumConsensus", "Minimum percentage of indicators agreeing for a signal (default: 60%)"
            )
        ));
        
        descriptions.put("CUSTOM", Map.of(
            "description", "Custom user-defined strategy using supported indicators",
            "parameters", Map.of(
                "customLogic", "JSON representation of custom strategy logic"
            )
        ));
        
        response.put("descriptions", descriptions);
        
        return ResponseEntity.ok(response);
    }
} 