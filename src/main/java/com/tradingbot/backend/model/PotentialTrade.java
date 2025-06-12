package com.tradingbot.backend.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PotentialTrade {
    private String symbol;
    private String side; // "BUY" or "SELL"
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    private String rationale;
    private String exchange;
    private String marketType; // "SPOT" or "LINEAR"
    private String interval;
    private String title;
    private double confidence;
} 