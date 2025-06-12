package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing account information and balance
 */
@Service
public class AccountService {

    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final ObjectMapper objectMapper;
    
    // Cache for account data to minimize API calls
    private Map<String, Object> accountCache = new HashMap<>();
    private long lastCacheUpdate = 0;
    private static final long CACHE_VALIDITY_MS = 60000; // 1 minute
    
    public AccountService(MexcApiClientService mexcApiClientService, ObjectMapper objectMapper) {
        this.mexcApiClientService = mexcApiClientService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Get account balance information from MEXC
     * @return Map containing account balance information
     * @throws Exception if there's an error fetching or parsing the data
     */
    public Map<String, Object> getAccountBalance() throws Exception {
        try {
            // Fetch account information from MEXC API
            ResponseEntity<String> response = mexcApiClientService.getAccountInformation();
            
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("Failed to fetch account information from MEXC");
            }
            
            // Parse the response
            Map<String, Object> accountData = objectMapper.readValue(response.getBody(), Map.class);
            
            // Extract balances
            List<Map<String, Object>> balances = (List<Map<String, Object>>) accountData.get("balances");
            
            // Filter out zero balances and format the response
            List<Map<String, Object>> nonZeroBalances = balances.stream()
                .filter(balance -> {
                    BigDecimal free = new BigDecimal(balance.get("free").toString());
                    BigDecimal locked = new BigDecimal(balance.get("locked").toString());
                    return free.compareTo(BigDecimal.ZERO) > 0 || locked.compareTo(BigDecimal.ZERO) > 0;
                })
                .collect(Collectors.toList());
            
            // Prepare response
            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("balances", nonZeroBalances);
            responseMap.put("updateTime", accountData.get("updateTime"));
            responseMap.put("accountType", accountData.get("accountType"));
            responseMap.put("canTrade", accountData.get("canTrade"));
            responseMap.put("canWithdraw", accountData.get("canWithdraw"));
            responseMap.put("canDeposit", accountData.get("canDeposit"));
            
            return responseMap;
        } catch (Exception e) {
            logger.error("Error fetching account balance: ", e);
            throw new RuntimeException("Failed to fetch account balance: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get a summary of account balances with total estimated value
     * @return Map containing balance summary
     * @throws Exception if there's an error
     */
    public Map<String, Object> getBalanceSummary() throws Exception {
        try {
            Map<String, Object> accountInfo = getAccountBalance();
            List<Map<String, Object>> balances = (List<Map<String, Object>>) accountInfo.get("balances");
            
            // Calculate total balance in USDT
            BigDecimal totalInUSDT = BigDecimal.ZERO;
            List<Map<String, Object>> balanceSummary = new ArrayList<>();
            
            for (Map<String, Object> balance : balances) {
                String asset = balance.get("asset").toString();
                BigDecimal free = new BigDecimal(balance.get("free").toString());
                BigDecimal locked = new BigDecimal(balance.get("locked").toString());
                BigDecimal total = free.add(locked);
                
                // Get estimated value in USDT
                BigDecimal valueInUSDT = estimateValueInUSDT(asset, total);
                totalInUSDT = totalInUSDT.add(valueInUSDT);
                
                Map<String, Object> assetSummary = new HashMap<>();
                assetSummary.put("asset", asset);
                assetSummary.put("free", free.toPlainString());
                assetSummary.put("locked", locked.toPlainString());
                assetSummary.put("total", total.toPlainString());
                assetSummary.put("estimatedValueUSDT", valueInUSDT.toPlainString());
                
                balanceSummary.add(assetSummary);
            }
            
            // Sort by estimated value descending
            balanceSummary.sort((a, b) -> {
                BigDecimal valueA = new BigDecimal(a.get("estimatedValueUSDT").toString());
                BigDecimal valueB = new BigDecimal(b.get("estimatedValueUSDT").toString());
                return valueB.compareTo(valueA);
            });
            
            Map<String, Object> summary = new HashMap<>();
            summary.put("totalEstimatedValueUSDT", totalInUSDT.toPlainString());
            summary.put("balances", balanceSummary);
            summary.put("updateTime", accountInfo.get("updateTime"));
            
            return summary;
        } catch (Exception e) {
            logger.error("Error creating balance summary: ", e);
            throw new RuntimeException("Failed to create balance summary: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get balance for a specific asset
     * @param asset The asset symbol
     * @return Map containing asset balance information
     * @throws Exception if there's an error
     */
    public Map<String, Object> getAssetBalance(String asset) throws Exception {
        try {
            Map<String, Object> accountInfo = getAccountBalance();
            List<Map<String, Object>> balances = (List<Map<String, Object>>) accountInfo.get("balances");
            
            // Find the specific asset
            Optional<Map<String, Object>> assetBalance = balances.stream()
                .filter(balance -> balance.get("asset").toString().equalsIgnoreCase(asset))
                .findFirst();
            
            if (assetBalance.isPresent()) {
                Map<String, Object> balance = assetBalance.get();
                BigDecimal free = new BigDecimal(balance.get("free").toString());
                BigDecimal locked = new BigDecimal(balance.get("locked").toString());
                BigDecimal total = free.add(locked);
                
                Map<String, Object> result = new HashMap<>();
                result.put("asset", balance.get("asset"));
                result.put("free", free.toPlainString());
                result.put("locked", locked.toPlainString());
                result.put("total", total.toPlainString());
                result.put("estimatedValueUSDT", estimateValueInUSDT(asset, total).toPlainString());
                
                return result;
            } else {
                // Return zero balance for non-existent asset
                Map<String, Object> result = new HashMap<>();
                result.put("asset", asset);
                result.put("free", "0");
                result.put("locked", "0");
                result.put("total", "0");
                result.put("estimatedValueUSDT", "0");
                
                return result;
            }
        } catch (Exception e) {
            logger.error("Error fetching asset balance for {}: ", asset, e);
            throw new RuntimeException("Failed to fetch asset balance: " + e.getMessage(), e);
        }
    }
    
    /**
     * Estimate the value of an asset in USDT
     * @param asset The asset symbol
     * @param amount The amount of the asset
     * @return Estimated value in USDT
     */
    private BigDecimal estimateValueInUSDT(String asset, BigDecimal amount) {
        try {
            // If already USDT, return the amount
            if ("USDT".equalsIgnoreCase(asset)) {
                return amount;
            }
            
            // For stablecoins, assume 1:1 with USDT
            if (Arrays.asList("USDC", "BUSD", "DAI", "TUSD").contains(asset.toUpperCase())) {
                return amount;
            }
            
            // For other assets, try to get the current price
            try {
                Map<String, Object> priceData = mexcApiClientService.getSymbolPrice(asset + "USDT");
                if (priceData != null && priceData.containsKey("price")) {
                    BigDecimal price = new BigDecimal(priceData.get("price").toString());
                    return amount.multiply(price).setScale(8, RoundingMode.HALF_UP);
                }
            } catch (Exception e) {
                logger.debug("Could not fetch price for {}: {}", asset, e.getMessage());
            }
            
            // If we can't get the price, return 0
            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.error("Error estimating value in USDT for {}: ", asset, e);
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * Get the account's available balance in quote currency (e.g., USDT)
     * 
     * @return The available balance
     */
    public double getAvailableBalance() {
        refreshAccountDataIfNeeded();
        
        if (accountCache.containsKey("availableBalance")) {
            return (double) accountCache.get("availableBalance");
        }
        
        // Default to 0 if balance can't be determined
        return 0;
    }
    
    /**
     * Get the account's total balance (including funds in open orders)
     * 
     * @return The total balance
     */
    public double getTotalBalance() {
        refreshAccountDataIfNeeded();
        
        if (accountCache.containsKey("totalBalance")) {
            return (double) accountCache.get("totalBalance");
        }
        
        // Default to 0 if balance can't be determined
        return 0;
    }
    
    /**
     * Get detailed account information
     * 
     * @return Map containing account details
     */
    public Map<String, Object> getAccountInfo() {
        refreshAccountDataIfNeeded();
        return new HashMap<>(accountCache);
    }
    
    /**
     * Refresh the account data cache if it's expired
     */
    private void refreshAccountDataIfNeeded() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCacheUpdate > CACHE_VALIDITY_MS || accountCache.isEmpty()) {
            try {
                // Fetch account data from the exchange
                Map<String, Object> accountData = mexcApiClientService.getAccountInfo();
                
                if (accountData != null) {
                    processAccountData(accountData);
                    lastCacheUpdate = currentTime;
                    logger.debug("Account data refreshed");
                }
            } catch (Exception e) {
                logger.error("Error refreshing account data: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Process raw account data from the exchange API
     * 
     * @param rawData The raw account data from the API
     */
    private void processAccountData(Map<String, Object> rawData) {
        // Reset the cache
        accountCache.clear();
        
        // Extract and process the data as needed
        // This implementation will vary based on the exchange API format
        
        if (rawData.containsKey("balances")) {
            // Process balances
            Object balances = rawData.get("balances");
            
            // Find the quote currency balance (e.g., USDT)
            // This is a simplified example and would need to be adapted for the actual API response format
            if (balances instanceof Map) {
                Map<?, ?> balancesMap = (Map<?, ?>) balances;
                
                // Look for USDT or another quote currency
                Object usdtBalance = balancesMap.get("USDT");
                if (usdtBalance instanceof Map) {
                    Map<?, ?> usdtData = (Map<?, ?>) usdtBalance;
                    
                    if (usdtData.containsKey("available")) {
                        double available = Double.parseDouble(usdtData.get("available").toString());
                        accountCache.put("availableBalance", available);
                    }
                    
                    if (usdtData.containsKey("total")) {
                        double total = Double.parseDouble(usdtData.get("total").toString());
                        accountCache.put("totalBalance", total);
                    }
                }
            }
        }
        
        // Store the raw data for reference
        accountCache.put("rawData", rawData);
    }
} 