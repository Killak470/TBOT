package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class RiskMetrics {
    private BigDecimal currentRisk;
    private BigDecimal riskRewardRatio;
    private BigDecimal maxDrawdown;
    private BigDecimal valueAtRisk;
    private BigDecimal marginUtilization;
    private BigDecimal liquidationRisk;
    private BigDecimal positionSizeRisk;
    private BigDecimal exposureRatio;
    private BigDecimal correlationRisk;
} 