package com.tradingbot.backend.service.strategy;

import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.SentimentAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

/**
 * A trading strategy based on news sentiment analysis.
 * This strategy generates signals based on sentiment scores from recent news and social media.
 */
public class NewsSentimentStrategy implements TradingStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(NewsSentimentStrategy.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final SentimentAnalysisService sentimentAnalysisService;
    
    // Strategy configuration with default values
    private Map<String, Object> configuration;
    
    public NewsSentimentStrategy(MexcApiClientService mexcApiClientService, 
                                SentimentAnalysisService sentimentAnalysisService) {
        this.mexcApiClientService = mexcApiClientService;
        this.sentimentAnalysisService = sentimentAnalysisService;
        
        // Initialize default configuration
        this.configuration = new HashMap<>();
        configuration.put("sentimentThresholdBullish", 0.6); // Threshold for bullish sentiment (0-1)
        configuration.put("sentimentThresholdBearish", 0.4); // Threshold for bearish sentiment (0-1)
        configuration.put("minSentimentSamples", 10); // Minimum number of sentiment samples needed
        configuration.put("priceMomentumConfirmation", true); // Whether to require price momentum confirmation
        configuration.put("lookbackPeriodHours", 24); // How far back to look for news
        configuration.put("stopLossPercent", 0.02); // 2% stop loss from entry
        configuration.put("takeProfitPercent", 0.04); // 4% take profit from entry
        configuration.put("riskPerTradePercent", 0.01); // 1% of account per trade
    }
    
    @Override
    public String getId() {
        return "news_sentiment";
    }
    
    @Override
    public String evaluateEntry(String symbol, String interval) {
        try {
            // 1. Get sentiment data for the symbol
            Map<String, Object> sentimentData = sentimentAnalysisService.getSymbolSentiment(
                    symbol, (int) configuration.get("lookbackPeriodHours"));
            
            if (sentimentData == null || !sentimentData.containsKey("averageSentiment") 
                    || !sentimentData.containsKey("sampleSize")) {
                logger.warn("No sentiment data available for {}", symbol);
                return "NEUTRAL";
            }
            
            // 2. Check if we have enough sentiment samples
            int sampleSize = (int) sentimentData.get("sampleSize");
            int minSamples = (int) configuration.get("minSentimentSamples");
            
            if (sampleSize < minSamples) {
                logger.info("Insufficient sentiment samples for {}: {} (need {})", 
                        symbol, sampleSize, minSamples);
                return "NEUTRAL";
            }
            
            // 3. Check if sentiment is bullish
            double averageSentiment = (double) sentimentData.get("averageSentiment");
            double sentimentThreshold = (double) configuration.get("sentimentThresholdBullish");
            
            boolean sentimentIsBullish = averageSentiment >= sentimentThreshold;
            
            // 4. If price momentum confirmation is required, check price trend
            boolean priceMomentumConfirms = true;
            if ((boolean) configuration.get("priceMomentumConfirmation")) {
                priceMomentumConfirms = isPriceTrendBullish(symbol, interval);
            }
            
            // 5. Generate entry signal if both conditions are met
            if (sentimentIsBullish && priceMomentumConfirms) {
                return "BUY";
            }
            
            return "NEUTRAL";
            
        } catch (Exception e) {
            logger.error("Error evaluating entry for {}: {}", symbol, e.getMessage());
            return "NEUTRAL";
        }
    }
    
    @Override
    public boolean evaluateExit(String symbol, String interval) {
        try {
            // 1. Get sentiment data for the symbol
            Map<String, Object> sentimentData = sentimentAnalysisService.getSymbolSentiment(
                    symbol, (int) configuration.get("lookbackPeriodHours"));
            
            if (sentimentData == null || !sentimentData.containsKey("averageSentiment") 
                    || !sentimentData.containsKey("sampleSize")) {
                logger.warn("No sentiment data available for {}", symbol);
                return false;
            }
            
            // 2. Check if we have enough sentiment samples
            int sampleSize = (int) sentimentData.get("sampleSize");
            int minSamples = (int) configuration.get("minSentimentSamples");
            
            if (sampleSize < minSamples) {
                logger.info("Insufficient sentiment samples for {}: {} (need {})", 
                        symbol, sampleSize, minSamples);
                return false;
            }
            
            // 3. Check if sentiment is bearish
            double averageSentiment = (double) sentimentData.get("averageSentiment");
            double sentimentThreshold = (double) configuration.get("sentimentThresholdBearish");
            
            boolean sentimentIsBearish = averageSentiment <= sentimentThreshold;
            
            // 4. If price momentum confirmation is required, check price trend
            boolean priceMomentumConfirms = true;
            if ((boolean) configuration.get("priceMomentumConfirmation")) {
                priceMomentumConfirms = isPriceTrendBearish(symbol, interval);
            }
            
            // 5. Generate exit signal if both conditions are met
            return sentimentIsBearish && priceMomentumConfirms;
            
        } catch (Exception e) {
            logger.error("Error evaluating exit for {}: {}", symbol, e.getMessage());
            return false;
        }
    }
    
    @Override
    public BigDecimal calculatePositionSize(String symbol, BigDecimal accountBalance) {
        // Calculate position size based on risk percentage
        BigDecimal riskPerTradePercent = new BigDecimal(configuration.get("riskPerTradePercent").toString());
        BigDecimal riskAmount = accountBalance.multiply(riskPerTradePercent);
        
        try {
            // Get current price
            List<Double> prices = getHistoricalPrices(symbol, "1h");
            BigDecimal currentPrice = BigDecimal.valueOf(prices.get(prices.size() - 1));
            
            // Calculate stop loss based on configuration
            BigDecimal stopLossPercent = new BigDecimal(configuration.get("stopLossPercent").toString());
            BigDecimal stopLossAmount = currentPrice.multiply(stopLossPercent);
            
            // Calculate position size: risk amount / stop loss amount
            return riskAmount.divide(stopLossAmount, 8, BigDecimal.ROUND_HALF_DOWN);
            
        } catch (Exception e) {
            logger.error("Error calculating position size for {}: {}", symbol, e.getMessage());
            // Return a default conservative position size
            return accountBalance.multiply(new BigDecimal("0.01"));
        }
    }
    
    /**
     * Helper method to check if the price trend is bullish
     */
    private boolean isPriceTrendBullish(String symbol, String interval) throws Exception {
        // Get historical prices
        List<Double> prices = getHistoricalPrices(symbol, interval);
        
        // Use a simple moving average to determine trend
        int fastPeriod = 10;
        int slowPeriod = 20;
        
        if (prices.size() < slowPeriod + 1) {
            return false;
        }
        
        // Calculate fast and slow moving averages
        double fastMA = calculateSMA(prices, fastPeriod);
        double slowMA = calculateSMA(prices, slowPeriod);
        
        // Price trend is bullish if fast MA > slow MA
        return fastMA > slowMA;
    }
    
    /**
     * Helper method to check if the price trend is bearish
     */
    private boolean isPriceTrendBearish(String symbol, String interval) throws Exception {
        // Get historical prices
        List<Double> prices = getHistoricalPrices(symbol, interval);
        
        // Use a simple moving average to determine trend
        int fastPeriod = 10;
        int slowPeriod = 20;
        
        if (prices.size() < slowPeriod + 1) {
            return false;
        }
        
        // Calculate fast and slow moving averages
        double fastMA = calculateSMA(prices, fastPeriod);
        double slowMA = calculateSMA(prices, slowPeriod);
        
        // Price trend is bearish if fast MA < slow MA
        return fastMA < slowMA;
    }
    
    /**
     * Calculate Simple Moving Average
     */
    private double calculateSMA(List<Double> prices, int period) {
        double sum = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            sum += prices.get(i);
        }
        return sum / period;
    }
    
    /**
     * Helper method to retrieve historical price data from the exchange
     */
    private List<Double> getHistoricalPrices(String symbol, String interval) throws Exception {
        // Get klines from MEXC API
        ResponseEntity<String> response = mexcApiClientService.getSpotKlines(
                symbol, interval, null, null, 100);
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<Double> closePrices = new ArrayList<>();
            
            // Parse JSON response (assumes MEXC API format)
            JSONArray klines = new JSONArray(response.getBody());
            
            for (int i = 0; i < klines.length(); i++) {
                JSONArray candle = klines.getJSONArray(i);
                // Close price is typically the 4th element (index 3) in MEXC API kline data
                double closePrice = candle.getDouble(4);
                closePrices.add(closePrice);
            }
            
            return closePrices;
        } else {
            throw new Exception("Failed to retrieve kline data: " + response.getStatusCode());
        }
    }
    
    @Override
    public String getName() {
        double bullishThreshold = (double) configuration.get("sentimentThresholdBullish");
        double bearishThreshold = (double) configuration.get("sentimentThresholdBearish");
        return "News Sentiment Strategy (Thresholds: " + bearishThreshold + "/" + bullishThreshold + ")";
    }
    
    @Override
    public String getDescription() {
        return "This strategy analyzes sentiment from news and social media to identify potential trading opportunities. " +
               "It generates buy signals when sentiment is strongly positive and sell signals when sentiment turns negative. " +
               "The strategy can be configured to require price trend confirmation for additional validation.";
    }
    
    @Override
    public Map<String, Object> getConfiguration() {
        return new HashMap<>(configuration); // Return a copy to prevent external modification
    }
    
    @Override
    public void updateConfiguration(Map<String, Object> newConfiguration) {
        // Update only the provided settings, keep others unchanged
        for (Map.Entry<String, Object> entry : newConfiguration.entrySet()) {
            if (configuration.containsKey(entry.getKey())) {
                configuration.put(entry.getKey(), entry.getValue());
            }
        }
    }
} 