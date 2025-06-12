package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.BalanceSnapshot;
import com.tradingbot.backend.model.StrategyPerformance;
import com.tradingbot.backend.service.PerformanceTrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/performance")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class PerformanceController {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceController.class);
    
    private final PerformanceTrackingService performanceTrackingService;
    
    public PerformanceController(PerformanceTrackingService performanceTrackingService) {
        this.performanceTrackingService = performanceTrackingService;
    }
    
    @GetMapping("/balance-history")
    public ResponseEntity<Map<String, Object>> getBalanceHistory(
            @RequestParam(defaultValue = "30") int days) {
        try {
            logger.info("Fetching balance history for {} days", days);
            
            List<BalanceSnapshot> balanceHistory = performanceTrackingService.getBalanceHistory(days);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", balanceHistory);
            response.put("totalSnapshots", balanceHistory.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching balance history", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch balance history: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    @GetMapping("/strategy-performance")
    public ResponseEntity<Map<String, Object>> getLatestStrategyPerformances() {
        try {
            logger.info("Fetching latest strategy performances");
            
            List<StrategyPerformance> performances = performanceTrackingService.getLatestStrategyPerformances();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", performances);
            response.put("totalStrategies", performances.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching strategy performances", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch strategy performances: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    @GetMapping("/strategy-performance/{strategyName}")
    public ResponseEntity<Map<String, Object>> getStrategyPerformanceHistory(
            @PathVariable String strategyName,
            @RequestParam(defaultValue = "30") int days) {
        try {
            logger.info("Fetching performance history for strategy {} over {} days", strategyName, days);
            
            List<StrategyPerformance> performanceHistory = 
                performanceTrackingService.getStrategyPerformanceHistory(strategyName, days);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", performanceHistory);
            response.put("strategyName", strategyName);
            response.put("totalRecords", performanceHistory.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching strategy performance history for {}", strategyName, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch strategy performance history: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    @PostMapping("/capture-balance")
    public ResponseEntity<Map<String, Object>> captureBalanceSnapshot() {
        try {
            logger.info("Manually capturing balance snapshot");
            
            performanceTrackingService.captureBalanceSnapshot();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Balance snapshot captured successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error capturing balance snapshot", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to capture balance snapshot: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    @PostMapping("/update-strategy-performance")
    public ResponseEntity<Map<String, Object>> updateStrategyPerformance() {
        try {
            logger.info("Manually updating strategy performance");
            
            performanceTrackingService.updateStrategyPerformance();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Strategy performance updated successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error updating strategy performance", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update strategy performance: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
} 