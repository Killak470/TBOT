package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class PriceLevel {
    private BigDecimal price;
    private BigDecimal strength;
    private String type; // "SUPPORT" or "RESISTANCE"
    private BigDecimal confidence;
} 