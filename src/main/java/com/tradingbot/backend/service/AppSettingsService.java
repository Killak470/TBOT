package com.tradingbot.backend.service;

import com.tradingbot.backend.model.AppSettings;
import com.tradingbot.backend.repository.AppSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AppSettingsService {
    
    private static final Logger logger = LoggerFactory.getLogger(AppSettingsService.class);
    
    private final AppSettingsRepository settingsRepository;
    
    public AppSettingsService(AppSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }
    
    /**
     * Get all settings as a key-value map
     */
    public Map<String, Object> getAllSettings() {
        List<AppSettings> allSettings = settingsRepository.findAll();
        Map<String, Object> settingsMap = new HashMap<>();
        
        for (AppSettings setting : allSettings) {
            Object value = parseSettingValue(setting);
            settingsMap.put(setting.getKey(), value);
        }
        
        return settingsMap;
    }
    
    /**
     * Get a specific setting by key
     */
    public Object getSetting(String key) {
        Optional<AppSettings> setting = settingsRepository.findByKey(key);
        return setting.map(this::parseSettingValue).orElse(null);
    }
    
    /**
     * Save or update a setting
     */
    @Transactional
    public void saveSetting(String key, Object value, String description) {
        AppSettings setting = settingsRepository.findByKey(key).orElse(new AppSettings());
        
        setting.setKey(key);
        setting.setValue(value != null ? value.toString() : null);
        setting.setType(determineType(value));
        setting.setDescription(description);
        
        settingsRepository.save(setting);
        logger.info("Saved setting: {} = {}", key, value);
    }
    
    /**
     * Save multiple settings at once
     */
    @Transactional
    public void saveSettings(Map<String, Object> settings) {
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            saveSetting(entry.getKey(), entry.getValue(), null);
        }
    }
    
    /**
     * Delete a setting
     */
    @Transactional
    public void deleteSetting(String key) {
        settingsRepository.deleteByKey(key);
        logger.info("Deleted setting: {}", key);
    }
    
    /**
     * Parse setting value based on its type
     */
    private Object parseSettingValue(AppSettings setting) {
        if (setting.getValue() == null) {
            return null;
        }
        
        switch (setting.getType()) {
            case "BOOLEAN":
                return Boolean.parseBoolean(setting.getValue());
            case "NUMBER":
                try {
                    if (setting.getValue().contains(".")) {
                        return Double.parseDouble(setting.getValue());
                    } else {
                        return Integer.parseInt(setting.getValue());
                    }
                } catch (NumberFormatException e) {
                    return setting.getValue();
                }
            case "JSON":
                // For now, return as string. Could add JSON parsing later
                return setting.getValue();
            default:
                return setting.getValue();
        }
    }
    
    /**
     * Determine the type of a value
     */
    private String determineType(Object value) {
        if (value == null) {
            return "STRING";
        }
        
        if (value instanceof Boolean) {
            return "BOOLEAN";
        } else if (value instanceof Number) {
            return "NUMBER";
        } else if (value instanceof String) {
            String str = (String) value;
            if (str.startsWith("{") || str.startsWith("[")) {
                return "JSON";
            }
            return "STRING";
        }
        
        return "STRING";
    }
} 