package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.BybitApiClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Test controller for debugging API connectivity
 */
@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class TestController {
    
    private static final Logger logger = LoggerFactory.getLogger(TestController.class);
    
    private final BybitApiClientService bybitApiClientService;
    
    public TestController(BybitApiClientService bybitApiClientService) {
        this.bybitApiClientService = bybitApiClientService;
    }
    
    /**
     * Test Bybit public endpoint connectivity
     */
    @GetMapping("/bybit/public")
    public ResponseEntity<?> testBybitPublic() {
        try {
            logger.info("Testing Bybit public endpoint");
            
            // Test with a public endpoint first
            ResponseEntity<String> response = bybitApiClientService.getTickers("BTCUSDT", "spot");
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("statusCode", response.getStatusCode().value());
            result.put("body", response.getBody());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error testing Bybit public endpoint", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            return ResponseEntity.ok(error);
        }
    }
    
    /**
     * Test Bybit private endpoint connectivity (wallet balance)
     */
    @GetMapping("/bybit/private")
    public ResponseEntity<?> testBybitPrivate() {
        try {
            logger.info("Testing Bybit private endpoint (wallet balance)");
            
            // Test wallet balance endpoint
            ResponseEntity<String> response = bybitApiClientService.getWalletBalance("UNIFIED", null);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("statusCode", response.getStatusCode().value());
            result.put("body", response.getBody());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Error testing Bybit private endpoint", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            error.put("stackTrace", e.getStackTrace()[0].toString());
            return ResponseEntity.ok(error);
        }
    }
    
    /**
     * Get Bybit configuration (without revealing secrets)
     */
    @GetMapping("/bybit/config")
    public ResponseEntity<?> getBybitConfig() {
        try {
            Map<String, Object> config = new HashMap<>();
            config.put("baseUrl", bybitApiClientService.getConfig().getBaseUrl());
            config.put("spotApiUrl", bybitApiClientService.getConfig().getSpotApiUrl());
            config.put("apiKeyLength", bybitApiClientService.getConfig().getApiKey() != null ? 
                      bybitApiClientService.getConfig().getApiKey().length() : 0);
            config.put("secretKeyLength", bybitApiClientService.getConfig().getSecretKey() != null ? 
                      bybitApiClientService.getConfig().getSecretKey().length() : 0);
            config.put("recvWindow", bybitApiClientService.getConfig().getRecvWindow());
            
            return ResponseEntity.ok(config);
            
        } catch (Exception e) {
            logger.error("Error getting Bybit config", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }
} 