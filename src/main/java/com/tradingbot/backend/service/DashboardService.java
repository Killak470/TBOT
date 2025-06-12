package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class DashboardService {
    
    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);
    
    private final BybitApiClientService bybitApiClient;
    private final BybitPositionService positionService;
    private final ObjectMapper objectMapper;
    
    // Active trading pairs
    private static final String[] ACTIVE_PAIRS = {
        "BTCUSDT", "ETHUSDT", "XRPUSDT", "ADAUSDT", "SOLUSDT", "DOTUSDT"
    };
    
    public DashboardService(BybitApiClientService bybitApiClient, 
                          BybitPositionService positionService) {
        this.bybitApiClient = bybitApiClient;
        this.positionService = positionService;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Get complete dashboard data
     */
    public DashboardData getDashboardData() {
        try {
            Map<String, AssetBalance> assetBalances = getAssetBalances();
            List<Position> positions = getPositions();
            RiskMetrics riskMetrics = calculateRiskMetrics(positions, assetBalances);
            
            return DashboardData.builder()
                .serverStatus(getServerStatus())
                .serverTime(getCurrentTime())
                .portfolioValue(calculateTotalPortfolioValue(assetBalances))
                .assetBalances(assetBalances)
                .positions(positions)
                .recentTrades(getRecentTrades())
                .riskMetrics(riskMetrics)
                .systemInfo(getSystemInfo())
                .build();
        } catch (Exception e) {
            logger.error("Error getting dashboard data: {}", e.getMessage());
            return DashboardData.builder()
                .serverStatus("ERROR")
                .serverTime("N/A")
                .portfolioValue(BigDecimal.ZERO)
                .assetBalances(new HashMap<>())
                .positions(new ArrayList<>())
                .recentTrades(new ArrayList<>())
                .riskMetrics(RiskMetrics.builder().build())
                .systemInfo(Map.of(
                    "SERVER_TIME", "N/A",
                    "EXCHANGE", "BYBIT",
                    "TRADING_PAIRS", String.join(",", ACTIVE_PAIRS),
                    "BOT_VERSION", "0.1.0"
                ))
                .build();
        }
    }
    
    private String getCurrentTime() {
        try {
            var response = bybitApiClient.getPublicIp(); // This will make a request to check connectivity
            return String.valueOf(System.currentTimeMillis());
        } catch (Exception e) {
            return "N/A";
        }
    }
    
    private String getServerStatus() {
        try {
            bybitApiClient.getPublicIp(); // This will make a request to check connectivity
            return "ONLINE";
        } catch (Exception e) {
            return "OFFLINE";
        }
    }
    
    private Map<String, AssetBalance> getAssetBalances() {
        Map<String, AssetBalance> balances = new HashMap<>();
        try {
            var response = bybitApiClient.getWalletBalance("UNIFIED", "USDT,BTC,ETH");
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("result") && root.get("result").has("list")) {
                    JsonNode balanceList = root.get("result").get("list");
                    for (JsonNode balance : balanceList) {
                        String coin = balance.get("coin").asText();
                        BigDecimal free = new BigDecimal(balance.get("availableBalance").asText());
                        BigDecimal locked = new BigDecimal(balance.get("walletBalance").asText()).subtract(free);
                        BigDecimal usdValue = getUsdValue(coin, free.add(locked));
                        
                        balances.put(coin, AssetBalance.builder()
                            .asset(coin)
                            .free(free)
                            .locked(locked)
                            .usdValue(usdValue)
                            .build());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error getting asset balances: {}", e.getMessage());
        }
        return balances;
    }
    
    private List<Position> getPositions() {
        List<Position> positions = new ArrayList<>();
        try {
            List<BybitPositionService.Position> bybitPositions = positionService.getOpenPositions("linear");
            for (BybitPositionService.Position pos : bybitPositions) {
                positions.add(Position.builder()
                    .symbol(pos.getSymbol())
                    .side(pos.getSide())
                    .size(pos.getSize())
                    .entryPrice(pos.getEntryPrice())
                    .currentPrice(getCurrentPrice(pos.getSymbol()))
                    .unrealizedPnl(pos.getUnrealizedPnl())
                    .build());
            }
        } catch (Exception e) {
            logger.error("Error getting positions: {}", e.getMessage());
        }
        return positions;
    }
    
    private RiskMetrics calculateRiskMetrics(List<Position> positions, Map<String, AssetBalance> balances) {
        try {
            BigDecimal totalEquity = calculateTotalPortfolioValue(balances);
            BigDecimal totalPositionValue = positions.stream()
                .map(p -> p.getSize().multiply(p.getCurrentPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal totalUnrealizedPnl = positions.stream()
                .map(Position::getUnrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal highestLeverage = positions.stream()
                .map(Position::getSize)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            
            BigDecimal lowestMarginRatio = positions.stream()
                .map(p -> p.getUnrealizedPnl().divide(p.getSize().multiply(p.getCurrentPrice()), 4, RoundingMode.HALF_UP))
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            
            return RiskMetrics.builder()
                .totalEquity(totalEquity)
                .totalPositionValue(totalPositionValue)
                .totalUnrealizedPnl(totalUnrealizedPnl)
                .marginUtilization(totalPositionValue.divide(totalEquity, 4, RoundingMode.HALF_UP))
                .highestLeverage(highestLeverage)
                .lowestMarginRatio(lowestMarginRatio)
                .build();
        } catch (Exception e) {
            logger.error("Error calculating risk metrics: {}", e.getMessage());
            return RiskMetrics.builder().build();
        }
    }
    
    private BigDecimal getCurrentPrice(String symbol) {
        try {
            ResponseEntity<String> response = bybitApiClient.getLatestPrice(symbol);
            JsonNode data = objectMapper.readTree(response.getBody());
            return new BigDecimal(data.get("price").asText());
        } catch (Exception e) {
            logger.error("Error getting current price for {}: {}", symbol, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    private BigDecimal calculateLiquidationPrice(BybitPositionService.Position position) {
        try {
            BigDecimal entryPrice = position.getEntryPrice();
            BigDecimal leverage = position.getLeverage();
            BigDecimal maintenanceMargin = new BigDecimal("0.005"); // 0.5% maintenance margin
            
            if ("LONG".equals(position.getSide())) {
                return entryPrice.multiply(BigDecimal.ONE.subtract(maintenanceMargin.multiply(leverage)));
            } else {
                return entryPrice.multiply(BigDecimal.ONE.add(maintenanceMargin.multiply(leverage)));
            }
        } catch (Exception e) {
            logger.error("Error calculating liquidation price: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    private BigDecimal calculateMarginRatio(BybitPositionService.Position position) {
        try {
            BigDecimal positionValue = position.getSize().multiply(position.getEntryPrice());
            BigDecimal margin = positionValue.divide(position.getLeverage(), 4, RoundingMode.HALF_UP);
            return margin.divide(positionValue, 4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            logger.error("Error calculating margin ratio: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    private BigDecimal calculateTotalPortfolioValue(Map<String, AssetBalance> balances) {
        return balances.values().stream()
            .map(AssetBalance::getUsdValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    
    private BigDecimal getUsdValue(String coin, BigDecimal amount) {
        if ("USDT".equals(coin)) {
            return amount;
        }
        try {
            var response = bybitApiClient.getLatestPrice(coin + "USDT");
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("result") && root.get("result").has("list")) {
                    BigDecimal price = new BigDecimal(root.get("result").get("list").get(0).get("lastPrice").asText());
                    return amount.multiply(price);
                }
            }
        } catch (Exception e) {
            logger.error("Error getting USD value for {}: {}", coin, e.getMessage());
        }
        return BigDecimal.ZERO;
    }
    
    private List<Trade> getRecentTrades() {
        List<Trade> trades = new ArrayList<>();
        try {
            List<BybitPositionService.Position> positions = positionService.getOpenPositions("linear");
            for (BybitPositionService.Position pos : positions) {
                trades.add(Trade.builder()
                    .symbol(pos.getSymbol())
                    .side(pos.getSide())
                    .amount(pos.getSize())
                    .price(pos.getEntryPrice())
                    .pnl(pos.getUnrealizedPnl())
                    .timestamp(String.valueOf(System.currentTimeMillis()))
                    .build());
            }
        } catch (Exception e) {
            logger.error("Error getting recent trades: {}", e.getMessage());
        }
        return trades;
    }
    
    private Map<String, String> getSystemInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("SERVER_TIME", getCurrentTime());
        info.put("EXCHANGE", "BYBIT");
        info.put("TRADING_PAIRS", String.join(",", ACTIVE_PAIRS));
        info.put("BOT_VERSION", "0.1.0");
        return info;
    }
    
    @Builder
    @Data
    public static class DashboardData {
        private String serverStatus;
        private String serverTime;
        private BigDecimal portfolioValue;
        private Map<String, AssetBalance> assetBalances;
        private List<Position> positions;
        private List<Trade> recentTrades;
        private RiskMetrics riskMetrics;
        private Map<String, String> systemInfo;
    }
    
    @Builder
    @Data
    public static class AssetBalance {
        private String asset;
        private BigDecimal free;
        private BigDecimal locked;
        private BigDecimal usdValue;
    }
    
    @Builder
    @Data
    public static class Position {
        private String symbol;
        private String side;
        private BigDecimal size;
        private BigDecimal entryPrice;
        private BigDecimal currentPrice;
        private BigDecimal unrealizedPnl;
    }
    
    @Builder
    @Data
    public static class RiskMetrics {
        private BigDecimal totalEquity;
        private BigDecimal totalPositionValue;
        private BigDecimal totalUnrealizedPnl;
        private BigDecimal marginUtilization;
        private BigDecimal highestLeverage;
        private BigDecimal lowestMarginRatio;
    }
    
    @Builder
    @Data
    public static class Trade {
        private String symbol;
        private String side;
        private BigDecimal amount;
        private BigDecimal price;
        private BigDecimal pnl;
        private String timestamp;
    }
    
    @Data
    @Builder
    public static class PositionSummary {
        private String symbol;
        private String side;
        private BigDecimal size;
        private BigDecimal entryPrice;
        private BigDecimal currentPrice;
        private BigDecimal unrealizedPnl;
    }
} 