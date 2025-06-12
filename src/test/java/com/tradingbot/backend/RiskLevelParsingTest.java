package com.tradingbot.backend;

import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class RiskLevelParsingTest {
    
    public static void main(String[] args) {
        // Test AI analysis samples
        String[] aiAnalysisSamples = {
            "The current trend suggests a BUY signal. Consider setting stop loss at $22.50 and take profit around $25.00 for optimal risk management.",
            "Strong bullish momentum detected. SL: 22.50, TP: 25.00. Risk/reward ratio looks favorable.",
            "Entry point identified. Stop-loss around 22.50, target at 25.00.",
            "Buy signal confirmed. Risk 2% with stop loss, aim for 3% profit target.",
            "Technical analysis suggests going long with a 2.5% stop loss and 5% take profit.",
            "SELL signal generated. Stop loss: $25.00, Take profit: $22.00"
        };
        
        BigDecimal currentPrice = new BigDecimal("23.50");
        
        System.out.println("Testing AI Analysis Parsing for Risk Levels");
        System.out.println("Current Price: $" + currentPrice);
        System.out.println("==========================================\n");
        
        for (String analysis : aiAnalysisSamples) {
            System.out.println("AI Analysis: " + analysis);
            Map<String, BigDecimal> levels = parseRiskLevelsFromAI(analysis, currentPrice);
            
            if (!levels.isEmpty()) {
                System.out.println("Parsed Levels:");
                if (levels.containsKey("stopLoss")) {
                    System.out.println("  Stop Loss: $" + levels.get("stopLoss"));
                }
                if (levels.containsKey("takeProfit")) {
                    System.out.println("  Take Profit: $" + levels.get("takeProfit"));
                }
            } else {
                System.out.println("  No levels parsed");
            }
            System.out.println();
        }
    }
    
    private static Map<String, BigDecimal> parseRiskLevelsFromAI(String aiAnalysis, BigDecimal currentPrice) {
        Map<String, BigDecimal> levels = new HashMap<>();
        
        try {
            // Common patterns in AI analysis for price levels
            String slPattern = "(?i)(?:stop[\\s-]*loss|sl)\\s*(?:at|:|around|near)?\\s*\\$?([0-9]+\\.?[0-9]*)";
            String tpPattern = "(?i)(?:take[\\s-]*profit|target|tp)\\s*(?:at|:|around|near)?\\s*\\$?([0-9]+\\.?[0-9]*)";
            
            // Try to find stop loss
            Pattern slRegex = Pattern.compile(slPattern);
            Matcher slMatcher = slRegex.matcher(aiAnalysis);
            if (slMatcher.find()) {
                try {
                    BigDecimal stopLoss = new BigDecimal(slMatcher.group(1));
                    // Validate the stop loss is reasonable (within 20% of current price)
                    BigDecimal maxDeviation = currentPrice.multiply(BigDecimal.valueOf(0.20));
                    if (stopLoss.subtract(currentPrice).abs().compareTo(maxDeviation) <= 0) {
                        levels.put("stopLoss", stopLoss);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Could not parse stop loss value");
                }
            }
            
            // Try to find take profit
            Matcher tpMatcher = Pattern.compile(tpPattern).matcher(aiAnalysis);
            if (tpMatcher.find()) {
                try {
                    BigDecimal takeProfit = new BigDecimal(tpMatcher.group(1));
                    // Validate the take profit is reasonable (within 30% of current price)
                    BigDecimal maxDeviation = currentPrice.multiply(BigDecimal.valueOf(0.30));
                    if (takeProfit.subtract(currentPrice).abs().compareTo(maxDeviation) <= 0) {
                        levels.put("takeProfit", takeProfit);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Could not parse take profit value");
                }
            }
            
            // Also look for percentage-based recommendations
            String slPercentPattern = "(?i)stop[\\s-]*loss.*?([0-9]+\\.?[0-9]*)\\s*%";
            String tpPercentPattern = "(?i)(?:take[\\s-]*profit|target).*?([0-9]+\\.?[0-9]*)\\s*%";
            
            if (!levels.containsKey("stopLoss")) {
                Matcher slPercentMatcher = Pattern.compile(slPercentPattern).matcher(aiAnalysis);
                if (slPercentMatcher.find()) {
                    try {
                        BigDecimal percentage = new BigDecimal(slPercentMatcher.group(1)).divide(BigDecimal.valueOf(100));
                        // Determine if it's for a buy or sell signal based on context
                        if (aiAnalysis.toLowerCase().contains("buy") || aiAnalysis.toLowerCase().contains("long")) {
                            levels.put("stopLoss", currentPrice.multiply(BigDecimal.ONE.subtract(percentage)));
                        } else {
                            levels.put("stopLoss", currentPrice.multiply(BigDecimal.ONE.add(percentage)));
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Could not parse stop loss percentage");
                    }
                }
            }
            
            if (!levels.containsKey("takeProfit")) {
                Matcher tpPercentMatcher = Pattern.compile(tpPercentPattern).matcher(aiAnalysis);
                if (tpPercentMatcher.find()) {
                    try {
                        BigDecimal percentage = new BigDecimal(tpPercentMatcher.group(1)).divide(BigDecimal.valueOf(100));
                        // Determine if it's for a buy or sell signal based on context
                        if (aiAnalysis.toLowerCase().contains("buy") || aiAnalysis.toLowerCase().contains("long")) {
                            levels.put("takeProfit", currentPrice.multiply(BigDecimal.ONE.add(percentage)));
                        } else {
                            levels.put("takeProfit", currentPrice.multiply(BigDecimal.ONE.subtract(percentage)));
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Could not parse take profit percentage");
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("Error parsing risk levels: " + e.getMessage());
        }
        
        return levels;
    }
} 