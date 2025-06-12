package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class SettingsController {
    
    private static final Logger logger = LoggerFactory.getLogger(SettingsController.class);
    
    private final AppSettingsService settingsService;
    
    public SettingsController(AppSettingsService settingsService) {
        this.settingsService = settingsService;
    }
    
    /**
     * Get all application settings
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllSettings() {
        try {
            Map<String, Object> settings = settingsService.getAllSettings();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("settings", settings);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving settings", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error retrieving settings: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Get a specific setting by key
     */
    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getSetting(@PathVariable String key) {
        try {
            Object value = settingsService.getSetting(key);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("key", key);
            response.put("value", value);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving setting: {}", key, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error retrieving setting: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Save multiple settings
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, Object> settings) {
        try {
            logger.info("Saving {} settings", settings.size());
            settingsService.saveSettings(settings);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Settings saved successfully");
            response.put("savedCount", settings.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error saving settings", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error saving settings: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Save or update a single setting
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> saveSetting(
            @PathVariable String key, 
            @RequestBody Map<String, Object> request) {
        try {
            Object value = request.get("value");
            String description = (String) request.get("description");
            
            settingsService.saveSetting(key, value, description);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Setting saved successfully");
            response.put("key", key);
            response.put("value", value);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error saving setting: {}", key, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error saving setting: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * Delete a setting
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> deleteSetting(@PathVariable String key) {
        try {
            settingsService.deleteSetting(key);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Setting deleted successfully");
            response.put("key", key);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error deleting setting: {}", key, e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error deleting setting: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
} 