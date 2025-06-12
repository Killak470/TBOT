package com.tradingbot.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "mexc.api")
public class MexcApiConfig {
    private String key;
    private String secret;
    private String baseUrl = "https://api.mexc.com";
    private String spotBaseUrl = "https://api.mexc.com";
    private String futuresBaseUrl = "https://contract.mexc.com";
    private boolean useAuth = false;
    private boolean enabled = true;

    // Spot API v3 paths
    public String getSpotApiV3Path(String path) {
        return spotBaseUrl + "/api/v3" + path;
    }

    // Spot API paths (alias for v3)
    public String getSpotApiPath(String path) {
        return getSpotApiV3Path(path);
    }

    // Futures API v1 paths
    public String getFuturesApiV1Path(String path) {
        return futuresBaseUrl + "/api/v1/contract" + path;
    }
    
    // Check if authentication should be used
    public boolean shouldUseAuth() {
        return useAuth && key != null && !key.isEmpty() && secret != null && !secret.isEmpty();
    }
    
    // Getters that maintain backward compatibility
    public String getApiKey() {
        return key;
    }
    
    public String getSecretKey() {
        return secret;
    }

    // Setters that maintain backward compatibility
    public void setApiKey(String apiKey) {
        this.key = apiKey;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        this.spotBaseUrl = baseUrl;
    }

    public void setUseAuth(Boolean useAuth) {
        this.useAuth = useAuth;
    }
}

