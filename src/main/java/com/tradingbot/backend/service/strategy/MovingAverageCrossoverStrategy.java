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
 * A trading strategy based on Moving Average crossovers.
 * This strategy generates entry signals when a faster moving average crosses above a slower moving average,
 * and exit signals when the faster moving average crosses below the slower moving average.
 */
public class MovingAverageCrossoverStrategy implements TradingStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(MovingAverageCrossoverStrategy.class);
    
    private final ExchangeApiClientService exchangeApiClient;
    
    // Strategy configuration with default values
    private Map<String, Object> configuration;
    
    public MovingAverageCrossoverStrategy(ExchangeApiClientService exchangeApiClient) {
        this.exchangeApiClient = exchangeApiClient;
        
        // Initialize default configuration
        this.configuration = new HashMap<>();
        configuration.put("fastPeriod", 20); // Fast moving average period
        configuration.put("slowPeriod", 50); // Slow moving average period
        configuration.put("maType", "SMA"); // Moving average type (SMA, EMA)
        configuration.put("stopLossPercent", 0.02); // 2% stop loss from entry
        configuration.put("takeProfitPercent", 0.05); // 5% take profit from entry
        configuration.put("riskPerTradePercent", 0.01); // 1% of account per trade
    }
    
    @Override
    public String getId() {
        return "ma_crossover";
    }
    
    @Override
    public String evaluateEntry(String symbol, String interval) {
        try {
            // 1. Get historical kline data
            List<Double> closePrices = getHistoricalPrices(symbol, interval);
            if (closePrices.size() < 100) {  // Need enough data for the slow MA
                logger.warn("Not enough historical data for {} on {} interval", symbol, interval);
                return "NEUTRAL";
            }
            
            // 2. Get configuration parameters
            int fastPeriod = (int) configuration.get("fastPeriod");
            int slowPeriod = (int) configuration.get("slowPeriod");
            String maType = (String) configuration.get("maType");
            
            // 3. Calculate fast and slow moving averages
            List<Double> fastMA = calculateMA(closePrices, fastPeriod, maType);
            List<Double> slowMA = calculateMA(closePrices, slowPeriod, maType);
            
            // 4. Check for crossover (need at least 2 data points to check for crossover)
            if (fastMA.size() < 2 || slowMA.size() < 2) {
                return "NEUTRAL";
            }
            
            // Current values (most recent)
            double currentFastMA = fastMA.get(fastMA.size() - 1);
            double currentSlowMA = slowMA.get(slowMA.size() - 1);
            
            // Previous values
            double previousFastMA = fastMA.get(fastMA.size() - 2);
            double previousSlowMA = slowMA.get(slowMA.size() - 2);
            
            // 5. Check for bullish crossover (fast MA crosses above slow MA)
            boolean currentlyAbove = currentFastMA > currentSlowMA;
            boolean previouslyBelow = previousFastMA < previousSlowMA;
            
            // Entry signal: fast MA crosses above slow MA
            if (currentlyAbove && previouslyBelow) {
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
            if (closePrices.size() < 100) {  // Need enough data for the slow MA
                logger.warn("Not enough historical data for {} on {} interval", symbol, interval);
                return false;
            }
            
            // 2. Get configuration parameters
            int fastPeriod = (int) configuration.get("fastPeriod");
            int slowPeriod = (int) configuration.get("slowPeriod");
            String maType = (String) configuration.get("maType");
            
            // 3. Calculate fast and slow moving averages
            List<Double> fastMA = calculateMA(closePrices, fastPeriod, maType);
            List<Double> slowMA = calculateMA(closePrices, slowPeriod, maType);
            
            // 4. Check for crossover (need at least 2 data points to check for crossover)
            if (fastMA.size() < 2 || slowMA.size() < 2) {
                return false;
            }
            
            // Current values (most recent)
            double currentFastMA = fastMA.get(fastMA.size() - 1);
            double currentSlowMA = slowMA.get(slowMA.size() - 1);
            
            // Previous values
            double previousFastMA = fastMA.get(fastMA.size() - 2);
            double previousSlowMA = slowMA.get(slowMA.size() - 2);
            
            // 5. Check for bearish crossover (fast MA crosses below slow MA)
            boolean currentlyBelow = currentFastMA < currentSlowMA;
            boolean previouslyAbove = previousFastMA > previousSlowMA;
            
            // Exit signal: fast MA crosses below slow MA
            return currentlyBelow && previouslyAbove;
            
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
            List<Double> prices = getHistoricalPrices(symbol, "D");
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
        try {
            return fetchPrices(symbol, interval);
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            // More specific check for Bybit's "Invalid interval" error code -1121
            if (errorMessage != null && errorMessage.contains("Invalid interval") && errorMessage.contains("\"code\":-1121")) {
                logger.warn("Bybit 'Invalid interval' error for {} on interval {}. Retrying with '1d'. Original error: {}", symbol, interval, errorMessage);
                try {
                    return fetchPrices(symbol, "1d"); // Corrected fallback to "1d"
                } catch (Exception retryException) {
                    logger.error("Failed to retrieve kline data for {} on '1d' after Bybit fallback: {}", symbol, retryException.getMessage());
                    throw retryException; // Re-throw if fallback also fails
                }
            } else if (errorMessage != null && errorMessage.contains("Invalid interval")) {
                // Log a generic "Invalid interval" if it doesn't match Bybit's specific code, to investigate further
                logger.warn("Generic 'Invalid interval' error for {} on interval {} (possibly non-Bybit): {}. Not attempting Bybit-specific fallback.", symbol, interval, errorMessage);
                // Decide if a generic fallback or re-throw is appropriate here. For now, re-throw.
                 throw e;
            }
            logger.error("Failed to retrieve kline data for {} on interval {}: {}", symbol, interval, errorMessage);
            throw e; // Re-throw other exceptions
        }
    }
    
    private List<Double> fetchPrices(String symbol, String interval) throws Exception {
        // Get klines from the exchange API
        ResponseEntity<String> response = exchangeApiClient.getKlines(symbol, interval, null, null, 100);
        String responseBody = response.getBody();

        if (response.getStatusCode().is2xxSuccessful() && responseBody != null) {
            logger.debug("Successfully fetched kline data for {} on interval {}. Response body: {}", symbol, interval, responseBody);
            try {
                List<Double> closePrices = new ArrayList<>();
                JSONArray klines;

                // Attempt to parse as a JSONObject first (e.g., Bybit's wrapped response)
                try {
                    org.json.JSONObject responseObject = new org.json.JSONObject(responseBody);
                    if (responseObject.has("result") && responseObject.getJSONObject("result").has("list")) {
                        klines = responseObject.getJSONObject("result").getJSONArray("list");
                        logger.debug("Parsed klines from nested 'result.list' structure for {} on interval {}", symbol, interval);
                    } else if (responseObject.has("data") && responseObject.get("data") instanceof JSONArray) {
                        // Handling for other potential wrapped structures, e.g. { "data": [...] }
                        klines = responseObject.getJSONArray("data");
                        logger.debug("Parsed klines from nested 'data' array structure for {} on interval {}", symbol, interval);
                    }
                    else {
                        // If it's a JSON object but not the expected wrapped structure, try parsing as a direct array
                        logger.debug("Response for {} on interval {} is a JSON object but not a recognized wrapped kline structure. Attempting direct array parse.", symbol, interval);
                        klines = new JSONArray(responseBody);
                    }
                } catch (org.json.JSONException e) {
                    // If it's not a valid JSONObject, assume it's a direct JSONArray
                    logger.debug("Response for {} on interval {} is not a JSONObject, attempting to parse as direct JSONArray. Error: {}", symbol, interval, e.getMessage());
                    klines = new JSONArray(responseBody);
                }
                
                for (int i = 0; i < klines.length(); i++) {
                    JSONArray candle = klines.getJSONArray(i);
                    // Close price is typically the 4th element (index 3) in kline data
                    // For Bybit, it's also index 4 if the structure is result.list
                    // For direct array from some exchanges, it might be index 4
                    double closePrice = candle.getDouble(4); 
                    closePrices.add(closePrice);
                }
                return closePrices;
            } catch (Exception e) {
                logger.error("Error parsing kline JSON for {} on interval {}. Body: {}. Error: {}", 
                             symbol, interval, responseBody, e.getMessage());
                throw new Exception("Error parsing kline JSON: " + e.getMessage() + "; Body: " + responseBody, e);
            }
        } else {
            logger.error("Failed to retrieve kline data for {} on interval {}: Status Code {}, Body: {}", 
                         symbol, interval, response.getStatusCode(), responseBody);
            throw new Exception("Failed to retrieve kline data: " + response.getStatusCode() + " - " + responseBody);
        }
    }
    
    /**
     * Calculate Moving Average
     */
    private List<Double> calculateMA(List<Double> prices, int period, String type) {
        List<Double> maValues = new ArrayList<>();
        
        // We need at least 'period' prices to calculate the first MA value
        if (prices.size() < period) {
            return maValues;
        }
        
        // Calculate MA for each possible position
        for (int i = period; i <= prices.size(); i++) {
            List<Double> subset = prices.subList(i - period, i);
            double maValue;
            
            if ("EMA".equals(type)) {
                maValue = TechnicalAnalysisUtil.calculateEMA(subset, period);
            } else {
                maValue = TechnicalAnalysisUtil.calculateSMA(subset, period);
            }
            
            maValues.add(maValue);
        }
        
        return maValues;
    }
    
    @Override
    public String getName() {
        int fastPeriod = (int) configuration.get("fastPeriod");
        int slowPeriod = (int) configuration.get("slowPeriod");
        String maType = (String) configuration.get("maType");
        return String.format("%s Crossover Strategy (%d/%d)", maType, fastPeriod, slowPeriod);
    }
    
    @Override
    public String getDescription() {
        return "This strategy uses moving average crossovers to identify trend changes. " +
               "It generates buy signals when the faster moving average crosses above the slower moving average, " +
               "indicating a potential upward trend, and sell signals when the faster moving average crosses below " +
               "the slower moving average, indicating a potential downward trend.";
    }
    
    @Override
    public Map<String, Object> getConfiguration() {
        return new HashMap<>(configuration);
    }
    
    @Override
    public void updateConfiguration(Map<String, Object> newConfig) {
        // Update only valid configuration parameters
        for (Map.Entry<String, Object> entry : newConfig.entrySet()) {
            if (configuration.containsKey(entry.getKey())) {
                configuration.put(entry.getKey(), entry.getValue());
            }
        }
    }
} 