package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class MarketSentiment {
    private BigDecimal buyPressure;
    private BigDecimal sellPressure;
    private BigDecimal netFlow;
    private String overallSentiment; // "BULLISH", "BEARISH", or "NEUTRAL"
    private BigDecimal momentum;
    private BigDecimal volatility;
} 