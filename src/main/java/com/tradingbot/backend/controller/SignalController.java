package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.PotentialTrade;
import com.tradingbot.backend.service.BotSignalService;
import com.tradingbot.backend.service.SignalGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signals")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class SignalController {

    private static final Logger logger = LoggerFactory.getLogger(SignalController.class);
    
    private final SignalGenerationService signalGenerationService;
    private final BotSignalService botSignalService;
    
    public SignalController(SignalGenerationService signalGenerationService, BotSignalService botSignalService) {
        this.signalGenerationService = signalGenerationService;
        this.botSignalService = botSignalService;
    }
    
    /**
     * Generate combined trading signal for a specific symbol
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> getSignal(
            @PathVariable String symbol,
            @RequestParam(required = false, defaultValue = "1d") String interval) {
        
        logger.info("Generating signal for {} ({})", symbol, interval);
        Map<String, Object> signal = signalGenerationService.generateCombinedSignal(symbol, interval);
        return ResponseEntity.ok(signal);
    }
    
    /**
     * Generate signals for multiple symbols
     */
    @GetMapping("/batch")
    public ResponseEntity<Map<String, Map<String, Object>>> getBatchSignals(
            @RequestParam String[] symbols,
            @RequestParam(required = false, defaultValue = "1d") String interval) {
        
        logger.info("Generating batch signals for {} symbols", symbols.length);
        Map<String, Map<String, Object>> batchResults = signalGenerationService.generateBatchSignals(symbols, interval);
        return ResponseEntity.ok(batchResults);
    }
    
    /**
     * Get current signal component weights
     */
    @GetMapping("/weights")
    public ResponseEntity<Map<String, Double>> getSignalWeights() {
        return ResponseEntity.ok(signalGenerationService.getSignalWeights());
    }
    
    /**
     * Update signal component weights
     */
    @PutMapping("/weights")
    public ResponseEntity<Map<String, Object>> updateSignalWeights(
            @RequestBody Map<String, Double> weights) {
        
        logger.info("Updating signal weights: {}", weights);
        signalGenerationService.updateSignalWeights(weights);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Signal weights updated successfully");
        response.put("weights", signalGenerationService.getSignalWeights());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get alerts for strong signals
     */
    @GetMapping("/alerts")
    public ResponseEntity<Map<String, Object>> getSignalAlerts(
            @RequestParam String[] symbols,
            @RequestParam(required = false, defaultValue = "1d") String interval,
            @RequestParam(required = false, defaultValue = "0.7") double minimumConfidence) {
        
        logger.info("Checking for signal alerts with min confidence {}", minimumConfidence);
        
        Map<String, Object> alertsResponse = new HashMap<>();
        Map<String, Map<String, Object>> buyAlerts = new HashMap<>();
        Map<String, Map<String, Object>> sellAlerts = new HashMap<>();
        
        // Generate signals for all symbols
        Map<String, Map<String, Object>> allSignals = signalGenerationService.generateBatchSignals(symbols, interval);
        
        // Filter for strong signals
        for (Map.Entry<String, Map<String, Object>> entry : allSignals.entrySet()) {
            String symbol = entry.getKey();
            Map<String, Object> signal = entry.getValue();
            
            if (signal.containsKey("confidence") && signal.containsKey("signal")) {
                double confidence = (double) signal.get("confidence");
                String direction = (String) signal.get("signal");
                
                if (confidence >= minimumConfidence) {
                    if ("BUY".equals(direction)) {
                        buyAlerts.put(symbol, signal);
                    } else if ("SELL".equals(direction)) {
                        sellAlerts.put(symbol, signal);
                    }
                }
            }
        }
        
        alertsResponse.put("buyAlerts", buyAlerts);
        alertsResponse.put("sellAlerts", sellAlerts);
        alertsResponse.put("alertCount", buyAlerts.size() + sellAlerts.size());
        
        return ResponseEntity.ok(alertsResponse);
    }
    
    /**
     * Get all pending bot signals waiting for approval
     */
    @GetMapping("/bot/pending")
    public ResponseEntity<List<BotSignal>> getPendingBotSignals() {
        List<BotSignal> signals = botSignalService.getPendingSignals();
        return ResponseEntity.ok(signals);
    }
    
    /**
     * Get bot signals by status
     */
    @GetMapping("/bot/status/{status}")
    public ResponseEntity<List<BotSignal>> getBotSignalsByStatus(@PathVariable String status) {
        try {
            BotSignal.SignalStatus signalStatus = BotSignal.SignalStatus.valueOf(status.toUpperCase());
            List<BotSignal> signals = botSignalService.getSignalsByStatus(signalStatus);
            return ResponseEntity.ok(signals);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Get recent bot signals (last 24 hours)
     */
    @GetMapping("/bot/recent")
    public ResponseEntity<List<BotSignal>> getRecentBotSignals() {
        List<BotSignal> signals = botSignalService.getRecentSignals();
        return ResponseEntity.ok(signals);
    }
    
    /**
     * Approve a bot signal for execution
     */
    @PostMapping("/bot/{signalId}/approve")
    public ResponseEntity<Map<String, Object>> approveBotSignal(
            @PathVariable Long signalId,
            @RequestBody(required = false) Map<String, String> body) {
        
        String approvedBy = body != null ? body.get("approvedBy") : "USER";
        boolean success = botSignalService.approveSignal(signalId, approvedBy);
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "Signal approved and queued for execution" : "Failed to approve signal"
        ));
    }
    
    /**
     * Reject a bot signal
     */
    @PostMapping("/bot/{signalId}/reject")
    public ResponseEntity<Map<String, Object>> rejectBotSignal(
            @PathVariable Long signalId,
            @RequestBody Map<String, String> body) {
        
        String rejectedBy = body.getOrDefault("rejectedBy", "USER");
        String reason = body.getOrDefault("reason", "No reason provided");
        
        boolean success = botSignalService.rejectSignal(signalId, rejectedBy, reason);
        
        return ResponseEntity.ok(Map.of(
            "success", success,
            "message", success ? "Signal rejected" : "Failed to reject signal"
        ));
    }
    
    /**
     * Generate a test bot signal (for development purposes)
     */
    @PostMapping("/bot/generate-test")
    public ResponseEntity<BotSignal> generateTestBotSignal(@RequestBody Map<String, Object> request) {
        try {
            String symbol = (String) request.get("symbol");
            String signalType = (String) request.get("signalType");
            String strategyName = (String) request.get("strategyName");
            BigDecimal entryPrice = new BigDecimal(request.get("entryPrice").toString());
            BigDecimal quantity = new BigDecimal(request.get("quantity").toString());
            BigDecimal confidence = new BigDecimal(request.get("confidence").toString());
            String rationale = (String) request.get("rationale");
            
            BotSignal.SignalType type = BotSignal.SignalType.valueOf(signalType.toUpperCase());
            
            BotSignal signal = botSignalService.generateSignal(
                symbol, type, strategyName, entryPrice, quantity, confidence, rationale);
            
            return ResponseEntity.ok(signal);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Get bot signal statistics
     */
    @GetMapping("/bot/stats")
    public ResponseEntity<BotSignalService.SignalStats> getBotSignalStats() {
        BotSignalService.SignalStats stats = botSignalService.getSignalStats();
        return ResponseEntity.ok(stats);
    }
    
    /**
     * Get all bot signals (for development/debugging)
     */
    @GetMapping("/bot/all")
    public ResponseEntity<List<BotSignal>> getAllBotSignals() {
        List<BotSignal> signals = botSignalService.getRecentSignals();
        return ResponseEntity.ok(signals);
    }
    
    /**
     * Convert analysis signal to bot signal for approval
     * This bridges the gap between signal analysis and bot signal workflow
     */
    @PostMapping("/convert-to-bot-signal")
    public ResponseEntity<Map<String, Object>> convertAnalysisSignalToBotSignal(
            @RequestBody Map<String, Object> request) {
        
        try {
            String symbol = (String) request.get("symbol");
            String interval = (String) request.getOrDefault("interval", "1h");
            
            // Generate analysis signal first
            Map<String, Object> analysisSignal = signalGenerationService.generateCombinedSignal(symbol, interval);
            
            // Check if signal is strong enough to convert to bot signal
            String signalDirection = (String) analysisSignal.get("signal");
            Double confidence = (Double) analysisSignal.get("confidence");
            
            if (!"BUY".equals(signalDirection) && !"SELL".equals(signalDirection)) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Signal is neutral, not suitable for bot signal generation"
                ));
            }
            
            if (confidence == null || confidence < 0.6) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Signal confidence too low for bot signal generation",
                    "confidence", confidence != null ? confidence : 0.0
                ));
            }
            
            // Convert to bot signal
            BigDecimal entryPrice = new BigDecimal(request.getOrDefault("entryPrice", "0").toString());
            BigDecimal quantity = new BigDecimal(request.getOrDefault("quantity", "0.001").toString());
            String strategyName = (String) request.getOrDefault("strategyName", "Multi-Factor Analysis");
            
            // Build rationale from analysis
            StringBuilder rationale = new StringBuilder("Converted from signal analysis: ");
            rationale.append(analysisSignal.getOrDefault("signal", "NEUTRAL"));
            rationale.append(" signal with ").append(String.format("%.1f", confidence * 100)).append("% confidence. ");
            
            if (analysisSignal.containsKey("technicalScore")) {
                rationale.append("Technical: ").append(String.format("%.2f", (Double) analysisSignal.get("technicalScore"))).append(". ");
            }
            if (analysisSignal.containsKey("sentimentScore")) {
                rationale.append("Sentiment: ").append(String.format("%.2f", (Double) analysisSignal.get("sentimentScore"))).append(". ");
            }
            
            BotSignal.SignalType signalType = "BUY".equals(signalDirection) 
                ? BotSignal.SignalType.BUY 
                : BotSignal.SignalType.SELL;
            
            BotSignal botSignal = botSignalService.generateSignal(
                symbol,
                signalType,
                strategyName,
                entryPrice,
                quantity,
                BigDecimal.valueOf(confidence * 100),
                rationale.toString()
            );
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Analysis signal converted to bot signal successfully",
                "botSignal", botSignal,
                "originalAnalysis", analysisSignal
            ));
            
        } catch (Exception e) {
            logger.error("Error converting analysis signal to bot signal", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Error converting signal: " + e.getMessage()
            ));
        }
    }
    
    // ============================================================================
    // MANUAL SIGNAL GENERATION (User-controlled signal scanning)
    // ============================================================================
    
    /**
     * Manually generate bot signals for default market scanner pairs
     */
    @PostMapping("/generate-bot-signals")
    public ResponseEntity<Map<String, Object>> generateBotSignalsForDefaultPairs(
            @RequestParam(required = false, defaultValue = "1h") String interval) {
        
        logger.info("Manual bot signal generation requested for default pairs with interval: {}", interval);
        Map<String, Object> result = signalGenerationService.generateBotSignalsForDefaultPairs(interval);
        return ResponseEntity.ok(result);
    }
    
    /**
     * Manually generate bot signals for specific symbols
     */
    @PostMapping("/generate-bot-signals/custom")
    public ResponseEntity<Map<String, Object>> generateBotSignalsForSymbols(
            @RequestBody Map<String, Object> request) {
        
        try {
            @SuppressWarnings("unchecked")
            List<String> symbols = (List<String>) request.get("symbols");
            String interval = (String) request.getOrDefault("interval", "1h");
            
            if (symbols == null || symbols.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "No symbols provided"
                ));
            }
            
            logger.info("Manual bot signal generation requested for {} symbols with interval: {}", symbols.size(), interval);
            Map<String, Object> result = signalGenerationService.generateBotSignalsForSymbols(symbols, interval);
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error in manual bot signal generation", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Error generating signals: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Get default trading pairs used for signal generation
     */
    @GetMapping("/default-pairs")
    public ResponseEntity<Map<String, Object>> getDefaultTradingPairs() {
        List<String> pairs = signalGenerationService.getDefaultTradingPairs();
        return ResponseEntity.ok(Map.of(
            "tradingPairs", pairs,
            "count", pairs.size()
        ));
    }

    /**
     * Creates a new BotSignal from a PotentialTrade sent from the frontend.
     * This is used to stage a signal for manual approval.
     */
    @PostMapping("/create")
    public ResponseEntity<BotSignal> createSignalFromPotentialTrade(@RequestBody PotentialTrade trade) {
        logger.info("Received potential trade to stage as signal: {}", trade);
        try {
            BotSignal createdSignal = botSignalService.createSignalFromPotentialTrade(trade);
            return ResponseEntity.ok(createdSignal);
        } catch (Exception e) {
            logger.error("Error creating signal from potential trade", e);
            return ResponseEntity.badRequest().build();
        }
    }
} 