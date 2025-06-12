package com.tradingbot.backend.service.strategy;

import com.tradingbot.backend.service.ExchangeApiClientService;
import com.tradingbot.backend.service.util.TechnicalAnalysisUtil;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A trading strategy based on the Relative Strength Index (RSI).
 * This strategy generates entry signals when RSI enters oversold territory and then exits oversold,
 * and exit signals when RSI enters overbought territory and then exits overbought.
 */
public class RsiStrategy implements TradingStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(RsiStrategy.class);
    
    private final ExchangeApiClientService exchangeApiClient;
    
    // Strategy configuration with default values
    private Map<String, Object> configuration;
    
    public RsiStrategy(ExchangeApiClientService exchangeApiClient) {
        this.exchangeApiClient = exchangeApiClient;
        
        // Initialize default configuration
        this.configuration = new HashMap<>();
        configuration.put("rsiPeriod", 14); // Standard RSI period
        configuration.put("oversoldThreshold", 30.0); // RSI level for oversold condition
        configuration.put("overboughtThreshold", 70.0); // RSI level for overbought condition
        configuration.put("confirmationPeriod", 3); // Number of candles for confirmation
        configuration.put("stopLossPercent", 0.02); // 2% stop loss from entry
        configuration.put("takeProfitPercent", 0.05); // 5% take profit from entry
        configuration.put("riskPerTradePercent", 0.01); // 1% of account per trade
    }
    
    @Override
    public String getId() {
        return "rsi";
    }
    
    @Override
    public String evaluateEntry(String symbol, String interval) {
        try {
            // 1. Get historical kline data
            List<Double> closePrices = getHistoricalPrices(symbol, interval);
            if (closePrices.size() < 50) {  // Need enough data for RSI calculation
                logger.warn("Not enough historical data for {} on {} interval", symbol, interval);
                return "NEUTRAL";
            }
            
            // 2. Get configuration parameters
            int rsiPeriod = (int) configuration.get("rsiPeriod");
            double oversoldThreshold = (double) configuration.get("oversoldThreshold");
            int confirmationPeriod = (int) configuration.get("confirmationPeriod");
            
            // 3. Calculate RSI for the last several periods
            List<Double> rsiValues = new ArrayList<>();
            
            // Calculate RSI for the confirmation period + 1 (need at least 2 values to confirm a reversal)
            for (int i = 0; i < confirmationPeriod + 1; i++) {
                // Create a sublist of close prices up to the appropriate point
                int endIndex = closePrices.size() - i;
                List<Double> subPrices = closePrices.subList(0, endIndex);
                
                // Calculate RSI
                double rsi = TechnicalAnalysisUtil.calculateRSI(subPrices, rsiPeriod);
                rsiValues.add(0, rsi); // Add to the beginning to maintain chronological order
            }
            
            // 4. Check for oversold condition followed by a reversal
            // Entry condition: RSI was below oversold threshold and is now crossing above it
            boolean wasOversold = rsiValues.get(0) < oversoldThreshold;
            boolean isExitingOversold = rsiValues.get(1) > oversoldThreshold;
            
            if (wasOversold && isExitingOversold) {
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
            // 1. Get historical kline data
            List<Double> closePrices = getHistoricalPrices(symbol, interval);
            if (closePrices.size() < 50) {  // Need enough data for RSI calculation
                logger.warn("Not enough historical data for {} on {} interval", symbol, interval);
                return false;
            }
            
            // 2. Get configuration parameters
            int rsiPeriod = (int) configuration.get("rsiPeriod");
            double overboughtThreshold = (double) configuration.get("overboughtThreshold");
            int confirmationPeriod = (int) configuration.get("confirmationPeriod");
            
            // 3. Calculate RSI for the last several periods
            List<Double> rsiValues = new ArrayList<>();
            
            // Calculate RSI for the confirmation period + 1 (need at least 2 values to confirm a reversal)
            for (int i = 0; i < confirmationPeriod + 1; i++) {
                // Create a sublist of close prices up to the appropriate point
                int endIndex = closePrices.size() - i;
                List<Double> subPrices = closePrices.subList(0, endIndex);
                
                // Calculate RSI
                double rsi = TechnicalAnalysisUtil.calculateRSI(subPrices, rsiPeriod);
                rsiValues.add(0, rsi); // Add to the beginning to maintain chronological order
            }
            
            // 4. Check for overbought condition followed by a reversal
            // Exit condition: RSI was above overbought threshold and is now crossing below it
            boolean wasOverbought = rsiValues.get(0) > overboughtThreshold;
            boolean isExitingOverbought = rsiValues.get(1) < overboughtThreshold;
            
            return wasOverbought && isExitingOverbought;
            
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
     * Helper method to retrieve historical price data from the exchange
     */
    private List<Double> getHistoricalPrices(String symbol, String interval) throws Exception {
        // Get klines from the exchange API client
        ResponseEntity<String> response = exchangeApiClient.getKlines(
                symbol, interval, null, null, 200);
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<Double> closePrices = new ArrayList<>();
            
            // Parse JSON response (assumes a common format or the API client handles specifics)
            JSONArray klines = new JSONArray(response.getBody());
            
            for (int i = 0; i < klines.length(); i++) {
                JSONArray candle = klines.getJSONArray(i);
                // Close price is typically the 4th element (index 3) in kline data
                double closePrice = candle.getDouble(4);
                closePrices.add(closePrice);
            }
            
            return closePrices;
        } else {
            throw new Exception("Failed to retrieve kline data: " + response.getStatusCode() + " - " + response.getBody());
        }
    }
    
    @Override
    public String getName() {
        int rsiPeriod = (int) configuration.get("rsiPeriod");
        double oversold = (double) configuration.get("oversoldThreshold");
        double overbought = (double) configuration.get("overboughtThreshold");
        return "RSI Strategy (Period: " + rsiPeriod + ", Levels: " + oversold + "/" + overbought + ")";
    }
    
    @Override
    public String getDescription() {
        return "This strategy uses the Relative Strength Index (RSI) to identify potential reversal points. " +
               "It generates buy signals when RSI exits oversold territory, indicating a potential upward reversal, " +
               "and sell signals when RSI exits overbought territory, indicating a potential downward reversal. " +
               "The strategy works best in ranging markets but may generate premature signals in strong trends.";
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