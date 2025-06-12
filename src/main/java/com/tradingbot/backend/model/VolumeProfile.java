package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class VolumeProfile {
    private List<LiquidityZone> highLiquidityZones;
    private BigDecimal volumeWeightedAvgPrice;
    private BigDecimal totalBuyVolume;
    private BigDecimal totalSellVolume;
    private BigDecimal volumeImbalance;
    
    @Data
    @Builder
    public static class LiquidityZone {
        private BigDecimal priceLevel;
        private BigDecimal volume;
        private String type; // "BUY" or "SELL"
        private BigDecimal strength; // Normalized strength of the zone (0-1)
    }
} 