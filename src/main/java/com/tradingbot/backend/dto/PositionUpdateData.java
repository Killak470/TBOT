package com.tradingbot.backend.dto;

import java.math.BigDecimal;

// Using Lombok for boilerplate code reduction (getters, setters, constructor)
// Ensure Lombok is configured in your IDE and project (pom.xml has it as provided scope)
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionUpdateData {
    private String symbol;
    private String side; // "Buy" (Long), "Sell" (Short), or "None"
    private BigDecimal size;
    private BigDecimal entryPrice; // Average entry price
    private BigDecimal markPrice;
    private BigDecimal positionValue;
    private BigDecimal unrealisedPnl;
    private BigDecimal realisedPnl; // Cumulative realised PnL for this position session
    private String stopLoss; // Stop loss price as string from Bybit
    private String takeProfit; // Take profit price as string from Bybit
    private String positionStatus; // e.g., "Normal", "Liq", "Adl"
    private long updatedTimestamp; // Timestamp of the data from Bybit
    
    // Fields strategy needs to track, potentially derived or enriched
    private BigDecimal initialQuantity; // For tracking partial closes
    private BigDecimal highestPriceSinceEntry; // For ATR trailing stop for LONGs
    private BigDecimal lowestPriceSinceEntry;  // For ATR trailing stop for SHORTs
    private BigDecimal strategyStopLossPrice; // The strategy's calculated/active stop-loss
    private boolean firstProfitTargetTaken;
} 