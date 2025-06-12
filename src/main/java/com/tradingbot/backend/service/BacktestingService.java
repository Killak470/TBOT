package com.tradingbot.backend.service;

import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.service.strategy.TradingStrategy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service for backtesting trading strategies against historical data
 */
@Service
public class BacktestingService {

    private static final Logger logger = LoggerFactory.getLogger(BacktestingService.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final SentimentAnalysisService sentimentAnalysisService;
    
    public BacktestingService(MexcApiClientService mexcApiClientService, SentimentAnalysisService sentimentAnalysisService) {
        this.mexcApiClientService = mexcApiClientService;
        this.sentimentAnalysisService = sentimentAnalysisService;
    }
    
    /**
     * Run a backtest of a trading strategy over a historical period
     * 
     * @param strategy The trading strategy to test
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @param interval The time interval for analysis (e.g., "1d", "4h")
     * @param startTime The start timestamp (Unix time in ms)
     * @param endTime The end timestamp (Unix time in ms)
     * @param initialCapital The initial capital to simulate with
     * @return Map containing backtest results
     */
    public Map<String, Object> runBacktest(
            TradingStrategy strategy, 
            String symbol, 
            String interval, 
            Long startTime, 
            Long endTime, 
            double initialCapital) {
        
        logger.info("Running backtest for {} with {} strategy from {} to {}", 
                symbol, strategy.getName(), startTime, endTime);
        
        // Get historical price data for the period
        List<Map<String, Object>> historicalData = fetchHistoricalData(symbol, interval, startTime, endTime);
        
        if (historicalData.isEmpty()) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "Failed to fetch historical data for " + symbol);
            return errorResult;
        }
        
        // Backtest results storage
        Map<String, Object> results = new HashMap<>();
        List<Map<String, Object>> trades = new ArrayList<>();
        
        double currentCapital = initialCapital;
        double positionSize = 0;
        double entryPrice = 0;
        boolean inPosition = false;
        
        // Loop through each historical data point
        for (int i = 30; i < historicalData.size(); i++) { // Start from index 30 to have enough history for indicators
            Map<String, Object> candle = historicalData.get(i);
            
            double currentPrice = (double) candle.get("close");
            long timestamp = (long) candle.get("timestamp");
            
            // Create a subset of data to evaluate (current point and previous N candles)
            List<Map<String, Object>> dataWindow = historicalData.subList(0, i + 1);
            
            // Evaluate entry signal
            if (!inPosition && evaluateEntrySignal(strategy, symbol, interval, dataWindow)) {
                // Enter position
                entryPrice = currentPrice;
                // Calculate position size (how much to buy)
                positionSize = strategy.calculatePositionSize(symbol, BigDecimal.valueOf(currentCapital))
                    .divide(BigDecimal.valueOf(currentPrice), 8, BigDecimal.ROUND_HALF_DOWN)
                    .doubleValue();
                inPosition = true;
                
                // Record trade entry
                Map<String, Object> trade = new HashMap<>();
                trade.put("type", "ENTRY");
                trade.put("timestamp", timestamp);
                trade.put("price", entryPrice);
                trade.put("positionSize", positionSize);
                trade.put("capitalBefore", currentCapital);
                
                // Update capital (subtract used amount)
                currentCapital -= (positionSize * entryPrice);
                
                trade.put("capitalAfter", currentCapital);
                trades.add(trade);
                
                logger.debug("Backtest: Entered position at {} with size {}", entryPrice, positionSize);
            }
            
            // Evaluate exit signal
            else if (inPosition && evaluateExitSignal(strategy, symbol, interval, dataWindow)) {
                // Exit position
                double exitPrice = currentPrice;
                double pnl = positionSize * (exitPrice - entryPrice);
                
                // Record trade exit
                Map<String, Object> trade = new HashMap<>();
                trade.put("type", "EXIT");
                trade.put("timestamp", timestamp);
                trade.put("price", exitPrice);
                trade.put("positionSize", positionSize);
                trade.put("pnl", pnl);
                trade.put("pnlPercent", (exitPrice / entryPrice - 1) * 100);
                trade.put("capitalBefore", currentCapital);
                
                // Update capital
                currentCapital += (positionSize * exitPrice);
                
                trade.put("capitalAfter", currentCapital);
                trades.add(trade);
                
                // Reset position
                inPosition = false;
                positionSize = 0;
                
                logger.debug("Backtest: Exited position at {} with PnL {}", exitPrice, pnl);
            }
        }
        
        // If still in a position at the end, close it at the last price
        if (inPosition) {
            Map<String, Object> lastCandle = historicalData.get(historicalData.size() - 1);
            double lastPrice = (double) lastCandle.get("close");
            long lastTimestamp = (long) lastCandle.get("timestamp");
            
            double pnl = positionSize * (lastPrice - entryPrice);
            
            // Record forced exit at end of test
            Map<String, Object> trade = new HashMap<>();
            trade.put("type", "FORCE_EXIT");
            trade.put("timestamp", lastTimestamp);
            trade.put("price", lastPrice);
            trade.put("positionSize", positionSize);
            trade.put("pnl", pnl);
            trade.put("pnlPercent", (lastPrice / entryPrice - 1) * 100);
            trade.put("capitalBefore", currentCapital);
            
            // Update capital
            currentCapital += (positionSize * lastPrice);
            
            trade.put("capitalAfter", currentCapital);
            trades.add(trade);
        }
        
        // Calculate performance metrics
        double totalReturn = currentCapital - initialCapital;
        double percentReturn = (totalReturn / initialCapital) * 100;
        
        // Count winning and losing trades
        int totalTrades = 0;
        int winningTrades = 0;
        double totalProfit = 0;
        double totalLoss = 0;
        
        for (int i = 0; i < trades.size(); i++) {
            if (trades.get(i).get("type").equals("EXIT") || trades.get(i).get("type").equals("FORCE_EXIT")) {
                totalTrades++;
                double tradePnl = (double) trades.get(i).get("pnl");
                
                if (tradePnl > 0) {
                    winningTrades++;
                    totalProfit += tradePnl;
                } else {
                    totalLoss += Math.abs(tradePnl);
                }
            }
        }
        
        // Populate results
        results.put("success", true);
        results.put("strategy", strategy.getName());
        results.put("symbol", symbol);
        results.put("interval", interval);
        results.put("startTime", startTime);
        results.put("endTime", endTime);
        results.put("initialCapital", initialCapital);
        results.put("finalCapital", currentCapital);
        results.put("totalReturn", totalReturn);
        results.put("percentReturn", percentReturn);
        results.put("totalTrades", totalTrades);
        results.put("winningTrades", winningTrades);
        results.put("winRate", totalTrades > 0 ? ((double) winningTrades / totalTrades) * 100 : 0);
        results.put("profitFactor", totalLoss > 0 ? totalProfit / totalLoss : totalProfit > 0 ? Double.POSITIVE_INFINITY : 0);
        results.put("trades", trades);
        
        return results;
    }
    
    /**
     * Fetch historical price data for a given symbol and interval
     * 
     * @param symbol The trading pair
     * @param interval The time interval for candles
     * @param startTime The start timestamp
     * @param endTime The end timestamp
     * @return List of price candles
     */
    private List<Map<String, Object>> fetchHistoricalData(String symbol, String interval, Long startTime, Long endTime) {
        try {
            ResponseEntity<String> response = mexcApiClientService.getSpotKlines(symbol, interval, startTime, endTime, 1000);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> candles = new ArrayList<>();
                
                JSONArray klinesArray = new JSONArray(response.getBody());
                
                for (int i = 0; i < klinesArray.length(); i++) {
                    JSONArray candle = klinesArray.getJSONArray(i);
                    
                    Map<String, Object> candleMap = new HashMap<>();
                    // MEXC API kline format: [time, open, high, low, close, volume, ...]
                    candleMap.put("timestamp", candle.getLong(0));
                    candleMap.put("open", candle.getDouble(1));
                    candleMap.put("high", candle.getDouble(2));
                    candleMap.put("low", candle.getDouble(3));
                    candleMap.put("close", candle.getDouble(4));
                    candleMap.put("volume", candle.getDouble(5));
                    
                    candles.add(candleMap);
                }
                
                return candles;
            }
        } catch (Exception e) {
            logger.error("Error fetching historical data: {}", e.getMessage());
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Evaluate if an entry signal is generated based on the strategy and historical data
     * 
     * @param strategy The trading strategy
     * @param symbol The trading pair
     * @param interval The time interval
     * @param dataWindow The window of historical data to evaluate
     * @return true if entry signal is generated, false otherwise
     */
    private boolean evaluateEntrySignal(TradingStrategy strategy, String symbol, String interval, List<Map<String, Object>> dataWindow) {
        // This is a simplified implementation
        // In a real system, we would need to adapt the historical data to the format expected by the strategy
        
        // For now, we'll just delegate to the strategy's evaluateEntry method
        String signal = strategy.evaluateEntry(symbol, interval);
        return "STRONG_BUY".equalsIgnoreCase(signal) || "BUY".equalsIgnoreCase(signal);
    }
    
    /**
     * Evaluate if an exit signal is generated based on the strategy and historical data
     * 
     * @param strategy The trading strategy
     * @param symbol The trading pair
     * @param interval The time interval
     * @param dataWindow The window of historical data to evaluate
     * @return true if exit signal is generated, false otherwise
     */
    private boolean evaluateExitSignal(TradingStrategy strategy, String symbol, String interval, List<Map<String, Object>> dataWindow) {
        // This is a simplified implementation
        // In a real system, we would need to adapt the historical data to the format expected by the strategy
        
        // For now, we'll just delegate to the strategy's evaluateExit method
        return strategy.evaluateExit(symbol, interval);
    }
} 