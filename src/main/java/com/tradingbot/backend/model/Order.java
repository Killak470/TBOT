package com.tradingbot.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Represents an order in the trading system
 */
@Entity
@Table(name = "orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;
    
    private String clientOrderId;
    private String symbol;
    private String side;  // BUY, SELL
    private String type;  // LIMIT, MARKET, STOP_LOSS, etc.
    private String status; // NEW, FILLED, PARTIALLY_FILLED, CANCELED, REJECTED
    private String marketType; // spot, linear, inverse
    private String exchange; // MEXC, BYBIT
    
    @Column(precision = 19, scale = 8)
    private BigDecimal price;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal origQty;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal executedQty;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal cummulativeQuoteQty;
    
    private String timeInForce; // GTC, IOC, FOK
    
    @Column(columnDefinition = "TIMESTAMP")
    private Instant time;
    
    @Column(columnDefinition = "TIMESTAMP")
    private Instant updateTime;
    
    private Boolean isWorking;
    
    // Fields to track internal order management
    private String botId;          // ID of the bot that placed this order
    private String strategyName;   // Name of the strategy that generated this order
    
    @Column(precision = 19, scale = 8)
    private BigDecimal leverage; // Leverage for the order
    
    @Column(precision = 19, scale = 8)
    private BigDecimal stopPrice;  // For stop loss orders
    
    // For historical tracking purposes
    @Column(precision = 19, scale = 8)
    private BigDecimal priceAtCreation;
    
    @Column(precision = 19, scale = 8)
    private BigDecimal marketPriceAtExecution;
    
    private Double profitLoss;     // PnL if this is a closing order
    
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public Long getOrderId() {
        return orderId;
    }
    
    public void setOrderId(Long orderId) {
        this.orderId = orderId;
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
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public BigDecimal getPrice() {
        return price;
    }
    
    public void setPrice(BigDecimal price) {
        this.price = price;
    }
    
    public BigDecimal getExecutedQty() {
        return executedQty;
    }
    
    public void setExecutedQty(BigDecimal executedQty) {
        this.executedQty = executedQty;
    }
    
    public BigDecimal getCummulativeQuoteQty() {
        return cummulativeQuoteQty;
    }
    
    public void setCummulativeQuoteQty(BigDecimal cummulativeQuoteQty) {
        this.cummulativeQuoteQty = cummulativeQuoteQty;
    }
    
    public String getBotId() {
        return botId;
    }
    
    public void setBotId(String botId) {
        this.botId = botId;
    }
    
    public String getStrategyName() {
        return strategyName;
    }
    
    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
    }
    
    public BigDecimal getPriceAtCreation() {
        return priceAtCreation;
    }
    
    public void setPriceAtCreation(BigDecimal priceAtCreation) {
        this.priceAtCreation = priceAtCreation;
    }
    
    public boolean isStopLossSet() {
        return stopLoss != null;
    }
    
    public BigDecimal getStopLoss() {
        return stopLoss;
    }
    
    public void setStopLoss(BigDecimal stopLoss) {
        this.stopLoss = stopLoss;
    }
    
    public BigDecimal getTakeProfit() {
        return takeProfit;
    }
    
    public void setTakeProfit(BigDecimal takeProfit) {
        this.takeProfit = takeProfit;
    }
    
    public BigDecimal getLeverage() {
        return leverage;
    }

    public void setLeverage(BigDecimal leverage) {
        this.leverage = leverage;
    }
    
    // Convenience methods to support backward compatibility with older code
    public void setStrategy(String strategy) {
        this.strategyName = strategy;
    }
    
    public void setOrderType(String orderType) {
        this.type = orderType;
    }
    
    public void setQuantity(double quantity) {
        this.origQty = BigDecimal.valueOf(quantity);
    }
    
    public void setQuantity(BigDecimal quantity) {
        this.origQty = quantity;
    }
    
    public void setTimestamp(long timestamp) {
        this.time = Instant.ofEpochMilli(timestamp);
    }
    
    public String getMarketType() {
        return marketType;
    }
    
    public void setMarketType(String marketType) {
        this.marketType = marketType;
    }
    
    public String getExchange() {
        return exchange;
    }
    
    public void setExchange(String exchange) {
        this.exchange = exchange;
    }
} 