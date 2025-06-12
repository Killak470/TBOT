package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.tradingbot.backend.config.BybitApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Service for managing Bybit unified account information and balance
 */
@Service
public class BybitAccountService {

    private static final Logger logger = LoggerFactory.getLogger(BybitAccountService.class);
    
    private final BybitApiClientService bybitApiClientService;
    private final BybitApiConfig bybitApiConfig;
    private final ObjectMapper objectMapper;
    
    // Cache for account data to minimize API calls
    private Map<String, Object> accountCache = new HashMap<>();
    private long lastCacheUpdate = 0;
    private static final long CACHE_VALIDITY_MS = 60000; // 1 minute
    
    @Autowired
    public BybitAccountService(BybitApiClientService bybitApiClientService, BybitApiConfig bybitApiConfig) {
        this.bybitApiClientService = bybitApiClientService;
        this.bybitApiConfig = bybitApiConfig;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Validate API credentials before making any API calls
     * @throws RuntimeException if credentials are invalid
     */
    private void validateApiCredentials() {
        if (bybitApiConfig.getApiKey() == null || bybitApiConfig.getApiKey().trim().isEmpty()) {
            throw new RuntimeException("Bybit API key is not configured. Please check your .env file.");
        }
        if (bybitApiConfig.getSecretKey() == null || bybitApiConfig.getSecretKey().trim().isEmpty()) {
            throw new RuntimeException("Bybit API secret is not configured. Please check your .env file.");
        }
        
        // Validate IP address if enabled
        if (bybitApiConfig.isValidateIpAddress()) {
            String currentIp = getCurrentIpAddress();
            String boundIp = bybitApiConfig.getBoundIpAddress();
            
            if (currentIp == null || boundIp == null) {
                throw new RuntimeException("Failed to validate IP address: IP information is missing");
            }
            
            if (!boundIp.equals(currentIp)) {
                logger.error("Current IP {} does not match bound IP {}. Please ensure you are connected to the correct VPN (CyberGhost).", 
                    currentIp, boundIp);
                throw new RuntimeException("Current IP address does not match the IP bound to the API key. Please ensure you are connected to the CyberGhost VPN.");
            } else {
                logger.info("IP validation successful. Current IP {} matches bound IP {}", currentIp, boundIp);
            }
        }
        
        // Verify API key permissions
        try {
            ResponseEntity<String> response = bybitApiClientService.getAccountInfo();
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Failed to verify API key permissions. Status code: " + response.getStatusCode());
            }
            
            Map<String, Object> responseData = objectMapper.readValue(response.getBody(), Map.class);
            Integer retCode = (Integer) responseData.get("retCode");
            
            if (retCode == null || retCode != 0) {
                String retMsg = (String) responseData.get("retMsg");
                if (retMsg != null && retMsg.contains("api_key_invalid")) {
                    logger.error("Invalid Bybit API key detected. Please check your API key permissions and ensure it has access to the required endpoints.");
                    throw new RuntimeException("Invalid Bybit API key. Please check your API key permissions in the Bybit dashboard.");
                }
                throw new RuntimeException("Bybit API error: " + retMsg);
            }
            
            // Check account type
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) responseData.get("result");
            if (result != null) {
                Integer unifiedMarginStatus = (Integer) result.get("unifiedMarginStatus");
                if (unifiedMarginStatus != null && unifiedMarginStatus != 6) {
                    logger.warn("Account is not UTA2.0 Pro. Current status: {}", unifiedMarginStatus);
                }
            }
            
            logger.info("API key validation successful. All required permissions are present.");
            
        } catch (Exception e) {
            logger.error("Error validating API key: ", e);
            throw new RuntimeException("Failed to validate API key: " + e.getMessage());
        }
    }
    
    /**
     * Get the current external IP address
     * @return The current IP address
     */
    private String getCurrentIpAddress() {
        try {
            ResponseEntity<String> response = bybitApiClientService.getPublicIp();
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode ipData = objectMapper.readTree(response.getBody());
                return ipData.get("ip").asText();
            }
            throw new RuntimeException("Failed to get current IP address");
        } catch (Exception e) {
            logger.error("Error getting current IP address: ", e);
            throw new RuntimeException("Failed to validate IP address: " + e.getMessage());
        }
    }
    
    /**
     * Get account balance information from Bybit unified account
     * @return Map containing account balance information
     * @throws Exception if there's an error fetching or parsing the data
     */
    public Map<String, Object> getAccountBalance() throws Exception {
        try {
            // Validate API credentials first
            validateApiCredentials();
            
            // Check and upgrade account status if needed
            if (!checkAndUpgradeAccountStatus()) {
                throw new RuntimeException("Account is not ready for trading. Please check logs for details.");
            }
            
            // Fetch account information from Bybit API for unified account
            ResponseEntity<String> response = bybitApiClientService.getWalletBalance("UNIFIED", "USDT,USDC,BTC,ETH");
            
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Failed to fetch account information from Bybit. Status code: " + response.getStatusCode());
            }
            
            // Parse the response
            Map<String, Object> responseData = objectMapper.readValue(response.getBody(), Map.class);
            
            // Check if the response is successful
            Integer retCode = (Integer) responseData.get("retCode");
            if (retCode == null || retCode != 0) {
                String retMsg = (String) responseData.get("retMsg");
                logger.error("Bybit API error: {} (Code: {})", retMsg, retCode);
                throw new RuntimeException("Bybit API error: " + retMsg);
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) responseData.get("result");
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> accountList = (List<Map<String, Object>>) result.get("list");
            
            if (accountList == null || accountList.isEmpty()) {
                throw new RuntimeException("No account data returned from Bybit");
            }
            
            Map<String, Object> accountData = accountList.get(0);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> coins = (List<Map<String, Object>>) accountData.get("coin");
            
            if (coins == null || coins.isEmpty()) {
                throw new RuntimeException("No coin data returned from Bybit");
            }
            
            // Format the response to match our frontend expectations
            List<Map<String, Object>> formattedBalances = new ArrayList<>();
            
            for (Map<String, Object> coin : coins) {
                String coinName = (String) coin.get("coin");
                BigDecimal walletBalance = new BigDecimal(coin.get("walletBalance").toString());
                BigDecimal equity = new BigDecimal(coin.get("equity").toString());
                
                if (walletBalance.compareTo(BigDecimal.ZERO) > 0 || equity.compareTo(BigDecimal.ZERO) > 0) {
                    Map<String, Object> formattedCoin = new HashMap<>();
                    formattedCoin.put("asset", coinName);
                    formattedCoin.put("walletBalance", walletBalance.toPlainString());
                    formattedCoin.put("equity", equity.toPlainString());
                    formattedCoin.put("usdValue", coin.get("usdValue").toString());
                    formattedCoin.put("availableToWithdraw", coin.get("availableToWithdraw").toString());
                    formattedCoin.put("unrealisedPnl", coin.get("unrealisedPnl").toString());
                    formattedCoin.put("cumRealisedPnl", coin.get("cumRealisedPnl").toString());
                    formattedCoin.put("bonus", coin.get("bonus").toString());
                    
                    formattedBalances.add(formattedCoin);
                }
            }
            
            // Prepare response
            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("balances", formattedBalances);
            responseMap.put("accountType", accountData.get("accountType"));
            responseMap.put("totalEquity", accountData.get("totalEquity"));
            responseMap.put("totalWalletBalance", accountData.get("totalWalletBalance"));
            responseMap.put("totalMarginBalance", accountData.get("totalMarginBalance"));
            responseMap.put("totalAvailableBalance", accountData.get("totalAvailableBalance"));
            responseMap.put("totalPerpUPL", accountData.get("totalPerpUPL"));
            responseMap.put("totalInitialMargin", accountData.get("totalInitialMargin"));
            responseMap.put("totalMaintenanceMargin", accountData.get("totalMaintenanceMargin"));
            responseMap.put("exchange", "BYBIT");
            responseMap.put("updateTime", System.currentTimeMillis());
            
            return responseMap;
            
        } catch (Exception e) {
            logger.error("Error fetching account balance from Bybit: ", e);
            if (e.getMessage().contains("API key is invalid")) {
                throw new RuntimeException("Invalid Bybit API key. Please check your credentials in the .env file.", e);
            }
            throw new RuntimeException("Failed to fetch account balance from Bybit: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get balance summary with total estimated value in USDT
     * @return Map containing balance summary
     * @throws Exception if there's an error fetching or parsing the data
     */
    public Map<String, Object> getBalanceSummary() throws Exception {
        try {
            Map<String, Object> accountBalance = getAccountBalance();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> balances = (List<Map<String, Object>>) accountBalance.get("balances");
            
            BigDecimal totalEstimatedValueUSDT = BigDecimal.ZERO;
            
            if (balances != null) {
                for (Map<String, Object> balance : balances) {
                    String usdValueStr = balance.get("usdValue").toString();
                    if (usdValueStr != null && !usdValueStr.isEmpty() && !"null".equals(usdValueStr)) {
                        BigDecimal usdValue = new BigDecimal(usdValueStr);
                        totalEstimatedValueUSDT = totalEstimatedValueUSDT.add(usdValue);
                    }
                }
            }
            
            // If usdValue calculation doesn't work, fall back to totalEquity
            if (totalEstimatedValueUSDT.compareTo(BigDecimal.ZERO) == 0) {
                String totalEquityStr = accountBalance.get("totalEquity").toString();
                if (totalEquityStr != null && !totalEquityStr.isEmpty()) {
                    totalEstimatedValueUSDT = new BigDecimal(totalEquityStr);
                }
            }
            
            // Prepare enhanced balances with USDT values
            List<Map<String, Object>> enhancedBalances = new ArrayList<>();
            if (balances != null) {
                for (Map<String, Object> balance : balances) {
                    Map<String, Object> enhancedBalance = new HashMap<>(balance);
                    
                    String usdValueStr = balance.get("usdValue").toString();
                    if (usdValueStr != null && !usdValueStr.isEmpty() && !"null".equals(usdValueStr)) {
                        enhancedBalance.put("estimatedValueUSDT", usdValueStr);
                    } else {
                        enhancedBalance.put("estimatedValueUSDT", "0");
                    }
                    
                    enhancedBalances.add(enhancedBalance);
                }
            }
            
            Map<String, Object> summary = new HashMap<>();
            summary.put("totalEstimatedValueUSDT", totalEstimatedValueUSDT.toPlainString());
            summary.put("balances", enhancedBalances);
            summary.put("exchange", "BYBIT");
            summary.put("accountType", accountBalance.get("accountType"));
            summary.put("totalEquity", accountBalance.get("totalEquity"));
            summary.put("totalWalletBalance", accountBalance.get("totalWalletBalance"));
            summary.put("totalMarginBalance", accountBalance.get("totalMarginBalance"));
            summary.put("totalAvailableBalance", accountBalance.get("totalAvailableBalance"));
            summary.put("totalPerpUPL", accountBalance.get("totalPerpUPL"));
            summary.put("totalInitialMargin", accountBalance.get("totalInitialMargin"));
            summary.put("totalMaintenanceMargin", accountBalance.get("totalMaintenanceMargin"));
            summary.put("updateTime", System.currentTimeMillis());
            
            return summary;
            
        } catch (Exception e) {
            logger.error("Error creating balance summary for Bybit: ", e);
            throw new RuntimeException("Failed to create balance summary for Bybit: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get balance for a specific asset
     * @param asset The asset symbol
     * @return Map containing asset balance information
     * @throws Exception if there's an error fetching or parsing the data
     */
    public Map<String, Object> getAssetBalance(String asset) throws Exception {
        try {
            Map<String, Object> accountInfo = getAccountBalance();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> balances = (List<Map<String, Object>>) accountInfo.get("balances");
            
            // Find the specific asset
            Optional<Map<String, Object>> assetBalance = balances.stream()
                .filter(balance -> balance.get("asset").toString().equalsIgnoreCase(asset))
                .findFirst();
            
            if (assetBalance.isPresent()) {
                Map<String, Object> balance = assetBalance.get();
                Map<String, Object> result = new HashMap<>();
                result.put("asset", balance.get("asset"));
                result.put("free", balance.get("free"));
                result.put("locked", balance.get("locked"));
                result.put("total", balance.get("total"));
                result.put("estimatedValueUSDT", balance.get("usdValue"));
                result.put("equity", balance.get("equity"));
                result.put("unrealisedPnl", balance.get("unrealisedPnl"));
                result.put("cumRealisedPnl", balance.get("cumRealisedPnl"));
                result.put("exchange", "BYBIT");
                
                return result;
            } else {
                // Return zero balance for non-existent asset
                Map<String, Object> result = new HashMap<>();
                result.put("asset", asset);
                result.put("free", "0");
                result.put("locked", "0");
                result.put("total", "0");
                result.put("estimatedValueUSDT", "0");
                result.put("equity", "0");
                result.put("unrealisedPnl", "0");
                result.put("cumRealisedPnl", "0");
                result.put("exchange", "BYBIT");
                
                return result;
            }
        } catch (Exception e) {
            logger.error("Error fetching asset balance for {} from Bybit: ", asset, e);
            throw new RuntimeException("Failed to fetch asset balance from Bybit: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if cache is valid
     */
    private boolean isCacheValid() {
        return System.currentTimeMillis() - lastCacheUpdate < CACHE_VALIDITY_MS;
    }
    
    /**
     * Check and upgrade account status if needed
     * @return true if account is ready for trading, false otherwise
     */
    public boolean checkAndUpgradeAccountStatus() {
        try {
            validateApiCredentials();
            
            // Get current account status
            ResponseEntity<String> response = bybitApiClientService.getAccountInfo();
            
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Failed to get account info from Bybit. Status code: " + response.getStatusCode());
            }
            
            // Parse the response
            Map<String, Object> responseData = objectMapper.readValue(response.getBody(), Map.class);
            
            // Check if the response is successful
            Integer retCode = (Integer) responseData.get("retCode");
            if (retCode == null || retCode != 0) {
                String retMsg = (String) responseData.get("retMsg");
                if (retMsg.contains("api_key_invalid")) {
                    logger.error("Invalid Bybit API key detected");
                    throw new RuntimeException("Invalid Bybit API key. Please check your credentials in the .env file.");
                }
                throw new RuntimeException("Bybit API error: " + retMsg);
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) responseData.get("result");
            
            // Check unified margin status
            Integer unifiedMarginStatus = (Integer) result.get("unifiedMarginStatus");
            
            if (unifiedMarginStatus == null) {
                logger.warn("Could not determine unified margin status");
                return false;
            }
            
            // Check if upgrade is needed based on status
            boolean needsUpgrade = false;
            
            switch (unifiedMarginStatus) {
                case 1: // Classic account
                case 4: // UTA1.0 Pro
                case 5: // UTA2.0
                    needsUpgrade = true;
                    break;
                case 3: // UTA1.0
                    logger.warn("Account is UTA1.0, please upgrade to UTA2.0 on the website first");
                    return false;
                case 6: // UTA2.0 Pro
                    return true;
                default:
                    logger.warn("Unknown unified margin status: {}", unifiedMarginStatus);
                    return false;
            }
            
            if (needsUpgrade) {
                // Attempt to upgrade to UTA2.0 Pro
                ResponseEntity<String> upgradeResponse = bybitApiClientService.upgradeToUnifiedAccount();
                
                if (!upgradeResponse.getStatusCode().is2xxSuccessful() || upgradeResponse.getBody() == null) {
                    throw new RuntimeException("Failed to upgrade account to UTA");
                }
                
                Map<String, Object> upgradeData = objectMapper.readValue(upgradeResponse.getBody(), Map.class);
                
                Integer upgradeRetCode = (Integer) upgradeData.get("retCode");
                if (upgradeRetCode == null || upgradeRetCode != 0) {
                    String upgradeRetMsg = (String) upgradeData.get("retMsg");
                    throw new RuntimeException("Failed to upgrade account: " + upgradeRetMsg);
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> upgradeResult = (Map<String, Object>) upgradeData.get("result");
                String upgradeStatus = (String) upgradeResult.get("unifiedUpdateStatus");
                
                if ("SUCCESS".equals(upgradeStatus)) {
                    logger.info("Successfully upgraded account to UTA2.0 Pro");
                    return true;
                } else if ("PROCESS".equals(upgradeStatus)) {
                    logger.info("Account upgrade is in process, please try again later");
                    return false;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> upgradeMsg = (Map<String, Object>) upgradeResult.get("unifiedUpdateMsg");
                    if (upgradeMsg != null) {
                        @SuppressWarnings("unchecked")
                        List<String> msgs = (List<String>) upgradeMsg.get("msg");
                        if (msgs != null && !msgs.isEmpty()) {
                            throw new RuntimeException("Failed to upgrade account: " + String.join(", ", msgs));
                        }
                    }
                    throw new RuntimeException("Failed to upgrade account: Unknown error");
                }
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error checking/upgrading account status: ", e);
            throw new RuntimeException("Failed to check/upgrade account status: " + e.getMessage(), e);
        }
    }
} 