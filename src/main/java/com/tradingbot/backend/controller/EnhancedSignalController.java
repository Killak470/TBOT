package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.SignalGenerationService;
import com.tradingbot.backend.service.PerformanceTrackerService;
import com.tradingbot.backend.service.RiskManagementService;
import com.tradingbot.backend.service.MarketScannerService;
import com.tradingbot.backend.model.ScanConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Enhanced Signal Controller showcasing advanced trading bot features
 */
@RestController
@RequestMapping("/api/enhanced-signals")
@CrossOrigin(origins = "*")
public class EnhancedSignalController {
    
    @Autowired
    private SignalGenerationService signalGenerationService;
    
    @Autowired
    private PerformanceTrackerService performanceTrackerService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private MarketScannerService marketScannerService;
    
    /**
     * Generate enhanced multi-timeframe signal
     */
    @GetMapping("/multi-timeframe/{symbol}")
    public ResponseEntity<Map<String, Object>> generateEnhancedSignal(@PathVariable String symbol) {
        try {
            Map<String, Object> signal = signalGenerationService.generateEnhancedSignal(symbol);
            
            // Add additional context
            String marketRegime = signalGenerationService.detectMarketRegime(symbol);
            signal.put("marketRegime", marketRegime);
            signal.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(signal);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to generate enhanced signal: " + e.getMessage())
            );
        }
    }
    
    /**
     * Get market regime analysis
     */
    @GetMapping("/market-regime/{symbol}")
    public ResponseEntity<Map<String, Object>> getMarketRegime(@PathVariable String symbol) {
        try {
            String regime = signalGenerationService.detectMarketRegime(symbol);
            
            Map<String, Object> analysis = new HashMap<>();
            analysis.put("symbol", symbol);
            analysis.put("marketRegime", regime);
            analysis.put("description", getRegimeDescription(regime));
            analysis.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(analysis);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to analyze market regime: " + e.getMessage())
            );
        }
    }
    
    /**
     * Calculate optimal position size using Kelly Criterion
     */
    @PostMapping("/optimal-position-size")
    public ResponseEntity<Map<String, Object>> calculateOptimalPositionSize(
            @RequestBody Map<String, Object> request) {
        try {
            String symbol = (String) request.get("symbol");
            BigDecimal entryPrice = new BigDecimal(request.get("entryPrice").toString());
            BigDecimal stopLoss = new BigDecimal(request.get("stopLoss").toString());
            BigDecimal accountBalance = new BigDecimal(request.get("accountBalance").toString());
            
            // Get historical performance data
            double winRate = riskManagementService.getHistoricalWinRate(symbol);
            Map<String, Double> ratios = riskManagementService.getHistoricalWinLossRatios(symbol);
            
            BigDecimal optimalSize = riskManagementService.calculateOptimalPositionSize(
                symbol, entryPrice, stopLoss, winRate, 
                ratios.get("avgWin"), ratios.get("avgLoss"), accountBalance
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("symbol", symbol);
            result.put("optimalPositionSize", optimalSize);
            result.put("winRate", winRate);
            result.put("avgWin", ratios.get("avgWin"));
            result.put("avgLoss", ratios.get("avgLoss"));
            result.put("kellyFraction", calculateKellyFraction(winRate, ratios.get("avgWin"), ratios.get("avgLoss")));
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to calculate optimal position size: " + e.getMessage())
            );
        }
    }
    
    /**
     * Get performance statistics
     */
    @GetMapping("/performance/{symbol}")
    public ResponseEntity<Map<String, Object>> getPerformanceStats(@PathVariable String symbol) {
        try {
            Map<String, Object> stats = performanceTrackerService.getPerformanceStats(symbol);
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to get performance stats: " + e.getMessage())
            );
        }
    }
    
    /**
     * Get best performing strategies
     */
    @GetMapping("/best-strategies")
    public ResponseEntity<List<Map<String, Object>>> getBestStrategies(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<Map<String, Object>> strategies = performanceTrackerService.getBestStrategies(limit);
            return ResponseEntity.ok(strategies);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                List.of(Map.of("error", "Failed to get best strategies: " + e.getMessage()))
            );
        }
    }
    
    /**
     * Get current signal weights
     */
    @GetMapping("/signal-weights")
    public ResponseEntity<Map<String, Object>> getSignalWeights() {
        try {
            Map<String, Double> weights = signalGenerationService.getSignalWeights();
            
            Map<String, Object> result = new HashMap<>();
            result.put("weights", weights);
            result.put("timestamp", System.currentTimeMillis());
            result.put("description", "Current adaptive signal generation weights");
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to get signal weights: " + e.getMessage())
            );
        }
    }
    
    /**
     * Enhanced market scan with intelligent leverage
     */
    @PostMapping("/enhanced-scan")
    public ResponseEntity<Map<String, Object>> enhancedMarketScan(
            @RequestBody ScanConfig config) {
        try {
            // Perform standard market scan
            var scanResults = marketScannerService.scanMarket(config);
            
            // Enhance results with additional analysis
            Map<String, Object> enhancedResults = new HashMap<>();
            enhancedResults.put("results", scanResults);
            enhancedResults.put("totalScanned", scanResults.size());
            enhancedResults.put("timestamp", System.currentTimeMillis());
            
            // Add market regime analysis for top results
            Map<String, String> regimes = new HashMap<>();
            scanResults.stream()
                .limit(5)
                .forEach(result -> {
                    try {
                        String regime = signalGenerationService.detectMarketRegime(result.getSymbol());
                        regimes.put(result.getSymbol(), regime);
                    } catch (Exception e) {
                        regimes.put(result.getSymbol(), "UNKNOWN");
                    }
                });
            
            enhancedResults.put("marketRegimes", regimes);
            enhancedResults.put("enhancementType", "MULTI_FACTOR_ANALYSIS");
            
            return ResponseEntity.ok(enhancedResults);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to perform enhanced scan: " + e.getMessage())
            );
        }
    }
    
    /**
     * Test enhanced signal generation for multiple symbols
     */
    @PostMapping("/batch-enhanced")
    public ResponseEntity<Map<String, Object>> batchEnhancedSignals(
            @RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<String> symbols = (List<String>) request.get("symbols");
            
            Map<String, Object> results = new HashMap<>();
            Map<String, Map<String, Object>> signalsBySymbol = new HashMap<>();
            
            for (String symbol : symbols) {
                try {
                    Map<String, Object> signal = signalGenerationService.generateEnhancedSignal(symbol);
                    signalsBySymbol.put(symbol, signal);
                } catch (Exception e) {
                    signalsBySymbol.put(symbol, Map.of("error", e.getMessage()));
                }
            }
            
            results.put("signals", signalsBySymbol);
            results.put("processedSymbols", symbols.size());
            results.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(results);
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "Failed to generate batch signals: " + e.getMessage())
            );
        }
    }
    
    // Helper methods
    private String getRegimeDescription(String regime) {
        switch (regime) {
            case "BULL_MARKET":
                return "Strong upward trend with low volatility - favorable for long positions";
            case "BEAR_MARKET":
                return "Strong downward trend with low volatility - favorable for short positions";
            case "SIDEWAYS_MARKET":
                return "Range-bound market - consider mean reversion strategies";
            case "HIGH_VOLATILITY":
                return "High volatility environment - use smaller position sizes and wider stops";
            case "LOW_VOLATILITY":
                return "Low volatility environment - potential for breakout, can use higher leverage";
            default:
                return "Market conditions are mixed or unclear";
        }
    }
    
    private double calculateKellyFraction(double winRate, double avgWin, double avgLoss) {
        if (avgLoss <= 0) return 0.01; // Avoid division by zero
        
        double b = avgWin / avgLoss;
        double p = winRate;
        double q = 1 - winRate;
        
        double kelly = (b * p - q) / b;
        return Math.max(0.01, Math.min(0.25, kelly)); // Cap between 1% and 25%
    }
} 