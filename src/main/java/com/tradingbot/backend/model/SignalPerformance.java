package com.tradingbot.backend.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * POJO to track signal performance for machine learning and optimization
 */
public class SignalPerformance {
    
    private Long id;
    private Long signalId;
    private String symbol;
    private BigDecimal initialConfidence;
    private String actualOutcome; // WIN/LOSS/BREAKEVEN
    private BigDecimal expectedReturn;
    private BigDecimal actualReturn;
    private BigDecimal aiAccuracy;
    private BigDecimal technicalAccuracy;
    private BigDecimal sentimentAccuracy;
    private String marketRegime;
    private BigDecimal volatilityAtEntry;
    private BigDecimal volumeStrength;
    private String timeframe;
    private Integer leverageUsed;
    private BigDecimal positionSize;
    private LocalDateTime createdAt;
    private LocalDateTime closedAt;
    
    public SignalPerformance() {
        this.createdAt = LocalDateTime.now();
    }
    
    public SignalPerformance(Long signalId, String symbol) {
        this();
        this.signalId = signalId;
        this.symbol = symbol;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getSignalId() { return signalId; }
    public void setSignalId(Long signalId) { this.signalId = signalId; }
    
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    
    public BigDecimal getInitialConfidence() { return initialConfidence; }
    public void setInitialConfidence(BigDecimal initialConfidence) { this.initialConfidence = initialConfidence; }
    
    public String getActualOutcome() { return actualOutcome; }
    public void setActualOutcome(String actualOutcome) { this.actualOutcome = actualOutcome; }
    
    public BigDecimal getExpectedReturn() { return expectedReturn; }
    public void setExpectedReturn(BigDecimal expectedReturn) { this.expectedReturn = expectedReturn; }
    
    public BigDecimal getActualReturn() { return actualReturn; }
    public void setActualReturn(BigDecimal actualReturn) { this.actualReturn = actualReturn; }
    
    public BigDecimal getAiAccuracy() { return aiAccuracy; }
    public void setAiAccuracy(BigDecimal aiAccuracy) { this.aiAccuracy = aiAccuracy; }
    
    public BigDecimal getTechnicalAccuracy() { return technicalAccuracy; }
    public void setTechnicalAccuracy(BigDecimal technicalAccuracy) { this.technicalAccuracy = technicalAccuracy; }
    
    public BigDecimal getSentimentAccuracy() { return sentimentAccuracy; }
    public void setSentimentAccuracy(BigDecimal sentimentAccuracy) { this.sentimentAccuracy = sentimentAccuracy; }
    
    public String getMarketRegime() { return marketRegime; }
    public void setMarketRegime(String marketRegime) { this.marketRegime = marketRegime; }
    
    public BigDecimal getVolatilityAtEntry() { return volatilityAtEntry; }
    public void setVolatilityAtEntry(BigDecimal volatilityAtEntry) { this.volatilityAtEntry = volatilityAtEntry; }
    
    public BigDecimal getVolumeStrength() { return volumeStrength; }
    public void setVolumeStrength(BigDecimal volumeStrength) { this.volumeStrength = volumeStrength; }
    
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    
    public Integer getLeverageUsed() { return leverageUsed; }
    public void setLeverageUsed(Integer leverageUsed) { this.leverageUsed = leverageUsed; }
    
    public BigDecimal getPositionSize() { return positionSize; }
    public void setPositionSize(BigDecimal positionSize) { this.positionSize = positionSize; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public java.util.Map<String, Object> getContributingFactors() {
        java.util.Map<String, Object> factors = new java.util.HashMap<>();
        factors.put("aiAccuracy", this.aiAccuracy);
        factors.put("technicalAccuracy", this.technicalAccuracy);
        factors.put("sentimentAccuracy", this.sentimentAccuracy);
        factors.put("marketRegime", this.marketRegime);
        factors.put("volatilityAtEntry", this.volatilityAtEntry);
        factors.put("volumeStrength", this.volumeStrength);
        return factors;
    }
} 