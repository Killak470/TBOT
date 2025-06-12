package com.tradingbot.backend.service.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class OrderUtil {
    
    /**
     * Format quantity for Bybit spot orders
     * Bybit has strict requirements for decimal places
     * Spot typically allows 2 decimal places for most pairs
     * 
     * @param quantity The original quantity
     * @return Formatted quantity with appropriate decimal places
     */
    public static BigDecimal formatQuantity(BigDecimal quantity) {
        // Bybit spot typically allows up to 2 decimal places for quantity
        // This may vary by symbol, but 2 is safe for most pairs
        return quantity.setScale(2, RoundingMode.DOWN);
    }
    
    /**
     * Format quantity for Bybit futures orders
     * Futures typically allow more decimal places than spot
     * 
     * @param quantity The original quantity
     * @return Formatted quantity with appropriate decimal places
     */
    public static BigDecimal formatFuturesQuantity(BigDecimal quantity) {
        // Bybit futures typically allows up to 3 decimal places for quantity
        // This provides better precision for futures trading
        return quantity.setScale(3, RoundingMode.DOWN);
    }
    
    /**
     * Format price for Bybit orders
     * 
     * @param price The original price
     * @return Formatted price with appropriate decimal places
     */
    public static BigDecimal formatPrice(BigDecimal price) {
        // Bybit typically allows up to 6 decimal places for price
        return price.setScale(6, RoundingMode.DOWN);
    }

    /**
     * Calculates the number of decimal places (scale) from a tickSize string.
     * For example, a tickSize of "0.01" means 2 decimal places.
     * A tickSize of "0.00001" means 5 decimal places.
     * A tickSize of "1" means 0 decimal places.
     * @param tickSizeStr The tickSize as a string (e.g., "0.01").
     * @return The number of decimal places.
     */
    public static int getPriceScaleFromTickSize(String tickSizeStr) {
        if (tickSizeStr == null || tickSizeStr.isEmpty() || tickSizeStr.equals("0")) {
            // Default or error case, adjust as necessary
            // Consider logging a warning here if this indicates an issue
            return 2; 
        }
        try {
            BigDecimal tickSizeDecimal = new BigDecimal(tickSizeStr);
            if (tickSizeDecimal.compareTo(BigDecimal.ZERO) <= 0) {
                 return 2; // Invalid tick size, fallback
            }
            // If tickSize is 1, 10, etc. (no fractional part), scale is 0 or negative.
            // We are interested in fractional precision, so scale() works directly for values like 0.01, 0.001
            // For 1, 10, the scale would be negative or 0. A scale of 0 is correct (e.g. for JPY pairs sometimes)
            // A negative scale (e.g. for tickSize 10, scale is -1) should also effectively be 0 for price formatting.
            int scale = tickSizeDecimal.stripTrailingZeros().scale();
            return Math.max(0, scale); // Ensure scale is not negative for price formatting
        } catch (NumberFormatException e) {
            // Log error: System.err.println("Invalid tickSizeStr format: " + tickSizeStr);
            return 2; // Default on parsing error
        }
    }
} 