package com.tradingbot.backend.service.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
// import java.util.Arrays; // Uncomment if using the example for SOME_MEXC_COIN

public class ExchangeUtil {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeUtil.class);

    /**
     * Parses the price from a JsonNode response based on the exchange.
     * This method centralizes the logic for handling different API response structures.
     *
     * @param rootNode The root JsonNode of the API response.
     * @param exchange The name of the exchange (e.g., "BYBIT", "MEXC").
     * @param symbol The trading symbol, for logging purposes.
     * @return The parsed price as BigDecimal, or null if parsing fails or price is not found.
     */
    public static BigDecimal parsePriceFromResponse(JsonNode rootNode, String exchange, String symbol) {
        if (rootNode == null) {
            logger.warn("Cannot parse price for {} on {}: rootNode is null", symbol, exchange);
            return null;
        }

        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // Bybit V5 API /v5/market/tickers for a single symbol: result.list[0].lastPrice
                if (rootNode.hasNonNull("result") &&
                    rootNode.get("result").hasNonNull("list") &&
                    rootNode.get("result").get("list").isArray() &&
                    !rootNode.get("result").get("list").isEmpty()) {
                    JsonNode tickerInfo = rootNode.get("result").get("list").get(0);
                    if (tickerInfo.hasNonNull("lastPrice")) {
                        String priceStr = tickerInfo.get("lastPrice").asText();
                        if (priceStr != null && !priceStr.isEmpty()) {
                            return new BigDecimal(priceStr);
                        }
                    }
                }
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                // MEXC responses can vary. Examples:
                // 1. Spot ticker array: root.get(0).path("lastPrice")
                // 2. Direct price object: root.path("price")
                // 3. Contract price: root.path("data").path("lastPrice")
                if (rootNode.isArray() && !rootNode.isEmpty() && rootNode.get(0).hasNonNull("lastPrice")) {
                    String priceStr = rootNode.get(0).get("lastPrice").asText();
                    if (priceStr != null && !priceStr.isEmpty()) {
                        return new BigDecimal(priceStr);
                    }
                } else if (rootNode.hasNonNull("price")) {
                    String priceStr = rootNode.get("price").asText();
                    if (priceStr != null && !priceStr.isEmpty()) {
                        return new BigDecimal(priceStr);
                    }
                } else if (rootNode.hasNonNull("data") && rootNode.get("data").hasNonNull("lastPrice")) {
                    String priceStr = rootNode.get("data").get("lastPrice").asText();
                    if (priceStr != null && !priceStr.isEmpty()) {
                        return new BigDecimal(priceStr);
                    }
                }
            }
            logger.warn("Could not parse price for symbol {} on exchange {} from response: {}. Structure not recognized.", symbol, exchange, rootNode.toString());
            return null;
        } catch (Exception e) {
            logger.error("Exception parsing price for symbol {} on exchange {} from response: {}. Error: {}", symbol, exchange, rootNode.toString(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Determines the exchange for a given symbol based on common patterns or a predefined mapping.
     * This is a simplified implementation and might need to be made more robust or configurable.
     *
     * @param symbol The trading symbol (e.g., BTCUSDT, ETH_PERP).
     * @return The inferred exchange name (e.g., "BYBIT", "MEXC") or null if not determinable.
     */
    public static String determineExchangeForSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            logger.warn("Cannot determine exchange for null or empty symbol.");
            return null;
        }
        // Example: Simple rule-based determination
        // More sophisticated logic might involve checking a database, configuration, or symbol formatting rules.
        if (symbol.endsWith("_PERP")) { // Assuming _PERP is specific to MEXC futures in this system
            logger.debug("Determined exchange for symbol {} as MEXC (due to _PERP suffix)", symbol);
            return "MEXC";
        } else if (symbol.endsWith("USDT")) { // Common for many, default to Bybit for this example for now.
            logger.debug("Determined exchange for symbol {} as BYBIT (default for USDT pairs unless overridden by other rules)", symbol);
            return "BYBIT";
        }
        // Add more rules as needed, e.g., for specific prefixes or other patterns.
        // For example, if you have a list of known MEXC-only altcoins without specific suffixes:
        // if (Arrays.asList("SOME_MEXC_COIN").contains(symbol)) { return "MEXC"; }

        logger.warn("Could not confidently determine exchange for symbol: {}. Returning null. Consider adding explicit mapping.", symbol);
        return null; // Fallback if no rule matches, forces explicit handling or error downstream.
    }
} 