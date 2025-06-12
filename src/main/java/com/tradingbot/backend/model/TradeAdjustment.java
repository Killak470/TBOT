package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class TradeAdjustment {
    private String type; // "POSITION_SIZE", "STOP_LOSS", "TAKE_PROFIT"
    private String action; // "INCREASE", "DECREASE", "MOVE"
    private BigDecimal currentValue;
    private BigDecimal suggestedValue;
    private BigDecimal adjustmentPercentage;
    private String reason;
    private BigDecimal confidence; // 0-1 scale
    private RiskImpact riskImpact;

    @Data
    @Builder
    public static class RiskImpact {
        private BigDecimal newRiskRewardRatio;
        private BigDecimal maxLossChange;
        private BigDecimal probabilityOfSuccess;
    }
} 