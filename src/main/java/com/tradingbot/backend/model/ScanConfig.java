package com.tradingbot.backend.model;

import java.util.List;

/**
 * Configuration for market scanner
 */
public class ScanConfig {
    
    /**
     * Supported market types for different exchanges
     */
    public enum MarketType {
        SPOT("spot"),
        FUTURES("futures"),  // For MEXC
        LINEAR("linear");    // For Bybit
        
        private final String value;
        
        MarketType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static MarketType fromString(String value) {
            for (MarketType type : MarketType.values()) {
                if (type.value.equalsIgnoreCase(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown market type: " + value);
        }
    }
    
    // Trading pairs to scan (BTCUSDT, ETHUSDT, etc.)
    private List<String> tradingPairs;
    
    // Interval to analyze ("5m", "15m", "1h", "4h", "1d")
    private String interval;
    
    // Market type to scan (spot, futures, or linear)
    private MarketType marketType = MarketType.SPOT;
    
    // Minimum signal strength to include in results (STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL)
    private String minimumSignalStrength;
    
    // Whether to include AI analysis in the results
    private boolean includeAiAnalysis = false;
    
    // Whether to run the scan continuously
    private boolean continuousScan = false;
    
    // Scan interval in minutes (if continuous scan is enabled)
    private int scanIntervalMinutes = 60;
    
    // Alert threshold for significant changes
    private double alertThreshold = 5.0;
    
    // Whether to enable alerts for this scan
    private boolean enableAlerts = false;
    
    // Exchange to use for scanning ("MEXC" or "BYBIT")
    private String exchange = "MEXC";

    // Lookback period for Fibonacci trend identification
    private int fiboLookbackPeriod = 50; // Default to 50 periods

    // Minimum number of data points required for TA calculations
    private int minCalculationPeriod = 20; // Default to 20 periods

    // Getters and Setters
    public List<String> getTradingPairs() {
        return tradingPairs;
    }

    public void setTradingPairs(List<String> tradingPairs) {
        this.tradingPairs = tradingPairs;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public MarketType getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        this.marketType = MarketType.fromString(marketType);
    }

    public void setMarketType(MarketType marketType) {
        this.marketType = marketType;
    }

    public String getMinimumSignalStrength() {
        return minimumSignalStrength;
    }

    public void setMinimumSignalStrength(String minimumSignalStrength) {
        this.minimumSignalStrength = minimumSignalStrength;
    }

    public boolean isIncludeAiAnalysis() {
        return includeAiAnalysis;
    }

    public void setIncludeAiAnalysis(boolean includeAiAnalysis) {
        this.includeAiAnalysis = includeAiAnalysis;
    }

    public boolean isContinuousScan() {
        return continuousScan;
    }

    public void setContinuousScan(boolean continuousScan) {
        this.continuousScan = continuousScan;
    }

    public int getScanIntervalMinutes() {
        return scanIntervalMinutes;
    }

    public void setScanIntervalMinutes(int scanIntervalMinutes) {
        this.scanIntervalMinutes = scanIntervalMinutes;
    }

    public double getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(double alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public boolean isEnableAlerts() {
        return enableAlerts;
    }

    public void setEnableAlerts(boolean enableAlerts) {
        this.enableAlerts = enableAlerts;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public int getFiboLookbackPeriod() {
        return fiboLookbackPeriod;
    }

    public void setFiboLookbackPeriod(int fiboLookbackPeriod) {
        this.fiboLookbackPeriod = fiboLookbackPeriod;
    }

    public int getMinCalculationPeriod() {
        return minCalculationPeriod;
    }

    public void setMinCalculationPeriod(int minCalculationPeriod) {
        this.minCalculationPeriod = minCalculationPeriod;
    }
    
    @Override
    public String toString() {
        return "ScanConfig{" +
                "tradingPairs=" + tradingPairs +
                ", interval='" + interval + '\'' +
                ", marketType='" + marketType + '\'' +
                ", minimumSignalStrength='" + minimumSignalStrength + '\'' +
                ", includeAiAnalysis=" + includeAiAnalysis +
                ", continuousScan=" + continuousScan +
                ", scanIntervalMinutes=" + scanIntervalMinutes +
                ", exchange='" + exchange + '\'' +
                ", fiboLookbackPeriod=" + fiboLookbackPeriod +
                ", minCalculationPeriod=" + minCalculationPeriod +
                '}';
    }
} 