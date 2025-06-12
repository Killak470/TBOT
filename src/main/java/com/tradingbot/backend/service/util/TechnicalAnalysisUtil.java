package com.tradingbot.backend.service.util;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import com.tradingbot.backend.model.Candlestick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Utility class for technical analysis calculations.
 */
public class TechnicalAnalysisUtil {

    private static final Logger logger = LoggerFactory.getLogger(TechnicalAnalysisUtil.class);

    // Common Fibonacci retracement levels
    private static final double[] FIBONACCI_LEVELS = {0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0};
    
    /**
     * Calculates Fibonacci retracement levels based on high and low prices.
     * 
     * @param high The highest price in the trend
     * @param low The lowest price in the trend
     * @return An array of price levels corresponding to Fibonacci retracement percentages
     */
    public static double[] calculateFibonacciLevels(double high, double low) {
        double difference = high - low;
        double[] levels = new double[FIBONACCI_LEVELS.length];
        
        for (int i = 0; i < FIBONACCI_LEVELS.length; i++) {
            // For an uptrend, retracement levels are calculated from the low
            levels[i] = high - (difference * FIBONACCI_LEVELS[i]);
        }
        
        return levels;
    }
    
    /**
     * Calculates Fibonacci extension levels based on high, low, and pullback prices.
     * 
     * @param high The highest price in the initial trend
     * @param low The lowest price in the initial trend
     * @param pullback The price at the pullback (retracement) level
     * @return An array of price levels corresponding to Fibonacci extension percentages
     */
    public static double[] calculateFibonacciExtensions(double high, double low, double pullback) {
        double difference = high - low;
        
        // Common Fibonacci extension levels: 0%, 61.8%, 100%, 161.8%, 261.8%
        double[] extensionLevels = {0.0, 0.618, 1.0, 1.618, 2.618};
        double[] extensions = new double[extensionLevels.length];
        
        for (int i = 0; i < extensionLevels.length; i++) {
            extensions[i] = pullback + (difference * extensionLevels[i]);
        }
        
        return extensions;
    }
    
    /**
     * Determines if the current price is at or near a Fibonacci support level.
     * 
     * @param price The current price
     * @param fibLevels An array of Fibonacci price levels
     * @param tolerance The percentage tolerance for price proximity (e.g., 0.005 for 0.5%)
     * @return true if the price is near a Fibonacci support level, false otherwise
     */
    public static boolean isAtFibonacciSupport(double price, double[] fibLevels, double tolerance) {
        for (double level : fibLevels) {
            // Calculate how close the price is to a Fibonacci level
            if (level == 0) { // Avoid division by zero if the level itself is zero
                if (Math.abs(price - level) <= tolerance) { // Use absolute tolerance for zero level
                    return true;
                }
                continue;
            }
            double percentDifference = Math.abs(price - level) / Math.abs(level); // Use Math.abs(level) defensively
            
            if (percentDifference <= tolerance) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Determines if the current price is at or near a Fibonacci resistance level.
     * 
     * @param price The current price
     * @param fibLevels An array of Fibonacci price levels
     * @param tolerance The percentage tolerance for price proximity (e.g., 0.005 for 0.5%)
     * @return true if the price is near a Fibonacci resistance level, false otherwise
     */
    public static boolean isAtFibonacciResistance(double price, double[] fibLevels, double tolerance) {
        // Same implementation as support, but semantically different for clarity in trading strategies
        return isAtFibonacciSupport(price, fibLevels, tolerance);
    }
    
    /**
     * Calculates a simple moving average (SMA) from a list of prices.
     * 
     * @param prices List of closing prices (oldest first)
     * @param period The period for the moving average calculation
     * @return The simple moving average value
     */
    public static double calculateSMA(List<Double> prices, int period) {
        if (prices == null || prices.size() < period || period <= 0) {
            logger.warn("SMA: Not enough data or invalid period. Prices size: {}, Period: {}", prices != null ? prices.size() : "null", period);
            return 0.0; // Or throw IllegalArgumentException
        }
        // Calculate SMA for the most recent 'period' prices
        double sum = 0;
        List<Double> relevantPrices = prices.subList(prices.size() - period, prices.size());
        for (double price : relevantPrices) {
            sum += price;
        }
        return sum / period;
    }
    
    /**
     * Calculates an exponential moving average (EMA) from a list of prices.
     * 
     * @param prices List of closing prices (oldest first)
     * @param period The period for the moving average calculation
     * @return The exponential moving average value
     */
    public static double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException("Not enough data points for the requested period");
        }
        
        // Calculate multiplier for weighting the EMA
        double multiplier = 2.0 / (period + 1);
        
        // Calculate initial SMA for the first EMA value
        double ema = calculateSMA(prices.subList(0, period), period);
        
        // Calculate EMA for each subsequent price
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        
        return ema;
    }
    
    /**
     * Calculates the Relative Strength Index (RSI) from a list of prices.
     * 
     * @param prices List of closing prices (oldest first)
     * @param period The period for RSI calculation (typically 14)
     * @return The RSI value
     */
    public static double calculateRSI(List<Double> prices, int period) {
        if (prices == null || prices.size() < period + 1 || period <= 0) {
            logger.warn("RSI: Not enough data or invalid period. Prices size: {}, Period: {}, Required: {}", 
                        prices != null ? prices.size() : "null", period, period + 1);
            return -1.0; // Indicate error or insufficient data
        }

        List<Double> changes = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            changes.add(prices.get(i) - prices.get(i - 1));
        }

        if (changes.size() < period) {
            logger.warn("RSI: Not enough price changes to calculate RSI. Changes: {}, Period: {}", changes.size(), period);
            return -1.0; // Not enough changes for the initial average
        }

        double firstAvgGain = 0;
        double firstAvgLoss = 0;

        // Calculate first average gain and loss
        for (int i = 0; i < period; i++) {
            double change = changes.get(i);
            if (change > 0) {
                firstAvgGain += change;
            } else {
                firstAvgLoss += Math.abs(change);
            }
        }
        firstAvgGain /= period;
        firstAvgLoss /= period;

        double currentAvgGain = firstAvgGain;
        double currentAvgLoss = firstAvgLoss;

        // Smooth subsequent averages
        for (int i = period; i < changes.size(); i++) {
            double change = changes.get(i);
            double gain = 0;
            double loss = 0;
            if (change > 0) {
                gain = change;
            } else {
                loss = Math.abs(change);
            }
            currentAvgGain = (currentAvgGain * (period - 1) + gain) / period;
            currentAvgLoss = (currentAvgLoss * (period - 1) + loss) / period;
        }

        if (currentAvgLoss == 0) {
            return currentAvgGain > 0 ? 100.0 : 50.0; // RSI is 100 if all losses are 0 and gains > 0, else 50 if no movement or only gains
        }

        double rs = currentAvgGain / currentAvgLoss;
        double rsi = 100.0 - (100.0 / (1.0 + rs));
        
        // Ensure RSI is within 0-100 range, though mathematically it should be.
        if (rsi > 100) rsi = 100;
        if (rsi < 0) rsi = 0;

        return rsi;
    }

    /**
     * Calculates the Average True Range (ATR) from lists of high, low, and close prices.
     *
     * @param highPrices List of high prices (oldest first)
     * @param lowPrices List of low prices (oldest first)
     * @param closePrices List of close prices (oldest first)
     * @param period The period for ATR calculation (typically 14)
     * @return The ATR value
     */
    public static double calculateATR(List<Double> highPrices, List<Double> lowPrices, List<Double> closePrices, int period) {
        if (highPrices == null || lowPrices == null || closePrices == null ||
            highPrices.isEmpty() || lowPrices.isEmpty() || closePrices.isEmpty()) {
            logger.warn("ATR: Price lists cannot be null or empty.");
            throw new IllegalArgumentException("Price lists cannot be null or empty.");
        }
        if (highPrices.size() != lowPrices.size() || highPrices.size() != closePrices.size()) {
            logger.warn("ATR: High, low, and close price lists must be of the same size. High: {}, Low: {}, Close: {}", highPrices.size(), lowPrices.size(), closePrices.size());
            throw new IllegalArgumentException("High, low, and close price lists must be of the same size.");
        }
        if (period <= 0) {
            logger.warn("ATR: Period must be a positive integer. Period: {}", period);
            throw new IllegalArgumentException("Period must be a positive integer.");
        }
        // We need 'period' True Range values to calculate the first ATR (which is an SMA of TRs).
        // To get 'period' TRs, we need 'period + 1' kline data points, as the first TR uses the previous close.
        if (highPrices.size() < period + 1) {
            logger.warn("ATR: Not enough data points for ATR. Need at least {} data points for period {}. Got: {}", (period + 1), period, highPrices.size());
            throw new IllegalArgumentException("Not enough data points for ATR. Need at least " + (period + 1) + " data points for period " + period + ".");
        }

        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < highPrices.size(); i++) { // Start from 1 because we need closePrices.get(i-1)
            double highLow = highPrices.get(i) - lowPrices.get(i);
            double highPrevClose = Math.abs(highPrices.get(i) - closePrices.get(i - 1));
            double lowPrevClose = Math.abs(lowPrices.get(i) - closePrices.get(i - 1));
            trueRanges.add(Math.max(highLow, Math.max(highPrevClose, lowPrevClose)));
        }

        if (trueRanges.isEmpty() || trueRanges.size() < period) {
            // This should be caught by the check highPrices.size() < period + 1,
            // as trueRanges.size() will be highPrices.size() - 1.
            // If highPrices.size() is period + 1, trueRanges.size() is period.
            logger.warn("ATR: Not enough true ranges calculated ({}) for period {}. This indicates an issue with input data size relative to period.", trueRanges.size(), period);
            throw new IllegalArgumentException("Not enough true ranges to calculate ATR for the given period. True ranges calculated: " + trueRanges.size() + ", period: " + period);
        }

        // Calculate the first ATR: SMA of the first 'period' TRs
        double firstAtr = 0;
        for (int i = 0; i < period; i++) {
            firstAtr += trueRanges.get(i);
        }
        double currentAtr = firstAtr / period;

        // Apply Wilder's smoothing for subsequent ATR values
        // The loop should start from the 'period'-th element of trueRanges,
        // which corresponds to the (period+1)-th kline.
        for (int i = period; i < trueRanges.size(); i++) {
            currentAtr = (currentAtr * (period - 1) + trueRanges.get(i)) / period;
        }
        
        return currentAtr;
    }

    /**
     * Checks if the latest volume represents a spike compared to the average volume of a preceding period.
     *
     * @param volumes List of volume data (oldest first, latest at the end)
     * @param lookbackPeriod The period over which to calculate the average volume (excluding the latest volume)
     * @param spikeMultiplier The factor by which the latest volume must exceed the average (e.g., 2.0 for 2x)
     * @return true if the latest volume is a spike, false otherwise
     */
    public static boolean isVolumeSpike(List<Double> volumes, int lookbackPeriod, double spikeMultiplier) {
        if (volumes == null || volumes.size() <= lookbackPeriod || lookbackPeriod <= 0 || spikeMultiplier <= 0) {
            return false;
        }
        double latestVolume = volumes.get(volumes.size() - 1);
        // Ensure subList doesn't go out of bounds if lookbackPeriod is same as volumes.size()-1
        List<Double> relevantVolumes = volumes.subList(Math.max(0, volumes.size() - 1 - lookbackPeriod), volumes.size() - 1);

        if(relevantVolumes.isEmpty() && lookbackPeriod > 0) { // If lookback is requested but no prior candles exist
            return latestVolume > 0; // Spike if any volume and no history
        } else if (relevantVolumes.isEmpty()) { // No relevant volumes for average calculation
            return false; 
        }

        double averageVolume = relevantVolumes.stream().mapToDouble(d -> d).average().orElse(0.0);
        
        if (averageVolume == 0 && latestVolume > 0) return true; 
        if (averageVolume == 0) return false; 

        return latestVolume >= (averageVolume * spikeMultiplier);
    }

    // Static inner class for S/R levels
    public static class SupportResistanceLevel {
        private final double level;
        private final String type; // "Support" or "Resistance"
        private int strength; // Number of times price touched or reversed near this level

        public SupportResistanceLevel(double level, String type) {
            this.level = level;
            this.type = type;
            this.strength = 1; // Initialize strength
        }

        public double getLevel() {
            return level;
        }

        public String getType() {
            return type;
        }

        public int getStrength() {
            return strength;
        }

        public void incrementStrength() {
            this.strength++;
        }

        @Override
        public String toString() {
            return type + " at " + String.format("%.4f", level) + " (strength " + strength + ")";
        }
    }

    /**
     * Identifies potential support and resistance levels from candlestick data.
     * This is a basic implementation focusing on swing highs and lows.
     * More advanced methods could use pivot points, clustering, etc.
     * @param candlesticks List of Candlestick data.
     * @param lookback The number of candles to look back to identify local highs/lows.
     * @param tolerance The percentage tolerance to group close levels.
     * @return A list of SupportResistanceLevel objects.
     */
    public static List<SupportResistanceLevel> findSupportResistanceLevels(List<Candlestick> candlesticks, int lookback, double tolerance) {
        List<SupportResistanceLevel> levels = new ArrayList<>();
        int trueLookback = Math.max(1, lookback); // Ensure lookback is at least 1 for meaningful pivots

        if (candlesticks == null || candlesticks.size() < (2 * trueLookback) + 1 ) { // Corrected data size check
            logger.warn("S/R: Not enough candlestick data. Size: {}, Required for lookback {}: {}", 
                        candlesticks != null ? candlesticks.size() : "null", trueLookback, (2 * trueLookback) + 1);
            return levels;
        }

        List<Double> potentialHighs = new ArrayList<>();
        List<Double> potentialLows = new ArrayList<>();

        // Simple pivot identification: a high is higher than 'lookback' candles on each side,
        // a low is lower than 'lookback' candles on each side.
        // If lookback is 0, every candle can be a pivot (not useful).
        // If lookback is 1, it's a 3-candle pattern (middle is highest/lowest).
        for (int i = trueLookback; i < candlesticks.size() - trueLookback; i++) {
            Candlestick current = candlesticks.get(i);
            boolean isLocalHigh = true;
            boolean isLocalLow = true;

            for (int j = 1; j <= trueLookback; j++) {
                if (candlesticks.get(i - j).getHigh() >= current.getHigh() && j>0) isLocalHigh = false; // Strictly higher for pivot
                if (candlesticks.get(i + j).getHigh() > current.getHigh() && j>0) isLocalHigh = false; // If checking strictly higher peaks on both sides.
                
                if (candlesticks.get(i - j).getLow() <= current.getLow() && j>0) isLocalLow = false;  // Strictly lower for pivot
                if (candlesticks.get(i + j).getLow() < current.getLow() && j>0) isLocalLow = false;  // If checking strictly lower troughs on both sides
                
                // Simplified: if any candle in lookback window is higher/lower, it's not a pivot of that type.
                // More precise would be: current.getHigh() > candlesticks.get(i-j).getHigh() AND current.getHigh() > candlesticks.get(i+j).getHigh()
                // Let's use a simpler check: current high must be highest in window [i-trueLookback, i+trueLookback]
            }
            // Re-evaluating local high/low with a window scan approach
            double windowHigh = current.getHigh();
            double windowLow = current.getLow();
            for(int k= i - trueLookback; k <= i + trueLookback; k++){
                if(k==i) continue;
                if(candlesticks.get(k).getHigh() > windowHigh) isLocalHigh = false;
                if(candlesticks.get(k).getLow() < windowLow) isLocalLow = false;
            }

            if (isLocalHigh) {
                 // Check if this high is distinct enough from previous identified highs
                boolean distinct = true;
                for(double h : potentialHighs){
                    if (h != 0) {
                        if(Math.abs(current.getHigh() - h) / Math.abs(h) < tolerance / 2.0) { 
                            distinct = false;
                            break;
                        }
                    } else if (current.getHigh() == 0) { // Both h and current.getHigh() are 0
                        distinct = false;
                        break;
                    }
                }
                if(distinct) potentialHighs.add(current.getHigh());
            }
            if (isLocalLow) {
                boolean distinct = true;
                for(double l : potentialLows){
                     if (l != 0) {
                        if(Math.abs(current.getLow() - l) / Math.abs(l) < tolerance / 2.0) {
                            distinct = false;
                            break;
                        }
                    } else if (current.getLow() == 0) { // Both l and current.getLow() are 0
                        distinct = false;
                        break;
                    }
                }
                if(distinct) potentialLows.add(current.getLow());
            }
        }
        
        Collections.sort(potentialHighs);
        Collections.sort(potentialLows);

        addConsolidatedLevels(levels, potentialHighs, "Resistance", tolerance);
        addConsolidatedLevels(levels, potentialLows, "Support", tolerance);
        
        levels.sort(Comparator.comparingDouble(SupportResistanceLevel::getLevel));
        return levels;
    }
    
    private static void addConsolidatedLevels(List<SupportResistanceLevel> finalLevels, List<Double> uniquePotentialLevels, String type, double tolerance) {
        if (uniquePotentialLevels.isEmpty()) {
            return;
        }

        for (double levelPrice : uniquePotentialLevels) {
            boolean foundGroup = false;
            for (SupportResistanceLevel existingSR : finalLevels) { 
                if (existingSR.getType().equals(type)) {
                    double existingLevelVal = existingSR.getLevel();
                    boolean levelsClose;
                    if (existingLevelVal != 0) {
                        levelsClose = (Math.abs(levelPrice - existingLevelVal) / Math.abs(existingLevelVal)) <= tolerance;
                    } else { // existingLevelVal is 0
                        levelsClose = (Math.abs(levelPrice - existingLevelVal) <= tolerance); // Use absolute tolerance if base is 0
                    }

                    if (levelsClose) {
                        existingSR.incrementStrength(); 
                        // Optional: existingSR.level = (existingSR.level * existingSR.strength + levelPrice) / (existingSR.strength + 1); // Averaging
                        foundGroup = true;
                        break;
                    }
                }
            }
            if (!foundGroup) {
                finalLevels.add(new SupportResistanceLevel(levelPrice, type));
            }
        }
    }

    // --- Fibonacci Methods ---

    public static class FibonacciRetracement {
        public final double level; // e.g., 0.382, 0.5, 0.618
        public final double price;

        public FibonacciRetracement(double level, double price) {
            this.level = level;
            this.price = price;
        }

        @Override
        public String toString() {
            return String.format("Fib %.3f at %.4f", level, price);
        }
    }

    /**
     * Calculates Fibonacci retracement levels.
     * @param highPrice The swing high price.
     * @param lowPrice The swing low price.
     * @param isUptrend true if calculating for an uptrend (retracement down), false for downtrend (retracement up).
     * @return List of FibonacciRetracement objects.
     */
    public static List<FibonacciRetracement> calculateFibonacciRetracements(double highPrice, double lowPrice, boolean isUptrend) {
        List<FibonacciRetracement> fibLevels = new ArrayList<>();
        if (highPrice <= lowPrice) {
            logger.warn("Fibonacci: High price must be greater than low price.");
            return fibLevels;
        }

        double[] fibRatios = {0.236, 0.382, 0.5, 0.618, 0.786, 1.0}; 
        double range = highPrice - lowPrice;

        for (double ratio : fibRatios) {
            double price;
            if (isUptrend) { 
                price = highPrice - (range * ratio);
            } else { 
                price = lowPrice + (range * ratio);
            }
            fibLevels.add(new FibonacciRetracement(ratio, price));
        }
        return fibLevels;
    }

    /**
     * Checks if the current price is near a key Fibonacci level.
     * @param currentPrice The current market price.
     * @param highPrice Swing high for the relevant period.
     * @param lowPrice Swing low for the relevant period.
     * @param isUptrend true if general trend is up (looking for support at Fib levels).
     * @param keyFibRatios List of key Fibonacci levels to check (e.g., [0.618, 0.786]).
     * @param tolerance Percentage tolerance around the Fib level.
     * @return The FibonacciRetracement level if price is near, null otherwise.
     */
    public static FibonacciRetracement isPriceNearFibLevel(double currentPrice, double highPrice, double lowPrice, boolean isUptrend, List<Double> keyFibRatios, double tolerance) {
        if (highPrice <= lowPrice || keyFibRatios == null || keyFibRatios.isEmpty()) {
            return null;
        }
        double range = highPrice - lowPrice;
        for (double fibRatio : keyFibRatios) {
            double fibPriceLevel;
            if (isUptrend) {
                fibPriceLevel = highPrice - (range * fibRatio);
            } else {
                fibPriceLevel = lowPrice + (range * fibRatio);
            }

            if (fibPriceLevel == 0 && currentPrice == 0) return new FibonacciRetracement(fibRatio, fibPriceLevel); // Both zero, consider it near
            if (fibPriceLevel == 0) continue; // Avoid division by zero if fibPriceLevel is 0 but currentPrice is not

            if (Math.abs(currentPrice - fibPriceLevel) / fibPriceLevel <= tolerance) {
                return new FibonacciRetracement(fibRatio, fibPriceLevel);
            }
        }
        return null;
    }
} 