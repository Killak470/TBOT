package com.tradingbot.backend.model;

import java.util.Map;

/**
 * Technical indicator calculated for market analysis
 */
public class TechnicalIndicator {
    
    // Name of the indicator (e.g., "RSI_14", "MA_50")
    private String name;
    
    // Calculated value of the indicator
    private double value;
    
    // Signal derived from the indicator (BULLISH, BEARISH, NEUTRAL, OVERBOUGHT, OVERSOLD, etc.)
    private String signal;
    
    // Percent difference between current price and indicator value (for some indicators)
    private Double percentDifference;
    
    // Additional data specific to the indicator type
    private Map<String, Object> additionalData;

    // Getters and setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public String getSignal() {
        return signal;
    }

    public void setSignal(String signal) {
        this.signal = signal;
    }

    public Double getPercentDifference() {
        return percentDifference;
    }

    public void setPercentDifference(Double percentDifference) {
        this.percentDifference = percentDifference;
    }

    public Map<String, Object> getAdditionalData() {
        return additionalData;
    }

    public void setAdditionalData(Map<String, Object> additionalData) {
        this.additionalData = additionalData;
    }

    @Override
    public String toString() {
        return "TechnicalIndicator{" +
                "name='" + name + '\'' +
                ", value=" + value +
                ", signal='" + signal + '\'' +
                (percentDifference != null ? ", percentDifference=" + percentDifference : "") +
                '}';
    }
} 