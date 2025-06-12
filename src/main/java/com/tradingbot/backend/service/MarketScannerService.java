package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.MexcApiConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.TechnicalIndicator;
import com.tradingbot.backend.service.BybitApiClientService;
import com.tradingbot.backend.service.BybitFuturesApiService;
import com.tradingbot.backend.service.BybitSpotApiService;
import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.MexcFuturesApiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;

import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.volume.*;
import org.ta4j.core.rules.*;
import org.ta4j.core.num.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutionException;

@Service
public class MarketScannerService {

    private static final Logger logger = LoggerFactory.getLogger(MarketScannerService.class);
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private BybitApiClientService bybitApiClientService;
    
    @Autowired
    private BybitFuturesApiService bybitFuturesApiService;
    
    @Autowired
    private BybitSpotApiService bybitSpotApiService;
    
    @Autowired
    private MexcFuturesApiService mexcFuturesApiService;
    
    @Autowired
    private MexcApiClientService mexcApiClientService;
    
    @Autowired
    private AIAnalysisService aiAnalysisService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private SignalWeightingService signalWeightingService;
    
    @Autowired
    private MultiTimeframeService multiTimeframeService;
    
    @Autowired
    private PerformanceLearningService performanceLearningService;
    
    @Autowired
    private MarketRegimeService marketRegimeService;
    
    @Autowired
    private EnhancedAIContextService enhancedAIContextService;
    
    @Autowired
    private EventDrivenAiAnalysisService eventDrivenAiAnalysisService;
    
    private final ExecutorService marketScanExecutor;
    
    @Value("${event.trigger.volatility.atr.threshold:0.5}")
    private double volatilityAtrThreshold;

    @Value("${event.trigger.volume.spike.multiplier:2.0}")
    private double volumeSpikeMultiplier;
    
    // Default spot trading pairs
    private static final List<String> DEFAULT_SPOT_PAIRS = 
        Arrays.asList("BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "NXPCUSDT", "KSMUSDT", "MOVRUSDT", "DOGEUSDT", "WALUSDT");
    
    // Default futures trading pairs
    private static final List<String> DEFAULT_FUTURES_PAIRS = 
        Arrays.asList("BTC_USDT", "ETH_USDT", "SOL_USDT", "AVAX_USDT", "NXPC_USDT", "KSM_USDT", "MOVR_USDT", "DOGE_USDT", "WAL_USDT");
    
    // Commonly used intervals for analysis
    private static final List<String> SUPPORTED_INTERVALS = 
        Arrays.asList("5m", "15m", "30m", "1h", "4h", "1d");
    
    // How many candles to fetch for analysis
    private static final int DEFAULT_LOOKBACK_PERIODS = 100;
    
    // Maximum number of concurrent requests to avoid rate limits
    private static final int MAX_CONCURRENT_REQUESTS = 4;
    
    public MarketScannerService(
        ObjectMapper objectMapper,
        BybitApiClientService bybitApiClientService,
        BybitFuturesApiService bybitFuturesApiService,
        BybitSpotApiService bybitSpotApiService,
        MexcFuturesApiService mexcFuturesApiService,
        MexcApiClientService mexcApiClientService,
        AIAnalysisService aiAnalysisService,
        RiskManagementService riskManagementService,
        SignalWeightingService signalWeightingService,
        MultiTimeframeService multiTimeframeService,
        PerformanceLearningService performanceLearningService,
        MarketRegimeService marketRegimeService,
        EnhancedAIContextService enhancedAIContextService,
        EventDrivenAiAnalysisService eventDrivenAiAnalysisService) {
        this.objectMapper = objectMapper;
        this.bybitApiClientService = bybitApiClientService;
        this.bybitFuturesApiService = bybitFuturesApiService;
        this.bybitSpotApiService = bybitSpotApiService;
        this.mexcFuturesApiService = mexcFuturesApiService;
        this.mexcApiClientService = mexcApiClientService;
        this.aiAnalysisService = aiAnalysisService;
        this.riskManagementService = riskManagementService;
        this.signalWeightingService = signalWeightingService;
        this.multiTimeframeService = multiTimeframeService;
        this.performanceLearningService = performanceLearningService;
        this.marketRegimeService = marketRegimeService;
        this.enhancedAIContextService = enhancedAIContextService;
        this.eventDrivenAiAnalysisService = eventDrivenAiAnalysisService;
        // Initialize the executor service
        // Using a fixed thread pool size, adjust based on expected load and external API limits
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2); // Example: half of available processors, min 2
        int maxPoolSize = Runtime.getRuntime().availableProcessors(); // Example: all available processors
        this.marketScanExecutor = new ThreadPoolExecutor(corePoolSize, maxPoolSize,
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }
    
    /**
     * Scans the market based on configuration parameters and returns scan results.
     *
     * @param config The configuration for the market scan
     * @return List of scan results matching the criteria
     */
    @Cacheable(value = "marketScanResults", key = "#config.toString()", condition = "#config != null")
    public List<ScanResult> scanMarket(ScanConfig config) {
        List<ScanResult> results = new ArrayList<>();
        
        // Get trading pairs to scan
        List<String> tradingPairs = config.getTradingPairs();
        if (tradingPairs == null || tradingPairs.isEmpty()) {
            tradingPairs = getDefaultTradingPairs();
        }
        
        // Get interval to scan
        String effectiveInterval = config.getInterval();
        if (effectiveInterval == null || effectiveInterval.isEmpty()) {
            effectiveInterval = "1h"; // Default to 1 hour
        }
        
        // Get exchange and market type
        String effectiveExchange = config.getExchange();
        ScanConfig.MarketType effectiveMarketType = config.getMarketType();
        
        if (effectiveExchange == null || effectiveExchange.isEmpty()) {
            effectiveExchange = "BYBIT"; // Default to Bybit
        }
        
        if (effectiveMarketType == null) {
            effectiveMarketType = ScanConfig.MarketType.SPOT; // Default to spot
        }
        
        // For Bybit, convert futures to linear
        if ("BYBIT".equalsIgnoreCase(effectiveExchange) && effectiveMarketType == ScanConfig.MarketType.FUTURES) {
            effectiveMarketType = ScanConfig.MarketType.LINEAR;
        }
        
        logger.info("Starting market scan for {} pairs on {} {} market", 
            tradingPairs.size(), effectiveExchange, effectiveMarketType.getValue());
        
        List<CompletableFuture<ScanResult>> futures = new ArrayList<>();

        // Final variables for use in lambda
        final String finalInterval = effectiveInterval;
        final String finalExchange = effectiveExchange;
        final ScanConfig.MarketType finalMarketType = effectiveMarketType;

        // Scan each trading pair in parallel
        for (String symbol : tradingPairs) {
            CompletableFuture<ScanResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    ScanResult result = new ScanResult();
                    result.setSymbol(symbol);
                    result.setInterval(finalInterval);
                    result.setTimestamp(System.currentTimeMillis());
                    result.setMarketType(finalMarketType.getValue());
                    
                    // Get historical data
                    JsonNode klineData = getHistoricalData(symbol, finalInterval, 100, finalExchange, finalMarketType);
                    
                    if (klineData == null || !klineData.isArray() || klineData.size() < 30) {
                        logger.warn("Insufficient historical data for {}", symbol);
                        return null; // Or an empty/error ScanResult
                    }
                    
                    // Process historical data
                    double[] closePrices = new double[klineData.size()];
                    double[] highPrices = new double[klineData.size()];
                    double[] lowPrices = new double[klineData.size()];
                    double[] volumes = new double[klineData.size()];
                    
                    if ("BYBIT".equalsIgnoreCase(finalExchange)) {
                        // Bybit returns array format: [startTime, openPrice, highPrice, lowPrice, closePrice, volume, turnover]
                        for (int i = 0; i < klineData.size(); i++) {
                            JsonNode candle = klineData.get(i);
                            if (candle.isArray() && candle.size() >= 6) {
                                closePrices[i] = candle.get(4).asDouble();  // Close price
                                highPrices[i] = candle.get(2).asDouble();   // High price
                                lowPrices[i] = candle.get(3).asDouble();    // Low price
                                volumes[i] = candle.get(5).asDouble();      // Volume
                            } else {
                                logger.warn("Invalid Bybit candle data format at index {}: {}", i, candle);
                                // Decide how to handle this error: skip candle, return null for symbol, etc.
                                return null;
                            }
                        }
                    } else if (finalMarketType == ScanConfig.MarketType.FUTURES || finalMarketType == ScanConfig.MarketType.LINEAR) {
                        // MEXC Futures API returns transformed data format: {time, open, high, low, close, volume}
                        for (int i = 0; i < klineData.size(); i++) {
                            JsonNode candle = klineData.get(i);
                            closePrices[i] = candle.get("close").asDouble();
                            highPrices[i] = candle.get("high").asDouble();
                            lowPrices[i] = candle.get("low").asDouble();
                            volumes[i] = candle.get("volume").asDouble();
                        }
                    } else {
                        // MEXC Spot API returns array format: [time, open, high, low, close, volume, ...]
                        for (int i = 0; i < klineData.size(); i++) {
                            JsonNode candle = klineData.get(i);
                            closePrices[i] = candle.get(4).asDouble();
                            highPrices[i] = candle.get(2).asDouble();
                            lowPrices[i] = candle.get(3).asDouble();
                            volumes[i] = candle.get(5).asDouble();
                        }
                    }
                    
                    // Get the most recent price
                    double currentPrice = closePrices[closePrices.length - 1];
                    result.setPrice(currentPrice);
                    
                    // Calculate technical indicators
                    Map<String, TechnicalIndicator> indicators = calculateIndicators(
                        closePrices, highPrices, lowPrices, volumes, config);
                    
                    result.setIndicators(indicators);
                    
                    // Perform AI analysis
                    String aiAnalysis = getAIAnalysis(result, symbol);
                    result.setAiAnalysis(aiAnalysis);

                    // Analyze market regime
                    MarketRegimeService.RegimeAnalysis regimeAnalysis = marketRegimeService.analyzeMarketRegime(symbol, finalExchange, finalMarketType);

                    // Get adaptive weights based on market conditions and learned performance
                    Map<String, Double> baseWeights = signalWeightingService.calculateAdaptiveWeights(symbol, finalInterval);
                    Map<String, Double> regimeAdjustedWeights = marketRegimeService.adjustWeightsForRegime(baseWeights, regimeAnalysis.getRegime());
                    Map<String, Double> learnedWeights = performanceLearningService.getLearnedWeights(symbol, finalInterval);
                    
                    // Combine all weight adjustments
                    Map<String, Double> finalWeights = combineWeightSystems(regimeAdjustedWeights, learnedWeights);
                    
                    // Generate signal summary (MOVED TO AFTER WEIGHTS/REGIME ARE CALCULATED)
                    String signalSummary = generateEnhancedSignalSummary(indicators, config, finalWeights, regimeAnalysis, aiAnalysis);
                    result.setSignal(signalSummary);
                    
                    // Check if result meets filter criteria
                    if (!meetsFilterCriteria(result, config)) {
                        return null;
                    }
                    
                    logger.debug("Enhanced signal for {}: technical={:.2f}, weighted={:.2f}, adjusted={:.2f}, regime={}, final={}", 
                                config.getTradingPairs() != null && !config.getTradingPairs().isEmpty() ? config.getTradingPairs().get(0) : "unknown",
                                signalWeightingService.convertTechnicalToScore(indicators), signalWeightingService.convertTechnicalToScore(indicators) * signalWeightingService.convertTechnicalToScore(indicators), signalWeightingService.convertTechnicalToScore(indicators) * signalWeightingService.convertTechnicalToScore(indicators), regimeAnalysis.getRegime(), signalSummary);
                    
                    return result;
                } catch (Exception e) {
                    logger.error("Error analyzing symbol {} in parallel: {}", symbol, e.getMessage(), e);
                    return null; // Or an empty/error ScanResult
                }
            }, marketScanExecutor);
            futures.add(future);
        }
        
        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // Collect results, filtering out nulls (from errors)
        results = futures.stream()
            .map(future -> {
                try {
                    return future.get();
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Exception retrieving future result: {}", e.getMessage(), e);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        logger.info("Market scan completed. Found {} matching results", results.size());
        return results;
    }
    
    /**
     * Scans a single trading pair for technical patterns and signals.
     */
    private ScanResult scanSinglePair(String symbol, String interval, ScanConfig config) {
        logger.debug("Scanning pair: {} with interval: {}", symbol, interval);
        
        try {
            // Fetch candlestick data for this pair
            JsonNode klineData = fetchKlineData(symbol, interval, DEFAULT_LOOKBACK_PERIODS, config);
            
            if (klineData == null || !klineData.isArray() || klineData.size() < 10) {
                logger.warn("Insufficient kline data for {}", symbol);
                return null;
            }
            
            // Build a scan result object for this pair
            ScanResult result = new ScanResult();
            result.setSymbol(symbol);
            result.setInterval(interval);
            result.setTimestamp(System.currentTimeMillis());
            
            // Determine final market type: honor explicit config but fall back to symbol pattern
            // If symbol contains an underscore (e.g. BTC_USDT) treat it as a futures pair by default
            ScanConfig.MarketType marketType;
            if (config.getMarketType() == ScanConfig.MarketType.FUTURES) {
                marketType = ScanConfig.MarketType.FUTURES;
            } else if (config.getMarketType() == ScanConfig.MarketType.SPOT) {
                marketType = symbol.contains("_") ? ScanConfig.MarketType.FUTURES : ScanConfig.MarketType.SPOT;
            } else {
                // If not explicitly specified, infer from symbol
                marketType = symbol.contains("_") ? ScanConfig.MarketType.FUTURES : ScanConfig.MarketType.SPOT;
            }
            result.setMarketType(marketType.getValue());
            
            // Set recommended leverage for futures
            if (marketType == ScanConfig.MarketType.FUTURES || marketType == ScanConfig.MarketType.LINEAR) {
                // Calculate dynamic leverage based on volatility
                int recommendedLeverage = calculateRecommendedLeverage(klineData);
                result.setRecommendedLeverage(recommendedLeverage);
            } else {
                result.setRecommendedLeverage(1); // Spot trading has no leverage
            }
            
            // Add risk metrics to the result
            try {
                BigDecimal currentDrawdown = riskManagementService.calculateMaxDrawdown(symbol);
                result.setCurrentDrawdown(currentDrawdown.multiply(BigDecimal.valueOf(100))); // Convert to percentage
                result.setRiskLevel(currentDrawdown.compareTo(BigDecimal.valueOf(0.15)) > 0 ? "HIGH" :
                                  currentDrawdown.compareTo(BigDecimal.valueOf(0.05)) > 0 ? "MEDIUM" : "LOW");
            } catch (Exception e) {
                result.setCurrentDrawdown(BigDecimal.ZERO);
                result.setRiskLevel("UNKNOWN");
            }
            
            // Extract OHLCV data based on exchange and market type
            double[] closePrices = new double[klineData.size()];
            double[] highPrices = new double[klineData.size()];
            double[] lowPrices = new double[klineData.size()];
            double[] volumes = new double[klineData.size()];
            
            String exchange = config.getExchange() != null ? config.getExchange() : "MEXC";
            
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // Bybit returns array format: [startTime, openPrice, highPrice, lowPrice, closePrice, volume, turnover]
                for (int i = 0; i < klineData.size(); i++) {
                    JsonNode candle = klineData.get(i);
                    if (candle.isArray() && candle.size() >= 6) {
                        closePrices[i] = candle.get(4).asDouble();  // Close price
                        highPrices[i] = candle.get(2).asDouble();   // High price
                        lowPrices[i] = candle.get(3).asDouble();    // Low price
                        volumes[i] = candle.get(5).asDouble();      // Volume
                    } else {
                        logger.warn("Invalid Bybit candle data format at index {}: {}", i, candle);
                        return null;
                    }
                }
            } else if ("futures".equalsIgnoreCase(config.getMarketType().getValue())) {
                // MEXC Futures API returns transformed data format: {time, open, high, low, close, volume}
                for (int i = 0; i < klineData.size(); i++) {
                    JsonNode candle = klineData.get(i);
                    closePrices[i] = candle.get("close").asDouble();
                    highPrices[i] = candle.get("high").asDouble();
                    lowPrices[i] = candle.get("low").asDouble();
                    volumes[i] = candle.get("volume").asDouble();
                }
            } else {
                // MEXC Spot API returns array format: [time, open, high, low, close, volume, ...]
                for (int i = 0; i < klineData.size(); i++) {
                    JsonNode candle = klineData.get(i);
                    closePrices[i] = candle.get(4).asDouble();
                    highPrices[i] = candle.get(2).asDouble();
                    lowPrices[i] = candle.get(3).asDouble();
                    volumes[i] = candle.get(5).asDouble();
                }
            }
            
            // Get the most recent price
            double currentPrice = closePrices[closePrices.length - 1];
            result.setPrice(currentPrice);
            
            // Calculate technical indicators
            Map<String, TechnicalIndicator> indicators = calculateIndicators(
                closePrices, highPrices, lowPrices, volumes, config);
            
            result.setIndicators(indicators);
            
            // Generate signal summary
            String signalSummary = generateSignalSummary(indicators, config);
            result.setSignal(signalSummary);
            
            // Add AI analysis if requested
            if (config.isIncludeAiAnalysis()) {
                try {
                    // Use the AIAnalysisService to get real Claude analysis
                    CompletableFuture<String> aiAnalysisFuture = aiAnalysisService.analyzeMarketDataAsync(result);
                    
                    // Implement retry logic with exponential backoff
                    String aiAnalysis = getAnalysisWithRetry(aiAnalysisFuture, symbol, 3);
                    result.setAiAnalysis(aiAnalysis);
                } catch (Exception e) {
                    logger.warn("Error getting AI analysis for {}: {}", symbol, e.getMessage());
                    // Fallback to a simpler analysis if AI fails
                    result.setAiAnalysis("AI analysis unavailable: " + e.getMessage());
                }
            }
            
            // --- Event-Driven AI Trigger Logic ---
            TechnicalIndicator atrIndicator = indicators.get("ATR_14");
            if (atrIndicator != null && !"INSUFFICIENT_DATA".equals(atrIndicator.getSignal())) { // MODIFIED: Removed atrIndicator.getValue() != null
                Double atrValueObj = atrIndicator.getValue(); // Assign to Double object first
                if (atrValueObj != null) { // THEN check the Double object for null
                    // Only proceed if atrValueObj is not null
                    double atrValue = atrValueObj.doubleValue(); // Unbox here
                    if (currentPrice > 0) {
                        double atrPercentage = (atrValue / currentPrice) * 100;
                        if (atrValue > volatilityAtrThreshold) {
                            eventDrivenAiAnalysisService.triggerAnalysisOnVolatilitySpike(symbol, interval, atrPercentage);
                        }
                    }
                }
            }

            TechnicalIndicator volumeSmaIndicator = indicators.get("VolumeSMA_20");
            if (volumeSmaIndicator != null && volumes.length > 0 && !"INSUFFICIENT_DATA".equals(volumeSmaIndicator.getSignal())) { // MODIFIED: Removed volumeSmaIndicator.getValue() != null
                Double avgVolumeObj = volumeSmaIndicator.getValue(); // Assign to Double object first
                if (avgVolumeObj != null) { // THEN check the Double object for null
                    double currentVolume = volumes[volumes.length - 1];
                    double avgVolume = avgVolumeObj.doubleValue(); // Unbox only after the null check
                    if (avgVolume > 0 && currentVolume > (avgVolume * volumeSpikeMultiplier)) {
                        double spikeFactor = currentVolume / avgVolume;
                        eventDrivenAiAnalysisService.triggerAnalysisOnEvent(symbol, interval, "VolumeSpike", 
                            String.format("Volume %.2fx average (%.0f vs %.0f)", spikeFactor, currentVolume, avgVolume));
                    }
                }
            }
            // --- End Event-Driven AI Trigger Logic ---
            
            return result;
        } catch (Exception e) {
            logger.error("Error analyzing {}: {}", symbol, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Calculate lookback time in milliseconds based on interval and limit
     */
    private long calculateLookbackTime(String interval, int limit) {
        long intervalMillis = getIntervalMillis(interval);
        return intervalMillis * limit;
    }

    /**
     * Parse kline response and extract the data array
     */
    private JsonNode parseKlineResponse(ResponseEntity<String> response, String source) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            logger.warn("Failed to fetch {} kline data: {}", source, response.getStatusCode());
            return null;
        }

        try {
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            
            // Check for error response
            if (responseJson.has("retCode") && responseJson.get("retCode").asInt() != 0) {
                logger.warn("{} API error: {}", source, responseJson.get("retMsg").asText());
                return null;
            }
            
            // Handle Bybit response format
            if (source.startsWith("Bybit")) {
                if (responseJson.has("result")) {
                    JsonNode result = responseJson.get("result");
                    if (result.has("list") && result.get("list").isArray() && result.get("list").size() > 0) {
                        logger.debug("{} returned {} klines", source, result.get("list").size());
                        return result.get("list");
                    }
                }
                logger.warn("{} response missing result.list data", source);
            }
            // Handle MEXC futures response format
            else if (source.equals("MEXC futures")) {
                if (responseJson.has("data") && responseJson.get("data").isArray()) {
                    logger.debug("MEXC futures returned {} klines", responseJson.get("data").size());
                    return responseJson.get("data");
                }
                logger.warn("MEXC futures response missing data array");
            }
            // Handle MEXC spot response format
            else if (source.equals("MEXC spot")) {
                if (responseJson.isArray()) {
                    logger.debug("MEXC spot returned {} klines", responseJson.size());
                    return responseJson;
                }
                logger.warn("MEXC spot response not an array");
            }
            
            logger.warn("{} response has unexpected format: {}", source, responseJson);
            return null;
        } catch (JsonProcessingException e) {
            logger.error("Error parsing {} kline data: {}", source, e.getMessage());
            return null;
        }
    }

    private JsonNode fetchKlineData(String symbol, String interval, int limit, ScanConfig config) {
        logger.debug("Fetching kline data for {} with interval {}", symbol, interval);
        
        try {
            String exchange = config.getExchange() != null ? config.getExchange() : "MEXC";
            ScanConfig.MarketType marketType = config.getMarketType();
            
            // Format symbol according to exchange requirements
            String formattedSymbol = formatSymbol(symbol, exchange, marketType);
            
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                long endTime = System.currentTimeMillis();
                long startTime = endTime - (limit * getIntervalMillis(interval));
                String category = marketType == ScanConfig.MarketType.LINEAR ? "linear" : "spot";
                ResponseEntity<String> response = bybitApiClientService.getKlineData(
                    formattedSymbol, interval, limit, startTime, endTime, category);
                return parseKlineResponse(response, "Bybit " + category);
            } else {
                long endTime = System.currentTimeMillis();
                long startTime = endTime - (limit * getIntervalMillis(interval));
                if (marketType == ScanConfig.MarketType.FUTURES) {
                    ResponseEntity<String> response = mexcFuturesApiService.getFuturesKlines(
                        formattedSymbol, interval, startTime / 1000, endTime / 1000);
                    return objectMapper.readTree(response.getBody()).get("data");
                } else {
                    ResponseEntity<String> response = mexcApiClientService.getSpotKlines(
                        formattedSymbol, interval, startTime, endTime, limit);
                    return objectMapper.readTree(response.getBody());
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching kline data: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Formats a symbol according to exchange and market type requirements
     */
    private String formatSymbol(String symbol, String exchange, ScanConfig.MarketType marketType) {
        if ("BYBIT".equalsIgnoreCase(exchange)) {
            return (marketType == ScanConfig.MarketType.FUTURES || marketType == ScanConfig.MarketType.LINEAR) ? 
                bybitApiClientService.formatSymbolForFutures(symbol) : 
                bybitApiClientService.formatSymbolForSpot(symbol);
        } else {
            return (marketType == ScanConfig.MarketType.FUTURES || marketType == ScanConfig.MarketType.LINEAR) ? 
                symbol.replace("/", "_").toUpperCase() : 
                symbol.replace("/", "").toUpperCase();
        }
    }
    
    /**
     * Calculate technical indicators based on price data
     */
    private Map<String, TechnicalIndicator> calculateIndicators(
            double[] closePrices, double[] highPrices, double[] lowPrices, 
            double[] volumes, ScanConfig config) {
        Map<String, TechnicalIndicator> indicators = new HashMap<>();
        
        // Ensure there's enough data for calculations
        if (closePrices == null || closePrices.length < config.getMinCalculationPeriod()) {
            logger.warn("Not enough close price data to calculate indicators. Required: {}, Available: {}", 
                        config.getMinCalculationPeriod(), closePrices != null ? closePrices.length : 0);
            // Add placeholder/error indicators or return empty map
            indicators.put("SMA_20", createInvalidIndicator("SMA_20"));
            indicators.put("RSI_14", createInvalidIndicator("RSI_14"));
            // Potentially add more invalid indicators for everything this method would normally calculate
            return indicators;
        }

        List<Double> closePriceList = Arrays.stream(closePrices).boxed().collect(Collectors.toList());
        List<Double> highPriceList = Arrays.stream(highPrices).boxed().collect(Collectors.toList());
        List<Double> lowPriceList = Arrays.stream(lowPrices).boxed().collect(Collectors.toList());
        List<Double> volumeList = Arrays.stream(volumes).boxed().collect(Collectors.toList());

        // Calculate various indicators
        // SMA
        indicators.put("SMA_20", calculateMA(closePrices, 20));
        indicators.put("SMA_50", calculateMA(closePrices, 50));
        
        // RSI
        indicators.put("RSI_14", calculateRSI(closePrices, 14));
        
        // MACD
        indicators.put("MACD", calculateMACD(closePrices));
        
        // Bollinger Bands
        indicators.put("BollingerBands", calculateBollingerBands(closePrices, 20, 2));
        
        // Volume SMA
        indicators.put("VolumeSMA_20", calculateVolumeSMA(volumes, 20));
        
        // ATR
        if (highPrices.length >= 15 && lowPrices.length >= 15 && closePrices.length >= 15) { // ATR 14 needs 15 data points
             indicators.put("ATR_14", calculateATR(highPrices, lowPrices, closePrices, 14));
        } else {
            logger.warn("Not enough data for ATR_14 calculation. Required: 15, Available: {}", highPrices.length);
            indicators.put("ATR_14", createInvalidIndicator("ATR_14"));
        }

        // Fibonacci Retracement Levels
        final int fiboLookback = config.getFiboLookbackPeriod() > 0 ? config.getFiboLookbackPeriod() : 50; // Default 50 if not set or invalid
        if (highPrices.length >= fiboLookback && lowPrices.length >= fiboLookback && fiboLookback > 1) {
            // Get the slice of data for trend identification
            List<Double> recentHighs = highPriceList.subList(highPriceList.size() - fiboLookback, highPriceList.size());
            List<Double> recentLows = lowPriceList.subList(lowPriceList.size() - fiboLookback, lowPriceList.size());

            double trendHighPrice = Double.MIN_VALUE;
            int trendHighIndex = -1;
            double trendLowPrice = Double.MAX_VALUE;
            int trendLowIndex = -1;

            for (int i = 0; i < fiboLookback; i++) {
                if (recentHighs.get(i) > trendHighPrice) {
                    trendHighPrice = recentHighs.get(i);
                    trendHighIndex = i;
                }
                if (recentLows.get(i) < trendLowPrice) {
                    trendLowPrice = recentLows.get(i);
                    trendLowIndex = i;
                }
            }

            if (trendHighIndex != -1 && trendLowIndex != -1 && trendHighPrice > trendLowPrice) {
                boolean isUptrend;
                double identifiedHigh, identifiedLow;

                // Determine the primary trend direction for retracement calculation
                if (trendHighIndex > trendLowIndex) { // Low appeared before High - Uptrend from Low to High
                    isUptrend = true;
                    identifiedHigh = trendHighPrice;
                    identifiedLow = trendLowPrice;
                } else { // High appeared before Low - Downtrend from High to Low
                    isUptrend = false;
                    identifiedHigh = trendHighPrice;
                    identifiedLow = trendLowPrice;
                }
                
                logger.debug("Fibonacci: Trend High: {} at index {}, Trend Low: {} at index {}. isUptrend for retracement: {}", identifiedHigh, trendHighIndex, identifiedLow, trendLowIndex, isUptrend);

                try {
                    List<com.tradingbot.backend.service.util.TechnicalAnalysisUtil.FibonacciRetracement> fibLevels = 
                        com.tradingbot.backend.service.util.TechnicalAnalysisUtil.calculateFibonacciRetracements(identifiedHigh, identifiedLow, isUptrend);
                    
                    for (com.tradingbot.backend.service.util.TechnicalAnalysisUtil.FibonacciRetracement fibLevel : fibLevels) {
                        String indicatorName = String.format("FIB_%.3f", fibLevel.level).replace(',', '.'); // Ensure dot for decimal
                        TechnicalIndicator ti = new TechnicalIndicator();
                        ti.setName(indicatorName);
                        ti.setValue(fibLevel.price); // Corrected: pass double directly
                        ti.setSignal("LEVEL"); // Or some other relevant signal type
                        indicators.put(indicatorName, ti);
                        logger.debug("Calculated Fibonacci Level: {} = {}", indicatorName, ti.getValue());
                    }
                } catch (Exception e) {
                    logger.error("Error calculating Fibonacci retracements for symbol with high: {}, low: {}: {}", identifiedHigh, identifiedLow, e.getMessage(), e);
                }
            } else {
                logger.warn("Could not determine valid trend for Fibonacci (High: {}, Low: {}) over lookback {} for symbol.", trendHighPrice, trendLowPrice, fiboLookback);
            }
        } else {
            logger.warn("Not enough data for Fibonacci {} lookback. Required: {}, Available: {}", fiboLookback, fiboLookback, highPrices.length);
            // Optionally add placeholder invalid fib indicators
             String[] fibLevelsToPlaceholder = {"0.236", "0.382", "0.500", "0.618", "0.786"};
             for (String level : fibLevelsToPlaceholder) {
                indicators.put("FIB_" + level, createInvalidIndicator("FIB_" + level));
            }
        }
        
        // Example: Add other indicators as needed
        return indicators;
    }
    
    /**
     * Calculate Moving Average
     */
    private TechnicalIndicator calculateMA(double[] prices, int period) {
        if (prices.length < period) {
            return createInvalidIndicator("MA_" + period);
        }
        
        // Calculate simple moving average
        double sum = 0;
        for (int i = prices.length - period; i < prices.length; i++) {
            sum += prices[i];
        }
        double ma = sum / period;
        
        // Get current price
        double currentPrice = prices[prices.length - 1];
        
        // Create indicator
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName("MA_" + period);
        indicator.setValue(ma);
        
        // Determine signal
        String signal;
        if (currentPrice > ma) {
            signal = "BULLISH";
        } else if (currentPrice < ma) {
            signal = "BEARISH";
        } else {
            signal = "NEUTRAL";
        }
        indicator.setSignal(signal);
        
        // Calculate percent difference from price
        double percentDiff = (currentPrice - ma) / ma * 100;
        indicator.setPercentDifference(percentDiff);
        
        return indicator;
    }
    
    /**
     * Calculate RSI (Relative Strength Index)
     */
    private TechnicalIndicator calculateRSI(double[] prices, int period) {
        if (prices.length <= period) {
            return createInvalidIndicator("RSI_" + period);
        }
        
        double[] deltas = new double[prices.length - 1];
        for (int i = 1; i < prices.length; i++) {
            deltas[i - 1] = prices[i] - prices[i - 1];
        }
        
        double[] gains = new double[deltas.length];
        double[] losses = new double[deltas.length];
        
        for (int i = 0; i < deltas.length; i++) {
            gains[i] = Math.max(0, deltas[i]);
            losses[i] = Math.max(0, -deltas[i]);
        }
        
        double avgGain = 0;
        double avgLoss = 0;
        
        // First RSI value
        for (int i = 0; i < period; i++) {
            avgGain += gains[i];
            avgLoss += losses[i];
        }
        
        avgGain /= period;
        avgLoss /= period;
        
        // Smooth RSI values
        for (int i = period; i < deltas.length; i++) {
            avgGain = ((avgGain * (period - 1)) + gains[i]) / period;
            avgLoss = ((avgLoss * (period - 1)) + losses[i]) / period;
        }
        
        double rs = avgGain / (avgLoss == 0 ? 0.001 : avgLoss); // Avoid division by zero
        double rsi = 100 - (100 / (1 + rs));
        
        // Create indicator
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName("RSI_" + period);
        indicator.setValue(rsi);
        
        // Determine signal
        String signal;
        if (rsi > 70) {
            signal = "OVERBOUGHT";
        } else if (rsi < 30) {
            signal = "OVERSOLD";
        } else {
            signal = "NEUTRAL";
        }
        indicator.setSignal(signal);
        
        return indicator;
    }
    
    /**
     * Calculate MACD (Moving Average Convergence Divergence)
     */
    private TechnicalIndicator calculateMACD(double[] prices) {
        if (prices.length < 26 + 9) {
            return createInvalidIndicator("MACD");
        }
        
        // Calculate EMAs
        double[] ema12 = calculateEMA(prices, 12);
        double[] ema26 = calculateEMA(prices, 26);
        
        // Calculate MACD line
        double[] macdLine = new double[ema26.length];
        for (int i = 0; i < macdLine.length; i++) {
            macdLine[i] = ema12[i + (prices.length - ema12.length)] - ema26[i];
        }
        
        // Calculate signal line (9-period EMA of MACD line)
        double[] signalLine = calculateEMA(macdLine, 9);
        
        // Calculate histogram
        double macdCurrent = macdLine[macdLine.length - 1];
        double signalCurrent = signalLine[signalLine.length - 1];
        double histogram = macdCurrent - signalCurrent;
        
        // Create indicator
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName("MACD");
        indicator.setValue(macdCurrent);
        
        // Add additional data
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("signal", signalCurrent);
        additionalData.put("histogram", histogram);
        indicator.setAdditionalData(additionalData);
        
        // Determine signal
        String signal;
        if (macdCurrent > signalCurrent && macdCurrent > 0) {
            signal = "STRONG_BULLISH";
        } else if (macdCurrent > signalCurrent) {
            signal = "BULLISH";
        } else if (macdCurrent < signalCurrent && macdCurrent < 0) {
            signal = "STRONG_BEARISH";
        } else if (macdCurrent < signalCurrent) {
            signal = "BEARISH";
        } else {
            signal = "NEUTRAL";
        }
        indicator.setSignal(signal);
        
        return indicator;
    }
    
    /**
     * Calculate EMA (Exponential Moving Average)
     */
    private double[] calculateEMA(double[] prices, int period) {
        if (prices.length < period) {
            return new double[0];
        }
        
        double[] ema = new double[prices.length - period + 1];
        double multiplier = 2.0 / (period + 1);
        
        // First EMA is SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += prices[i];
        }
        ema[0] = sum / period;
        
        // Calculate EMA
        for (int i = 1; i < ema.length; i++) {
            ema[i] = (prices[period - 1 + i] - ema[i - 1]) * multiplier + ema[i - 1];
        }
        
        return ema;
    }
    
    /**
     * Calculate Bollinger Bands
     */
    private TechnicalIndicator calculateBollingerBands(double[] prices, int period, double stdDevMultiplier) {
        if (prices.length < period) {
            return createInvalidIndicator("BBANDS");
        }
        
        // Calculate SMA
        double sum = 0;
        for (int i = prices.length - period; i < prices.length; i++) {
            sum += prices[i];
        }
        double sma = sum / period;
        
        // Calculate Standard Deviation
        double sumSquaredDiff = 0;
        for (int i = prices.length - period; i < prices.length; i++) {
            double diff = prices[i] - sma;
            sumSquaredDiff += diff * diff;
        }
        double standardDeviation = Math.sqrt(sumSquaredDiff / period);
        
        // Calculate upper and lower bands
        double upperBand = sma + (standardDeviation * stdDevMultiplier);
        double lowerBand = sma - (standardDeviation * stdDevMultiplier);
        
        // Get current price
        double currentPrice = prices[prices.length - 1];
        
        // Create indicator
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName("BBANDS");
        indicator.setValue(sma);
        
        // Add additional data
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("upper", upperBand);
        additionalData.put("lower", lowerBand);
        additionalData.put("standardDeviation", standardDeviation);
        indicator.setAdditionalData(additionalData);
        
        // Determine signal
        String signal;
        if (currentPrice > upperBand) {
            signal = "OVERBOUGHT";
        } else if (currentPrice < lowerBand) {
            signal = "OVERSOLD";
        } else if (currentPrice > sma) {
            signal = "BULLISH";
        } else if (currentPrice < sma) {
            signal = "BEARISH";
        } else {
            signal = "NEUTRAL";
        }
        indicator.setSignal(signal);
        
        // Calculate bandwidth for volatility
        double bandwidth = (upperBand - lowerBand) / sma * 100;
        additionalData.put("bandwidth", bandwidth);
        
        return indicator;
    }
    
    /**
     * Calculate Volume SMA
     */
    private TechnicalIndicator calculateVolumeSMA(double[] volumes, int period) {
        if (volumes.length < period) {
            return createInvalidIndicator("VOLUME_SMA");
        }
        
        // Calculate volume SMA
        double sum = 0;
        for (int i = volumes.length - period; i < volumes.length; i++) {
            sum += volumes[i];
        }
        double volumeSMA = sum / period;
        
        // Get current volume
        double currentVolume = volumes[volumes.length - 1];
        
        // Create indicator
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName("VOLUME_SMA");
        indicator.setValue(volumeSMA);
        
        // Determine signal
        String signal;
        if (currentVolume > volumeSMA * 2) {
            signal = "VERY_HIGH";
        } else if (currentVolume > volumeSMA * 1.5) {
            signal = "HIGH";
        } else if (currentVolume < volumeSMA * 0.5) {
            signal = "VERY_LOW";
        } else if (currentVolume < volumeSMA * 0.75) {
            signal = "LOW";
        } else {
            signal = "NORMAL";
        }
        indicator.setSignal(signal);
        
        // Calculate volume change percentage
        double volumeChangePercent = (currentVolume - volumeSMA) / volumeSMA * 100;
        indicator.setPercentDifference(volumeChangePercent);
        
        return indicator;
    }
    
    /**
     * Calculate ATR (Average True Range)
     */
    private TechnicalIndicator calculateATR(double[] high, double[] low, double[] close, int period) {
        if (high.length < period + 1 || low.length < period + 1 || close.length < period + 1) {
            return createInvalidIndicator("ATR");
        }
        
        double[] trueRanges = new double[high.length - 1];
        
        // Calculate true range for each candle
        for (int i = 1; i < high.length; i++) {
            double highLowRange = high[i] - low[i];
            double highClosePrevRange = Math.abs(high[i] - close[i - 1]);
            double lowClosePrevRange = Math.abs(low[i] - close[i - 1]);
            
            trueRanges[i - 1] = Math.max(highLowRange, Math.max(highClosePrevRange, lowClosePrevRange));
        }
        
        // Calculate ATR
        double atr = 0;
        for (int i = trueRanges.length - period; i < trueRanges.length; i++) {
            atr += trueRanges[i];
        }
        atr /= period;
        
        // Create indicator
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName("ATR");
        indicator.setValue(atr);
        
        // Get current price
        double currentPrice = close[close.length - 1];
        
        // Determine volatility level
        double atrPercentage = (atr / currentPrice) * 100;
        
        String signal;
        if (atrPercentage > 5) {
            signal = "EXTREME_VOLATILITY";
        } else if (atrPercentage > 3) {
            signal = "HIGH_VOLATILITY";
        } else if (atrPercentage > 1.5) {
            signal = "MODERATE_VOLATILITY";
        } else {
            signal = "LOW_VOLATILITY";
        }
        indicator.setSignal(signal);
        
        // Add ATR percentage to additional data
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("atrPercentage", atrPercentage);
        indicator.setAdditionalData(additionalData);
        
        return indicator;
    }
    
    /**
     * Create an invalid indicator when data is insufficient
     */
    private TechnicalIndicator createInvalidIndicator(String name) {
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName(name);
        indicator.setValue(0);
        indicator.setSignal("INSUFFICIENT_DATA");
        return indicator;
    }
    
    /**
     * Generate a summary signal based on all indicators
     */
    private String generateSignalSummary(Map<String, TechnicalIndicator> indicators, ScanConfig config) {
        int bullishCount = 0;
        int bearishCount = 0;
        int neutralCount = 0;
        
        // Count different signals
        for (TechnicalIndicator indicator : indicators.values()) {
            String signal = indicator.getSignal();
            
            if (signal.contains("BULLISH") || signal.equals("OVERSOLD")) {
                bullishCount++;
            } else if (signal.contains("BEARISH") || signal.equals("OVERBOUGHT")) {
                bearishCount++;
            } else if (!signal.equals("INSUFFICIENT_DATA")) {
                neutralCount++;
            }
        }
        
        // Generate overall signal
        if (bullishCount > bearishCount + neutralCount) {
            return "STRONG_BUY";
        } else if (bullishCount > bearishCount) {
            return "BUY";
        } else if (bearishCount > bullishCount + neutralCount) {
            return "STRONG_SELL";
        } else if (bearishCount > bullishCount) {
            return "SELL";
        } else {
            return "NEUTRAL";
        }
    }
    
    /**
     * Determine if a scan result meets the filtering criteria specified in the config
     */
    private boolean meetsFilterCriteria(ScanResult result, ScanConfig config) {
        // If no filters specified, include all results
        if (config.getMinimumSignalStrength() == null) {
            return true;
        }
        
        // Filter by signal strength
        String signal = result.getSignal();
        String minimumStrength = config.getMinimumSignalStrength();
        
        if (minimumStrength.equals("STRONG_BUY")) {
            return signal.equals("STRONG_BUY");
        } else if (minimumStrength.equals("BUY")) {
            return signal.equals("STRONG_BUY") || signal.equals("BUY");
        } else if (minimumStrength.equals("STRONG_SELL")) {
            return signal.equals("STRONG_SELL");
        } else if (minimumStrength.equals("SELL")) {
            return signal.equals("STRONG_SELL") || signal.equals("SELL");
        }
        
        return true;
    }
    
    /**
     * Gets analysis result with retry logic and exponential backoff
     * 
     * @param future The CompletableFuture that will return the analysis
     * @param symbol The trading pair symbol for logging
     * @param maxRetries Maximum number of retries
     * @return The analysis text or fallback message
     */
    private String getAnalysisWithRetry(CompletableFuture<String> future, String symbol, int maxRetries) {
        int retryCount = 0;
        long waitTime = 2000; // Start with 2 second wait
        
        while (retryCount <= maxRetries) {
            try {
                if (retryCount == 0) {
                    // First attempt with normal timeout
                    return future.get(45, TimeUnit.SECONDS); // Increased initial timeout to 45 seconds
                } else {
                    // Check if already completed without blocking
                    if (future.isDone()) {
                        return future.get(); // Already completed, just get the result
                    }
                    
                    // Still not ready, wait with timeout
                    logger.info("Retry #{} for AI analysis of {}, waiting {}ms", 
                            retryCount, symbol, waitTime);
                    return future.get(waitTime, TimeUnit.MILLISECONDS);
                }
            } catch (TimeoutException e) {
                retryCount++;
                if (retryCount > maxRetries) {
                    logger.warn("Max retries reached for AI analysis of {}", symbol);
                    break;
                }
                
                // Exponential backoff
                waitTime *= 2;
                
                // Don't let wait time exceed 30 seconds
                waitTime = Math.min(waitTime, 30000); // Increased max wait time to 30 seconds
            } catch (Exception e) {
                logger.error("Error getting AI analysis for {}: {}", symbol, e.getMessage());
                break;
            }
        }
        
        // If we reach here, we've either exhausted retries or hit another exception
        return "AI analysis unavailable after " + retryCount + " retries";
    }
    
    /**
     * Calculate volatility from kline data
     */
    private double calculateVolatility(JsonNode klineData) {
        try {
            List<Double> prices = new ArrayList<>();
            
            for (JsonNode kline : klineData) {
                prices.add(kline.get(4).asDouble()); // Close price
            }
            
            if (prices.size() < 2) return 0.02; // Default volatility
            
            // Calculate price changes
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < prices.size(); i++) {
                double priceChange = (prices.get(i) - prices.get(i - 1)) / prices.get(i - 1);
                returns.add(priceChange);
            }
            
            // Calculate standard deviation of returns
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average().orElse(0.0);
            
            return Math.sqrt(variance);
            
        } catch (Exception e) {
            logger.warn("Error calculating volatility: {}", e.getMessage());
            return 0.02; // Default volatility
        }
    }
    
    /**
     * Calculate recommended leverage based on multiple factors
     */
    private int calculateRecommendedLeverage(JsonNode klineData) {
        try {
            // Calculate volatility from kline data
            double volatility = calculateVolatility(klineData);
            
            // Adjust leverage based on volatility
            // Lower leverage for high volatility, higher leverage for low volatility
            if (volatility > 0.05) { // High volatility (>5%)
                return 2;
            } else if (volatility > 0.03) { // Medium volatility (3-5%)
                return 5;
            } else if (volatility > 0.01) { // Low volatility (1-3%)
                return 10;
            } else { // Very low volatility (<1%)
                return 20;
            }
        } catch (Exception e) {
            logger.warn("Error calculating recommended leverage: {}", e.getMessage());
            return 2; // Default to conservative leverage
        }
    }
    
    /**
     * Enhanced leverage calculation with multiple factors
     */
    private int calculateEnhancedLeverage(JsonNode klineData, ScanResult result, String exchange) {
        try {
            // Factor 1: Volatility (existing)
            double volatility = calculateVolatility(klineData);
            
            // Factor 2: Signal Confidence
            double signalConfidence = getSignalConfidence(result);
            
            // Factor 3: Market Structure (trending vs ranging)
            String marketStructure = analyzeMarketStructure(klineData);
            
            // Factor 4: Volume Profile
            double volumeStrength = analyzeVolumeStrength(klineData);
            
            // Factor 5: Correlation with major indices (simplified)
            double marketCorrelation = getMarketCorrelation(result.getSymbol());
            
            // Base leverage from volatility
            int baseLeverage = getBaseLeverageFromVolatility(volatility);
            
            // Adjust for signal confidence
            if (signalConfidence > 0.8) {
                baseLeverage = Math.min(baseLeverage + 2, 15); // Max 15x
            } else if (signalConfidence < 0.6) {
                baseLeverage = Math.max(baseLeverage - 1, 2); // Min 2x
            }
            
            // Adjust for market structure
            if ("TRENDING".equals(marketStructure)) {
                baseLeverage = Math.min(baseLeverage + 1, 15);
            } else if ("RANGING".equals(marketStructure)) {
                baseLeverage = Math.max(baseLeverage - 1, 2);
            }
            
            // Adjust for volume
            if (volumeStrength > 1.5) { // High volume
                baseLeverage = Math.min(baseLeverage + 1, 15);
            } else if (volumeStrength < 0.7) { // Low volume
                baseLeverage = Math.max(baseLeverage - 1, 2);
            }
            
            // Conservative adjustment for high market correlation
            if (marketCorrelation > 0.8) { // Highly correlated with market
                baseLeverage = Math.max(baseLeverage - 1, 2);
            }
            
            logger.debug("Enhanced leverage for {}: {} (volatility: {}, confidence: {}, structure: {}, volume: {}, correlation: {})",
                result.getSymbol(), baseLeverage, volatility, signalConfidence, marketStructure, volumeStrength, marketCorrelation);
            
            return baseLeverage;
            
        } catch (Exception e) {
            logger.warn("Error calculating enhanced leverage: {}", e.getMessage());
            return 2; // Ultra-conservative fallback
        }
    }
    
    /**
     * Get signal confidence from scan result
     */
    private double getSignalConfidence(ScanResult result) {
        if (result == null) return 0.5;
        
        String signal = result.getSignal();
        switch (signal) {
            case "STRONG_BUY":
            case "STRONG_SELL":
                return 0.85;
            case "BUY":
            case "SELL":
                return 0.70;
            default:
                return 0.50;
        }
    }
    
    /**
     * Analyze market structure from price data
     */
    private String analyzeMarketStructure(JsonNode klineData) {
        try {
            List<Double> highs = new ArrayList<>();
            List<Double> lows = new ArrayList<>();
            
            for (JsonNode kline : klineData) {
                highs.add(kline.get(2).asDouble()); // High price
                lows.add(kline.get(3).asDouble());  // Low price
            }
            
            if (highs.size() < 10) return "NEUTRAL";
            
            // Check for trending pattern
            double recentHighsAvg = highs.subList(highs.size() - 5, highs.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double earlierHighsAvg = highs.subList(0, 5)
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            double recentLowsAvg = lows.subList(lows.size() - 5, lows.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double earlierLowsAvg = lows.subList(0, 5)
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            double highsTrend = (recentHighsAvg - earlierHighsAvg) / earlierHighsAvg;
            double lowsTrend = (recentLowsAvg - earlierLowsAvg) / earlierLowsAvg;
            
            if (highsTrend > 0.02 && lowsTrend > 0.02) {
                return "TRENDING"; // Uptrend
            } else if (highsTrend < -0.02 && lowsTrend < -0.02) {
                return "TRENDING"; // Downtrend
            } else {
                return "RANGING";  // Sideways
            }
            
        } catch (Exception e) {
            logger.warn("Error analyzing market structure: {}", e.getMessage());
            return "NEUTRAL";
        }
    }
    
    /**
     * Analyze volume strength
     */
    private double analyzeVolumeStrength(JsonNode klineData) {
        try {
            List<Double> volumes = new ArrayList<>();
            
            for (JsonNode kline : klineData) {
                volumes.add(kline.get(5).asDouble()); // Volume
            }
            
            if (volumes.size() < 10) return 1.0;
            
            // Compare recent volume to historical average
            double recentVolumeAvg = volumes.subList(volumes.size() - 5, volumes.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
            double historicalVolumeAvg = volumes.stream()
                .mapToDouble(Double::doubleValue).average().orElse(1.0);
            
            return historicalVolumeAvg > 0 ? recentVolumeAvg / historicalVolumeAvg : 1.0;
            
        } catch (Exception e) {
            logger.warn("Error analyzing volume strength: {}", e.getMessage());
            return 1.0;
        }
    }
    
    /**
     * Get market correlation (simplified implementation)
     */
    private double getMarketCorrelation(String symbol) {
        // Simplified correlation logic
        // In a real implementation, this would calculate correlation with BTC or major indices
        if (symbol.startsWith("BTC")) {
            return 1.0; // BTC is the market
        } else if (symbol.startsWith("ETH")) {
            return 0.8; // ETH highly correlated with BTC
        } else {
            return 0.6; // Most altcoins have moderate correlation
        }
    }
    
    /**
     * Get base leverage from volatility
     */
    private int getBaseLeverageFromVolatility(double volatility) {
        if (volatility > 0.05) {       // High volatility (>5%)
            return 2;
        } else if (volatility > 0.03) { // Medium volatility (3-5%)
            return 5;
        } else if (volatility > 0.01) { // Low volatility (1-3%)
            return 10;
        } else {                       // Very low volatility (<1%)
            return 20;
        }
    }
    


    private List<String> getDefaultTradingPairs() {
        return Arrays.asList(
            "BTCUSDT", "ETHUSDT", "DOGEUSDT", "NXPCUSDT", "MOVRUSDT", "KSMUSDT"
        );
    }
    
    private JsonNode getHistoricalData(String symbol, String interval, int limit, String exchange, ScanConfig.MarketType marketType) {
        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                long endTime = System.currentTimeMillis();
                long startTime = endTime - (limit * getIntervalMillis(interval));
                String category = marketType == ScanConfig.MarketType.LINEAR ? "linear" : "spot";
                ResponseEntity<String> response = bybitApiClientService.getKlineData(
                    symbol, interval, limit, startTime, endTime, category);
                return objectMapper.readTree(response.getBody()).get("result").get("list");
            } else {
                long endTime = System.currentTimeMillis();
                long startTime = endTime - (limit * getIntervalMillis(interval));
                if (marketType == ScanConfig.MarketType.FUTURES) {
                    ResponseEntity<String> response = mexcFuturesApiService.getFuturesKlines(
                        symbol, interval, startTime / 1000, endTime / 1000);
                    return objectMapper.readTree(response.getBody()).get("data");
                } else {
                    ResponseEntity<String> response = mexcApiClientService.getSpotKlines(
                        symbol, interval, startTime, endTime, limit);
                    return objectMapper.readTree(response.getBody());
                }
            }
        } catch (Exception e) {
            logger.error("Error getting historical data for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    private long getIntervalMillis(String interval) {
        interval = interval.toLowerCase();
        switch (interval) {
            case "1m": return 60_000L;
            case "3m": return 180_000L;
            case "5m": return 300_000L;
            case "15m": return 900_000L;
            case "30m": return 1_800_000L;
            case "1h": return 3_600_000L;
            case "2h": return 7_200_000L;
            case "4h": return 14_400_000L;
            case "6h": return 21_600_000L;
            case "12h": return 43_200_000L;
            case "1d": return 86_400_000L;
            case "1w": return 604_800_000L;
            case "1M": return 2_592_000_000L;
            default: return 3_600_000L; // Default to 1 hour
        }
    }
    
    /**
     * Generate enhanced signal summary using adaptive weights, regime analysis, performance learning, and AI analysis
     */
    private String generateEnhancedSignalSummary(Map<String, TechnicalIndicator> indicators, 
                                               ScanConfig config, 
                                               Map<String, Double> finalWeights,
                                               MarketRegimeService.RegimeAnalysis regimeAnalysis,
                                               String aiAnalysis) {

        if (finalWeights == null) {
            logger.error("CRITICAL: generateEnhancedSignalSummary called with null finalWeights for symbol {}. Returning NEUTRAL.", config.getTradingPairs());
            return "NEUTRAL";
        }

        // 1. Get base technical score
        double technicalScore = signalWeightingService.convertTechnicalToScore(indicators);
        
        // AI score - NOW USES ACTUAL AI ANALYSIS!
        double aiScore = signalWeightingService.convertAiToScore(aiAnalysis);
        
        // Sentiment score (would be set from sentiment analysis if available)
        double sentimentScore = 0.5; // Default neutral
        
        // Calculate weighted signal score using the final adaptive weights
        double weightedScore = (technicalScore * finalWeights.getOrDefault("technical", 0.4)) +
                              (aiScore * finalWeights.getOrDefault("ai", 0.4)) +
                              (sentimentScore * finalWeights.getOrDefault("sentiment", 0.2));
        
        // Adjust signal strength based on market regime confidence
        double regimeConfidence = regimeAnalysis.getConfidence();
        double adjustedScore = weightedScore * (0.5 + (regimeConfidence * 0.5));
        
        // Generate signal based on adjusted score
        String baseSignal;
        if (adjustedScore >= 0.7) {
            baseSignal = "STRONG_BUY";
        } else if (adjustedScore >= 0.55) {
            baseSignal = "BUY";
        } else if (adjustedScore <= 0.3) {
            baseSignal = "STRONG_SELL";
        } else if (adjustedScore <= 0.45) {
            baseSignal = "SELL";
        } else {
            baseSignal = "NEUTRAL";
        }
        
        // Apply regime-specific signal filtering
        String finalSignal = applyRegimeSignalFiltering(baseSignal, regimeAnalysis);
        
        logger.debug("Enhanced signal with AI for {}: technical={:.2f}, ai={:.2f}, weighted={:.2f}, adjusted={:.2f}, regime={}, final={}", 
                    config.getTradingPairs() != null && !config.getTradingPairs().isEmpty() ? config.getTradingPairs().get(0) : "unknown",
                    technicalScore, aiScore, weightedScore, adjustedScore, regimeAnalysis.getRegime(), finalSignal);
        
        return finalSignal;
    }
    
    // Keep the original method for backward compatibility
    private String generateEnhancedSignalSummary(Map<String, TechnicalIndicator> indicators, 
                                               ScanConfig config, 
                                               Map<String, Double> finalWeights,
                                               MarketRegimeService.RegimeAnalysis regimeAnalysis) {
        // Call the new method with null AI analysis (falls back to neutral)
        return generateEnhancedSignalSummary(indicators, config, finalWeights, regimeAnalysis, null);
    }
    
    /**
     * Apply regime-specific signal filtering
     */
    private String applyRegimeSignalFiltering(String baseSignal, MarketRegimeService.RegimeAnalysis regimeAnalysis) {
        MarketRegimeService.MarketRegime regime = regimeAnalysis.getRegime();
        
        switch (regime) {
            case BULL_MARKET:
                // In bull markets, be more conservative with SELL signals
                if ("STRONG_SELL".equals(baseSignal)) {
                    return "SELL";
                } else if ("SELL".equals(baseSignal)) {
                    return "NEUTRAL";
                }
                break;
                
            case BEAR_MARKET:
                // In bear markets, be more conservative with BUY signals
                if ("STRONG_BUY".equals(baseSignal)) {
                    return "BUY";
                } else if ("BUY".equals(baseSignal)) {
                    return "NEUTRAL";
                }
                break;
                
            case LOW_VOLUME_MARKET:
                // In low volume markets, be conservative with all signals
                if ("STRONG_BUY".equals(baseSignal) || "STRONG_SELL".equals(baseSignal)) {
                    return baseSignal.replace("STRONG_", "");
                }
                break;
                
            case TRANSITION_MARKET:
                // In transition markets, only allow neutral signals unless very strong
                if (!"STRONG_BUY".equals(baseSignal) && !"STRONG_SELL".equals(baseSignal)) {
                    return "NEUTRAL";
                }
                break;
        }
        
        return baseSignal; // No filtering applied
    }
    
    /**
     * Ultimate enhanced market scanning with all advanced features
     */
    public List<ScanResult> scanMarketWithAllFeatures(ScanConfig config) {
        logger.info("Starting ultimate enhanced market scan with all advanced features");
        
        List<ScanResult> results = new ArrayList<>();
        
        try {
            List<String> symbols = config.getTradingPairs() != null && !config.getTradingPairs().isEmpty() 
                ? config.getTradingPairs() 
                : getDefaultTradingPairs();
            
            for (String symbol : symbols) {
                try {
                    ScanResult result = scanSinglePairWithAllFeatures(symbol, config.getInterval(), config);
                    if (result != null && meetsFilterCriteria(result, config)) {
                        results.add(result);
                    }
                } catch (Exception e) {
                    logger.error("Error scanning {} with all features: {}", symbol, e.getMessage());
                }
            }
            
            // Sort by enhanced confidence score
            results.sort((a, b) -> Double.compare(b.getConfidence(), a.getConfidence()));
            
            logger.info("Ultimate enhanced scan completed. Found {} qualifying signals", results.size());
            
        } catch (Exception e) {
            logger.error("Error in ultimate enhanced market scan: {}", e.getMessage());
        }
        
        return results;
    }
    
    /**
     * Scan single pair with all advanced features
     */
    private ScanResult scanSinglePairWithAllFeatures(String symbol, String interval, ScanConfig config) {
        try {
            logger.debug("Scanning {} with all advanced features", symbol);
            
            // 1. Basic technical analysis
            ScanResult baseResult = scanSinglePair(symbol, interval, config);
            if (baseResult == null) return null;
            
            // 2. Generate enhanced AI context
            EnhancedAIContextService.AIContext aiContext = enhancedAIContextService
                .generateEnhancedContext(symbol, config.getExchange(), config.getMarketType());
            
            // 3. Get adaptive weights based on market regime
            Map<String, Double> baseWeights = signalWeightingService.calculateAdaptiveWeights(symbol, interval);
            Map<String, Double> regimeAdjustedWeights = marketRegimeService
                .adjustWeightsForRegime(baseWeights, aiContext.getMarketRegime().getRegime());
            
            // 4. Get learned weights from performance history
            Map<String, Double> learnedWeights = performanceLearningService.getLearnedWeights(symbol, interval);
            
            // 5. Combine all weight systems
            Map<String, Double> finalWeights = combineWeightSystems(regimeAdjustedWeights, learnedWeights);
            
            // 6. Calculate multi-timeframe confluence
            MultiTimeframeService.ConfluenceResult confluence = multiTimeframeService
                .analyzeMultiTimeframeConfluence(symbol, config.getExchange(), config.getMarketType());
            
            // 7. Apply Kelly Criterion for position sizing
            BigDecimal kellyPositionSize = calculateKellyPositionSize(symbol, baseResult, aiContext);
            
            // 8. Enhanced signal generation with all context
            String aiAnalysis = null;
            if (config.isIncludeAiAnalysis()) {
                // Get AI analysis FIRST
                aiAnalysis = getAIAnalysis(baseResult, symbol);
                baseResult.setAiAnalysis(aiAnalysis);
            }
            
            String enhancedSignal = generateUltimateSignal(baseResult, aiAnalysis, aiContext, confluence, finalWeights);
            baseResult.setSignal(enhancedSignal);
            
            // 9. Enhanced confidence calculation
            double enhancedConfidence = calculateUltimateConfidence(baseResult, aiContext, confluence, finalWeights);
            baseResult.setConfidence(enhancedConfidence);
            
            // 10. Set enhanced recommendations
            baseResult.setPositionSizeRecommendation(kellyPositionSize);
            baseResult.setRiskLevel(aiContext.getRiskMetrics().getRiskLevel());
            
            // 11. Enhanced AI analysis with full context
            String contextualAIAnalysis = generateContextualAIAnalysis(baseResult, aiContext);
            baseResult.setAiAnalysis(contextualAIAnalysis);
            
            logger.debug("Enhanced scan completed for {}: signal={}, confidence={}", 
                        symbol, enhancedSignal, enhancedConfidence);
            
            return baseResult;
            
        } catch (Exception e) {
            logger.error("Error in enhanced single pair scan for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    /**
     * Combine multiple weight systems
     */
    private Map<String, Double> combineWeightSystems(Map<String, Double> regimeWeights, Map<String, Double> learnedWeights) {
        if (regimeWeights == null || learnedWeights == null) {
            logger.error("Regime or Learned weights are null. Cannot combine. Regime: {}, Learned: {}", regimeWeights, learnedWeights);
            // Return a default, safe set of weights
            Map<String, Double> defaultWeights = new HashMap<>();
            defaultWeights.put("technical", 0.4);
            defaultWeights.put("ai", 0.4);
            defaultWeights.put("sentiment", 0.2);
            return defaultWeights;
        }

        Map<String, Double> finalWeights = new HashMap<>();
        Set<String> allKeys = new HashSet<>(regimeWeights.keySet());
        allKeys.addAll(learnedWeights.keySet());

        for (String key : allKeys) {
            double regimeWeight = regimeWeights.getOrDefault(key, 0.33);
            double learnedWeight = learnedWeights.getOrDefault(key, 0.33);
            // Combine them, e.g., with a 50/50 blend
            finalWeights.put(key, (regimeWeight + learnedWeight) / 2.0);
        }
        
        // Normalize the final weights to sum to 1.0
        double totalWeight = finalWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            final double normalizer = totalWeight;
            finalWeights.replaceAll((k, v) -> v / normalizer);
        } else {
            logger.warn("Total combined weight is zero. Returning default weights.");
            finalWeights.put("technical", 0.4);
            finalWeights.put("ai", 0.4);
            finalWeights.put("sentiment", 0.2);
        }
        
        logger.debug("Combined weights: {}", finalWeights);
        return finalWeights;
    }
    
    private BigDecimal calculateKellyPositionSize(String symbol, ScanResult result, EnhancedAIContextService.AIContext aiContext) {
        // This is a placeholder for a more sophisticated Kelly Criterion calculation
        // It would require historical win/loss data for the specific setup
        logger.warn("Kelly Criterion position sizing not fully implemented. Using default risk management.");
        return null;
    }

    private String generateUltimateSignal(ScanResult baseResult, String aiAnalysis, EnhancedAIContextService.AIContext aiContext,
                                         MultiTimeframeService.ConfluenceResult confluence, Map<String, Double> weights) {
        // Simple weighted average of signals
        double technicalScore = signalWeightingService.convertTechnicalToScore(baseResult.getIndicators());
        double aiScore = signalWeightingService.convertAiToScore(aiAnalysis);
        double sentimentScore = aiContext.getMarketRegime().getRegimeScores().getOrDefault("sentiment", 0.5);
        double confluenceScore = confluence.getConfluenceStrength();

        double finalScore = (technicalScore * weights.getOrDefault("technical", 0.3)) +
                            (aiScore * weights.getOrDefault("ai", 0.4)) +
                            (sentimentScore * weights.getOrDefault("sentiment", 0.1)) +
                            (confluenceScore * weights.getOrDefault("confluence", 0.2));

        if (finalScore > 0.7) return "STRONG_BUY";
        if (finalScore > 0.55) return "BUY";
        if (finalScore < 0.3) return "STRONG_SELL";
        if (finalScore < 0.45) return "SELL";
        return "NEUTRAL";
    }

    private double calculateUltimateConfidence(ScanResult baseResult, EnhancedAIContextService.AIContext aiContext,
                                              MultiTimeframeService.ConfluenceResult confluence, Map<String, Double> weights) {
        double baseConfidence = baseResult.getConfidence();
        
        // Confluence boost
        if (confluence.isHasConfluence()) {
            baseConfidence += confluence.getConfluenceStrength() * 0.3;
        }
        
        // Market regime clarity boost
        if (aiContext.getMarketRegime().getConfidence() > 0.7) {
            baseConfidence += 0.1;
        }
        
        // Historical performance boost
        EnhancedAIContextService.HistoricalPerformance perf = aiContext.getHistoricalPerformance();
        if (perf.getTotalTrades() > 10 && perf.getWinRate() > 0.6) {
            baseConfidence += 0.1;
        }
        
        // Risk penalty
        if ("HIGH".equals(aiContext.getRiskMetrics().getRiskLevel())) {
            baseConfidence -= 0.15;
        }
        
        return Math.max(0.0, Math.min(1.0, baseConfidence));
    }
    
    /**
     * Generate contextual AI analysis
     */
    private String generateContextualAIAnalysis(ScanResult baseResult, EnhancedAIContextService.AIContext aiContext) {
        StringBuilder analysis = new StringBuilder();
        
        analysis.append("ENHANCED AI ANALYSIS:\n\n");
        analysis.append(enhancedAIContextService.formatContextForAI(aiContext));
        analysis.append("\n\nTECHNICAL SIGNALS:\n");
        analysis.append(baseResult.getSignalSummary());
        
        // Add AI recommendations
        if (aiContext.getRecommendations() != null) {
            analysis.append("\n\nAI RECOMMENDATIONS:\n");
            analysis.append("Primary: ").append(aiContext.getRecommendations().getPrimaryRecommendation()).append("\n");
            analysis.append("Confidence: ").append(String.format("%.1f%%", 
                aiContext.getRecommendations().getConfidenceLevel() * 100)).append("\n");
            analysis.append("Position Sizing: ").append(aiContext.getRecommendations().getPositionSizingAdvice()).append("\n");
            analysis.append("Exit Strategy: ").append(aiContext.getRecommendations().getExitStrategyAdvice()).append("\n");
        }
        
        return analysis.toString();
    }
    
    /**
     * Get regime-based adjustment factor
     */
    private double getRegimeAdjustment(MarketRegimeService.RegimeAnalysis regime) {
        if (regime == null) return 0.0;
        
        switch (regime.getRegime()) {
            case BULL_MARKET:
            case BEAR_MARKET:
                return regime.getConfidence() > 0.7 ? 0.1 : 0.05; // Boost for clear trends
            case VOLATILE_MARKET:
                return -0.05; // Slight penalty for volatile conditions
            case LOW_VOLUME_MARKET:
                return -0.1; // Penalty for low volume
            default:
                return 0.0;
        }
    }

    /**
     * Enhanced scan market method that includes multi-timeframe confluence
     */
    public List<ScanResult> scanMarketWithConfluence(ScanConfig config) {
        logger.info("Starting enhanced market scan with multi-timeframe confluence");
        
        try {
            // Get trading pairs
            List<String> tradingPairs = config.getTradingPairs();
            if (tradingPairs == null || tradingPairs.isEmpty()) {
                tradingPairs = getDefaultTradingPairs();
            }
            
            String exchange = config.getExchange() != null ? config.getExchange() : "BYBIT";
            ScanConfig.MarketType marketType = config.getMarketType() != null ? config.getMarketType() : ScanConfig.MarketType.SPOT;
            
            // Get confluence signals (only signals with multi-timeframe agreement)
            List<MultiTimeframeService.ConfluenceResult> confluenceResults = 
                multiTimeframeService.getConfluenceSignals(tradingPairs, exchange, marketType);
            
            List<ScanResult> results = new ArrayList<>();
            
            // Convert confluence results to scan results
            for (MultiTimeframeService.ConfluenceResult confluenceResult : confluenceResults) {
                try {
                    // Find the symbol in confluence result
                    String symbol = confluenceResult.getTimeframeAnalyses().isEmpty() ? 
                        "UNKNOWN" : confluenceResult.getTimeframeAnalyses().get(0).getTimeframe();
                    
                    // Create enhanced scan result
                    ScanResult result = new ScanResult();
                    result.setSymbol(symbol);
                    result.setSignal(confluenceResult.getFinalSignal());
                    result.setInterval("MULTI"); // Indicates multi-timeframe analysis
                    result.setTimestamp(System.currentTimeMillis());
                    result.setMarketType(marketType.getValue());
                    
                    // Add confluence-specific information
                    result.setAiAnalysis(String.format(
                        "Multi-timeframe confluence analysis: %s (Confluence: %.1f%%). %s", 
                        confluenceResult.getFinalSignal(),
                        confluenceResult.getConfluenceStrength() * 100,
                        confluenceResult.getConfluenceReason()
                    ));
                    
                    results.add(result);
                    
                } catch (Exception e) {
                    logger.error("Error processing confluence result: {}", e.getMessage());
                }
            }
            
            logger.info("Enhanced market scan completed. Found {} confluence signals", results.size());
            return results;
            
        } catch (Exception e) {
            logger.error("Error in enhanced market scan: {}", e.getMessage());
            // Fallback to regular scan
            return scanMarket(config);
        }
    }

    private String getAIAnalysis(ScanResult result, String symbol) {
        String analysis = "NEUTRAL";
        // Check if AI is enabled in config before proceeding
        if (!aiAnalysisService.isAiEffectivelyEnabled()) {
           return "AI_DISABLED";
        }
        try {
            // Correctly call the AI analysis service. This method exists and 
            // handles its own context building and queuing internally.
            CompletableFuture<String> analysisFuture = aiAnalysisService.analyzeMarketDataAsync(result);
            
            // Use the existing retry wrapper to await the result
            analysis = getAnalysisWithRetry(analysisFuture, symbol, 3);
        } catch (Exception e) {
            // Log any unexpected errors during the async call submission
            logger.error("Error dispatching AI analysis for {}: {}", symbol, e.getMessage());
        }
        return analysis;
    }
} 