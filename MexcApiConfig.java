package com.tradingbot.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@Setter
public class MexcApiConfig {

    @Value("${mexc.api.key:}")
    private String apiKey;

    @Value("${mexc.api.secret:}")
    private String secretKey;

    @Value("${mexc.api.baseUrl.spot:https://api.mexc.com}")
    private String spotBaseUrl;

    @Value("${mexc.api.baseUrl.futures:https://contract.mexc.com}")
    private String futuresBaseUrl;

    @Value("${mexc.api.useAuth:false}")
    private boolean useAuth;

    // Spot API v3 paths
    public String getSpotApiV3Path(String path) {
        return spotBaseUrl + "/api/v3" + path;
    }

    // Futures API v1 paths
    public String getFuturesApiV1Path(String path) {
        return futuresBaseUrl + "/api/v1/contract" + path;
    }
    
    // Check if authentication should be used
    public boolean shouldUseAuth() {
        return useAuth && apiKey != null && !apiKey.isEmpty() && secretKey != null && !secretKey.isEmpty();
    }
}

