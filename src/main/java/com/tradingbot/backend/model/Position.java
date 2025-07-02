package com.tradingbot.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a trading position (open or closed)
 */
@Entity
@Table(name = "positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public class Position {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String positionId; // Exchange-specific position ID
    private String symbol;
    private String side;                 // LONG or SHORT
    private String marketType;           // SPOT or FUTURES
    private String type;                 // MARKET or LIMIT
    private String exchange;             // MEXC or BYBIT
    private String status;               // OPEN, CLOSED, CANCELLED
    private String botId;                // ID of the bot that created this position
    private String strategyName;         // Name of the strategy that created this position
    
    private Integer positionIdx;          // Position index from Bybit (0 for one-way, 1 for buy hedge, 2 for sell hedge)
    
    private String exitReason;           // Reason for closing the position
    
    private BigDecimal quantity;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private BigDecimal takeProfitPrice;  // Take profit price level
    private BigDecimal stopLossPrice;    // Stop loss price level
    private BigDecimal unrealizedPnl;
    private BigDecimal realizedPnl;
    private BigDecimal pnlPercentage;
    private BigDecimal markPrice; // ADDED: To store the mark price from the exchange
    private BigDecimal positionValue;    // ADDED: To store the current position value
    private BigDecimal fees;
    private BigDecimal grossProfit;
    private BigDecimal costBasis;
    private BigDecimal riskRewardRatio;
    private BigDecimal margin;           // Margin allocated for the position
    private BigDecimal drawdownPercentage; // ADDED: To store drawdown percentage separately
    
    private LocalDateTime openTime;
    private LocalDateTime closeTime;
    private LocalDateTime updatedAt;
    
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "position_entry_order_ids", joinColumns = @JoinColumn(name = "position_id"))
    @Column(name = "entry_order_id")
    @Builder.Default
    private List<String> entryOrderIds = new ArrayList<>();
    
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "position_exit_order_ids", joinColumns = @JoinColumn(name = "position_id"))
    @Column(name = "exit_order_id")
    @Builder.Default
    private List<String> exitOrderIds = new ArrayList<>();
    
    @Column(precision = 19, scale = 8)
    private BigDecimal currentPrice;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal liquidationPrice; // RESTORED: This is the correct one with DB annotation
    
    @Column(precision = 19, scale = 8)
    private BigDecimal leverage;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal volatility;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal initialQuantity;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal netProfit;
    
    @Column(precision = 5, scale = 2)
    private BigDecimal riskPercentage;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal highestPrice;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal lowestPrice;
    
    private Integer closureCount;
    
    @Column(columnDefinition = "TEXT")
    private String notes;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal sharpeRatio;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal maxDrawdown;
    
    private Instant updateTime;
    
    // Signal tracking fields for restart persistence
    private Long originalSignalId;       // Links to BotSignal that created this position
    private String orderLinkId;          // Precise tracking using signal's orderLinkId
    private String signalSource;         // Track signal origin (MARKET_SCAN, AI_ANALYSIS, etc.)
    private Boolean isScalpTrade;        // Differentiate scalp vs regular trades
    private Boolean sltpApplied;         // Track if SL/TP has been applied
    private Boolean trailingStopInitialized;  // Track trailing stop status
    private LocalDateTime lastSltpCheck; // Timestamp of last SL/TP check
    
    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
        updateTime = Instant.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateTime = Instant.now();
    }
    
    public void setUpdateTime(Instant updateTime) {
        this.updateTime = updateTime;
    }
    
    public Instant getUpdateTime() {
        return updateTime;
    }
    
    public boolean isOpen() {
        return "OPEN".equals(status);
    }
    
    public boolean isPartiallyClosed() {
        return "PARTIALLY_CLOSED".equals(status);
    }
    
    public boolean isClosed() {
        return "CLOSED".equals(status) || "LIQUIDATED".equals(status);
    }
    
    public BigDecimal getRemainingQuantity() {
        if (initialQuantity == null || quantity == null) {
            return quantity;
        }
        return quantity;
    }
    
    public BigDecimal getClosedQuantity() {
        if (initialQuantity == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        return initialQuantity.subtract(quantity);
    }
    
    public BigDecimal getPercentageClosed() {
        if (initialQuantity == null || initialQuantity.compareTo(BigDecimal.ZERO) == 0 || quantity == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.ONE.subtract(quantity.divide(initialQuantity, 8, BigDecimal.ROUND_HALF_UP))
            .multiply(BigDecimal.valueOf(100));
    }
    
    public BigDecimal getTotalValue() {
        if (quantity == null || currentPrice == null) {
            return BigDecimal.ZERO;
        }
        return quantity.multiply(currentPrice);
    }
    
    public BigDecimal getSize() {
        return quantity;
    }
    
    public BigDecimal getBreakEvenPrice() {
        if (side == null || entryPrice == null) {
            return entryPrice;
        }
        
        if ("LONG".equals(side) && fees != null) {
            if (initialQuantity != null && initialQuantity.compareTo(BigDecimal.ZERO) > 0) {
                return entryPrice.add(fees.divide(initialQuantity, 8, BigDecimal.ROUND_HALF_UP));
            }
        } else if ("SHORT".equals(side) && fees != null) {
            if (initialQuantity != null && initialQuantity.compareTo(BigDecimal.ZERO) > 0) {
                return entryPrice.subtract(fees.divide(initialQuantity, 8, BigDecimal.ROUND_HALF_UP));
            }
        }
        
        return entryPrice;
    }
    
    // Added getters for missing fields
    public BigDecimal getStopLoss() {
        return stopLossPrice;
    }
    
    public BigDecimal getTakeProfit() {
        return takeProfitPrice;
    }
    
    public BigDecimal getMargin() {
        return margin;
    }
    
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
    
    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }
    
    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }
    
    public BigDecimal getHighestPrice() {
        return highestPrice;
    }
    
    public void setHighestPrice(BigDecimal highestPrice) {
        this.highestPrice = highestPrice;
    }
    
    public BigDecimal getLowestPrice() {
        return lowestPrice;
    }
    
    public void setLowestPrice(BigDecimal lowestPrice) {
        this.lowestPrice = lowestPrice;
    }
    
    public BigDecimal getVolatility() {
        return volatility;
    }
    
    public void setVolatility(BigDecimal volatility) {
        this.volatility = volatility;
    }
    
    public BigDecimal getSharpeRatio() {
        return sharpeRatio;
    }
    
    public void setSharpeRatio(BigDecimal sharpeRatio) {
        this.sharpeRatio = sharpeRatio;
    }
    
    public BigDecimal getMaxDrawdown() {
        return maxDrawdown;
    }
    
    public void setMaxDrawdown(BigDecimal maxDrawdown) {
        this.maxDrawdown = maxDrawdown;
    }
    
    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }
    
    public void setStopLossPrice(BigDecimal stopLossPrice) {
        this.stopLossPrice = stopLossPrice;
    }
    
    public String getPositionId() {
        return positionId;
    }

    public void setPositionId(String positionId) {
        this.positionId = positionId;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }
} 