package com.tradingbot.backend.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.tradingbot.backend.service.MarketDataService;
import com.tradingbot.backend.service.RiskManagementService;
import com.tradingbot.backend.service.SignalWeightingService;
import com.tradingbot.backend.service.AIAnalysisService;
import com.tradingbot.backend.service.EventDrivenAiAnalysisService;
import com.tradingbot.backend.service.util.TechnicalAnalysisUtil;
import com.tradingbot.backend.service.util.TechnicalAnalysisUtil.SupportResistanceLevel;
import com.tradingbot.backend.service.cache.PositionCacheService;
import com.tradingbot.backend.service.cache.PositionCacheService.PositionUpdateData;
import com.tradingbot.backend.service.OrderManagementService;
import com.tradingbot.backend.service.BybitFuturesApiService;
import com.tradingbot.backend.service.MexcFuturesApiService;
import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.model.Order;
import com.tradingbot.backend.model.Candlestick;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.service.MultiTimeframeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the "Sniper Entry Strategy" as defined in NewTS.md.
 * - Multi-coin continuous scanning (external scheduler will drive this strategy for multiple coins)
 * - Entry criteria: S/R + Fib (61.8, 78.6) + Volume Spike (2x avg) + MA Confluence (multi-TF) + RSI (in trend)
 * - Ultra-tight stop losses (0.5-1%)
 * - R/R: min 3:1, target 5:1
 * - High leverage (20x-100x based on signal tier)
 * - Profit taking: 50% at 2:1 R/R, rest to 3-5:1
 * - Trailing stops: ATR-based
 */
@Service
public class SniperEntryStrategy implements TradingStrategy {

    private static final Logger logger = LoggerFactory.getLogger(SniperEntryStrategy.class);

    // Signal Tier Constants
    public static final String NO_SIGNAL = "NO_SIGNAL";
    public static final String TIER_1_BREAKOUT_BUY = "TIER_1_BREAKOUT_BUY";
    public static final String TIER_1_BREAKOUT_SELL = "TIER_1_BREAKOUT_SELL";
    public static final String TIER_2_BREAKOUT_BUY = "TIER_2_BREAKOUT_BUY";
    public static final String TIER_2_BREAKOUT_SELL = "TIER_2_BREAKOUT_SELL";
    public static final String TIER_1_REJECTION_BUY = "TIER_1_REJECTION_BUY";
    public static final String TIER_1_REJECTION_SELL = "TIER_1_REJECTION_SELL";
    public static final String TIER_2_REJECTION_BUY = "TIER_2_REJECTION_BUY";
    public static final String TIER_2_REJECTION_SELL = "TIER_2_REJECTION_SELL";
    public static final String TIER_1_CONFLUENCE_BUY = "TIER_1_CONFLUENCE_BUY";
    public static final String TIER_1_CONFLUENCE_SELL = "TIER_1_CONFLUENCE_SELL";
    public static final String TIER_2_CONFLUENCE_BUY = "TIER_2_CONFLUENCE_BUY";
    public static final String TIER_2_CONFLUENCE_SELL = "TIER_2_CONFLUENCE_SELL";
    public static final String TIER_3_CONFLUENCE_BUY = "TIER_3_CONFLUENCE_BUY";
    public static final String TIER_3_CONFLUENCE_SELL = "TIER_3_CONFLUENCE_SELL";
    
    private final Map<String, String> currentSignalTiers = new ConcurrentHashMap<>();

    private final MarketDataService marketDataService;
    private final RiskManagementService riskManagementService;
    private final PositionCacheService positionCacheService;
    private final ObjectMapper objectMapper;
    private final OrderManagementService orderManagementService;
    private final BybitFuturesApiService bybitFuturesApiService;
    private final MexcFuturesApiService mexcFuturesApiService;
    private final SignalWeightingService signalWeightingService;
    private final AIAnalysisService aiAnalysisService;
    private final EventDrivenAiAnalysisService eventDrivenAiAnalysisService;
    private final MultiTimeframeService multiTimeframeService;

    private Map<String, Object> configuration;

    // Default configuration parameters - these will be aligned with NewTS.md
    private static final int DEFAULT_RSI_PERIOD = 14;
    private static final double DEFAULT_FIB_LEVEL_618 = 0.618;
    private static final double DEFAULT_FIB_LEVEL_786 = 0.786;
    private static final double DEFAULT_VOLUME_SPIKE_MULTIPLIER = 2.0;
    private static final double DEFAULT_STOP_LOSS_PERCENT_MIN = 0.005; // 0.5%
    private static final double DEFAULT_STOP_LOSS_PERCENT_MAX = 0.01;  // 1.0%
    private static final double DEFAULT_RR_MIN = 3.0; // Minimum 3:1 R/R
    private static final double DEFAULT_RR_TARGET = 5.0; // Target 5:1 R/R
    // ATR settings for trailing stop
    private static final int DEFAULT_ATR_PERIOD = 14;
    private static final double DEFAULT_ATR_MULTIPLIER = 1.5;

    private static final int MAX_ORDER_STATUS_CHECK_ATTEMPTS = 5;
    private static final long ORDER_STATUS_CHECK_DELAY_MS = 1000; // 1 second between checks

    // Leverage and Risk Tiers (from NewTS.md)
    // Tier 1: 1-2% risk, 20-30x leverage
    // Tier 2: 0.5-1% risk, 30-50x leverage
    // Tier 3: 0.3-0.5% risk, 50-100x leverage
    // These will influence calculatePositionSize and potentially order placement calls.

    // Tiering thresholds for weighted score (0.0 to 5.0, multiplied by tech weight later)
    private static final double TIER_1_THRESHOLD = 4.5; // e.g. 4.5+ raw score for Tier 1
    private static final double TIER_2_THRESHOLD = 3.5; // e.g. 3.5+ raw score for Tier 2
    private static final double TIER_3_THRESHOLD = 2.5; // e.g. 2.5+ raw score for Tier 3

    @Autowired
    public SniperEntryStrategy(MarketDataService marketDataService,
                             RiskManagementService riskManagementService,
                             PositionCacheService positionCacheService,
                             OrderManagementService orderManagementService,
                             BybitFuturesApiService bybitFuturesApiService,
                             MexcFuturesApiService mexcFuturesApiService,
                             ObjectMapper objectMapper,
                             SignalWeightingService signalWeightingService,
                             AIAnalysisService aiAnalysisService,
                             EventDrivenAiAnalysisService eventDrivenAiAnalysisService,
                             MultiTimeframeService multiTimeframeService) {
        this.marketDataService = marketDataService;
        this.riskManagementService = riskManagementService;
        this.positionCacheService = positionCacheService;
        this.objectMapper = objectMapper;
        this.orderManagementService = orderManagementService;
        this.bybitFuturesApiService = bybitFuturesApiService;
        this.mexcFuturesApiService = mexcFuturesApiService;
        this.signalWeightingService = signalWeightingService;
        this.aiAnalysisService = aiAnalysisService;
        this.eventDrivenAiAnalysisService = eventDrivenAiAnalysisService;
        this.multiTimeframeService = multiTimeframeService;

        this.configuration = new HashMap<>();
        // Initialize default configuration values
        loadDefaultConfiguration();
    }

    private void loadDefaultConfiguration() {
        configuration.put("coinsToMonitor", List.of("TRUMPUSDT", "NXPCUSDT", "SUIUSDT", "WALUSDT"));
        configuration.put("scanningFrequencyHighVolMin", 1);
        configuration.put("scanningFrequencyLowVolMin", 5);
        configuration.put("fibRetracementLevels", List.of(DEFAULT_FIB_LEVEL_618, DEFAULT_FIB_LEVEL_786));
        configuration.put("volumeSpikeMultiplier", DEFAULT_VOLUME_SPIKE_MULTIPLIER);
        configuration.put("rsiPeriod", DEFAULT_RSI_PERIOD);
        configuration.put("rsiOversold", 30.0);
        configuration.put("rsiOverbought", 70.0);
        configuration.put("stopLossPercentMin", DEFAULT_STOP_LOSS_PERCENT_MIN);
        configuration.put("stopLossPercentMax", DEFAULT_STOP_LOSS_PERCENT_MAX);
        configuration.put("atrPeriod", DEFAULT_ATR_PERIOD);
        configuration.put("atrMultiplier", DEFAULT_ATR_MULTIPLIER);
        configuration.put("tier1RiskPercent", 0.015);
        configuration.put("tier1Leverage", 25);
        configuration.put("tier2RiskPercent", 0.0075);
        configuration.put("tier2Leverage", 40);
        configuration.put("tier3RiskPercent", 0.004);
        configuration.put("tier3Leverage", 75);
        configuration.put("fibTolerance", 0.005);
        configuration.put("firstProfitTargetRR", 2.0);
        configuration.put("secondProfitTargetRRMin", DEFAULT_RR_MIN);
        configuration.put("secondProfitTargetRRMax", DEFAULT_RR_TARGET);
        configuration.put("srTolerance", 0.005);
        configuration.put("volumeLookbackPeriod", 20);
        configuration.put("longTermMaPeriod", 200); // Added for trend confirmation
        configuration.put("rejectionWickToBodyRatio", 1.5); // New
        configuration.put("rejectionMinCandleRangePercent", 0.003); // New

        Map<String, Integer> maPeriods1h = new HashMap<>();
        maPeriods1h.put("short", 9);
        maPeriods1h.put("medium", 21);
        maPeriods1h.put("long", 50);
        configuration.put("maPeriods_1h", maPeriods1h);

        Map<String, Integer> maPeriods15m = new HashMap<>();
        maPeriods15m.put("short", 9);
        maPeriods15m.put("medium", 21);
        maPeriods15m.put("long", 50);
        configuration.put("maPeriods_15m", maPeriods15m);

        Map<String, Integer> maPeriods4h = new HashMap<>();
        maPeriods4h.put("short", 21);
        maPeriods4h.put("medium", 50);
        maPeriods4h.put("long", 100);
        configuration.put("maPeriods_4h", maPeriods4h);

        Map<String, Integer> maPeriods1d = new HashMap<>();
        maPeriods1d.put("short", 20);
        maPeriods1d.put("medium", 50);
        maPeriods1d.put("long", 100);
        configuration.put("maPeriods_1d", maPeriods1d);
    }

    @Override
    public String evaluateEntry(String symbol, String interval) {
        logger.warn("SNIPER: evaluateEntry(symbol, interval) called. Defaulting to BUY side. Update calling code if applicable.");
        return evaluateEntry(symbol, interval, "BUY");
    }

    public String evaluateEntry(String symbol, String interval, String side) {
        logger.info("SNIPER: Evaluating {} ENTRY for {} on interval {}", side.toUpperCase(), symbol, interval);
        currentSignalTiers.put(symbol, NO_SIGNAL); // Initialize with NO_SIGNAL

        int longTermMaPeriod = (int) configuration.getOrDefault("longTermMaPeriod", 200);
        // Ensure primaryLookback is sufficient for S/R levels AND the longest MA period.
        // S/R might need ~50-100 candles. Long-term MA needs longTermMaPeriod candles.
        // Max of these, plus a small buffer.
        int srLookback = 100; // Example lookback for S/R
        int primaryLookback = Math.max(longTermMaPeriod + 10, srLookback + 10); 

        List<Candlestick> primaryKlines = fetchCandlestickData(symbol, interval, primaryLookback, "BYBIT");

        if (primaryKlines.isEmpty() || primaryKlines.size() < primaryLookback - 5) { // Adjusted condition slightly
            logger.warn("SNIPER: Not enough primary kline data for {} on interval {}. Fetched: {}, Needed approx: {}",
                symbol, interval, primaryKlines.size(), primaryLookback);
            return NO_SIGNAL;
        }

        Candlestick latestCandle = primaryKlines.get(primaryKlines.size() - 1);
        double currentPrice = latestCandle.getClose();
        logger.info("SNIPER: Current price for {} from latest {} candle: {}", symbol, interval, currentPrice);

        // --- S/R Level Identification & Breakout Check ---
        List<SupportResistanceLevel> srLevels = TechnicalAnalysisUtil.findSupportResistanceLevels(primaryKlines, 50, 0.01); // Using lookback of 50 candles for S/R detection within primaryKlines
        
        boolean srBreakoutConditionMet = false;
        String breakoutType = ""; // To store e.g., "RESISTANCE_BROKEN_ABOVE" or "SUPPORT_BROKEN_BELOW"

        boolean srRejectionOccurred = false;
        String rejectionType = ""; // e.g., "SUPPORT_REJECTION_FOR_BUY", "RESISTANCE_REJECTION_FOR_SELL"
        double rejectionLevelPrice = 0;

        if (srLevels.isEmpty()) {
            logger.info("SNIPER: No significant S/R levels found for {} on interval {}. Skipping S/R breakout/rejection check.", symbol, interval);
        } else {
            logger.info("SNIPER: Identified S/R levels for {}: {}", symbol, srLevels.stream().map(sr -> sr.getType() + "@" + sr.getLevel()).collect(Collectors.toList()));
            // Iterating through S/R levels for breakouts and rejections
            for (SupportResistanceLevel level : srLevels) {
                double levelPrice = level.getLevel();
                double candleBodySize = Math.abs(latestCandle.getClose() - latestCandle.getOpen());
                double upperWick = latestCandle.getHigh() - Math.max(latestCandle.getOpen(), latestCandle.getClose());
                double lowerWick = Math.min(latestCandle.getOpen(), latestCandle.getClose()) - latestCandle.getLow();
                double range = latestCandle.getHigh() - latestCandle.getLow();
                
                double wickToBodyRatioThreshold = (double)configuration.getOrDefault("rejectionWickToBodyRatio", 1.5);
                double minCandleRangeForRejection = (double)configuration.getOrDefault("rejectionMinCandleRangePercent", 0.003) * currentPrice;

                // Breakout Check (existing logic, slight modification to store type)
                if ("Resistance".equals(level.getType())) {
                    if ("BUY".equalsIgnoreCase(side) && currentPrice > levelPrice && latestCandle.getClose() > levelPrice) { 
                        if (primaryKlines.size() > 1) {
                            Candlestick prevCandle = primaryKlines.get(primaryKlines.size() - 2);
                            if (prevCandle.getClose() <= levelPrice) {
                                logger.info("SNIPER: {} broke ABOVE Resistance level {} at {}. Current Price: {}. Triggering AI.", 
                                            symbol, level.getType(), levelPrice, currentPrice);
                                eventDrivenAiAnalysisService.triggerAnalysisOnLevelBreakout(symbol, interval, "Resistance", "broken_above", levelPrice);
                                srBreakoutConditionMet = true; 
                                breakoutType = "RESISTANCE_BROKEN_ABOVE";
                                // break; // Consider if one breakout is enough
                            }
                        }
                    }
                    // Rejection at Resistance (for SELL signal)
                    else if ("SELL".equalsIgnoreCase(side) && latestCandle.getHigh() > levelPrice && latestCandle.getClose() < levelPrice) {
                        // Strong upper wick, close below resistance
                        if (range > minCandleRangeForRejection && upperWick > candleBodySize * wickToBodyRatioThreshold && upperWick > lowerWick * 0.8) { // ensure upper wick is dominant
                            logger.info("SNIPER (SELL): {} REJECTED at Resistance {} (Price: {}, High: {}, Close: {}). Upper wick significant.", 
                                symbol, levelPrice, currentPrice, latestCandle.getHigh(), latestCandle.getClose());
                            srRejectionOccurred = true;
                            rejectionType = "RESISTANCE_REJECTION_FOR_SELL";
                            rejectionLevelPrice = levelPrice;
                            eventDrivenAiAnalysisService.triggerAnalysisOnLevelRejection(symbol, interval, "Resistance", "rejection_at_resistance_for_sell", levelPrice, latestCandle.getHigh());
                            // break;
                        }
                    }
                } else if ("Support".equals(level.getType())) {
                    if ("SELL".equalsIgnoreCase(side) && currentPrice < levelPrice && latestCandle.getClose() < levelPrice) { 
                        if (primaryKlines.size() > 1) {
                            Candlestick prevCandle = primaryKlines.get(primaryKlines.size() - 2);
                            if (prevCandle.getClose() >= levelPrice) {
                                logger.info("SNIPER: {} broke BELOW Support level {} at {}. Current Price: {}. Triggering AI.", 
                                            symbol, level.getType(), levelPrice, currentPrice);
                                eventDrivenAiAnalysisService.triggerAnalysisOnLevelBreakout(symbol, interval, "Support", "broken_below", levelPrice);
                                srBreakoutConditionMet = true; 
                                breakoutType = "SUPPORT_BROKEN_BELOW";
                                // break; 
                            }
                        }
                    }
                     // Rejection at Support (for BUY signal)
                    else if ("BUY".equalsIgnoreCase(side) && latestCandle.getLow() < levelPrice && latestCandle.getClose() > levelPrice) {
                         // Strong lower wick, close above support
                        if (range > minCandleRangeForRejection && lowerWick > candleBodySize * wickToBodyRatioThreshold && lowerWick > upperWick * 0.8) { // ensure lower wick is dominant
                            logger.info("SNIPER (BUY): {} REJECTED at Support {} (Price: {}, Low: {}, Close: {}). Lower wick significant.", 
                                symbol, levelPrice, currentPrice, latestCandle.getLow(), latestCandle.getClose());
                            srRejectionOccurred = true;
                            rejectionType = "SUPPORT_REJECTION_FOR_BUY";
                            rejectionLevelPrice = levelPrice;
                            eventDrivenAiAnalysisService.triggerAnalysisOnLevelRejection(symbol, interval, "Support", "rejection_at_support_for_buy", levelPrice, latestCandle.getLow());
                            // break;
                        }
                    }
                }
            }
             if (!srBreakoutConditionMet) {
                logger.info("SNIPER: S/R *breakout* condition not met for {} based on current logic.", symbol);
            }
             if (!srRejectionOccurred) {
                logger.info("SNIPER: S/R *rejection* condition not met for {} based on current candle patterns at S/R levels.", symbol);
            }
        }

        // --- General Trend Confirmation ---
        boolean generalTrendConditionMet = false;
        if (primaryKlines.size() >= longTermMaPeriod) {
            List<Double> pricesForTrendMa = primaryKlines.stream().map(Candlestick::getClose).collect(Collectors.toList());
            List<Double> relevantPricesForTrendMa = pricesForTrendMa.subList(Math.max(0, pricesForTrendMa.size() - longTermMaPeriod), pricesForTrendMa.size());
            double longTermMA = TechnicalAnalysisUtil.calculateSMA(relevantPricesForTrendMa, longTermMaPeriod);
            if ("BUY".equalsIgnoreCase(side) && currentPrice > longTermMA) {
                logger.info("SNIPER: {} Confirmed UPTREND (Price {} > {} {}-SMA {})", symbol, currentPrice, interval, longTermMaPeriod, longTermMA);
                generalTrendConditionMet = true;
            } else if ("SELL".equalsIgnoreCase(side) && currentPrice < longTermMA) {
                logger.info("SNIPER: {} Confirmed DOWNTREND (Price {} < {} {}-SMA {})", symbol, currentPrice, interval, longTermMaPeriod, longTermMA);
                generalTrendConditionMet = true;
            } else {
                logger.info("SNIPER: {} Trend condition for {} not met. Price: {}, {}-SMA: {}", symbol, side, currentPrice, longTermMaPeriod, longTermMA);
                // return false; // Strict trend filter - re-enable if necessary
            }
        } else {
            logger.warn("SNIPER: Not enough data for {}-period long-term MA trend confirmation on {} {}. Have {}, need {}.", longTermMaPeriod, symbol, interval, primaryKlines.size(), longTermMaPeriod);
            // return false; // Re-enable if trend confirmation is strictly required
        }
        
        if (!generalTrendConditionMet) { // if strict trend filter enabled above, this might be redundant
             logger.info("SNIPER: General trend condition not met for {} {} entry. Proceeding with caution or other checks.", symbol, side);
             // return false; // Decided to proceed even if trend is not met for now, to allow other signals to weigh in.
        }

        // --- Individual Condition Checks (S/R Proximity, Fib, Volume, MA Confluence, RSI) ---
        boolean srProximityCondition = false; // For price being *near* S/R (not breakout)
        boolean fibCondition = false;
        boolean volumeCondition = false;
        boolean maConfluenceCondition = false;
        boolean rsiCondition = false;

        if ("BUY".equalsIgnoreCase(side)) {
            // S/R Level Check (Price near Support for BUY)
            double srTolerance = (double) configuration.getOrDefault("srTolerance", 0.005);
            // Use the same srLevels identified earlier
            if (!srLevels.isEmpty()) {
                for (SupportResistanceLevel level : srLevels) {
                    if ("Support".equals(level.getType())) {
                        if (Math.abs(currentPrice - level.getLevel()) / level.getLevel() <= srTolerance) {
                            logger.info("SNIPER (LONG): {} near support level {} (current price {})", symbol, level.getLevel(), currentPrice);
                            srProximityCondition = true;
                            break;
                        }
                    }
                }
            }
            if (!srProximityCondition) logger.info("SNIPER (LONG): {} not near significant support for proximity check.", symbol);

            // Fibonacci Retracement Check (Support in Uptrend)
            double swingHigh = primaryKlines.stream().mapToDouble(Candlestick::getHigh).max().orElse(currentPrice);
            double swingLow = primaryKlines.stream().mapToDouble(Candlestick::getLow).min().orElse(currentPrice);
            if (swingHigh > swingLow && (currentPrice > swingLow && currentPrice < swingHigh)) { 
                double fibTolerance = (double) configuration.getOrDefault("fibTolerance", 0.005);
                double fibLevel618 = swingHigh - (swingHigh - swingLow) * DEFAULT_FIB_LEVEL_618;
                double fibLevel786 = swingHigh - (swingHigh - swingLow) * DEFAULT_FIB_LEVEL_786;
                if ((Math.abs(currentPrice - fibLevel618) / fibLevel618 <= fibTolerance) ||
                    (Math.abs(currentPrice - fibLevel786) / fibLevel786 <= fibTolerance)) {
                    logger.info("SNIPER (LONG): {} near key Fibonacci support (61.8 or 78.6). Price: {}, Swing: {} - {}. Level 61.8: {}, Level 78.6: {}", 
                        symbol, currentPrice, swingLow, swingHigh, fibLevel618, fibLevel786);
                    fibCondition = true;
                } else {
                    logger.info("SNIPER (LONG): {} not at 61.8 or 78.6 Fibonacci support. Price: {}, Swing: {} - {}. Level 61.8: {}, Level 78.6: {}", 
                        symbol, currentPrice, swingLow, swingHigh, fibLevel618, fibLevel786);
                }
            } else {
                logger.warn("SNIPER (LONG): Invalid swing for Fibonacci (Low: {}, High: {}) or price {} outside swing for {}.", 
                            swingLow, swingHigh, currentPrice, symbol);
            }

            // Volume Spike Check
            List<Double> volumes = primaryKlines.stream().map(Candlestick::getVolume).collect(Collectors.toList());
            int volumeLookback = (int) configuration.getOrDefault("volumeLookbackPeriod", 20);
            double volSpikeMultiplier = (double) configuration.getOrDefault("volumeSpikeMultiplier", DEFAULT_VOLUME_SPIKE_MULTIPLIER);
            if (volumes.size() > volumeLookback) {
                volumeCondition = TechnicalAnalysisUtil.isVolumeSpike(volumes, volumeLookback, volSpikeMultiplier);
                if (volumeCondition) logger.info("SNIPER (LONG): Volume spike confirmed for {}.", symbol);
                else logger.info("SNIPER (LONG): No significant volume spike for {}.", symbol);
            } else {
                logger.warn("SNIPER (LONG): Not enough volume data (size: {}) for lookback period: {} for symbol: {}", 
                            volumes.size(), volumeLookback, symbol);
            }

            // MA Confluence Check (Bullish)
            List<Double> pricesForMA = primaryKlines.stream().map(Candlestick::getClose).collect(Collectors.toList());
            String maConfigKey = "maPeriods_" + interval.replace("m", "").replace("h","");
            Map<String, Integer> maPeriodsDefaults = (Map<String, Integer>) configuration.get("maPeriods_1h"); 
            Map<String, Integer> maConfig = (Map<String, Integer>) configuration.getOrDefault(maConfigKey, maPeriodsDefaults);

            if (pricesForMA.size() >= maConfig.getOrDefault("long", 50)) {
                double shortMA = TechnicalAnalysisUtil.calculateSMA(pricesForMA, maConfig.get("short"));
                double mediumMA = TechnicalAnalysisUtil.calculateSMA(pricesForMA, maConfig.get("medium"));
                double longMA = TechnicalAnalysisUtil.calculateSMA(pricesForMA, maConfig.get("long"));
                if (currentPrice > shortMA && shortMA > mediumMA && mediumMA > longMA) {
                    logger.info("SNIPER (LONG): {} MA bullish confluence on {} interval. Price: {:.4f}, S:{:.4f} M:{:.4f} L:{:.4f}", 
                        symbol, interval, currentPrice, shortMA, mediumMA, longMA);
                    maConfluenceCondition = true;
                } else {
                    logger.info("SNIPER (LONG): {} MA not in bullish confluence on {} interval. Price: {:.4f}, S:{:.4f} M:{:.4f} L:{:.4f}", 
                        symbol, interval, currentPrice, shortMA, mediumMA, longMA);
                }
            } else {
                logger.warn("SNIPER (LONG): Not enough data for MA confluence on {} {}. Needed {}, Got {}.", 
                            symbol, interval, maConfig.getOrDefault("long", 50), pricesForMA.size());
            }

            // RSI Check (In Uptrend, not Overbought or Oversold with Bullish Divergence)
            int rsiPeriod = (int) configuration.getOrDefault("rsiPeriod", DEFAULT_RSI_PERIOD);
            double rsiOversold = (double) configuration.getOrDefault("rsiOversold", 30.0);
            double rsiOverbought = (double) configuration.getOrDefault("rsiOverbought", 70.0);
            if (pricesForMA.size() > rsiPeriod) { 
                double rsiValue = TechnicalAnalysisUtil.calculateRSI(pricesForMA, rsiPeriod);
                logger.info("SNIPER (LONG): {} RSI({}) is {:.2f}. Trend is UP (based on generalTrendConditionMet: {}).", symbol, rsiPeriod, rsiValue, generalTrendConditionMet);
                if (generalTrendConditionMet && rsiValue < rsiOverbought && rsiValue > rsiOversold) { 
                    logger.info("SNIPER (LONG): {} RSI {} is neutral ({:.2f}) within uptrend. Good.", symbol, rsiPeriod, rsiValue);
                    rsiCondition = true;
                } else if (generalTrendConditionMet && rsiValue <= rsiOversold) { 
                    logger.info("SNIPER (LONG): {} RSI {} is oversold ({:.2f}) in uptrend. Potential entry.", symbol, rsiPeriod, rsiValue);
                    rsiCondition = true; 
                } else if (!generalTrendConditionMet && rsiValue <= rsiOversold) { // Counter-trend consideration (oversold)
                     logger.info("SNIPER (LONG): {} RSI {} is oversold ({:.2f}) but general trend is NOT up. Possible reversal play?", symbol, rsiPeriod, rsiValue);
                     // rsiCondition = true; // If allowing counter-trend based on RSI extreme
                } else if (rsiValue >= rsiOverbought) { 
                     logger.info("SNIPER (LONG): {} RSI {} is overbought ({:.2f}). Less ideal for long unless strong momentum confirmed.", symbol, rsiPeriod, rsiValue);
                }
            } else {
                logger.warn("SNIPER (LONG): Not enough data for RSI on {} {}. Needed {}, Got {}.", 
                            symbol, interval, rsiPeriod + 1, pricesForMA.size());
            }

        } else if ("SELL".equalsIgnoreCase(side)) {
            // S/R Level Check (Price near Resistance for SELL)
            double srTolerance = (double) configuration.getOrDefault("srTolerance", 0.005);
            if (!srLevels.isEmpty()) {
                for (SupportResistanceLevel level : srLevels) {
                    if ("Resistance".equals(level.getType())) {
                        if (Math.abs(currentPrice - level.getLevel()) / level.getLevel() <= srTolerance) {
                            logger.info("SNIPER (SHORT): {} near resistance level {} (current price {})", symbol, level.getLevel(), currentPrice);
                            srProximityCondition = true;
                            break;
                        }
                    }
                }
            }
            if (!srProximityCondition) logger.info("SNIPER (SHORT): {} not near significant resistance for proximity check.", symbol);
            
            // Fibonacci Retracement Check (Resistance in Downtrend)
            double swingHighS = primaryKlines.stream().mapToDouble(Candlestick::getHigh).max().orElse(currentPrice);
            double swingLowS = primaryKlines.stream().mapToDouble(Candlestick::getLow).min().orElse(currentPrice);
            if (swingHighS > swingLowS && (currentPrice > swingLowS && currentPrice < swingHighS)) { 
                double fibTolerance = (double) configuration.getOrDefault("fibTolerance", 0.005);
                double fibLevel618Short = swingLowS + (swingHighS - swingLowS) * DEFAULT_FIB_LEVEL_618; 
                double fibLevel786Short = swingLowS + (swingHighS - swingLowS) * DEFAULT_FIB_LEVEL_786;

                if ((Math.abs(currentPrice - fibLevel618Short) / fibLevel618Short <= fibTolerance) ||
                    (Math.abs(currentPrice - fibLevel786Short) / fibLevel786Short <= fibTolerance)) {
                    logger.info("SNIPER (SHORT): {} near key Fibonacci resistance (61.8 or 78.6). Price: {}, Swing Low: {}, Swing High: {}. Fib618: {}, Fib786: {}", 
                        symbol, currentPrice, swingLowS, swingHighS, fibLevel618Short, fibLevel786Short);
                    fibCondition = true;
                } else {
                    logger.info("SNIPER (SHORT): {} not at 61.8 or 78.6 Fibonacci resistance. Price: {}, Swing Low: {}, Swing High: {}. Fib618: {}, Fib786: {}", 
                        symbol, currentPrice, swingLowS, swingHighS, fibLevel618Short, fibLevel786Short);
                }
            } else {
                logger.warn("SNIPER (SHORT): Invalid swing for Fibonacci (Low: {}, High: {}) or price {} outside swing for {}.", 
                            swingLowS, swingHighS, currentPrice, symbol);
            }
            
            // Volume Spike Check
            List<Double> volumes = primaryKlines.stream().map(Candlestick::getVolume).collect(Collectors.toList());
            int volumeLookback = (int) configuration.getOrDefault("volumeLookbackPeriod", 20);
            double volSpikeMultiplier = (double) configuration.getOrDefault("volumeSpikeMultiplier", DEFAULT_VOLUME_SPIKE_MULTIPLIER);
            if (volumes.size() > volumeLookback) {
                volumeCondition = TechnicalAnalysisUtil.isVolumeSpike(volumes, volumeLookback, volSpikeMultiplier);
                if (volumeCondition) logger.info("SNIPER (SHORT): Volume spike confirmed for {}.", symbol);
                else logger.info("SNIPER (SHORT): No significant volume spike for {}.", symbol);
            } else {
                logger.warn("SNIPER (SHORT): Not enough volume data (size: {}) for lookback period: {} for symbol: {}.", 
                            volumes.size(), volumeLookback, symbol);
            }

            // MA Confluence Check (Bearish)
            List<Double> pricesForMA = primaryKlines.stream().map(Candlestick::getClose).collect(Collectors.toList());
            String maConfigKey = "maPeriods_" + interval.replace("m", "").replace("h","");
            Map<String, Integer> maPeriodsDefaults = (Map<String, Integer>) configuration.get("maPeriods_1h");
            Map<String, Integer> maConfig = (Map<String, Integer>) configuration.getOrDefault(maConfigKey, maPeriodsDefaults);

            if (pricesForMA.size() >= maConfig.getOrDefault("long", 50)) {
                double shortMA = TechnicalAnalysisUtil.calculateSMA(pricesForMA, maConfig.get("short"));
                double mediumMA = TechnicalAnalysisUtil.calculateSMA(pricesForMA, maConfig.get("medium"));
                double longMA = TechnicalAnalysisUtil.calculateSMA(pricesForMA, maConfig.get("long"));
                if (currentPrice < shortMA && shortMA < mediumMA && mediumMA < longMA) { 
                    logger.info("SNIPER (SHORT): {} MA bearish confluence on {} interval. Price: {:.4f}, S:{:.4f} M:{:.4f} L:{:.4f}", 
                        symbol, interval, currentPrice, shortMA, mediumMA, longMA);
                    maConfluenceCondition = true;
                } else {
                    logger.info("SNIPER (SHORT): {} MA not in bearish confluence on {} interval. Price: {:.4f}, S:{:.4f} M:{:.4f} L:{:.4f}", 
                        symbol, interval, currentPrice, shortMA, mediumMA, longMA);
                }
            } else {
                logger.warn("SNIPER (SHORT): Not enough data for MA confluence on {} {}. Needed {}, Got {}.", 
                            symbol, interval, maConfig.getOrDefault("long", 50), pricesForMA.size());
            }
            
            // RSI Check (In Downtrend, not Oversold or Overbought with Bearish Divergence)
            int rsiPeriod = (int) configuration.getOrDefault("rsiPeriod", DEFAULT_RSI_PERIOD);
            double rsiOversold = (double) configuration.getOrDefault("rsiOversold", 30.0);
            double rsiOverbought = (double) configuration.getOrDefault("rsiOverbought", 70.0);
            if (pricesForMA.size() > rsiPeriod) { 
                double rsiValue = TechnicalAnalysisUtil.calculateRSI(pricesForMA, rsiPeriod);
                logger.info("SNIPER (SHORT): {} RSI({}) is {:.2f}. Trend is DOWN (based on generalTrendConditionMet: {}).", symbol, rsiPeriod, rsiValue, generalTrendConditionMet);
                if (generalTrendConditionMet && rsiValue > rsiOversold && rsiValue < rsiOverbought) { 
                    logger.info("SNIPER (SHORT): {} RSI {} is neutral ({:.2f}) within downtrend. Good.", symbol, rsiPeriod, rsiValue);
                    rsiCondition = true;
                } else if (generalTrendConditionMet && rsiValue >= rsiOverbought) { 
                    logger.info("SNIPER (SHORT): {} RSI {} is overbought ({:.2f}) in downtrend. Potential entry.", symbol, rsiPeriod, rsiValue);
                    rsiCondition = true; 
                } else if (!generalTrendConditionMet && rsiValue >= rsiOverbought) { // Counter-trend (overbought)
                    logger.info("SNIPER (SHORT): {} RSI {} is overbought ({:.2f}) but general trend is NOT down. Possible reversal play?", symbol, rsiPeriod, rsiValue);
                    // rsiCondition = true; // If allowing counter-trend based on RSI extreme
                } else if (rsiValue <= rsiOversold) { 
                     logger.info("SNIPER (SHORT): {} RSI {} is oversold ({:.2f}) in downtrend. Less ideal for short unless strong momentum confirmed.", symbol, rsiPeriod, rsiValue);
                }
            } else {
                logger.warn("SNIPER (SHORT): Not enough data for RSI on {} {}. Needed {}, Got {}.", 
                            symbol, interval, rsiPeriod + 1, pricesForMA.size());
            }

        } else {
            logger.warn("SNIPER: Invalid side '{}' for evaluateEntry. Must be 'BUY' or 'SELL'.", side);
            return NO_SIGNAL;
        }

        // Calculate Raw Sniper Technical Score (0-5 points, 1 per condition)
        // Note: srBreakoutConditionMet is for event-driven AI trigger, not direct part of this TA score yet.
        // The srProximityCondition is the one used for the score.
        double rawSniperTechnicalScore = (srProximityCondition ? 1 : 0) +
                                     (fibCondition ? 1 : 0) +
                                     (volumeCondition ? 1 : 0) +
                                     (maConfluenceCondition ? 1 : 0) +
                                     (rsiCondition ? 1 : 0);
        logger.info("SNIPER: {} Raw Technical Score (proximity S/R, Fib, Vol, MA, RSI): {} (S/R_prox={}, Fib={}, Vol={}, MA={}, RSI={}). Breakout detected: {}",
            symbol, rawSniperTechnicalScore, srProximityCondition, fibCondition, volumeCondition, maConfluenceCondition, rsiCondition, srBreakoutConditionMet);

        // Get Adaptive Weights from SignalWeightingService (for logging/observation)
        Map<String, Double> adaptiveWeights = signalWeightingService.calculateAdaptiveWeights(symbol, interval);
        double technicalWeightFromService = adaptiveWeights.getOrDefault("technical", 0.4); 
        logger.info("SNIPER: {} Adaptive Technical Weight (from SWS): {}", symbol, technicalWeightFromService);

        double finalScoreForTiering = rawSniperTechnicalScore; 
        boolean aiConfirmsTrade = false;
        String exchangeForAI = "BYBIT";
        if (rawSniperTechnicalScore >= 3.0) { // Threshold to call AI
            ScanResult scanResultForAI = new ScanResult();
            scanResultForAI.setSymbol(symbol);
            scanResultForAI.setInterval(interval);
            scanResultForAI.setExchange(exchangeForAI); 
            scanResultForAI.setPrice(BigDecimal.valueOf(currentPrice));
            
            String aiRawResponse = "";
            try {
                aiRawResponse = aiAnalysisService.getClaudeAnalysisSynchronous(scanResultForAI); 
            } catch (java.io.IOException e) {
                logger.error("SNIPER: IOException calling AIAnalysisService.getClaudeAnalysisSynchronous for {}: {}", symbol, e.getMessage());
            }
            
            String aiSignal = aiAnalysisService.extractSignalFromClaudeResponse(aiRawResponse);

            if (aiSignal != null) {
                logger.info("SNIPER: {} AI Analysis Signal: {}", symbol, aiSignal);
                if ("BUY".equalsIgnoreCase(side) && (aiSignal.contains("STRONG_BUY") || aiSignal.contains("BUY"))) {
                    aiConfirmsTrade = true;
                    finalScoreForTiering += 1.0; 
                } else if ("SELL".equalsIgnoreCase(side) && (aiSignal.contains("STRONG_SELL") || aiSignal.contains("SELL"))) {
                    aiConfirmsTrade = true;
                    finalScoreForTiering += 1.0; 
                } else {
                    logger.info("SNIPER: {} AI Signal ({}) does not align with {} intent or is neutral/opposite.", symbol, aiSignal, side);
                }
            } else {
                logger.warn("SNIPER: {} Could not get a clear signal from AI Analysis.", symbol);
            }
        } else {
            logger.info("SNIPER: {} Skipping AI Analysis, raw technical score ({}) too low.", symbol, rawSniperTechnicalScore);
        }
        
        // --- Multi-Timeframe Analysis (MTFA) Confirmation ---
        logger.info("SNIPER: {} Attempting Multi-Timeframe Analysis confirmation for side: {}", symbol, side);
        // Assuming BYBIT linear for Sniper strategy MTFA for now.
        MultiTimeframeService.MTFAConfirmationStatus mtfaStatus = multiTimeframeService.getHigherTimeframeConfirmation(
            symbol, 
            interval, 
            side, 
            "BYBIT", // TODO: Make exchange dynamic if needed
            com.tradingbot.backend.model.ScanConfig.MarketType.LINEAR // TODO: Make marketType dynamic if needed
        );

        double mtfaScoreAdjustment = 0.0;
        switch (mtfaStatus) {
            case STRONG_CONFIRMATION:
                mtfaScoreAdjustment = 0.75;
                logger.info("SNIPER: {} MTFA Strong Confirmation. Adjusting score by: {}", symbol, mtfaScoreAdjustment);
                break;
            case WEAK_CONFIRMATION:
                mtfaScoreAdjustment = 0.25;
                logger.info("SNIPER: {} MTFA Weak Confirmation. Adjusting score by: {}", symbol, mtfaScoreAdjustment);
                break;
            case NO_CONFIRMATION:
                logger.info("SNIPER: {} MTFA No Confirmation. No score adjustment.", symbol);
                break;
            case CONTRADICTION:
                mtfaScoreAdjustment = -1.0; // Penalty for contradiction
                logger.info("SNIPER: {} MTFA Contradiction. Adjusting score by: {}", symbol, mtfaScoreAdjustment);
                break;
            case NOT_APPLICABLE:
                logger.info("SNIPER: {} MTFA Not Applicable for interval {}. No score adjustment.", symbol, interval);
                break;
            case SERVICE_ERROR:
                logger.warn("SNIPER: {} MTFA Service Error. No score adjustment.", symbol);
                break;
            default:
                logger.warn("SNIPER: {} MTFA Unknown status: {}. No score adjustment.", symbol, mtfaStatus);
                break;
        }
        finalScoreForTiering += mtfaScoreAdjustment;
        
        logger.info("SNIPER: {} Score after MTFA (raw TA + AI bonus + MTFA adj): {} (MTFA Status: {}, Adjustment: {})", 
            symbol, finalScoreForTiering, mtfaStatus, mtfaScoreAdjustment);

        // --- Determine Final Signal Tier ---
        String determinedSignalTier = NO_SIGNAL;

        if (srBreakoutConditionMet) {
            if ("RESISTANCE_BROKEN_ABOVE".equals(breakoutType) && "BUY".equalsIgnoreCase(side)) {
                // Further qualify breakout tier based on AI/MTFA/Score if needed
                // For now, let's assume a strong breakout is Tier 1 if AI confirms or score is high, else Tier 2
                if (aiConfirmsTrade || finalScoreForTiering >= TIER_1_THRESHOLD -1) { // -1 because breakout is a strong signal itself
                    determinedSignalTier = TIER_1_BREAKOUT_BUY;
                } else {
                    determinedSignalTier = TIER_2_BREAKOUT_BUY;
                }
                logger.info("SNIPER: Breakout signal for {} {}: {}. AI Confirms={}, Score Factor: {}", symbol, side, determinedSignalTier, aiConfirmsTrade, finalScoreForTiering);
            } else if ("SUPPORT_BROKEN_BELOW".equals(breakoutType) && "SELL".equalsIgnoreCase(side)) {
                if (aiConfirmsTrade || finalScoreForTiering >= TIER_1_THRESHOLD -1) {
                    determinedSignalTier = TIER_1_BREAKOUT_SELL;
                } else {
                    determinedSignalTier = TIER_2_BREAKOUT_SELL;
                }
                logger.info("SNIPER: Breakout signal for {} {}: {}. AI Confirms={}, Score Factor: {}", symbol, side, determinedSignalTier, aiConfirmsTrade, finalScoreForTiering);
            }
        } else if (srRejectionOccurred) {
            if ("SUPPORT_REJECTION_FOR_BUY".equals(rejectionType) && "BUY".equalsIgnoreCase(side)) {
                if (aiConfirmsTrade || finalScoreForTiering >= TIER_1_THRESHOLD -1) {
                    determinedSignalTier = TIER_1_REJECTION_BUY;
                } else {
                    determinedSignalTier = TIER_2_REJECTION_BUY;
                }
                logger.info("SNIPER: Rejection signal for {} {}: {} at level {}. AI Confirms={}, Score Factor: {}", symbol, side, determinedSignalTier, rejectionLevelPrice, aiConfirmsTrade, finalScoreForTiering);
            } else if ("RESISTANCE_REJECTION_FOR_SELL".equals(rejectionType) && "SELL".equalsIgnoreCase(side)) {
                 if (aiConfirmsTrade || finalScoreForTiering >= TIER_1_THRESHOLD -1) {
                    determinedSignalTier = TIER_1_REJECTION_SELL;
                } else {
                    determinedSignalTier = TIER_2_REJECTION_SELL;
                }
                logger.info("SNIPER: Rejection signal for {} {}: {} at level {}. AI Confirms={}, Score Factor: {}", symbol, side, determinedSignalTier, rejectionLevelPrice, aiConfirmsTrade, finalScoreForTiering);
            }
        }
        
        // If no breakout or rejection signal, fall back to confluence score based tiering
        if (NO_SIGNAL.equals(determinedSignalTier)) {
            if (finalScoreForTiering >= TIER_1_THRESHOLD) { // Using defined thresholds now
                determinedSignalTier = "BUY".equalsIgnoreCase(side) ? TIER_1_CONFLUENCE_BUY : TIER_1_CONFLUENCE_SELL;
                logger.info("SNIPER: Tier 1 Confluence signal for {} {} (Score: {}). AI Confirms={}", symbol, side, finalScoreForTiering, aiConfirmsTrade);
            } else if (finalScoreForTiering >= TIER_2_THRESHOLD) {
                determinedSignalTier = "BUY".equalsIgnoreCase(side) ? TIER_2_CONFLUENCE_BUY : TIER_2_CONFLUENCE_SELL;
                logger.info("SNIPER: Tier 2 Confluence signal for {} {} (Score: {}). AI Confirms={}", symbol, side, finalScoreForTiering, aiConfirmsTrade);
            } else if (finalScoreForTiering >= TIER_3_THRESHOLD) {
                determinedSignalTier = "BUY".equalsIgnoreCase(side) ? TIER_3_CONFLUENCE_BUY : TIER_3_CONFLUENCE_SELL;
                logger.info("SNIPER: Tier 3 Confluence signal for {} {} (Score: {}). AI Confirms={}", symbol, side, finalScoreForTiering, aiConfirmsTrade);
            } else {
                logger.info("SNIPER: Not enough confluences or score for {} {} signal (Score: {}). AI Confirms={}", symbol, side, finalScoreForTiering, aiConfirmsTrade);
                // determinedSignalTier remains NO_SIGNAL
            }
        }
        
        currentSignalTiers.put(symbol, determinedSignalTier);
        return determinedSignalTier;
    }

    @Override
    public boolean evaluateExit(String symbol, String interval) {
        logger.info("SNIPER: Evaluating EXIT for {} on interval {}", symbol, interval);
        
        String exchange = "BYBIT"; // TODO: Make this configurable or derive from positionInfo if available and reliable

        PositionUpdateData positionInfo = positionCacheService.getPosition(symbol);
        if (positionInfo == null || positionInfo.getSize() == null || positionInfo.getSize().compareTo(BigDecimal.ZERO) == 0) {
            logger.debug("SNIPER: No active position for {}", symbol);
            return false;
        }
        // If exchange is stored in PositionUpdateData, prefer that.
        if (positionInfo.getExchange() != null && !positionInfo.getExchange().isEmpty()){
            exchange = positionInfo.getExchange();
        }

        List<Candlestick> klines = fetchCandlestickData(symbol, interval, 2, exchange); // Pass exchange
        if (klines.isEmpty()) {
            logger.warn("SNIPER: Could not fetch current price data for {} on exchange {}", symbol, exchange);
            return false;
        }
        BigDecimal currentPrice = BigDecimal.valueOf(klines.get(klines.size() - 1).getClose());
        
        BigDecimal activeStopLoss = positionInfo.getStrategyStopLossPrice();

        // --- Profit Taking Logic ---
        if (!positionInfo.isFirstProfitTargetTaken() && positionInfo.getEntryPrice() != null && activeStopLoss != null && activeStopLoss.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal riskPerUnit = positionInfo.getEntryPrice().subtract(activeStopLoss).abs();
            if (riskPerUnit.compareTo(BigDecimal.ZERO) > 0) {
                double firstProfitTargetRR = (double) configuration.getOrDefault("firstProfitTargetRR", 2.0);
                BigDecimal profitTargetAmount = riskPerUnit.multiply(BigDecimal.valueOf(firstProfitTargetRR));
                BigDecimal priceForFirstPT;
                boolean pt1Reached = false;

                if ("Buy".equalsIgnoreCase(positionInfo.getSide())) {
                    priceForFirstPT = positionInfo.getEntryPrice().add(profitTargetAmount);
                    if (currentPrice.compareTo(priceForFirstPT) >= 0) {
                        logger.info("SNIPER: Profit Target 1 ({}R R/R) reached for LONG {}. Current: {}, Target: {}. Attempting partial close.", 
                                    firstProfitTargetRR, symbol, currentPrice, priceForFirstPT);
                        pt1Reached = true;
                    }
                } else if ("Sell".equalsIgnoreCase(positionInfo.getSide())) {
                    priceForFirstPT = positionInfo.getEntryPrice().subtract(profitTargetAmount);
                    if (currentPrice.compareTo(priceForFirstPT) <= 0) {
                        logger.info("SNIPER: Profit Target 1 ({}R R/R) reached for SHORT {}. Current: {}, Target: {}. Attempting partial close.", 
                                    firstProfitTargetRR, symbol, currentPrice, priceForFirstPT);
                        pt1Reached = true;
                    }
                }

                if (pt1Reached) {
                    // The handlePartialProfitTaking method will now call the modified calculateNewStopLoss
                    // which uses currentPrice and interval for ATR calculation.
                    return handlePartialProfitTaking(symbol, positionInfo, currentPrice, interval, firstProfitTargetRR);
                }
            }
        }

        // --- Trailing Stop Logic (if PT1 is already taken) ---
        if (positionInfo.isFirstProfitTargetTaken() && activeStopLoss != null && activeStopLoss.compareTo(BigDecimal.ZERO) != 0) {
            String positionExchange = positionInfo.getExchange(); // Renamed to avoid conflict
             if (positionExchange == null) {
                positionExchange = symbol.endsWith("USDT") ? "BYBIT" : "MEXC"; // Fallback
                logger.warn("SNIPER_TrailingSL: Exchange not found in PositionUpdateData for {}, inferring as {}.", symbol, positionExchange);
            }

            int atrPeriod = (int) configuration.getOrDefault("atrPeriod", DEFAULT_ATR_PERIOD);
            double atrMultiplierConfig = (double) configuration.getOrDefault("atrMultiplier", DEFAULT_ATR_MULTIPLIER);
            BigDecimal atrMultiplier = BigDecimal.valueOf(atrMultiplierConfig);

            try {
                BigDecimal atrValue = riskManagementService.calculateATRForSymbol(symbol, positionExchange, interval, atrPeriod);
                if (atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal atrOffset = atrValue.multiply(atrMultiplier);
                    BigDecimal newPotentialStop;

                    if ("Buy".equalsIgnoreCase(positionInfo.getSide())) {
                        newPotentialStop = currentPrice.subtract(atrOffset);
                        // Trail only if the new stop is higher (better) than the current active stop
                        if (newPotentialStop.compareTo(activeStopLoss) > 0) {
                            logger.info("SNIPER: Trailing SL for LONG {} updating from {} to {}. CurrentPrice: {}", 
                                        symbol, activeStopLoss, newPotentialStop, currentPrice);
                            positionCacheService.updateStrategyPositionInfo(symbol, newPotentialStop, true); // true for PT1 taken
                            activeStopLoss = newPotentialStop; // Update activeStopLoss for the current check cycle
                        }
                    } else if ("Sell".equalsIgnoreCase(positionInfo.getSide())) {
                        newPotentialStop = currentPrice.add(atrOffset);
                        // Trail only if the new stop is lower (better) than the current active stop
                        if (newPotentialStop.compareTo(activeStopLoss) < 0) {
                            logger.info("SNIPER: Trailing SL for SHORT {} updating from {} to {}. CurrentPrice: {}", 
                                        symbol, activeStopLoss, newPotentialStop, currentPrice);
                            positionCacheService.updateStrategyPositionInfo(symbol, newPotentialStop, true);
                            activeStopLoss = newPotentialStop; // Update activeStopLoss
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                logger.error("SNIPER: JsonProcessingException calculating ATR for trailing SL on {}: {}", symbol, e.getMessage());
            } catch (Exception e) {
                logger.error("SNIPER: Error calculating ATR for trailing SL on {}: {}", symbol, e.getMessage(), e);
            }
        }
        
        // --- Check Stop Loss Hit (using the potentially updated activeStopLoss) ---
        if (activeStopLoss != null && activeStopLoss.compareTo(BigDecimal.ZERO) != 0) {
            if ("Buy".equalsIgnoreCase(positionInfo.getSide())) {
                if (currentPrice.compareTo(activeStopLoss) <= 0) {
                    logger.info("SNIPER: Stop loss hit for LONG {}. Current: {}, SL: {}", 
                                symbol, currentPrice, activeStopLoss);
                    return true;
                }
            } else if ("Sell".equalsIgnoreCase(positionInfo.getSide())) {
                if (currentPrice.compareTo(activeStopLoss) >= 0) {
                    logger.info("SNIPER: Stop loss hit for SHORT {}. Current: {}, SL: {}", 
                                symbol, currentPrice, activeStopLoss);
                    return true;
                }
            }
        }

        return false; // Default to no exit if no conditions met
    }

    private boolean handlePartialProfitTaking(String symbol, PositionUpdateData positionInfo, BigDecimal currentPrice, 
                                            String interval, double firstProfitTargetRR) {
        try {
            BigDecimal currentQuantity = positionInfo.getSize();
            BigDecimal quantityToClose = currentQuantity.multiply(BigDecimal.valueOf(0.5)); // Close 50%
            
            OrderRequest partialCloseOrder = new OrderRequest();
            partialCloseOrder.setSymbol(symbol);
            partialCloseOrder.setSide("Buy".equalsIgnoreCase(positionInfo.getSide()) ? "SELL" : "BUY");
            partialCloseOrder.setType("MARKET");
            partialCloseOrder.setQuantity(quantityToClose);
            partialCloseOrder.setBotId("SniperStrategy");
            partialCloseOrder.setStrategyName(getName());
            partialCloseOrder.setMarketType(positionInfo.getExchange().equalsIgnoreCase("BYBIT") && symbol.endsWith("USDT") ? "linear" : "spot");

            logger.info("SNIPER: Submitting partial close MARKET order for {}: {} {} @ MARKET", 
                        symbol, partialCloseOrder.getSide(), partialCloseOrder.getQuantity());

            try {
                com.tradingbot.backend.model.Order apiOrderResponse = 
                    orderManagementService.placeOrder(partialCloseOrder, positionInfo.getExchange());

                if (apiOrderResponse == null || apiOrderResponse.getOrderId() == null) {
                    logger.error("SNIPER: Partial close order submission failed for {}. No order ID returned.", symbol);
                    return false;
                }

                // Wait for order fill confirmation
                String orderId = apiOrderResponse.getOrderId().toString(); // Convert Long to String
                boolean isOrderFilled = waitForOrderFill(symbol, orderId, positionInfo.getExchange());

                if (!isOrderFilled) {
                    logger.error("SNIPER: Partial close order {} for {} not filled after {} attempts.", 
                        orderId, symbol, MAX_ORDER_STATUS_CHECK_ATTEMPTS);
                    return false;
                }

                // Order is confirmed filled, update strategy state
                // Pass currentPrice and interval to calculateNewStopLoss for ATR calculation
                BigDecimal newStopLoss = calculateNewStopLoss(positionInfo, currentPrice, interval);
                positionCacheService.updateStrategyPositionInfo(symbol, newStopLoss, true); // true indicates PT1 taken
                
                logger.info("SNIPER: PT1 taken for {}. Updated cache - SL moved to {} (was {})", 
                    symbol, newStopLoss, positionInfo.getStrategyStopLossPrice());
                
                return false; // Return false as we want to keep the remaining position open
                
            } catch (IllegalArgumentException e) {
                logger.error("SNIPER: Invalid order parameters for {} partial close: {}", symbol, e.getMessage());
                return false;
            } catch (JsonProcessingException e) { // Specific catch for JsonProcessingException
                logger.error("SNIPER: Error processing JSON during partial close order for {}: {}", symbol, e.getMessage(), e);
                return false;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("insufficient balance")) {
                    logger.error("SNIPER: Insufficient balance for {} partial close", symbol);
                    return false;
                }
                if (e.getMessage() != null && e.getMessage().contains("position closed")) {
                    logger.warn("SNIPER: Position already closed for {}", symbol);
                    return true; // Signal full exit since position is already closed
                }
                logger.error("SNIPER: Error placing partial close order for {}: {}", symbol, e.getMessage(), e);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("SNIPER: Unexpected error in partial profit taking for {}: {}", symbol, e.getMessage(), e);
            return false;
        }
    }

    private boolean waitForOrderFill(String symbol, String orderId, String exchange) {
        for (int attempt = 0; attempt < MAX_ORDER_STATUS_CHECK_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(ORDER_STATUS_CHECK_DELAY_MS);
                
                // Get order details from exchange-specific service
                String orderStatus = null;
                if ("BYBIT".equalsIgnoreCase(exchange)) {
                    String orderDetails = bybitFuturesApiService.getOrderDetails(symbol, orderId);
                    JsonNode orderData = objectMapper.readTree(orderDetails);
                    if (orderData.has("result") && orderData.get("result").has("status")) {
                        orderStatus = orderData.get("result").get("status").asText();
                    } else {
                        logger.warn("SNIPER: Could not parse order status from Bybit response: {}", orderDetails);
                        continue;
                    }
                } else {
                    // For MEXC, check open orders - if not found, assume filled
                    ResponseEntity<String> response = mexcFuturesApiService.getCurrentOpenOrders(symbol);
                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        JsonNode ordersData = objectMapper.readTree(response.getBody());
                        if (ordersData.has("data") && ordersData.get("data").isArray()) {
                            boolean orderFound = false;
                            for (JsonNode order : ordersData.get("data")) {
                                if (orderId.equals(order.get("orderId").asText())) {
                                    orderStatus = order.get("status").asText();
                                    orderFound = true;
                                    break;
                                }
                            }
                            if (!orderFound) {
                                // Order not in open orders, assume filled
                                orderStatus = "FILLED";
                            }
                        } else {
                            logger.warn("SNIPER: Could not parse open orders from MEXC response: {}", response.getBody());
                            continue;
                        }
                    } else {
                        logger.warn("SNIPER: Error getting open orders from MEXC: {}", response.getStatusCode());
                        continue;
                    }
                }
                
                if (orderStatus == null) {
                    logger.warn("SNIPER: Could not determine order status for {} ({})", symbol, orderId);
                    continue;
                }
                
                if ("FILLED".equalsIgnoreCase(orderStatus)) {
                    logger.info("SNIPER: Order {} for {} confirmed filled", orderId, symbol);
                    return true;
                } else if ("CANCELED".equalsIgnoreCase(orderStatus) || "REJECTED".equalsIgnoreCase(orderStatus)) {
                    logger.error("SNIPER: Order {} for {} was {}", orderId, symbol, orderStatus);
                    return false;
                }
                
                logger.debug("SNIPER: Order {} for {} status check attempt {}: {}", 
                    orderId, symbol, attempt + 1, orderStatus);
                
            } catch (Exception e) {
                logger.warn("SNIPER: Error checking order status for {} ({}): {}", 
                    symbol, orderId, e.getMessage());
            }
        }
        return false;
    }

    private BigDecimal calculateNewStopLoss(PositionUpdateData positionInfo, BigDecimal currentPrice, String interval) {
        // After PT1, set stop loss based on ATR
        // The interval for ATR calculation should ideally be consistent or configured.
        // Using the interval passed to evaluateExit for now.
        String symbol = positionInfo.getSymbol();
        String exchange = positionInfo.getExchange(); // Assuming PositionUpdateData has exchange

        if (exchange == null) {
            // Attempt to infer exchange from symbol if common pattern, or default.
            // This is a fallback, ideally 'exchange' comes from positionInfo.
            exchange = symbol.endsWith("USDT") ? "BYBIT" : "MEXC"; // Simple inference
            logger.warn("SNIPER: Exchange not found in PositionUpdateData for {}, inferring as {}. ATR calculation might be affected.", symbol, exchange);
        }

        int atrPeriod = (int) configuration.getOrDefault("atrPeriod", DEFAULT_ATR_PERIOD);
        double atrMultiplierConfig = (double) configuration.getOrDefault("atrMultiplier", DEFAULT_ATR_MULTIPLIER);
        BigDecimal atrMultiplier = BigDecimal.valueOf(atrMultiplierConfig);

        try {
            BigDecimal atrValue = riskManagementService.calculateATRForSymbol(symbol, exchange, interval, atrPeriod);

            if (atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atrOffset = atrValue.multiply(atrMultiplier);
                if ("Buy".equalsIgnoreCase(positionInfo.getSide())) {
                    BigDecimal newSL = currentPrice.subtract(atrOffset);
                    // Ensure SL is at least entry price to lock in some profit/breakeven
                    if (newSL.compareTo(positionInfo.getEntryPrice()) < 0) {
                        newSL = positionInfo.getEntryPrice(); 
                        logger.info("SNIPER: ATR-based SL for {} below entry, setting to entry price: {}", symbol, newSL);
                    }
                    logger.info("SNIPER: Calculated new ATR-based SL for LONG {} at {}. CurrentPrice: {}, ATR: {}, Multiplier: {}", 
                                symbol, newSL, currentPrice, atrValue, atrMultiplier);
                    return newSL;
                } else if ("Sell".equalsIgnoreCase(positionInfo.getSide())) {
                    BigDecimal newSL = currentPrice.add(atrOffset);
                    // Ensure SL is at least entry price
                    if (newSL.compareTo(positionInfo.getEntryPrice()) > 0) {
                        newSL = positionInfo.getEntryPrice();
                         logger.info("SNIPER: ATR-based SL for {} above entry, setting to entry price: {}", symbol, newSL);
                    }
                    logger.info("SNIPER: Calculated new ATR-based SL for SHORT {} at {}. CurrentPrice: {}, ATR: {}, Multiplier: {}", 
                                symbol, newSL, currentPrice, atrValue, atrMultiplier);
                    return newSL;
                }
            } else {
                logger.warn("SNIPER: ATR value is null or zero for {}. Falling back to entry price for SL.", symbol);
            }
        } catch (JsonProcessingException e) {
            logger.error("SNIPER: JsonProcessingException calculating ATR for new SL on {}: {}. Falling back to entry price.", symbol, e.getMessage());
        } catch (Exception e) {
            logger.error("SNIPER: Error calculating ATR for new SL on {}: {}. Falling back to entry price.", symbol, e.getMessage(), e);
        }

        // Fallback: move to entry price if ATR calculation fails
        logger.warn("SNIPER: Fallback SL for {} set to entry price: {}", symbol, positionInfo.getEntryPrice());
        return positionInfo.getEntryPrice();
    }

    // Placeholder method to simulate fetching active position info. No longer used.
    // private ActivePositionInfo getPlaceholderActivePositionInfo(String symbol) { ... }
    
    // Helper method to calculate an initial stop loss if not found in cache (e.g., for R/R calculation)
    // This should align with how stop losses are determined at trade entry.
    private BigDecimal calculateInitialStopLoss(BigDecimal entryPrice, String side) {
        if (entryPrice == null || side == null) return null;
        double stopLossPercent = (double) configuration.getOrDefault("stopLossPercentMax", DEFAULT_STOP_LOSS_PERCENT_MAX); // Default to max for safety
        BigDecimal slAmount = entryPrice.multiply(BigDecimal.valueOf(stopLossPercent));
        if ("Buy".equalsIgnoreCase(side)) { // Bybit uses "Buy" for Long
            return entryPrice.subtract(slAmount);
        } else if ("Sell".equalsIgnoreCase(side)) { // Bybit uses "Sell" for Short
            return entryPrice.add(slAmount);
        }
        return null;
    }

    @Override
    public BigDecimal calculatePositionSize(String symbol, BigDecimal accountBalance) {
        logger.debug("SNIPER: Calculating position size for {} with account balance {}", symbol, accountBalance);

        String tier = currentSignalTiers.getOrDefault(symbol, "tier1"); // Default to Tier 1 if not set
        double riskPercent;
        int leverage;

        switch (tier.toLowerCase()) {
            case "tier1":
                riskPercent = (double) configuration.getOrDefault("tier1RiskPercent", 0.015);
                leverage = (int) configuration.getOrDefault("tier1Leverage", 25);
                break;
            case "tier2":
                riskPercent = (double) configuration.getOrDefault("tier2RiskPercent", 0.0075);
                leverage = (int) configuration.getOrDefault("tier2Leverage", 40);
                break;
            case "tier3":
                riskPercent = (double) configuration.getOrDefault("tier3RiskPercent", 0.004);
                leverage = (int) configuration.getOrDefault("tier3Leverage", 75);
                break;
            default:
                logger.warn("SNIPER: Unknown tier '{}' for symbol {}. Defaulting to Tier 1 risk/leverage.", tier, symbol);
                riskPercent = (double) configuration.getOrDefault("tier1RiskPercent", 0.015);
                leverage = (int) configuration.getOrDefault("tier1Leverage", 25);
        }

        logger.info("SNIPER: Symbol {}, Tier {}, Risk Percent: {}, Leverage: {}", symbol, tier, riskPercent, leverage);

        BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol, "BYBIT");

        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("SNIPER: Could not fetch current price for {} on exchange BYBIT. Cannot calculate position size.", symbol);
            return BigDecimal.ZERO;
        }

        // Calculate stop loss distance (e.g., 1% of current price)
        // For Sniper, this should be ultra-tight, e.g., 0.5% to 1%
        // The actual SL placement might use ATR, but for sizing, a percentage is often used.
        double stopLossPercent = (double) configuration.getOrDefault("stopLossPercentMax", DEFAULT_STOP_LOSS_PERCENT_MAX);
        BigDecimal stopLossDistance = currentPrice.multiply(BigDecimal.valueOf(stopLossPercent));

        if (accountBalance == null || accountBalance.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("SNIPER: Account balance is zero or null for {}. Cannot calculate position size.", symbol);
            return BigDecimal.ZERO;
        }

        // Max amount to risk = Account Balance * Risk Percent Per Trade for the tier
        BigDecimal maxRiskAmount = accountBalance.multiply(BigDecimal.valueOf(riskPercent));

        // Position size = Max Risk Amount / Stop Loss Distance (in quote currency)
        // This is for 1x leverage. For leveraged trading, the actual contract quantity needs to be derived.
        // For linear contracts (USDT margined): Position Size (in contracts) = (Max Risk Amount * Leverage) / (Stop Loss Distance * Contract_Value_Per_Point)
        // For Bybit USDT perpetuals, 1 contract = 1 unit of base currency (e.g., 1 BTC for BTCUSDT)
        // So, Stop Loss Distance is effectively per unit of base currency.
        // Position Size (in base currency) = (Account Balance * Risk Percent * Leverage) / (Stop Loss Percent * Current Price)
        // Simplified: Position Size (in base currency) = (Account Balance * Risk Percent * Leverage) / Stop Loss Distance
        // More directly: Quantity (in USDT) = Account Balance * Risk Percent * Leverage
        // Quantity (in Base Asset) = (Account Balance * Risk Percent * Leverage) / Current Price

        if (stopLossDistance.compareTo(BigDecimal.ZERO) == 0) {
            logger.error("SNIPER: Stop loss distance is zero for {}. Cannot calculate position size to avoid division by zero.", symbol);
            return BigDecimal.ZERO;
        }

        BigDecimal positionSizeInBaseAsset = accountBalance
            .multiply(BigDecimal.valueOf(riskPercent))
            .multiply(BigDecimal.valueOf(leverage))
            .divide(currentPrice, 8, BigDecimal.ROUND_HALF_UP); // Precision 8 for base asset quantity
        
        // Validate against exchange minimums/maximums if possible (requires more API calls or cached data)
        // For now, log and return
        logger.info("SNIPER: Calculated position size for {}: {} units of base asset (e.g., BTC for BTCUSDT)", symbol, positionSizeInBaseAsset);

        return positionSizeInBaseAsset;
    }
    
    // TODO: Implement helper methods like getHistoricalPrices, checkSupportResistance, etc.
    // These will involve calls to exchangeApiClient and TechnicalAnalysisUtil.

    @Override
    public String getName() {
        return "Sniper Entry Strategy";
    }

    @Override
    public String getDescription() {
        return "High-frequency sniper entry strategy focusing on confluence of S/R, Fibonacci, Volume, MA, and RSI, with tight stop losses and high R/R targets.";
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return new HashMap<>(this.configuration); // Return a copy
    }

    @Override
    public void updateConfiguration(Map<String, Object> newConfig) {
        if (newConfig != null) {
            for (Map.Entry<String, Object> entry : newConfig.entrySet()) {
                if (this.configuration.containsKey(entry.getKey())) {
                    this.configuration.put(entry.getKey(), entry.getValue());
                    logger.info("SNIPER: Updated configuration for {}: {} = {}", getName(), entry.getKey(), entry.getValue());
                } else {
                    logger.warn("SNIPER: Attempted to update unknown configuration key '{}' for {}", entry.getKey(), getName());
                }
            }
        }
    }

    @Override
    public BigDecimal getInitialStopLossPrice(String symbol, String side, BigDecimal entryPrice, String exchange, String interval) {
        if (entryPrice == null) {
            logger.warn("SNIPER: Entry price is null, cannot calculate initial stop loss for {}", symbol);
            return null; 
        }
        // Use a fixed percentage for initial stop loss as per NewTS.md (0.5-1%)
        // Let's use the tighter end for Sniper, e.g., stopLossPercentMin or a specific Sniper SL config.
        // For now, using stopLossPercentMax (default 1%) for simplicity, can be refined with tiered SL percentages.
        double stopLossPercent = (double) configuration.getOrDefault("stopLossPercentMax", DEFAULT_STOP_LOSS_PERCENT_MAX);
        
        BigDecimal slAmount = entryPrice.multiply(BigDecimal.valueOf(stopLossPercent));
        
        if ("BUY".equalsIgnoreCase(side)) {
            return entryPrice.subtract(slAmount);
        } else if ("SELL".equalsIgnoreCase(side)) {
            return entryPrice.add(slAmount);
        } else {
            logger.warn("SNIPER: Unknown side '{}' for initial SL calculation for {}. Returning null.", side, symbol);
            return null;
        }
    }

    // Public method to get the determined signal tier for a symbol
    public String getCurrentSignalTier(String symbol) {
        return currentSignalTiers.getOrDefault(symbol, "tier3"); // Default if not found
    }

    private BigDecimal calculateInitialATRStopLoss(String symbol, BigDecimal entryPrice, String side, String exchange, String interval) {
        int atrPeriod = (int) configuration.getOrDefault("atrPeriod", DEFAULT_ATR_PERIOD);
        double atrMultiplierConfig = (double) configuration.getOrDefault("atrMultiplier", DEFAULT_ATR_MULTIPLIER);
        BigDecimal atrMultiplier = BigDecimal.valueOf(atrMultiplierConfig);

        try {
            BigDecimal atrValue = riskManagementService.calculateATRForSymbol(symbol, exchange, interval, atrPeriod);
            if (atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atrOffset = atrValue.multiply(atrMultiplier);
                if ("BUY".equalsIgnoreCase(side)) {
                    return entryPrice.subtract(atrOffset);
                } else if ("SELL".equalsIgnoreCase(side)) {
                    return entryPrice.add(atrOffset);
                }
            } else {
                logger.warn("SNIPER: ATR value is null or zero for initial SL calculation on {}. Cannot use ATR-based SL.", symbol);
            }
        } catch (JsonProcessingException e) {
            logger.error("SNIPER: JsonProcessingException calculating ATR for initial SL on {}: {}. Cannot use ATR-based SL.", symbol, e.getMessage());
        } catch (Exception e) {
            logger.error("SNIPER: Error calculating ATR for initial SL on {}: {}. Cannot use ATR-based SL.", symbol, e.getMessage(), e);
        }
        // Fallback to percentage-based SL if ATR calculation fails or is not available
        double stopLossPercent = (double) configuration.getOrDefault("stopLossPercentMax", DEFAULT_STOP_LOSS_PERCENT_MAX);
        BigDecimal slAmount = entryPrice.multiply(BigDecimal.valueOf(stopLossPercent));
        if ("BUY".equalsIgnoreCase(side)) {
            return entryPrice.subtract(slAmount);
        } else {
            return entryPrice.add(slAmount);
        }
    }

    private List<Candlestick> fetchCandlestickData(String symbol, String interval, int limit, String exchange) {
        List<Candlestick> klines = marketDataService.getKlines(symbol, interval, limit, exchange);
        if (klines.isEmpty()) {
            logger.warn("SNIPER (fetchCandlestickData): Kline data from MarketDataService is empty for {}-{} on {}, limit {}", 
                symbol, interval, exchange, limit);
        }
        return klines;
    }

    @Override
    public String getId() {
        return "SniperEntryStrategy";
    }
} 