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
 * A trading strategy based on Fibonacci retracements and extensions.
 * This strategy identifies trend reversals at key Fibonacci levels and
 * uses confirmation from other technical indicators.
 */
public class FibonacciStrategy implements TradingStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(FibonacciStrategy.class);
    
    private final ExchangeApiClientService exchangeApiClient;
    
    // Strategy configuration with default values
    private Map<String, Object> configuration;
    
    public FibonacciStrategy(ExchangeApiClientService exchangeApiClient) {
        this.exchangeApiClient = exchangeApiClient;
        
        // Initialize default configuration
        this.configuration = new HashMap<>();
        configuration.put("fibLevelTolerance", 0.005); // 0.5% tolerance for price near fib levels
        configuration.put("rsiPeriod", 14); // Standard RSI period
        configuration.put("rsiOversold", 30.0); // RSI level for oversold condition
        configuration.put("rsiOverbought", 70.0); // RSI level for overbought condition
        configuration.put("stopLossPercent", 0.02); // 2% stop loss from entry
        configuration.put("takeProfitPercent", 0.05); // 5% take profit from entry
        configuration.put("riskPerTradePercent", 0.01); // 1% of account per trade
    }
    
    @Override
    public String getId() {
        return "fibonacci";
    }
    
    @Override
    public String evaluateEntry(String symbol, String interval) {
        try {
            // 1. Get historical kline data
            List<Double> closePrices = getHistoricalPrices(symbol, interval);
            if (closePrices.size() < 50) {
                logger.warn("Not enough historical data for {} on {} interval", symbol, interval);
                return "NEUTRAL";
            }
            
            // 2. Identify recent high and low for Fibonacci calculations
            double high = Double.MIN_VALUE;
            double low = Double.MAX_VALUE;
            
            // Use the last 30 candles to identify high and low
            for (int i = closePrices.size() - 30; i < closePrices.size(); i++) {
                double price = closePrices.get(i);
                if (price > high) high = price;
                if (price < low) low = price;
            }
            
            // 3. Calculate Fibonacci retracement levels
            double[] fibLevels = TechnicalAnalysisUtil.calculateFibonacciLevels(high, low);
            
            // 4. Get current price (last closing price)
            double currentPrice = closePrices.get(closePrices.size() - 1);
            
            // 5. Check if price is at a Fibonacci support level
            double tolerance = (double) configuration.get("fibLevelTolerance");
            boolean atFibSupport = TechnicalAnalysisUtil.isAtFibonacciSupport(currentPrice, fibLevels, tolerance);
            
            // 6. Calculate RSI for confirmation
            int rsiPeriod = (int) configuration.get("rsiPeriod");
            double rsi = TechnicalAnalysisUtil.calculateRSI(closePrices, rsiPeriod);
            double rsiOversold = (double) configuration.get("rsiOversold");
            
            // 7. Entry condition: Price at Fibonacci support AND RSI oversold (for long entries)
            if (atFibSupport && rsi <= rsiOversold) {
                return "BUY";
            }
            
        } catch (Exception e) {
            logger.error("Error evaluating entry for {}: {}", symbol, e.getMessage());
        }
        return "NEUTRAL";
    }
    
    @Override
    public boolean evaluateExit(String symbol, String interval) {
        try {
            // 1. Get historical kline data
            List<Double> closePrices = getHistoricalPrices(symbol, interval);
            if (closePrices.size() < 50) {
                logger.warn("Not enough historical data for {} on {} interval", symbol, interval);
                return false;
            }
            
            // 2. Identify recent high and low for Fibonacci calculations
            double high = Double.MIN_VALUE;
            double low = Double.MAX_VALUE;
            
            // Use the last 30 candles to identify high and low
            for (int i = closePrices.size() - 30; i < closePrices.size(); i++) {
                double price = closePrices.get(i);
                if (price > high) high = price;
                if (price < low) low = price;
            }
            
            // 3. Calculate Fibonacci retracement levels
            double[] fibLevels = TechnicalAnalysisUtil.calculateFibonacciLevels(high, low);
            
            // 4. Get current price (last closing price)
            double currentPrice = closePrices.get(closePrices.size() - 1);
            
            // 5. Check if price is at a Fibonacci resistance level
            double tolerance = (double) configuration.get("fibLevelTolerance");
            boolean atFibResistance = TechnicalAnalysisUtil.isAtFibonacciResistance(currentPrice, fibLevels, tolerance);
            
            // 6. Calculate RSI for confirmation
            int rsiPeriod = (int) configuration.get("rsiPeriod");
            double rsi = TechnicalAnalysisUtil.calculateRSI(closePrices, rsiPeriod);
            double rsiOverbought = (double) configuration.get("rsiOverbought");
            
            // 7. Exit condition: Price at Fibonacci resistance AND RSI overbought
            return atFibResistance && rsi >= rsiOverbought;
            
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
        // Convert interval for API call if needed
        
        // Get klines from the exchange API client
        ResponseEntity<String> response = exchangeApiClient.getKlines(
                symbol, interval, null, null, 100);
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            List<Double> closePrices = new ArrayList<>();
            
            // Parse JSON response (assumes a common format or the API client handles specifics)
            // Note: Parsing logic might need to be more robust if kline structures differ significantly
            // between exchanges beyond what the API client abstracts.
            JSONArray klines = new JSONArray(response.getBody());
            
            for (int i = 0; i < klines.length(); i++) {
                JSONArray candle = klines.getJSONArray(i);
                // Close price is typically the 4th element (index 3) in kline data
                // This assumption needs to hold true for all exchange clients used.
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
        return "Fibonacci Retracement Strategy";
    }
    
    @Override
    public String getDescription() {
        return "This strategy identifies potential reversal zones using Fibonacci retracement levels " +
               "and confirms entries/exits with RSI to avoid false signals. It works best in trending " +
               "markets where price respects key Fibonacci levels.";
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