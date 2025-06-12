package com.tradingbot.backend.service;

import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class EventDrivenAiAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(EventDrivenAiAnalysisService.class);

    private final AIAnalysisService aiAnalysisService;
    private final MarketDataService marketDataService;

    // To prevent flooding AI analysis for the same symbol due to rapid events
    private final ConcurrentHashMap<String, Long> lastAnalysisRequestTimestamps = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_BETWEEN_EVENT_ANALYSIS_MS = TimeUnit.MINUTES.toMillis(5);

    @Value("${event.driven.ai.exchange:BYBIT}")
    private String eventDrivenExchange;

    @Autowired
    public EventDrivenAiAnalysisService(AIAnalysisService aiAnalysisService, MarketDataService marketDataService) {
        this.aiAnalysisService = aiAnalysisService;
        this.marketDataService = marketDataService;
    }

    /**
     * Triggers an AI analysis based on a generic event.
     *
     * @param symbol The trading symbol (e.g., BTCUSDT)
     * @param interval The timeframe (e.g., 1h)
     * @param eventType A string describing the type of event (e.g., "VolatilitySpike", "LevelBreakout")
     * @param eventDetails Further details about the event
     */
    public void triggerAnalysisOnEvent(String symbol, String interval, String eventType, String eventDetails) {
        long currentTime = System.currentTimeMillis();
        String throttleKey = symbol + "_" + eventType;

        long lastRequestTime = lastAnalysisRequestTimestamps.getOrDefault(throttleKey, 0L);
        if (currentTime - lastRequestTime < MIN_INTERVAL_BETWEEN_EVENT_ANALYSIS_MS) {
            logger.info("Skipping event-driven AI analysis for symbol: {}, event: {}. Recently analyzed (throttled).", symbol, eventType);
            return;
        }

        logger.info("Event-driven AI analysis triggered for symbol: {}, interval: {}, eventType: {}, details: {}",
                symbol, interval, eventType, eventDetails);

        try {
            ScanResult scanResult = new ScanResult();
            scanResult.setSymbol(symbol);
            scanResult.setInterval(interval);
            scanResult.setTimestamp(currentTime);
            scanResult.setSignal(eventType + ": " + eventDetails);
            scanResult.setIndicators(Collections.emptyMap());

            try {
                BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol, eventDrivenExchange);
                if (currentPrice != null) {
                    scanResult.setPrice(currentPrice.doubleValue());
                } else {
                    logger.warn("Could not fetch price for {} for event-driven AI analysis. Proceeding without price.", symbol);
                    scanResult.setPrice(0.0);
                }
            } catch (Exception e) {
                logger.error("Error fetching price for {} for event-driven AI analysis: {}. Proceeding without price.", symbol, e.getMessage());
                scanResult.setPrice(0.0);
            }

            String analysis = aiAnalysisService.getClaudeAnalysisSynchronous(scanResult);
            lastAnalysisRequestTimestamps.put(throttleKey, currentTime);

            if (analysis != null && !analysis.toLowerCase().contains("fallback analysis") && !analysis.toLowerCase().contains("api analysis unavailable")) {
                logger.info("Event-driven AI analysis received for {}: {}", symbol, analysis.substring(0, Math.min(analysis.length(), 100)) + "...");
                // TODO: Process this analysis (e.g., notify, log, act)
            } else if (analysis != null && analysis.contains("API daily call limit reached")) {
                logger.warn("Event-driven AI analysis not performed for {} as daily limit reached.", symbol);
            } else {
                logger.warn("Event-driven AI analysis for {} resulted in fallback or was null/unavailable.", symbol);
            }

        } catch (IOException e) {
            logger.error("IOException during event-driven AI analysis for symbol {}: {}", symbol, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error during event-driven AI analysis for symbol {}: {}", symbol, e.getMessage(), e);
        }
    }

    // Specific event trigger methods can call the generic one
    public void triggerAnalysisOnVolatilitySpike(String symbol, String interval, double priceChangePercent) {
        triggerAnalysisOnEvent(symbol, interval, "VolatilitySpike", "Price changed by " + String.format("%.2f", priceChangePercent) + "%");
    }

    public void triggerAnalysisOnLevelBreakout(String symbol, String interval, String levelType, String direction, double levelPrice) {
        triggerAnalysisOnEvent(symbol, interval, "LevelBreakout", levelType + " " + direction + " at " + levelPrice);
    }

    /**
     * Triggers an AI analysis when a price rejection occurs at a key S/R level.
     *
     * @param symbol The trading symbol (e.g., BTCUSDT)
     * @param interval The timeframe (e.g., 1h)
     * @param levelType The type of level where rejection occurred (e.g., "Support", "Resistance")
     * @param eventType A string describing the rejection event (e.g., "rejection_at_support_for_buy")
     * @param levelPrice The price of the S/R level.
     * @param wickPriceContext The price reached by the wick during the rejection (e.g., low for support, high for resistance).
     */
    public void triggerAnalysisOnLevelRejection(String symbol, String interval, String levelType, String eventType, double levelPrice, double wickPriceContext) {
        String eventDetails = String.format("%s at %s (level: %.4f, wick_context: %.4f)", 
                                            eventType, levelType, levelPrice, wickPriceContext);
        triggerAnalysisOnEvent(symbol, interval, "LevelRejection", eventDetails);
    }
} 