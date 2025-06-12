package com.tradingbot.backend.service;

import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

@Service
public class ScheduledAiAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledAiAnalysisService.class);

    private final AIAnalysisService aiAnalysisService;
    private final MarketDataService marketDataService;

    @Value("${medium.frequency.ai.symbols:BTCUSDT,ETHUSDT,SOLUSDT}")
    private String[] mediumFrequencySymbols;

    @Value("${medium.frequency.ai.interval:1h}")
    private String mediumFrequencyInterval;

    @Value("${medium.frequency.ai.exchange:BYBIT}")
    private String mediumFrequencyExchange;

    @Autowired
    public ScheduledAiAnalysisService(AIAnalysisService aiAnalysisService, MarketDataService marketDataService) {
        this.aiAnalysisService = aiAnalysisService;
        this.marketDataService = marketDataService;
    }

    // Schedule to run every 30 minutes
    @Scheduled(cron = "0 0/30 * * * ?")
    public void performMediumFrequencyAnalysis() {
        logger.info("Starting medium-frequency AI analysis for symbols: {} on interval: {} on exchange: {}", 
            Arrays.toString(mediumFrequencySymbols), mediumFrequencyInterval, mediumFrequencyExchange);

        for (String symbol : mediumFrequencySymbols) {
            try {
                ScanResult scanResult = new ScanResult();
                scanResult.setSymbol(symbol);
                scanResult.setInterval(mediumFrequencyInterval);
                scanResult.setTimestamp(System.currentTimeMillis());
                scanResult.setIndicators(Collections.emptyMap());

                try {
                    BigDecimal currentPrice = marketDataService.getCurrentPrice(symbol, mediumFrequencyExchange);
                    if (currentPrice != null) {
                        scanResult.setPrice(currentPrice.doubleValue());
                    } else {
                        logger.warn("Could not fetch price for {} for medium frequency AI analysis. Skipping symbol.", symbol);
                        continue;
                    }
                } catch (Exception e) {
                    logger.error("Error fetching price for {} for medium frequency AI analysis: {}. Skipping symbol.", symbol, e.getMessage());
                    continue;
                }

                logger.info("Requesting medium-frequency AI analysis for symbol: {}", symbol);
                String analysis = aiAnalysisService.getClaudeAnalysisSynchronous(scanResult);

                if (analysis != null && !analysis.toLowerCase().contains("fallback analysis") && !analysis.toLowerCase().contains("api analysis unavailable")) {
                    logger.info("Medium-frequency AI analysis received for {}: {}", symbol, analysis.substring(0, Math.min(analysis.length(), 100)) + "...");
                    // TODO: Further processing of the analysis, e.g., store it, generate alerts, etc.
                } else if (analysis != null && analysis.contains("API daily call limit reached")) {
                    logger.warn("Skipping further medium-frequency AI analysis as daily limit reached.");
                    break;
                } else {
                     logger.warn("Medium-frequency AI analysis for {} resulted in fallback or was null/unavailable.", symbol);
                }

            } catch (IOException e) {
                logger.error("IOException during medium-frequency AI analysis for symbol {}: {}", symbol, e.getMessage());
            } catch (Exception e) {
                logger.error("Unexpected error during medium-frequency AI analysis for symbol {}: {}", symbol, e.getMessage(), e);
            }
        }
        logger.info("Finished medium-frequency AI analysis cycle.");
    }
} 