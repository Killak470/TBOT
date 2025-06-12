package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.HedgingService;
import com.tradingbot.backend.service.HedgingService.HedgePosition;
import com.tradingbot.backend.service.HedgingService.HedgeReason;
import com.tradingbot.backend.service.HedgingService.HedgeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * REST Controller for managing trading position hedging
 * Provides endpoints for automated and manual hedge management
 */
@RestController
@RequestMapping("/api/hedging")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class HedgingController {

    private static final Logger logger = LoggerFactory.getLogger(HedgingController.class);
    
    private final HedgingService hedgingService;

    @Autowired
    public HedgingController(HedgingService hedgingService) {
        this.hedgingService = hedgingService;
    }

    /**
     * Evaluate all positions for hedging opportunities
     * This endpoint triggers the automated hedging evaluation process
     */
    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateHedging() {
        logger.info("Manual hedging evaluation request received");
        
        try {
            hedgingService.evaluateHedgingOpportunities();
            
            List<HedgePosition> activeHedges = hedgingService.getActiveHedges();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Hedging evaluation completed successfully");
            response.put("timestamp", System.currentTimeMillis());
            response.put("activeHedgesCount", activeHedges.size());
            response.put("activeHedges", activeHedges);
            
            logger.info("Hedging evaluation completed. Active hedges: {}", activeHedges.size());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error during hedging evaluation: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to evaluate hedging opportunities: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Force hedge a specific position with custom parameters
     */
    @PostMapping("/force-hedge")
    public ResponseEntity<Map<String, Object>> forceHedge(@RequestBody Map<String, Object> request) {
        logger.info("Force hedge request received: {}", request);
        
        try {
            // Validate required fields
            String symbol = (String) request.get("symbol");
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Symbol is required",
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
            // Parse hedge ratio
            BigDecimal hedgeRatio;
            try {
                Object ratioObj = request.get("hedgeRatio");
                if (ratioObj == null) {
                    hedgeRatio = BigDecimal.valueOf(0.5); // Default 50%
                } else {
                    hedgeRatio = new BigDecimal(ratioObj.toString());
                }
                
                // Validate hedge ratio range
                if (hedgeRatio.compareTo(BigDecimal.ZERO) <= 0 || hedgeRatio.compareTo(BigDecimal.ONE) > 0) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Hedge ratio must be between 0 and 1 (0-100%)",
                        "timestamp", System.currentTimeMillis()
                    ));
                }
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Invalid hedge ratio format. Must be a decimal number",
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
            // Parse hedge reason
            HedgeReason reason;
            try {
                String reasonStr = (String) request.getOrDefault("reason", "PORTFOLIO_RISK");
                reason = HedgeReason.valueOf(reasonStr);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Invalid hedge reason. Valid values: " + Arrays.toString(HedgeReason.values()),
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
            // Execute forced hedge
            hedgingService.forceHedgePosition(symbol, hedgeRatio, reason);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", String.format("Hedge forced for %s with ratio %.2f%% due to %s", 
                        symbol, hedgeRatio.multiply(BigDecimal.valueOf(100)), reason));
            response.put("symbol", symbol);
            response.put("hedgeRatio", hedgeRatio);
            response.put("reason", reason);
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("Force hedge executed for {} with ratio {} due to {}", symbol, hedgeRatio, reason);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error forcing hedge: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to force hedge: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Get all currently active hedges
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveHedges() {
        logger.debug("Active hedges request received");
        
        try {
            List<HedgePosition> activeHedges = hedgingService.getActiveHedges();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("count", activeHedges.size());
            response.put("hedges", activeHedges);
            response.put("timestamp", System.currentTimeMillis());
            
            logger.debug("Returning {} active hedges", activeHedges.size());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error retrieving active hedges: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to retrieve active hedges: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Close all active hedges
     */
    @PostMapping("/close-all")
    public ResponseEntity<Map<String, Object>> closeAllHedges() {
        logger.info("Close all hedges request received");
        
        try {
            List<HedgePosition> hedgesBeforeClose = hedgingService.getActiveHedges();
            int hedgeCountBeforeClose = hedgesBeforeClose.size();
            
            hedgingService.closeAllHedges();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", String.format("Successfully closed %d hedges", hedgeCountBeforeClose));
            response.put("hedgesClosed", hedgeCountBeforeClose);
            response.put("timestamp", System.currentTimeMillis());
            
            logger.info("All {} hedges closed successfully", hedgeCountBeforeClose);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error closing all hedges: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to close all hedges: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Get hedge status for a specific symbol
     */
    @GetMapping("/status/{symbol}")
    public ResponseEntity<Map<String, Object>> getHedgeStatus(@PathVariable String symbol) {
        logger.debug("Hedge status request for symbol: {}", symbol);
        
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Symbol cannot be empty",
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
            HedgePosition hedge = hedgingService.getHedgeForSymbol(symbol.trim().toUpperCase());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("symbol", symbol.toUpperCase());
            response.put("timestamp", System.currentTimeMillis());
            
            if (hedge != null && hedge.isActive()) {
                response.put("hasHedge", true);
                response.put("hedgeType", hedge.getHedgeType());
                response.put("hedgeRatio", hedge.getHedgeRatio());
                response.put("hedgeReason", hedge.getHedgeReason());
                response.put("hedgeSymbol", hedge.getHedgeSymbol());
                response.put("hedgeSide", hedge.getHedgeSide());
                response.put("hedgeSize", hedge.getHedgeSize());
                response.put("createdAt", hedge.getCreatedAt());
                response.put("triggerPrice", hedge.getTriggerPrice());
                response.put("exchange", hedge.getExchange());
                
                logger.debug("Hedge found for {}: {} hedge with ratio {}", 
                           symbol, hedge.getHedgeType(), hedge.getHedgeRatio());
            } else {
                response.put("hasHedge", false);
                response.put("message", "No active hedge found for symbol");
                
                logger.debug("No active hedge found for {}", symbol);
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error retrieving hedge status for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to retrieve hedge status: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Close hedge for a specific symbol
     */
    @PostMapping("/close/{symbol}")
    public ResponseEntity<Map<String, Object>> closeHedgeForSymbol(@PathVariable String symbol) {
        logger.info("Close hedge request for symbol: {}", symbol);
        
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Symbol cannot be empty",
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
            HedgePosition hedge = hedgingService.getHedgeForSymbol(symbol.trim().toUpperCase());
            
            if (hedge == null || !hedge.isActive()) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "No active hedge found for symbol: " + symbol,
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
            boolean closed = hedgingService.closeHedgeForSymbol(symbol.trim().toUpperCase());
            
            if (closed) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "Hedge closed successfully for " + symbol);
                response.put("symbol", symbol.toUpperCase());
                response.put("timestamp", System.currentTimeMillis());
                
                logger.info("Hedge closed successfully for {}", symbol);
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "Failed to close hedge for " + symbol,
                    "timestamp", System.currentTimeMillis()
                ));
            }
            
        } catch (Exception e) {
            logger.error("Error closing hedge for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to close hedge: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Get hedge configuration options
     */
    @GetMapping("/config-options")
    public ResponseEntity<Map<String, Object>> getHedgeConfigOptions() {
        Map<String, Object> options = new HashMap<>();
        
        // Available hedge types
        options.put("hedgeTypes", Arrays.asList(
            HedgeType.DIRECT_OPPOSITE,
            HedgeType.CORRELATION_HEDGE,
            HedgeType.PORTFOLIO_HEDGE,
            HedgeType.DYNAMIC_HEDGE
        ));
        
        // Available hedge reasons
        options.put("hedgeReasons", Arrays.asList(
            HedgeReason.MARKET_REGIME_CHANGE,
            HedgeReason.HIGH_UNREALIZED_LOSS,
            HedgeReason.AI_SIGNAL_REVERSAL,
            HedgeReason.VOLATILITY_SPIKE,
            HedgeReason.PORTFOLIO_RISK,
            HedgeReason.TIME_BASED,
            HedgeReason.CORRELATION_RISK
        ));
        
        // Recommended hedge ratios
        options.put("recommendedRatios", Arrays.asList(
            Map.of("label", "Conservative", "value", 0.25, "description", "25% hedge"),
            Map.of("label", "Moderate", "value", 0.50, "description", "50% hedge"),
            Map.of("label", "Aggressive", "value", 0.75, "description", "75% hedge"),
            Map.of("label", "Full", "value", 1.00, "description", "100% hedge")
        ));
        
        // Default settings
        options.put("defaults", Map.of(
            "hedgeRatio", 0.5,
            "reason", HedgeReason.PORTFOLIO_RISK,
            "minPnlLossThreshold", -0.15,
            "cooldownMinutes", 5
        ));
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "options", options,
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Get hedging statistics and metrics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getHedgingStatistics() {
        logger.debug("Hedging statistics request received");
        
        try {
            List<HedgePosition> activeHedges = hedgingService.getActiveHedges();
            
            // Calculate statistics
            Map<String, Long> hedgesByType = activeHedges.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    h -> h.getHedgeType().name(),
                    java.util.stream.Collectors.counting()
                ));
            
            Map<String, Long> hedgesByReason = activeHedges.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    h -> h.getHedgeReason().name(),
                    java.util.stream.Collectors.counting()
                ));
            
            double averageHedgeRatio = activeHedges.stream()
                .mapToDouble(h -> h.getHedgeRatio().doubleValue())
                .average()
                .orElse(0.0);
            
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalActiveHedges", activeHedges.size());
            statistics.put("hedgesByType", hedgesByType);
            statistics.put("hedgesByReason", hedgesByReason);
            statistics.put("averageHedgeRatio", averageHedgeRatio);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("statistics", statistics);
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error retrieving hedging statistics: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "error", "Failed to retrieve statistics: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }
}
