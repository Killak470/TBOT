package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import com.tradingbot.backend.service.BotSignalService;
import com.tradingbot.backend.service.MarketScannerService;
import com.tradingbot.backend.service.SignalGenerationService;
import com.tradingbot.backend.service.MarketDataService;
import com.tradingbot.backend.service.RiskManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Import for JsonProcessingException if it's thrown by a service method
import com.fasterxml.jackson.core.JsonProcessingException;

@RestController
@RequestMapping("/api/scanner")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class MarketScannerController {

    private static final Logger logger = LoggerFactory.getLogger(MarketScannerController.class);
    private final MarketScannerService marketScannerService;
    private final BotSignalService botSignalService;
    private final MarketDataService marketDataService;
    private final RiskManagementService riskManagementService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Value("${tradingbot.sniper.strategy.symbols}")
    private List<String> sniperStrategySymbols;

    /**
     * Represents a potential trade setup returned to the frontend for user selection.
     * This is a light-weight object for display purposes.
     */
    public static class PotentialTrade {
        private String symbol;
        private String side;
        private BigDecimal entryPrice;
        private BigDecimal stopLoss;
        private BigDecimal takeProfit;
        private String rationale;
        private String exchange;
        private ScanConfig.MarketType marketType;
        private String interval;
        private String title;
		private BigDecimal confidence;

        // Getters
        public String getSymbol() { return symbol; }
        public String getSide() { return side; }
        public BigDecimal getEntryPrice() { return entryPrice; }
        public BigDecimal getStopLoss() { return stopLoss; }
        public BigDecimal getTakeProfit() { return takeProfit; }
        public String getRationale() { return rationale; }
        public String getExchange() { return exchange; }
        public ScanConfig.MarketType getMarketType() { return marketType; }
        public String getInterval() { return interval; }
        public String getTitle() { return title; }
		public BigDecimal getConfidence() { return confidence; }

        // Setters
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public void setSide(String side) { this.side = side; }
        public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
        public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }
        public void setTakeProfit(BigDecimal takeProfit) { this.takeProfit = takeProfit; }
        public void setRationale(String rationale) { this.rationale = rationale; }
        public void setExchange(String exchange) { this.exchange = exchange; }
        public void setMarketType(ScanConfig.MarketType marketType) { this.marketType = marketType; }
        public void setInterval(String interval) { this.interval = interval; }
        public void setTitle(String title) { this.title = title; }
		public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    }

    /**
     * Represents a single, fully-formed trade setup parsed from AI analysis.
     */
    private static class TradeSetup {
        private final String title;
        private final String side;
        private final BigDecimal entryPrice;
        private final BigDecimal stopLoss;
        private final BigDecimal takeProfit;

        public TradeSetup(String title, String side, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit) {
            this.title = title;
            this.side = side;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
        }

        public String getTitle() { return title; }
        public String getSide() { return side; }
        public BigDecimal getEntryPrice() { return entryPrice; }
        public BigDecimal getStopLoss() { return stopLoss; }
        public BigDecimal getTakeProfit() { return takeProfit; }
    }

    @Autowired
    public MarketScannerController(MarketScannerService marketScannerService, BotSignalService botSignalService, MarketDataService marketDataService, RiskManagementService riskManagementService) {
        this.marketScannerService = marketScannerService;
        this.botSignalService = botSignalService;
        this.marketDataService = marketDataService;
        this.riskManagementService = riskManagementService;
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
        
        // Parse trading pairs if provided, otherwise default to Sniper symbols
        if (pairs != null && !pairs.isEmpty()) {
            List<String> tradingPairs = Arrays.asList(pairs.split(","));
            config.setTradingPairs(tradingPairs);
        } else {
            // Default to injected sniperStrategySymbols
            if (this.sniperStrategySymbols != null && !this.sniperStrategySymbols.isEmpty()) {
                config.setTradingPairs(this.sniperStrategySymbols);
                logger.info("No pairs provided for quick scan, defaulting to configured SNIPER_STRATEGY_SYMBOLS.");
            } else {
                logger.warn("No pairs provided for quick scan, and SNIPER_STRATEGY_SYMBOLS are not configured. Using empty list.");
                config.setTradingPairs(Collections.emptyList());
            }
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
        
        // Create deferred result with a longer timeout (e.g., 5 minutes) for AI analysis
        long timeout = 300_000L; // 5 minutes
        DeferredResult<ResponseEntity<Map<String, Object>>> deferredResult = new DeferredResult<>(timeout);
        
        // Set timeout callback
        deferredResult.onTimeout(() -> {
            logger.warn("Custom scan request timed out for config: {}", config);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Request timed out after " + (timeout / 1000) + " seconds. The analysis is taking longer than expected. Please check the logs for results.");
            deferredResult.setResult(ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(response));
        });
        
        // Execute the scan asynchronously
        logger.info("Dispatching custom scan to async thread pool.");
        CompletableFuture.supplyAsync(() -> marketScannerService.scanMarket(config))
            .thenAccept(results -> {
                 if (deferredResult.isSetOrExpired()) {
                    logger.warn("Async scan for config {} completed, but the HTTP request already timed out.", config);
                    return;
                }

                List<PotentialTrade> allPotentialTrades = new ArrayList<>();
                for (ScanResult result : results) {
                    if (result.getAiAnalysis() != null && !result.getAiAnalysis().isEmpty()) {
                        logger.info("AI Analysis to be parsed for {}:\n---\n{}\n---", result.getSymbol(), result.getAiAnalysis());
                        List<TradeSetup> setups = parseAllTradeSetupsFromAI(result.getAiAnalysis());
                        if (!setups.isEmpty()) {
                            for (TradeSetup setup : setups) {
                                allPotentialTrades.add(createPotentialTradeFromTradeSetup(setup, result, config.getExchange()));
                            }
                        }
                    }
                }

                // Format the response
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("timestamp", System.currentTimeMillis());
                
                // Return both raw results and parsed trade options
                response.put("results", results);
                response.put("tradeOptions", allPotentialTrades);
                response.put("count", results.size());
                
                logger.info("Custom scan completed with {} results and {} potential trade options.", results.size(), allPotentialTrades.size());
                
                // Set the deferred result
                deferredResult.setResult(ResponseEntity.ok(response));
            })
            .exceptionally(ex -> {
                logger.error("Error during custom scan for config: {}", config, ex);
                if (deferredResult.isSetOrExpired()) {
                    logger.warn("Async scan for config {} failed, but the HTTP request already timed out.", config);
                    return null;
                }
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "Failed to complete scan: " + ex.getMessage());
                // Use HttpStatus.INTERNAL_SERVER_ERROR for server-side exceptions
                deferredResult.setResult(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response));
                return null;
            });
        
        return deferredResult;
    }
    
    /**
     * DEPRECATED: This logic is now part of the /custom-scan endpoint itself.
     * This endpoint can be removed in the future.
     */
    @PostMapping("/generate-signals")
    public ResponseEntity<Map<String, Object>> generateSignalsFromScan(@RequestBody Map<String, Object> request) {
        logger.warn("The /generate-signals endpoint is deprecated and will be removed. Trade options are now generated as part of the /custom-scan response.");
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "This endpoint is deprecated. Please use the 'tradeOptions' array from the /custom-scan response.");
        return ResponseEntity.status(HttpStatus.GONE).body(response);
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
        
        // Spot trading pairs
        options.put("spotPairs", Arrays.asList(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "NXPCUSDT", "KSMUSDT", "MOVRUSDT", "DOGEUSDT", "WALUSDT", "TRUMPUSDT", "SUIUSDT", "HYPEUSDT", "VIRTUALUSDT", "GRASSUSDT", "PEOPLEUSDT", "1000PEPEUSDT"
        ));
        
        // Futures trading pairs (using _USDT suffix for futures)
        options.put("futuresPairs", Arrays.asList(
            "BTC_USDT", "ETH_USDT", "SOL_USDT", "AVAX_USDT", "NXPC_USDT", "KSM_USDT", "MOVR_USDT", "DOGE_USDT", "WAL_USDT", "TRUMP_USDT", "SUI_USDT", "HYPE_USDT", "VIRTUAL_USDT", "GRASS_USDT", "PEOPLE_USDT", "1000PEPE_USDT"
        ));
        
        // Combined pairs for backward compatibility
        List<String> allPairs = new ArrayList<>();
        allPairs.addAll((List<String>) options.get("spotPairs"));
        allPairs.addAll((List<String>) options.get("futuresPairs"));
        options.put("tradingPairs", allPairs);
        
        return ResponseEntity.ok(options);
    }

    /**
     * Creates and saves a BotSignal from a user-selected PotentialTrade.
     */
    @PostMapping("/create-signal")
    public ResponseEntity<Map<String, Object>> createSignalFromSelection(@RequestBody PotentialTrade selectedTrade) {
        Map<String, Object> response = new HashMap<>();
        try {
            BotSignal signal = new BotSignal();
            signal.setSymbol(selectedTrade.getSymbol());
            signal.setExchange(selectedTrade.getExchange());
            signal.setSide(selectedTrade.getSide());
            signal.setEntryPrice(selectedTrade.getEntryPrice());
            signal.setStopLoss(selectedTrade.getStopLoss());
            signal.setTakeProfit(selectedTrade.getTakeProfit());
            signal.setRationale(selectedTrade.getRationale());
            signal.setMarketType(selectedTrade.getMarketType());
            signal.setConfidence(selectedTrade.getConfidence());

            if ("BUY".equalsIgnoreCase(selectedTrade.getSide())) {
                signal.setSignalType(BotSignal.SignalType.BUY);
            } else {
                signal.setSignalType(BotSignal.SignalType.SELL);
            }

            signal.setStatus(BotSignal.SignalStatus.APPROVED);
            signal.setStrategyName("AI_ASSISTED_SNIPER");
            signal.setGeneratedAt(java.time.LocalDateTime.now());
            
            // This new service method saves the signal and immediately attempts execution
            BotSignal executedSignal = botSignalService.createAndExecuteSignal(signal);

            response.put("success", true);
            response.put("message", "Signal creation and execution initiated successfully!");
            response.put("signal", executedSignal);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error creating signal from user selection: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to create signal: " + e.getMessage());
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ScanResult mapToScanResult(Map<String, Object> map) {
        try {
            ScanResult sr = new ScanResult();
            sr.setSymbol((String) map.get("symbol"));
            sr.setSignal((String) map.get("signal"));
            if (map.get("price") != null) {
                 sr.setPrice(new BigDecimal(map.get("price").toString()));
            }
            sr.setAiAnalysis((String) map.get("aiAnalysis"));
            sr.setExchange((String) map.get("exchange"));
            sr.setInterval((String) map.get("interval"));
            sr.setMarketType((String) map.get("marketType"));
            return sr;
        } catch (Exception e) {
            logger.error("Error mapping raw data to ScanResult: {}. Data: {}", e.getMessage(), map);
            return null;
        }
    }

    private boolean isQualifyingForSignal(ScanResult result, String minSignalStrength) {
        String signal = result.getSignal();
        if (signal == null) return false;

        boolean isTradeableSignal = signal.contains("BUY") || signal.contains("SELL");
        if (!isTradeableSignal) return false;

        switch (minSignalStrength.toUpperCase()) {
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
     * Creates a list of potential signal data maps from a single scan result.
     * Each map represents a distinct trade setup found in the AI analysis.
     * @return A list of maps, where each map is a potential signal to be shown to the user.
     */
    private List<Map<String, Object>> createPotentialSignalsFromScanResult(ScanResult result, String exchange, ScanConfig scanConfig) {
        // This method can be removed if not used elsewhere
        return new ArrayList<>();
    }
    
    private List<TradeSetup> parseAllTradeSetupsFromAI(String aiAnalysis) {
        List<TradeSetup> setups = new ArrayList<>();
        Pattern setupBlockPattern = Pattern.compile("---SETUP---(.*?)---END_SETUP---", Pattern.DOTALL);
        Matcher blockMatcher = setupBlockPattern.matcher(aiAnalysis);
    
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
    
            try {
                String title = extractValue(block, "Title");
                String direction = extractValue(block, "Direction");
                String entryStr = extractValue(block, "Entry");
                String stopLossStr = extractValue(block, "StopLoss");
    
                if (title.isEmpty() || direction.isEmpty() || entryStr.isEmpty() || stopLossStr.isEmpty()) {
                    logger.warn("Skipping setup due to missing required fields in block:\n{}", block);
                    continue;
                }
    
                BigDecimal entry = new BigDecimal(entryStr.split("-")[0].trim()); // Handle ranges by taking the first number
                BigDecimal stopLoss = new BigDecimal(stopLossStr);
                String side = direction.equalsIgnoreCase("LONG") ? "BUY" : "SELL";
    
                // Extract all TakeProfit levels
                for (int i = 1; i <= 3; i++) {
                    String tpKey = "TakeProfit" + i;
                    String tpStr = extractValue(block, tpKey);
                    if (!tpStr.isEmpty()) {
                        BigDecimal takeProfit = new BigDecimal(tpStr);
                        String setupTitle = i > 1 ? String.format("%s (TP%d)", title, i) : title;
                        setups.add(new TradeSetup(setupTitle, side, entry, stopLoss, takeProfit));
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to parse a trade setup block due to an error: {}. Block content:\n{}", e.getMessage(), block, e);
            }
        }
    
        if (setups.isEmpty()) {
            logger.warn("Could not parse any valid ---SETUP--- blocks from the AI analysis.");
        } else {
            logger.info("Successfully parsed {} trade setups from AI analysis.", setups.size());
        }
    
        return setups;
    }
    
    private String extractValue(String block, String key) {
        Pattern pattern = Pattern.compile(key + ":\\s*([^\\n]+)");
        Matcher matcher = pattern.matcher(block);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private BigDecimal getCurrentPrice(String symbol, String exchange) {
        if (symbol == null || exchange == null) {
            logger.warn("MarketScannerController.getCurrentPrice: Symbol or exchange is null. Symbol: {}, Exchange: {}", symbol, exchange);
            return null;
        }
        return marketDataService.getCurrentPrice(symbol, exchange);
    }

    private BigDecimal calculateConfidence(ScanResult result) {
        double baseConfidence = 0.5; 
        if (result == null || result.getSignal() == null) return BigDecimal.valueOf(baseConfidence * 100);
        String signal = result.getSignal();
        
        switch (signal) {
            case "STRONG_BUY": case "STRONG_SELL": baseConfidence = 0.85; break;
            case "BUY": case "SELL": baseConfidence = 0.70; break;
            default: baseConfidence = 0.50;
        }
        if (result.getAiAnalysis() != null && !result.getAiAnalysis().isEmpty()) {
            if ( (signal.contains("BUY") && result.getAiAnalysis().toUpperCase().contains("BULLISH")) ||
                 (signal.contains("SELL") && result.getAiAnalysis().toUpperCase().contains("BEARISH")) ) {
                baseConfidence = Math.min(baseConfidence + 0.10, 0.95);
            }
        }
        return BigDecimal.valueOf(baseConfidence * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private String buildSignalRationale(ScanResult result) {
        StringBuilder rationale = new StringBuilder();
        rationale.append("Market Scanner: ").append(result.getSignal()).append(" for ").append(result.getSymbol()).append(". ");
        if (result.getAiAnalysis() != null && !result.getAiAnalysis().isEmpty()) {
            rationale.append("AI: ").append(result.getAiAnalysis().substring(0, Math.min(result.getAiAnalysis().length(), 150))).append("...");
        }
        return rationale.toString();
    }

    private List<TradeSetup> extractTradeSetupsFromScanResult(ScanResult result) {
        String aiAnalysis = result.getAiAnalysis();
        if (aiAnalysis == null || aiAnalysis.isEmpty()) {
            return Collections.emptyList();
        }
        return parseAllTradeSetupsFromAI(aiAnalysis);
    }

    private PotentialTrade createPotentialTradeFromTradeSetup(TradeSetup setup, ScanResult scanResult, String exchange) {
        PotentialTrade potential = new PotentialTrade();
        potential.setSymbol(scanResult.getSymbol());
        potential.setExchange(exchange);
        potential.setSide(setup.getSide());
        potential.setEntryPrice(setup.getEntryPrice());
        potential.setStopLoss(setup.getStopLoss());
        potential.setTakeProfit(setup.getTakeProfit());
        potential.setTitle(setup.getTitle());
        potential.setMarketType(scanResult.getMarketType());
        potential.setConfidence(calculateConfidence(scanResult));
        potential.setRationale(buildSignalRationale(scanResult) + " | Setup: " + setup.getTitle());
        potential.setInterval(scanResult.getInterval());
        return potential;
    }

    private BotSignal createBotSignalFromTradeSetup(TradeSetup setup, ScanResult scanResult, String exchange) {
        BotSignal signal = new BotSignal();
        signal.setSymbol(scanResult.getSymbol());
        signal.setExchange(exchange);
        signal.setSide(setup.getSide());
        signal.setEntryPrice(setup.getEntryPrice());
        signal.setStopLoss(setup.getStopLoss());
        signal.setTakeProfit(setup.getTakeProfit());

        if ("BUY".equalsIgnoreCase(setup.getSide())) {
            signal.setSignalType(BotSignal.SignalType.BUY);
        } else {
            signal.setSignalType(BotSignal.SignalType.SELL);
        }

        signal.setStatus(BotSignal.SignalStatus.APPROVED);

        signal.setStrategyName("AI_ASSISTED_SNIPER");

        signal.setGeneratedAt(java.time.LocalDateTime.now());

        if (scanResult.getMarketType() != null) {
            signal.setMarketType(scanResult.getMarketType());
        }
        
        signal.setConfidence(calculateConfidence(scanResult));
        signal.setRationale(buildSignalRationale(scanResult) + " | Setup: " + setup.getTitle());
        
        return signal;
    }
} 