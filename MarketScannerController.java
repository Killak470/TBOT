package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.TechnicalIndicator;
import com.tradingbot.backend.service.MarketScannerService;
import com.tradingbot.backend.service.BotSignalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/scanner")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class MarketScannerController {

    private static final Logger logger = LoggerFactory.getLogger(MarketScannerController.class);
    private final MarketScannerService marketScannerService;
    private final BotSignalService botSignalService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public MarketScannerController(MarketScannerService marketScannerService, BotSignalService botSignalService) {
        this.marketScannerService = marketScannerService;
        this.botSignalService = botSignalService;
    }

    /**
     * Scan market with default configuration - asynchronous version
     */
    @GetMapping("/quick-scan")
    public DeferredResult<ResponseEntity<Map<String, Object>>> quickScan(
            @RequestParam(required = false) String interval,
            @RequestParam(required = false) String pairs,
            @RequestParam(required = false, defaultValue = "spot") String marketType,
            @RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        
        logger.info("Starting quick scan with interval: {}, pairs: {}, marketType: {}, exchange: {}",
                    interval, pairs, marketType, exchange);
        
        // Create deferred result with 30s timeout
        DeferredResult<ResponseEntity<Map<String, Object>>> deferredResult = new DeferredResult<>(30000L);
        
        // Set timeout callback
        deferredResult.onTimeout(() -> {
            logger.warn("Quick scan request timed out for interval: {}, pairs: {}, marketType: {}", interval, pairs, marketType);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Request timed out. Try again with fewer pairs or a shorter interval.");
            deferredResult.setResult(ResponseEntity.ok(response));
        });
        
        // Create default scan configuration
        ScanConfig config = new ScanConfig();
        
        // Set market type and exchange
        config.setMarketType(marketType);
        config.setExchange(exchange);
        
        // Set interval if provided (default to "1h")
        if (interval != null && !interval.isEmpty()) {
            config.setInterval(interval);
        } else {
            config.setInterval("1h");
        }
        
        // Parse trading pairs if provided
        if (pairs != null && !pairs.isEmpty()) {
            List<String> tradingPairs = Arrays.asList(pairs.split(","));
            config.setTradingPairs(tradingPairs);
        }
        
        // Execute the scan asynchronously
        CompletableFuture.supplyAsync(() -> marketScannerService.scanMarket(config))
            .thenAccept(results -> {
                // Format the response
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("timestamp", System.currentTimeMillis());
                response.put("marketType", marketType);
                response.put("results", results);
                response.put("count", results.size());
                
                logger.info("Quick scan completed with {} results", results.size());
                
                // Set the deferred result
                deferredResult.setResult(ResponseEntity.ok(response));
            })
            .exceptionally(ex -> {
                logger.error("Error during quick scan", ex);
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "Failed to complete scan: " + ex.getMessage());
                deferredResult.setResult(ResponseEntity.ok(response));
                return null;
            });
        
        return deferredResult;
    }
    
    /**
     * Scan market with custom configuration - asynchronous version
     */
    @PostMapping("/custom-scan")
    public DeferredResult<ResponseEntity<Map<String, Object>>> customScan(@RequestBody ScanConfig config) {
        logger.info("Starting custom scan with config: {}", config);
        
        // Create deferred result with 30s timeout
        DeferredResult<ResponseEntity<Map<String, Object>>> deferredResult = new DeferredResult<>(30000L);
        
        // Set timeout callback
        deferredResult.onTimeout(() -> {
            logger.warn("Custom scan request timed out for config: {}", config);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Request timed out. Try again with fewer pairs or a shorter interval.");
            deferredResult.setResult(ResponseEntity.ok(response));
        });
        
        // Execute the scan asynchronously
        CompletableFuture.supplyAsync(() -> marketScannerService.scanMarket(config))
            .thenAccept(results -> {
                // Format the response
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("timestamp", System.currentTimeMillis());
                response.put("results", results);
                response.put("count", results.size());
                
                logger.info("Custom scan completed with {} results", results.size());
                
                // Set the deferred result
                deferredResult.setResult(ResponseEntity.ok(response));
            })
            .exceptionally(ex -> {
                logger.error("Error during custom scan", ex);
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "Failed to complete scan: " + ex.getMessage());
                deferredResult.setResult(ResponseEntity.ok(response));
                return null;
            });
        
        return deferredResult;
    }
    
    /**
     * Get supported intervals and trading pairs
     */
    @GetMapping("/config-options")
    public ResponseEntity<Map<String, Object>> getConfigOptions() {
        Map<String, Object> options = new HashMap<>();
        
        // Common intervals
        options.put("intervals", Arrays.asList("5m", "15m", "30m", "1h", "4h", "1d"));
        
        // Market types
        options.put("marketTypes", Arrays.asList("spot", "futures"));
        
        // Signal strengths
        options.put("signalStrengths", Arrays.asList("STRONG_BUY", "BUY", "NEUTRAL", "SELL", "STRONG_SELL"));
        
        // Trading pairs that match the ones used in the position dashboard
        options.put("tradingPairs", Arrays.asList(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "NXPCUSDT", "KSMUSDT", "MOVRUSDT", "DOGEUSDT", "WALUSDT", "ETH_USDT", "BTC_USDT"
        ));
        
        return ResponseEntity.ok(options);
    }

    /**
     * Generate bot signals from market scanner results
     * This endpoint allows users to convert promising scan results into actionable trading signals
     */
    @PostMapping("/generate-signals")
    public ResponseEntity<Map<String, Object>> generateSignalsFromScan(@RequestBody Map<String, Object> request) {
        logger.info("Starting signal generation from scanner results");
        
        try {
            // Extract scan configuration from request
            @SuppressWarnings("unchecked")
            List<String> symbols = (List<String>) request.get("symbols");
            String interval = (String) request.getOrDefault("interval", "1h");
            String minSignalStrength = (String) request.getOrDefault("minSignalStrength", "BUY");
            Boolean includeAiAnalysis = (Boolean) request.getOrDefault("includeAiAnalysis", true);
            
            if (symbols == null || symbols.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Symbols list is required"
                ));
            }
            
            // Run market scan first to get technical analysis
            ScanConfig scanConfig = new ScanConfig();
            scanConfig.setTradingPairs(symbols);
            scanConfig.setInterval(interval);
            scanConfig.setMinimumSignalStrength(minSignalStrength);
            scanConfig.setIncludeAiAnalysis(includeAiAnalysis);
            
            // Set exchange if provided
            String exchange = (String) request.getOrDefault("exchange", "MEXC");
            scanConfig.setExchange(exchange);
            
            // Set market type if provided
            String marketType = (String) request.getOrDefault("marketType", "spot");
            scanConfig.setMarketType(marketType);

            List<ScanResult> scanResults = marketScannerService.scanMarket(scanConfig);
            
            // Filter scan results that meet signal criteria
            List<ScanResult> qualifyingResults = scanResults.stream()
                .filter(result -> isQualifyingForSignal(result, minSignalStrength))
                .collect(Collectors.toList());
            
            logger.info("Found {} qualifying scan results for signal generation", qualifyingResults.size());
            
            // Generate bot signals for qualifying results
            List<Map<String, Object>> generatedSignals = new ArrayList<>();
            
            for (ScanResult result : qualifyingResults) {
                try {
                    Map<String, Object> signal = createBotSignalFromScanResult(result, exchange);
                    if (signal != null) {
                        generatedSignals.add(signal);
                    }
                } catch (Exception e) {
                    logger.error("Error generating signal for {}: {}", result.getSymbol(), e.getMessage());
                }
            }
            
            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("timestamp", System.currentTimeMillis());
            response.put("scannedResults", scanResults.size());
            response.put("qualifyingResults", qualifyingResults.size());
            response.put("signalsGenerated", generatedSignals.size());
            response.put("signals", generatedSignals);
            response.put("message", String.format("Generated %d bot signals from %d qualifying scan results", 
                                                generatedSignals.size(), qualifyingResults.size()));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error generating signals from scan", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to generate signals: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Check if scan result qualifies for signal generation
     */
    private boolean isQualifyingForSignal(ScanResult result, String minSignalStrength) {
        String signal = result.getSignal();
        
        // Only generate signals for buy/sell recommendations, not neutral
        if (!"STRONG_BUY".equals(signal) && !"BUY".equals(signal) && 
            !"STRONG_SELL".equals(signal) && !"SELL".equals(signal)) {
            return false;
        }
        
        // Check if it meets minimum signal strength requirement
        switch (minSignalStrength) {
            case "STRONG_BUY":
                return "STRONG_BUY".equals(signal);
            case "BUY":
                return "STRONG_BUY".equals(signal) || "BUY".equals(signal);
            case "STRONG_SELL":
                return "STRONG_SELL".equals(signal);
            case "SELL":
                return "STRONG_SELL".equals(signal) || "SELL".equals(signal);
            default:
                return true;
        }
    }
    
    /**
     * Create a bot signal from scan result
     */
    private Map<String, Object> createBotSignalFromScanResult(ScanResult result, String exchange) {
        try {
            String signal = result.getSignal();
            
            // Determine signal type
            BotSignal.SignalType signalType;
            if ("STRONG_BUY".equals(signal) || "BUY".equals(signal)) {
                signalType = BotSignal.SignalType.BUY;
            } else if ("STRONG_SELL".equals(signal) || "SELL".equals(signal)) {
                signalType = BotSignal.SignalType.SELL;
            } else {
                return null; // Skip neutral signals
            }
            
            // Calculate confidence based on signal strength and technical indicators
            BigDecimal confidence = calculateConfidenceFromScanResult(result);
            
            // Use current price as entry price
            BigDecimal entryPrice = BigDecimal.valueOf(result.getPrice());
            
            // Calculate reasonable position size (this could be made configurable)
            BigDecimal quantity = calculatePositionSize(result.getSymbol(), entryPrice);
            
            // Build rationale from scan result data
            String rationale = buildRationaleFromScanResult(result);
            
            // Generate the bot signal using the BotSignalService  
            String strategyName = "Market Scanner Strategy";
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                strategyName = "Market Scanner Strategy (BYBIT)";
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                strategyName = "Market Scanner Strategy (MEXC)";
            } else {
                strategyName = "Market Scanner Strategy (" + exchange.toUpperCase() + ")";
            }
            
            BotSignal botSignal = botSignalService.generateSignal(
                result.getSymbol(),
                signalType,
                strategyName,
                entryPrice,
                quantity,
                confidence,
                rationale
            );
            
            // Return signal information
            Map<String, Object> signalInfo = new HashMap<>();
            signalInfo.put("id", botSignal.getId());
            signalInfo.put("symbol", botSignal.getSymbol());
            signalInfo.put("signalType", botSignal.getSignalType());
            signalInfo.put("entryPrice", botSignal.getEntryPrice());
            signalInfo.put("quantity", botSignal.getQuantity());
            signalInfo.put("confidence", botSignal.getConfidence());
            signalInfo.put("rationale", botSignal.getRationale());
            signalInfo.put("status", botSignal.getStatus());
            signalInfo.put("generatedAt", botSignal.getGeneratedAt());
            
            // Add exchange and trading information
            signalInfo.put("exchange", exchange);
            signalInfo.put("strategyName", strategyName);
            signalInfo.put("leverage", botSignal.getLeverage());
            signalInfo.put("marketType", botSignal.getMarketType());
            signalInfo.put("stopLoss", botSignal.getStopLoss());
            signalInfo.put("takeProfit", botSignal.getTakeProfit());
            signalInfo.put("riskRewardRatio", botSignal.getRiskRewardRatio());
            
            logger.info("Generated {} signal for {} from scanner result with {}% confidence", 
                       signalType, result.getSymbol(), confidence);
            
            return signalInfo;
            
        } catch (Exception e) {
            logger.error("Error creating bot signal from scan result for {}: {}", result.getSymbol(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate confidence score from scan result
     */
    private BigDecimal calculateConfidenceFromScanResult(ScanResult result) {
        double baseConfidence = 0.5; // Start with neutral confidence
        
        String signal = result.getSignal();
        
        // Adjust confidence based on signal strength
        switch (signal) {
            case "STRONG_BUY":
            case "STRONG_SELL":
                baseConfidence = 0.85;
                break;
            case "BUY":
            case "SELL":
                baseConfidence = 0.70;
                break;
            default:
                baseConfidence = 0.50;
        }
        
        // Enhance confidence based on technical indicators if available
        Map<String, TechnicalIndicator> indicators = result.getIndicators();
        if (indicators != null) {
            // RSI confirmation
            TechnicalIndicator rsiIndicator = indicators.get("RSI_14");
            if (rsiIndicator != null) {
                double rsi = rsiIndicator.getValue();
                if ((signal.contains("BUY") && rsi < 40) || (signal.contains("SELL") && rsi > 60)) {
                    baseConfidence += 0.05; // RSI confirms signal
                }
            }
            
            // Volume confirmation - check if any volume-based indicator shows high activity
            TechnicalIndicator volumeIndicator = indicators.get("VOLUME_RATIO");
            if (volumeIndicator != null) {
                double volumeRatio = volumeIndicator.getValue();
                if (volumeRatio > 1.5) { // High volume confirms breakout
                    baseConfidence += 0.05;
                }
            }
            
            // MACD confirmation
            TechnicalIndicator macdIndicator = indicators.get("MACD");
            if (macdIndicator != null && macdIndicator.getSignal() != null) {
                if ((signal.contains("BUY") && "BULLISH".equals(macdIndicator.getSignal())) ||
                    (signal.contains("SELL") && "BEARISH".equals(macdIndicator.getSignal()))) {
                    baseConfidence += 0.05;
                }
            }
        }
        
        // Cap confidence at reasonable levels
        baseConfidence = Math.min(0.95, Math.max(0.50, baseConfidence));
        
        return BigDecimal.valueOf(baseConfidence * 100); // Convert to percentage
    }
    
    /**
     * Calculate position size for signal
     */
    private BigDecimal calculatePositionSize(String symbol, BigDecimal entryPrice) {
        // Conservative position sizing - this could be made configurable
        // For now, use a fixed USDT amount and calculate quantity
        BigDecimal fixedUsdtAmount = BigDecimal.valueOf(50.0); // $50 per trade
        
        return fixedUsdtAmount.divide(entryPrice, 6, RoundingMode.DOWN);
    }
    
    /**
     * Build rationale text from scan result
     */
    private String buildRationaleFromScanResult(ScanResult result) {
        StringBuilder rationale = new StringBuilder();
        
        rationale.append("Market Scanner detected ")
                 .append(result.getSignal())
                 .append(" signal for ")
                 .append(result.getSymbol())
                 .append(". ");
        
        // Add technical analysis details
        Map<String, TechnicalIndicator> indicators = result.getIndicators();
        if (indicators != null) {
            // RSI details
            TechnicalIndicator rsiIndicator = indicators.get("RSI_14");
            if (rsiIndicator != null) {
                rationale.append("RSI: ").append(String.format("%.1f", rsiIndicator.getValue())).append(". ");
            }
            
            // MACD details
            TechnicalIndicator macdIndicator = indicators.get("MACD");
            if (macdIndicator != null && macdIndicator.getSignal() != null) {
                rationale.append("MACD: ").append(macdIndicator.getSignal()).append(". ");
            }
            
            // Bollinger Bands details
            TechnicalIndicator bbIndicator = indicators.get("BOLLINGER_BANDS");
            if (bbIndicator != null && bbIndicator.getSignal() != null) {
                rationale.append("Bollinger: ").append(bbIndicator.getSignal()).append(". ");
            }
            
            // Moving Average details
            TechnicalIndicator ma50Indicator = indicators.get("MA_50");
            if (ma50Indicator != null && ma50Indicator.getSignal() != null) {
                rationale.append("MA50: ").append(ma50Indicator.getSignal()).append(". ");
            }
        }
        
        // Add AI analysis if available
        if (result.getAiAnalysis() != null && !result.getAiAnalysis().trim().isEmpty()) {
            rationale.append("AI Analysis: ").append(result.getAiAnalysis().substring(0, Math.min(100, result.getAiAnalysis().length()))).append("...");
        }
        
        return rationale.toString();
    }
} 