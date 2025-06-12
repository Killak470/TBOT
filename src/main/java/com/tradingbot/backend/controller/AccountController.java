package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.AccountService;
import com.tradingbot.backend.service.BybitAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class AccountController {
    
    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final AccountService accountService;
    private final BybitAccountService bybitAccountService;
    
    public AccountController(MexcApiClientService mexcApiClientService, 
                           AccountService accountService,
                           BybitAccountService bybitAccountService) {
        this.mexcApiClientService = mexcApiClientService;
        this.accountService = accountService;
        this.bybitAccountService = bybitAccountService;
    }
    
    /**
     * Get current account information including balances
     * @param exchange Optional exchange parameter (MEXC or BYBIT)
     * @return Account information with balances
     */
    @GetMapping
    public ResponseEntity<?> getAccountInfo(@RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        try {
            logger.info("Fetching account information for exchange: {}", exchange);
            
            Map<String, Object> accountInfo;
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                accountInfo = bybitAccountService.getAccountBalance();
            } else {
                accountInfo = accountService.getAccountBalance();
            }
            
            return ResponseEntity.ok(accountInfo);
        } catch (Exception e) {
            logger.error("Error fetching account information: ", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch account information");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get account balance summary
     * @param exchange Optional exchange parameter (MEXC or BYBIT)
     * @return Balance summary with total value
     */
    @GetMapping("/balance")
    public ResponseEntity<?> getBalanceSummary(@RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        try {
            logger.info("Fetching balance summary for exchange: {}", exchange);
            
            Map<String, Object> balanceSummary;
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                balanceSummary = bybitAccountService.getBalanceSummary();
            } else {
                balanceSummary = accountService.getBalanceSummary();
            }
            
            return ResponseEntity.ok(balanceSummary);
        } catch (Exception e) {
            logger.error("Error fetching balance summary: ", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch balance summary");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get specific asset balance
     * @param asset The asset symbol (e.g., USDT, BTC)
     * @param exchange Optional exchange parameter (MEXC or BYBIT)
     * @return Asset balance information
     */
    @GetMapping("/balance/{asset}")
    public ResponseEntity<?> getAssetBalance(@PathVariable String asset,
                                           @RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        try {
            logger.info("Fetching balance for asset: {} on exchange: {}", asset, exchange);
            
            Map<String, Object> assetBalance;
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                assetBalance = bybitAccountService.getAssetBalance(asset.toUpperCase());
            } else {
                assetBalance = accountService.getAssetBalance(asset.toUpperCase());
            }
            
            return ResponseEntity.ok(assetBalance);
        } catch (Exception e) {
            logger.error("Error fetching asset balance: ", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch asset balance");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get combined balance summary from all exchanges
     * @return Combined balance summary from all supported exchanges
     */
    @GetMapping("/balance/all")
    public ResponseEntity<?> getCombinedBalanceSummary() {
        try {
            logger.info("Fetching combined balance summary from all exchanges");
            
            Map<String, Object> combinedResponse = new HashMap<>();
            List<Map<String, Object>> exchanges = new ArrayList<>();
            BigDecimal totalValueUSDT = BigDecimal.ZERO;
            
            // Get MEXC balance
            try {
                Map<String, Object> mexcBalance = accountService.getBalanceSummary();
                mexcBalance.put("exchange", "MEXC");
                exchanges.add(mexcBalance);
                
                String mexcTotalStr = String.valueOf(mexcBalance.get("totalEstimatedValueUSDT"));
                if (mexcTotalStr != null && !mexcTotalStr.isEmpty() && !"null".equals(mexcTotalStr)) {
                    totalValueUSDT = totalValueUSDT.add(new BigDecimal(mexcTotalStr));
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch MEXC balance: {}", e.getMessage());
                // Add error entry for MEXC
                Map<String, Object> mexcError = new HashMap<>();
                mexcError.put("exchange", "MEXC");
                mexcError.put("error", "Failed to fetch balance");
                mexcError.put("message", e.getMessage());
                exchanges.add(mexcError);
            }
            
            // Get Bybit balance
            try {
                Map<String, Object> bybitBalance = bybitAccountService.getBalanceSummary();
                bybitBalance.put("exchange", "BYBIT");
                exchanges.add(bybitBalance);
                
                String bybitTotalStr = String.valueOf(bybitBalance.get("totalEstimatedValueUSDT"));
                if (bybitTotalStr != null && !bybitTotalStr.isEmpty() && !"null".equals(bybitTotalStr)) {
                    totalValueUSDT = totalValueUSDT.add(new BigDecimal(bybitTotalStr));
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch Bybit balance: {}", e.getMessage());
                // Add error entry for Bybit
                Map<String, Object> bybitError = new HashMap<>();
                bybitError.put("exchange", "BYBIT");
                bybitError.put("error", "Failed to fetch balance");
                bybitError.put("message", e.getMessage());
                exchanges.add(bybitError);
            }
            
            // Prepare combined response
            combinedResponse.put("exchanges", exchanges);
            combinedResponse.put("totalValueUSDT", totalValueUSDT.toPlainString());
            combinedResponse.put("exchangeCount", exchanges.size());
            combinedResponse.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(combinedResponse);
            
        } catch (Exception e) {
            logger.error("Error fetching combined balance summary: ", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch combined balance summary");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    
    /**
     * Get aggregated asset balances across all exchanges
     * @return Aggregated asset balances from all exchanges
     */
    @GetMapping("/balance/aggregated")
    public ResponseEntity<?> getAggregatedAssetBalances() {
        try {
            logger.info("Fetching aggregated asset balances from all exchanges");
            
            Map<String, Object> response = new HashMap<>();
            Map<String, Map<String, Object>> assetMap = new HashMap<>();
            BigDecimal totalValueUSDT = BigDecimal.ZERO;
            
            // Aggregate MEXC balances
            try {
                Map<String, Object> mexcSummary = accountService.getBalanceSummary();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mexcBalances = (List<Map<String, Object>>) mexcSummary.get("balances");
                
                if (mexcBalances != null) {
                    for (Map<String, Object> balance : mexcBalances) {
                        String asset = balance.get("asset").toString();
                        BigDecimal total = new BigDecimal(balance.get("total").toString());
                        BigDecimal valueUSDT = new BigDecimal(balance.get("estimatedValueUSDT").toString());
                        
                        Map<String, Object> assetData = assetMap.computeIfAbsent(asset, k -> {
                            Map<String, Object> data = new HashMap<>();
                            data.put("asset", asset);
                            data.put("totalBalance", BigDecimal.ZERO);
                            data.put("totalValueUSDT", BigDecimal.ZERO);
                            data.put("exchanges", new ArrayList<Map<String, Object>>());
                            return data;
                        });
                        
                        // Update totals
                        BigDecimal currentTotal = (BigDecimal) assetData.get("totalBalance");
                        BigDecimal currentValue = (BigDecimal) assetData.get("totalValueUSDT");
                        assetData.put("totalBalance", currentTotal.add(total));
                        assetData.put("totalValueUSDT", currentValue.add(valueUSDT));
                        
                        // Add exchange detail
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) assetData.get("exchanges");
                        Map<String, Object> mexcDetail = new HashMap<>();
                        mexcDetail.put("exchange", "MEXC");
                        mexcDetail.put("balance", total.toPlainString());
                        mexcDetail.put("valueUSDT", valueUSDT.toPlainString());
                        exchanges.add(mexcDetail);
                        
                        totalValueUSDT = totalValueUSDT.add(valueUSDT);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to aggregate MEXC balances: {}", e.getMessage());
            }
            
            // Aggregate Bybit balances
            try {
                Map<String, Object> bybitSummary = bybitAccountService.getBalanceSummary();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> bybitBalances = (List<Map<String, Object>>) bybitSummary.get("balances");
                
                if (bybitBalances != null) {
                    for (Map<String, Object> balance : bybitBalances) {
                        String asset = balance.get("asset").toString();
                        BigDecimal total = new BigDecimal(balance.get("total").toString());
                        BigDecimal valueUSDT = new BigDecimal(balance.get("estimatedValueUSDT").toString());
                        
                        Map<String, Object> assetData = assetMap.computeIfAbsent(asset, k -> {
                            Map<String, Object> data = new HashMap<>();
                            data.put("asset", asset);
                            data.put("totalBalance", BigDecimal.ZERO);
                            data.put("totalValueUSDT", BigDecimal.ZERO);
                            data.put("exchanges", new ArrayList<Map<String, Object>>());
                            return data;
                        });
                        
                        // Update totals
                        BigDecimal currentTotal = (BigDecimal) assetData.get("totalBalance");
                        BigDecimal currentValue = (BigDecimal) assetData.get("totalValueUSDT");
                        assetData.put("totalBalance", currentTotal.add(total));
                        assetData.put("totalValueUSDT", currentValue.add(valueUSDT));
                        
                        // Add exchange detail
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> exchanges = (List<Map<String, Object>>) assetData.get("exchanges");
                        Map<String, Object> bybitDetail = new HashMap<>();
                        bybitDetail.put("exchange", "BYBIT");
                        bybitDetail.put("balance", total.toPlainString());
                        bybitDetail.put("valueUSDT", valueUSDT.toPlainString());
                        exchanges.add(bybitDetail);
                        
                        totalValueUSDT = totalValueUSDT.add(valueUSDT);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to aggregate Bybit balances: {}", e.getMessage());
            }
            
            // Convert BigDecimal values to strings for JSON serialization
            List<Map<String, Object>> assetList = new ArrayList<>();
            for (Map<String, Object> assetData : assetMap.values()) {
                BigDecimal totalBalance = (BigDecimal) assetData.get("totalBalance");
                BigDecimal totalValue = (BigDecimal) assetData.get("totalValueUSDT");
                
                assetData.put("totalBalance", totalBalance.toPlainString());
                assetData.put("totalValueUSDT", totalValue.toPlainString());
                
                assetList.add(assetData);
            }
            
            // Sort by total value (descending)
            assetList.sort((a, b) -> {
                BigDecimal valueA = new BigDecimal(a.get("totalValueUSDT").toString());
                BigDecimal valueB = new BigDecimal(b.get("totalValueUSDT").toString());
                return valueB.compareTo(valueA);
            });
            
            response.put("assets", assetList);
            response.put("totalValueUSDT", totalValueUSDT.toPlainString());
            response.put("assetCount", assetList.size());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error fetching aggregated asset balances: ", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to fetch aggregated asset balances");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
} 