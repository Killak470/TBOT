package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.tradingbot.backend.model.Candlestick;
import com.fasterxml.jackson.core.JsonProcessingException;

@Service
public class MarketDataService {
    private static final Logger logger = LoggerFactory.getLogger(MarketDataService.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final MexcFuturesApiService mexcFuturesApiService;
    private final BybitApiClientService bybitApiClientService;
    private final BybitFuturesApiService bybitFuturesApiService;
    private final ObjectMapper objectMapper;
    
    @Autowired
    public MarketDataService(
            MexcApiClientService mexcApiClientService,
            MexcFuturesApiService mexcFuturesApiService,
            BybitApiClientService bybitApiClientService,
            BybitFuturesApiService bybitFuturesApiService,
            ObjectMapper objectMapper) {
        this.mexcApiClientService = mexcApiClientService;
        this.mexcFuturesApiService = mexcFuturesApiService;
        this.bybitApiClientService = bybitApiClientService;
        this.bybitFuturesApiService = bybitFuturesApiService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Get current price for a symbol
     */
    public BigDecimal getCurrentPrice(String symbol, String exchange) {
        try {
            // Determine if this is a futures or spot symbol - this logic might be too simplistic.
            // Exchange APIs usually differentiate spot/futures via endpoints or symbol naming conventions.
            // For now, we assume the correct client service (spot vs futures) is called.
            // The `exchange` parameter will guide which main exchange client to use (Bybit vs MEXC).
            
            String cleanSymbol = symbol.replace("BYBIT:", "").replace("MEXC:", ""); // Clean any legacy prefixes

            ResponseEntity<String> response = null;
            JsonNode priceData = null;

            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // For Bybit, assume linear (USDT) futures if symbol ends with USDT, otherwise spot.
                // This is a common convention. Bybit V5 API might have more robust ways to determine category.
                if (cleanSymbol.endsWith("USDT")) { // Simplified check for futures/linear
                    // This assumes bybitFuturesApiService.getLatestPrice takes only symbol
                    // and that `cleanSymbol` is appropriate for futures.
                    // The original code had `bybitFuturesApiService.getLatestPrice(cleanSymbol)`
                    // and `bybitApiClientService.getLatestPrice(cleanSymbol)`
                    // Bybit V5 get tickers: /v5/market/tickers, category=spot|linear|inverse
                    // We should ensure the underlying client methods handle this correctly or pass category.
                    // For now, let's assume the service's getLatestPrice is smart or handles this.
                    // A single getLatestPrice method on BybitApiClientService that handles category might be better.
                    response = bybitApiClientService.getLatestPrice(cleanSymbol, "linear"); // Assuming 'linear' for USDT pairs, adjust if needed
                } else {
                     response = bybitApiClientService.getLatestPrice(cleanSymbol, "spot"); // Assuming 'spot' otherwise
                }
                if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    priceData = objectMapper.readTree(response.getBody());
                    // Bybit V5 ticker response structure: result.list[0].lastPrice
                    if (priceData.hasNonNull("result") && priceData.path("result").hasNonNull("list") && 
                        priceData.path("result").path("list").isArray() && !priceData.path("result").path("list").isEmpty()) {
                        JsonNode tickerInfo = priceData.path("result").path("list").get(0);
                        if (tickerInfo.hasNonNull("lastPrice")) {
                            return new BigDecimal(tickerInfo.get("lastPrice").asText());
                        }
                    } else if (priceData.has("price")) { // Fallback for simpler price structure
                         return new BigDecimal(priceData.get("price").asText());
                    }
                }
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                // MEXC logic: The original call was `mexcApiClientService.getLatestPrice(symbol, exchangeName)`
                // Assuming mexcApiClientService.getLatestPrice can handle both spot and futures if symbol format dictates,
                // or it's primarily for spot and mexcFuturesApiService for futures.
                // For simplicity, if it's a futures symbol pattern (e.g. _PERP), use futures client.
                if (cleanSymbol.contains("_PERP")) { // Example check for MEXC futures
                    response = mexcFuturesApiService.getLatestPrice(cleanSymbol);
                } else {
                    response = mexcApiClientService.getLatestPrice(cleanSymbol, exchange); // Pass exchange to MEXC spot client
                }
                if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    priceData = objectMapper.readTree(response.getBody());
                    // MEXC often has a direct "price" field or an array structure.
                    if (priceData.has("price")) {
                        return new BigDecimal(priceData.get("price").asText());
                    } else if (priceData.isArray() && priceData.size() > 0 && priceData.get(0).has("price")) {
                        return new BigDecimal(priceData.get(0).get("price").asText());
                    }
                }
            } else {
                logger.error("Unsupported exchange: {} for symbol {}", exchange, cleanSymbol);
                return null;
            }
            
            logger.error("Could not get price for {} on exchange {}. Response: {}", cleanSymbol, exchange, response != null ? response.getBody() : "null");
            return null;
        } catch (Exception e) {
            logger.error("Error getting price for {} on exchange {}: {}", symbol, exchange, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get k-line (candlestick) data for a symbol.
     * Parses Bybit's V5 kline structure.
     */
    public List<Candlestick> getKlines(String symbol, String interval, int limit, String exchange) {
        try {
            String cleanSymbol = symbol.replace("BYBIT:", "").replace("MEXC:", "");

            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // Try fetching as a linear contract first, as they are more common for our use case
                ResponseEntity<String> klinesResponse = bybitApiClientService.getKlines(cleanSymbol, interval, "linear", limit);
                
                // Check for specific error indicating "Unknown symbol"
                if (isSymbolNotFoundError(klinesResponse)) {
                    logger.warn("Symbol {} not found on Bybit Linear. Retrying as Spot.", cleanSymbol);
                    klinesResponse = bybitApiClientService.getKlines(cleanSymbol, interval, "spot", limit);
                }
                
                return parseBybitKlineResponse(klinesResponse, cleanSymbol, interval, exchange, limit);

            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                ResponseEntity<String> klinesResponse = mexcApiClientService.getKlines(cleanSymbol, interval, null, null, limit);
                // MEXC parsing would need its own robust implementation
                return parseMexcKlineResponse(klinesResponse, cleanSymbol, interval, exchange, limit);
            } else {
                logger.error("Unsupported exchange '{}' for getKlines.", exchange);
            }
        } catch (Exception e) {
            logger.error("MarketDataService: Exception fetching klines for {}-{} on {}: {}", 
                symbol, interval, exchange, e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    private boolean isSymbolNotFoundError(ResponseEntity<String> response) {
        if (response == null || response.getBody() == null) {
            return false;
        }
        // Bybit V5 often returns retCode 10001 for "request parameter error" which can include unknown symbols
        // Or a more specific error code like 110001 for symbol not found.
        String body = response.getBody();
        return body.contains("\"retCode\":10001") || body.contains("\"retCode\":110001") || body.contains("symbol not found");
    }

    private List<Candlestick> parseBybitKlineResponse(ResponseEntity<String> klinesResponse, String symbol, String interval, String exchange, int limit) throws JsonProcessingException {
        if (klinesResponse.getStatusCode().is2xxSuccessful() && klinesResponse.getBody() != null) {
            JsonNode rootNode = objectMapper.readTree(klinesResponse.getBody());
            JsonNode klineArrayNode = rootNode.path("result").path("list");

            if (klineArrayNode.isMissingNode() || !klineArrayNode.isArray()) {
                logger.error("MarketDataService: Bybit kline data array not found for {}-{}: {}", 
                    symbol, interval, klinesResponse.getBody());
                return Collections.emptyList();
            }

            List<Candlestick> candlesticks = new ArrayList<>();
            for (JsonNode klineData : klineArrayNode) {
                if (klineData.isArray() && klineData.size() >= 6) {
                    candlesticks.add(new Candlestick(
                        klineData.get(0).asLong(),
                        klineData.get(1).asDouble(),
                        klineData.get(2).asDouble(),
                        klineData.get(3).asDouble(),
                        klineData.get(4).asDouble(),
                        klineData.get(5).asDouble(),
                        0
                    ));
                }
            }
            Collections.reverse(candlesticks);
            logger.info("MarketDataService: Parsed {} candlesticks for {}-{} on {}", 
                candlesticks.size(), symbol, interval, exchange);
            return candlesticks;
        } else {
            logger.error("MarketDataService: Failed to parse Bybit klines for {}-{}. Status: {}, Body: {}", 
                symbol, interval, klinesResponse.getStatusCode(), klinesResponse.getBody());
        }
        return Collections.emptyList();
    }

    private List<Candlestick> parseMexcKlineResponse(ResponseEntity<String> klinesResponse, String symbol, String interval, String exchange, int limit) throws JsonProcessingException {
        // Placeholder for MEXC-specific parsing logic
        logger.warn("MEXC Kline parsing not fully implemented. Using placeholder logic.");
        // This would need to be built out similar to the Bybit parser
        return Collections.emptyList();
    }
} 