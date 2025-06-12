package com.tradingbot.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for order placement
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderRequest {
    // Required fields
    private String symbol;
    private String side;  // BUY, SELL
    private String type;  // LIMIT, MARKET, STOP_LOSS, etc.
    private ScanConfig.MarketType marketType = ScanConfig.MarketType.SPOT;
    
    // Optional fields with defaults
    private String timeInForce = "GTC"; // GTC, IOC, FOK
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal stopPrice;       // For stop orders
    private Integer leverage;           // Added for futures trading
    private String clientOrderId;       // Optional client-side order ID
    private String exchange;            // MEXC, BYBIT
    private String botId;               // ID of the bot placing the order
    private String strategyName;        // Name of the strategy placing the order
    
    // Fields for SL/TP to be passed to Bybit createOrder
    private BigDecimal stopLossPrice;   // Stop loss price for the order
    private BigDecimal takeProfitPrice; // Take profit price for the order
    
    // Constructor for required fields
    public OrderRequest(String symbol, String side, String type, BigDecimal quantity, BigDecimal price) {
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.price = price;
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
    
    public BigDecimal getQuantity() {
        return quantity;
    }
    
    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
    
    public BigDecimal getPrice() {
        return price;
    }
    
    public void setPrice(BigDecimal price) {
        this.price = price;
    }
    
    public String getTimeInForce() {
        return timeInForce;
    }
    
    public void setTimeInForce(String timeInForce) {
        this.timeInForce = timeInForce;
    }
    
    public String getClientOrderId() {
        return clientOrderId;
    }
    
    public void setClientOrderId(String clientOrderId) {
        this.clientOrderId = clientOrderId;
    }
    
    public String getExchange() {
        return exchange;
    }
    
    public void setExchange(String exchange) {
        this.exchange = exchange;
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
    
    public BigDecimal getStopPrice() {
        return stopPrice;
    }
    
    public void setStopPrice(BigDecimal stopPrice) {
        this.stopPrice = stopPrice;
    }
    
    public Integer getLeverage() {
        return leverage;
    }
    
    public void setLeverage(Integer leverage) {
        this.leverage = leverage;
    }
    
    public String getNewClientOrderId() {
        if (clientOrderId == null || clientOrderId.isEmpty()) {
            clientOrderId = UUID.randomUUID().toString();
        }
        return clientOrderId;
    }

    // Getters and Setters for stopLossPrice and takeProfitPrice
    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }

    public void setStopLossPrice(BigDecimal stopLossPrice) {
        this.stopLossPrice = stopLossPrice;
    }

    public BigDecimal getTakeProfitPrice() {
        return takeProfitPrice;
    }

    public void setTakeProfitPrice(BigDecimal takeProfitPrice) {
        this.takeProfitPrice = takeProfitPrice;
    }
} 