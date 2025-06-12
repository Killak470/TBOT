package com.tradingbot.backend.service.strategy;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Interface for trading strategies that can be implemented to create
 * different trading algorithms and signal generators.
 */
public interface TradingStrategy {
    
    /**
     * Evaluates market conditions to determine if entry criteria are met
     * 
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @param interval The time interval (e.g., "1h", "4h", "1d")
     * @return true if entry conditions are met, false otherwise
     */
    String evaluateEntry(String symbol, String interval);
    
    /**
     * Evaluates market conditions to determine if exit criteria are met
     * 
     * @param symbol The trading pair
     * @param interval The time interval
     * @return true if exit conditions are met, false otherwise
     */
    boolean evaluateExit(String symbol, String interval);
    
    /**
     * Calculates the appropriate position size based on account balance and risk parameters
     * 
     * @param symbol The trading pair
     * @param accountBalance The available account balance
     * @return The calculated position size
     */
    BigDecimal calculatePositionSize(String symbol, BigDecimal accountBalance);
    
    /**
     * Gets the strategy name
     * 
     * @return The name of the strategy
     */
    String getName();
    
    /**
     * Gets the strategy description
     * 
     * @return A description of the strategy and how it works
     */
    String getDescription();
    
    /**
     * Gets the current configuration settings for the strategy
     * 
     * @return A map of parameter names to their current values
     */
    Map<String, Object> getConfiguration();
    
    /**
     * Updates the configuration settings for the strategy
     * 
     * @param configuration A map of parameter names to their new values
     */
    void updateConfiguration(Map<String, Object> configuration);

    /**
     * Calculates the initial stop loss price for a potential trade.
     *
     * @param symbol The trading symbol (e.g., BTCUSDT).
     * @param side The side of the trade ("BUY" or "SELL").
     * @param entryPrice The potential entry price.
     * @param exchange The exchange where the trade would occur.
     * @param interval The interval used for ATR calculation (e.g., "1h", "15m").
     * @return The calculated stop loss price, or null if not applicable/calculable.
     */
    default BigDecimal getInitialStopLossPrice(String symbol, String side, BigDecimal entryPrice, String exchange, String interval) {
        // Default implementation returns null, specific strategies should override.
        return null;
    }

    /**
     * Gets the unique identifier for the strategy. This should be a stable,
     * unique string, suitable for use as a key in a map.
     * @return The unique strategy ID.
     */
    String getId();
} 