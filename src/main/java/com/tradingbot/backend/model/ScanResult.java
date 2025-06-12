package com.tradingbot.backend.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Result of a market scan for a specific trading pair
 */
public class ScanResult {
    
    // Trading pair (e.g., BTCUSDT)
    private String symbol;
    
    // Exchange where the scan was performed
    private String exchange;
    
    // Time interval analyzed (e.g., "1h")
    private String interval;
    
    // Timestamp when scan was performed
    private long timestamp;
    
    // Current price at time of scan
    private BigDecimal price;
    
    // Overall signal (STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL)
    private String signal;
    
    // Market type (spot or futures)
    private ScanConfig.MarketType marketType = ScanConfig.MarketType.SPOT;
    
    // Recommended leverage for futures (1 for spot)
    private int recommendedLeverage = 1;
    
    // Current drawdown percentage
    private BigDecimal currentDrawdown = BigDecimal.ZERO;
    
    // Risk level (LOW, MEDIUM, HIGH)
    private String riskLevel = "LOW";
    
    // Map of technical indicators and their values
    private Map<String, TechnicalIndicator> indicators;
    
    // AI-generated analysis (optional)
    private String aiAnalysis;
    
    // Enhanced fields for advanced features
    private BigDecimal positionSizeRecommendation;
    private double confidence = 0.5; // Default neutral confidence
    private String signalSummary;

    // Getters and setters
    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setPrice(double price) {
        this.price = BigDecimal.valueOf(price);
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }

    public ScanConfig.MarketType getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        // For Bybit, ensure futures is mapped to linear
        if (marketType != null && marketType.equalsIgnoreCase("futures")) {
            this.marketType = ScanConfig.MarketType.LINEAR;
        } else if (marketType != null && marketType.equalsIgnoreCase("linear")) {
            this.marketType = ScanConfig.MarketType.LINEAR;
        } else if (marketType != null && marketType.equalsIgnoreCase("spot")) {
            this.marketType = ScanConfig.MarketType.SPOT;
        } else {
            this.marketType = ScanConfig.MarketType.SPOT; // Default to spot
        }
    }

    public void setMarketType(ScanConfig.MarketType marketType) {
        this.marketType = marketType;
    }

    public int getRecommendedLeverage() {
        return recommendedLeverage;
    }

    public void setRecommendedLeverage(int recommendedLeverage) {
        this.recommendedLeverage = recommendedLeverage;
    }

    public BigDecimal getCurrentDrawdown() {
        return currentDrawdown;
    }

    public void setCurrentDrawdown(BigDecimal currentDrawdown) {
        this.currentDrawdown = currentDrawdown;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Map<String, TechnicalIndicator> getIndicators() {
        return indicators;
    }

    public void setIndicators(Map<String, TechnicalIndicator> indicators) {
        this.indicators = indicators;
    }

    public String getAiAnalysis() {
        return aiAnalysis;
    }

    public void setAiAnalysis(String aiAnalysis) {
        this.aiAnalysis = aiAnalysis;
    }

    public BigDecimal getPositionSizeRecommendation() {
        return positionSizeRecommendation;
    }

    public void setPositionSizeRecommendation(BigDecimal positionSizeRecommendation) {
        this.positionSizeRecommendation = positionSizeRecommendation;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getSignalSummary() {
        return signalSummary;
    }

    public void setSignalSummary(String signalSummary) {
        this.signalSummary = signalSummary;
    }

    @Override
    public String toString() {
        return "ScanResult{" +
                "symbol='" + symbol + '\'' +
                ", exchange='" + exchange + '\'' +
                ", interval='" + interval + '\'' +
                ", timestamp=" + timestamp +
                ", price=" + price +
                ", signal='" + signal + '\'' +
                ", marketType='" + marketType + '\'' +
                ", recommendedLeverage=" + recommendedLeverage +
                ", currentDrawdown=" + currentDrawdown +
                ", riskLevel='" + riskLevel + '\'' +
                ", indicators=" + indicators.size() +
                ", hasAiAnalysis=" + (aiAnalysis != null && !aiAnalysis.isEmpty()) +
                '}';
    }
} 