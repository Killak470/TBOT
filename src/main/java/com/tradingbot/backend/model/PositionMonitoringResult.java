package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PositionMonitoringResult {
    private Position position;
    private String positionId;
    private String symbol;
    private String status;
    private BigDecimal currentPrice;
    private BigDecimal profitLoss;
    private BigDecimal profitLossPercentage;
    private RiskMetrics riskMetrics;
    private TradeAnalysis analysis;
    private LocalDateTime timestamp;
    private String message;
    private List<AdjustmentRecommendation> adjustmentRecommendations;
} 