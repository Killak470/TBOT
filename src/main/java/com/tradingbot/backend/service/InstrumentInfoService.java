package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.service.BybitApiClientService; // Corrected import path
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.math.BigDecimal;

@Service
public class InstrumentInfoService {

    private static final Logger logger = LoggerFactory.getLogger(InstrumentInfoService.class);

    private final BybitApiClientService bybitApiClientService;
    private final ObjectMapper objectMapper;

    // Cache for tick sizes: Key format "category:symbol", Value "tickSizeString"
    private final LoadingCache<String, String> tickSizeCache;

    // Cache for quantity rules: Key format "category:symbol", Value InstrumentQuantityRules
    private final LoadingCache<String, InstrumentQuantityRules> quantityRulesCache;

    @Autowired
    public InstrumentInfoService(BybitApiClientService bybitApiClientService, ObjectMapper objectMapper) {
        this.bybitApiClientService = bybitApiClientService;
        this.objectMapper = objectMapper;

        this.tickSizeCache = CacheBuilder.newBuilder()
                .maximumSize(1000) // Max number of entries
                .expireAfterWrite(24, TimeUnit.HOURS) // Cache entries for 24 hours
                .build(new CacheLoader<String, String>() {
                    @Override
                    public String load(String key) throws Exception {
                        String[] parts = key.split(":", 2);
                        if (parts.length != 2) {
                            throw new IllegalArgumentException("Invalid cache key format. Expected 'category:symbol'.");
                        }
                        return fetchTickSizeFromApi(parts[0], parts[1]);
                    }
                });

        this.quantityRulesCache = CacheBuilder.newBuilder()
                .maximumSize(1000) // Max number of entries
                .expireAfterWrite(24, TimeUnit.HOURS) // Cache entries for 24 hours
                .build(new CacheLoader<String, InstrumentQuantityRules>() {
                    @Override
                    public InstrumentQuantityRules load(String key) throws Exception {
                        String[] parts = key.split(":", 2);
                        if (parts.length != 2) {
                            throw new IllegalArgumentException("Invalid cache key format for quantity rules. Expected 'category:symbol'.");
                        }
                        return fetchQuantityRulesFromApi(parts[0], parts[1]);
                    }
                });
    }

    private String fetchTickSizeFromApi(String category, String symbol) {
        logger.info("Fetching instrument info for {} in category {} from API.", symbol, category);
        try {
            ResponseEntity<String> response = bybitApiClientService.getInstrumentsInfo(category, symbol);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                if (rootNode.has("result") && rootNode.get("result").has("list")) {
                    JsonNode listNode = rootNode.get("result").get("list");
                    if (listNode.isArray() && listNode.size() > 0) {
                        // Assuming the first item is the correct one if a specific symbol was queried
                        JsonNode instrumentInfo = listNode.get(0); 
                        if (instrumentInfo.has("priceFilter") && instrumentInfo.get("priceFilter").has("tickSize")) {
                            String tickSize = instrumentInfo.get("priceFilter").get("tickSize").asText();
                            if (tickSize != null && !tickSize.isEmpty() && !tickSize.equals("0")) { // "0" might mean not applicable or error
                                logger.info("Fetched tickSize for {}:{}: {}", category, symbol, tickSize);
                                return tickSize;
                            } else {
                                logger.warn("Received empty or zero tickSize for {}:{}. Full priceFilter: {}", category, symbol, instrumentInfo.get("priceFilter").toString());
                            }
                        } else {
                             logger.warn("tickSize not found in priceFilter for {}:{}. Full instrumentInfo: {}", category, symbol, instrumentInfo.toString());
                        }
                    } else {
                        logger.warn("Instrument list is empty for {}:{} in API response.", category, symbol);
                    }
                } else {
                    logger.warn("Unexpected response structure from instruments-info for {}:{}. Response: {}", category, symbol, response.getBody());
                }
            } else {
                logger.error("Failed to fetch instruments info for {}:{}. Status: {}, Body: {}",
                             category, symbol, response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Exception fetching tickSize for {}:{}: {}", category, symbol, e.getMessage(), e);
        }
        // Fallback or throw specific exception if critical
        logger.warn("Could not determine tickSize for {}:{}. Using default '0.01'. This may lead to inaccuracies.", category, symbol);
        return "0.01"; // Default fallback, adjust as necessary
    }

    private InstrumentQuantityRules fetchQuantityRulesFromApi(String category, String symbol) {
        logger.info("Fetching quantity rules for {} in category {} from API.", symbol, category);
        try {
            ResponseEntity<String> response = bybitApiClientService.getInstrumentsInfo(category, symbol);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                if (rootNode.has("result") && rootNode.get("result").has("list")) {
                    JsonNode listNode = rootNode.get("result").get("list");
                    if (listNode.isArray() && listNode.size() > 0) {
                        JsonNode instrumentInfo = listNode.get(0);
                        if (instrumentInfo.has("lotSizeFilter")) {
                            JsonNode lotSizeFilter = instrumentInfo.get("lotSizeFilter");
                            BigDecimal minOrderQty = new BigDecimal(lotSizeFilter.path("minOrderQty").asText("0"));
                            BigDecimal qtyStep = new BigDecimal(lotSizeFilter.path("qtyStep").asText("0.000001")); // Default to small step if missing

                            if (qtyStep.compareTo(BigDecimal.ZERO) == 0) { // Avoid division by zero if qtyStep is "0"
                                logger.warn("Received qtyStep of 0 for {}:{}, defaulting to 0.000001", category, symbol);
                                qtyStep = new BigDecimal("0.000001");
                            }

                            logger.info("Fetched quantity rules for {}:{}: MinQty={}, QtyStep={}", category, symbol, minOrderQty, qtyStep);
                            return new InstrumentQuantityRules(minOrderQty, qtyStep);
                        } else {
                            logger.warn("lotSizeFilter not found for {}:{}. Full instrumentInfo: {}", category, symbol, instrumentInfo.toString());
                        }
                    } else {
                        logger.warn("Instrument list is empty for {}:{} in API response for quantity rules.", category, symbol);
                    }
                } else {
                    logger.warn("Unexpected response structure from instruments-info for quantity rules {}:{}. Response: {}", category, symbol, response.getBody());
                }
            } else {
                logger.error("Failed to fetch instruments info for quantity rules {}:{}. Status: {}, Body: {}",
                             category, symbol, response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Exception fetching quantity rules for {}:{}: {}", category, symbol, e.getMessage(), e);
        }
        logger.warn("Could not determine quantity rules for {}:{}. Using default MinQty=0, QtyStep=0.000001. This may lead to order failures.", category, symbol);
        return new InstrumentQuantityRules(BigDecimal.ZERO, new BigDecimal("0.000001")); // Default fallback
    }

    public String getTickSize(String symbol, String category) {
        if (symbol == null || category == null || symbol.isEmpty() || category.isEmpty()) {
            logger.warn("Symbol or category is null/empty. Cannot fetch tickSize. Symbol: {}, Category: {}", symbol, category);
            return "0.01"; // Default fallback
        }
        try {
            String cacheKey = category + ":" + symbol;
            return tickSizeCache.get(cacheKey);
        } catch (Exception e) {
            logger.error("Error retrieving tickSize for {}:{} from cache: {}", category, symbol, e.getMessage(), e);
            // Fallback to a default if cache loading fails, though load() already has a fallback.
            return "0.01";
        }
    }

    public InstrumentQuantityRules getQuantityRules(String symbol, String category) {
        if (symbol == null || category == null || symbol.isEmpty() || category.isEmpty()) {
            logger.warn("Symbol or category is null/empty. Cannot fetch quantity rules. Symbol: {}, Category: {}", symbol, category);
            return new InstrumentQuantityRules(BigDecimal.ZERO, new BigDecimal("0.000001")); // Default fallback
        }
        try {
            String cacheKey = category + ":" + symbol;
            return quantityRulesCache.get(cacheKey);
        } catch (Exception e) {
            logger.error("Error retrieving quantity rules for {}:{} from cache: {}", category, symbol, e.getMessage(), e);
            return new InstrumentQuantityRules(BigDecimal.ZERO, new BigDecimal("0.000001"));
        }
    }

    // Optional: Pre-load popular symbols at startup
    @PostConstruct
    public void preloadPopularSymbols() {
        // Example: preload BTCUSDT and ETHUSDT for linear and spot
        // This needs to be adjusted based on typical usage
        logger.info("Preloading instrument info for popular symbols...");
        try {
            getTickSize("BTCUSDT", "linear");
            getTickSize("ETHUSDT", "linear");
            getTickSize("BTCUSDT", "spot");
            getTickSize("ETHUSDT", "spot");
        } catch (Exception e) {
            logger.warn("Failed to preload some popular symbols: {}", e.getMessage());
        }
        logger.info("Instrument info preloading complete.");
    }

    // DTO for quantity rules
    public static class InstrumentQuantityRules {
        private final BigDecimal minOrderQty;
        private final BigDecimal qtyStep;

        public InstrumentQuantityRules(BigDecimal minOrderQty, BigDecimal qtyStep) {
            this.minOrderQty = minOrderQty;
            this.qtyStep = qtyStep;
        }

        public BigDecimal getMinOrderQty() {
            return minOrderQty;
        }

        public BigDecimal getQtyStep() {
            return qtyStep;
        }
    }
} 