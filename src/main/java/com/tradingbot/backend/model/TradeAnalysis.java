package com.tradingbot.backend.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class TradeAnalysis {
    private VolumeProfile volumeProfile;
    private List<PriceLevel> supportLevels;
    private List<PriceLevel> resistanceLevels;
    private MarketSentiment marketSentiment;
    private List<TradeAdjustment> suggestedAdjustments;
} 