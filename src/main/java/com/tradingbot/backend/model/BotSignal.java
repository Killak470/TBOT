package com.tradingbot.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;

@Entity
@Table(name = "bot_signals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BotSignal {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SignalType signalType;
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private SignalStatus status;
    
    @Column(nullable = false)
    private String strategyName;
    
    // Market type field to distinguish between spot and futures
    @Column(nullable = true)
    @Enumerated(EnumType.STRING)
    private ScanConfig.MarketType marketType = ScanConfig.MarketType.SPOT;
    
    // Exchange field
    @Column(nullable = true)
    private String exchange;
    
    // Account balance
    @Column(precision = 19, scale = 8)
    private BigDecimal accountBalance;
    
    // Max leverage
    @Column(precision = 5, scale = 2)
    private BigDecimal maxLeverage;
    
    // Leverage for futures positions
    @Column(precision = 5, scale = 2)
    private BigDecimal leverage;
    
    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal entryPrice;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal quantity;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal stopLoss;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal takeProfit;
    
    @Column(precision = 5, scale = 2)
    private BigDecimal confidence; // 0-100%
    
    @Column(columnDefinition = "TEXT")
    private String rationale;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal riskRewardRatio;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal potentialLoss;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal potentialProfit;
    
    // Adjusted position size considering leverage and drawdown limits
    @Column(precision = 19, scale = 8)
    private BigDecimal adjustedQuantity;
    
    // Volatility at signal entry for risk management
    @Column(precision = 19, scale = 8)
    private BigDecimal volatilityAtEntry;
    
    // Enhanced risk management fields
    @Column(precision = 19, scale = 8)
    private BigDecimal trailingStopPrice;
    
    @Column(nullable = true)
    private String side; // LONG or SHORT
    
    @Column(precision = 19, scale = 8)
    private BigDecimal positionSize;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal stopLossPrice;
    
    @ElementCollection
    @CollectionTable(name = "bot_signal_profit_levels", joinColumns = @JoinColumn(name = "signal_id"))
    @Column(name = "level_taken")
    private java.util.List<Integer> profitLevelsTaken = new ArrayList<>();
    
    @Column(nullable = false)
    private LocalDateTime generatedAt;
    
    private LocalDateTime processedAt;
    
    private LocalDateTime executedAt;
    
    private String processedBy;
    
    private String rejectionReason;
    
    private String orderId;
    
    private Long executedOrderId; // Link to Order entity if approved and executed
    
    private String orderType;
    private Long stopLossOrderId;
    private Long takeProfitOrderId;
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getSymbol() {
        return symbol;
    }
    
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    
    public SignalType getSignalType() {
        return signalType;
    }
    
    public void setSignalType(SignalType signalType) {
        this.signalType = signalType;
    }
    
    public String getSide() {
        return side;
    }
    
    public void setSide(String side) {
        this.side = side;
    }
    
    public BigDecimal getEntryPrice() {
        return entryPrice;
    }
    
    public void setEntryPrice(BigDecimal entryPrice) {
        this.entryPrice = entryPrice;
    }
    
    public BigDecimal getPositionSize() {
        return positionSize;
    }
    
    public void setPositionSize(BigDecimal positionSize) {
        this.positionSize = positionSize;
    }
    
    public BigDecimal getTrailingStopPrice() {
        return trailingStopPrice;
    }
    
    public void setTrailingStopPrice(BigDecimal trailingStopPrice) {
        this.trailingStopPrice = trailingStopPrice;
    }
    
    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }
    
    public void setStopLossPrice(BigDecimal stopLossPrice) {
        this.stopLossPrice = stopLossPrice;
    }
    
    public java.util.List<Integer> getProfitLevelsTaken() {
        return profitLevelsTaken;
    }
    
    public void setProfitLevelsTaken(java.util.List<Integer> profitLevelsTaken) {
        this.profitLevelsTaken = profitLevelsTaken;
    }
    
    public BigDecimal getConfidence() {
        return confidence;
    }
    
    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }
    
    public String getRationale() {
        return rationale;
    }
    
    public void setRationale(String rationale) {
        this.rationale = rationale;
    }
    
    public BigDecimal getRiskRewardRatio() {
        return riskRewardRatio;
    }
    
    public void setRiskRewardRatio(BigDecimal riskRewardRatio) {
        this.riskRewardRatio = riskRewardRatio;
    }
    
    public String getOrderType() {
        return orderType;
    }
    
    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }
    
    public Long getStopLossOrderId() {
        return stopLossOrderId;
    }
    
    public void setStopLossOrderId(long stopLossOrderId) {
        this.stopLossOrderId = stopLossOrderId;
    }
    
    public Long getTakeProfitOrderId() {
        return takeProfitOrderId;
    }
    
    public void setTakeProfitOrderId(long takeProfitOrderId) {
        this.takeProfitOrderId = takeProfitOrderId;
    }
    
    public String getExchange() {
        return exchange;
    }
    
    public void setExchange(String exchange) {
        this.exchange = exchange;
    }
    
    public BigDecimal getAccountBalance() {
        return accountBalance;
    }
    
    public void setAccountBalance(BigDecimal accountBalance) {
        this.accountBalance = accountBalance;
    }
    
    public BigDecimal getMaxLeverage() {
        return maxLeverage;
    }
    
    public void setMaxLeverage(BigDecimal maxLeverage) {
        this.maxLeverage = maxLeverage;
    }
    
    public LocalDateTime getExecutedAt() {
        return executedAt;
    }
    
    public void setExecutedAt(LocalDateTime executedAt) {
        this.executedAt = executedAt;
    }
    
    public String getOrderId() {
        return orderId;
    }
    
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
    
    public void setFailureReason(String reason) {
        this.rejectionReason = reason;
    }
    
    public ScanConfig.MarketType getMarketType() {
        return marketType;
    }

    public void setMarketType(String marketType) {
        if (marketType == null) {
            this.marketType = ScanConfig.MarketType.SPOT;
            return;
        }
        
        switch (marketType.toLowerCase()) {
            case "linear":
            case "futures":
                this.marketType = ScanConfig.MarketType.LINEAR;
                break;
            case "spot":
            default:
                this.marketType = ScanConfig.MarketType.SPOT;
                break;
        }
    }

    public void setMarketType(ScanConfig.MarketType marketType) {
        this.marketType = marketType;
    }
    
    public enum SignalType {
        BUY, SELL
    }
    
    public enum SignalStatus {
        PENDING,     // Waiting for approval
        APPROVED,    // Approved but not yet executed
        EXECUTED,    // Approved and executed
        REJECTED,    // Rejected by user
        EXPIRED,     // Signal expired without action
        FAILED,      // Failed to execute
        PENDING_USER_CONFIRMATION // Add this new line
    }
    
    public BotSignal(String symbol, SignalType signalType, String strategyName, 
                     BigDecimal entryPrice, BigDecimal quantity, BigDecimal confidence, 
                     String rationale) {
        this.symbol = symbol;
        this.signalType = signalType;
        this.strategyName = strategyName;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.confidence = confidence;
        this.rationale = rationale;
        this.status = SignalStatus.PENDING;
        this.generatedAt = LocalDateTime.now();
    }
} 