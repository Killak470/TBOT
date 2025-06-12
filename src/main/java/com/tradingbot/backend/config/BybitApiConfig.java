package com.tradingbot.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Configuration
@Component
@ConfigurationProperties(prefix = "bybit.api")
public class BybitApiConfig {
    
    // Base URLs
    @Value("${baseUrl:https://api.bybit.com}")
    private String baseUrl;
    
    @Value("${bybit.api.testnet:false}")
    private boolean testMode;
    
    private String testnetBaseUrl = "https://api-testnet.bybit.com";
    
    // API Credentials
    @Value("${bybit.api.key:}")
    private String apiKey;
    
    @Value("${bybit.api.secret:}")
    private String secretKey;
    
    @Value("${bybit.api.bound.ip:}")
    private String boundIpAddress;
    
    @Value("${bybit.api.validate.ip:true}")
    private boolean validateIpAddress;
    
    // Account Configuration
    @Value("${bybit.account.type:UNIFIED}")
    private String accountType;
    
    @Value("${bybit.account.markets:SPOT,LINEAR,INVERSE,OPTION}")
    private String enabledMarkets;
    
    // API Settings
    @Value("${bybit.api.recv-window:10000}")
    private Integer recvWindow;
    
    @Value("${bybit.api.ws-url}")
    private String wsUrl;
    
    // Regional endpoints for specific countries
    private String netherlandsUrl = "https://api.bybit.nl";
    private String hongKongUrl = "https://api.byhkbit.com";
    private String turkeyUrl = "https://api.bybit-tr.com";
    private String kazakhstanUrl = "https://api.bybit.kz";
    
    public String getBaseUrl() {
        return testMode ? testnetBaseUrl : baseUrl;
    }
    
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
    
    public String getSpotApiUrl() {
        return getBaseUrl() + "/v5";
    }
    
    public String getFuturesApiUrl() {
        return getBaseUrl() + "/v5";
    }
    
    public boolean isMarketEnabled(String market) {
        return enabledMarkets != null && enabledMarkets.toUpperCase().contains(market.toUpperCase());
    }
    
    public boolean isUnifiedAccount() {
        return "UNIFIED".equalsIgnoreCase(accountType);
    }
    
    public String getCategory() {
        if (isUnifiedAccount()) {
            // For unified accounts, we need to return the correct category based on market type
            // This will be determined by the caller (spot or linear)
            return "spot"; // Default to spot, let the service override to linear when needed
        } else {
            return "linear"; // Default to linear for non-unified accounts
        }
    }
    
    /**
     * Get the category based on market type
     * @param marketType The market type ("spot" or "linear")
     * @return The appropriate category for the API
     */
    public String getCategoryForMarketType(String marketType) {
        if ("linear".equalsIgnoreCase(marketType) || "futures".equalsIgnoreCase(marketType)) {
            return "linear";
        }
        return "spot";
    }
    
    public String getAccountType() {
        return accountType;
    }
    
    public void setAccountType(String accountType) {
        this.accountType = accountType;
    }
    
    // Getters and setters for other fields
    public String getApiKey() {
        return apiKey;
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
    
    public String getSecretKey() {
        return secretKey;
    }
    
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
    
    public String getBoundIpAddress() {
        return boundIpAddress;
    }
    
    public void setBoundIpAddress(String boundIpAddress) {
        this.boundIpAddress = boundIpAddress;
    }
    
    public boolean isValidateIpAddress() {
        return validateIpAddress;
    }
    
    public void setValidateIpAddress(boolean validateIpAddress) {
        this.validateIpAddress = validateIpAddress;
    }
    
    public String getEnabledMarkets() {
        return enabledMarkets;
    }
    
    public void setEnabledMarkets(String enabledMarkets) {
        this.enabledMarkets = enabledMarkets;
    }
    
    public Integer getRecvWindow() {
        return recvWindow;
    }
    
    public void setRecvWindow(Integer recvWindow) {
        this.recvWindow = recvWindow;
    }
    
    public String getWsUrl() {
        return wsUrl;
    }
    
    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }
    
    /**
     * Check if test mode (testnet) is enabled
     * @return true if test mode is enabled, false otherwise
     */
    public boolean isTestMode() {
        return testMode;
    }
    
    public void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }
} 