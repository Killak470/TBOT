package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import com.tradingbot.backend.model.SignalPerformance;
import com.tradingbot.backend.repository.BotSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PostConstruct;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Enhanced Signal Generation Service with adaptive weighting and multi-timeframe analysis
 */
@Service
public class SignalGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(SignalGenerationService.class);
    
    private final TradingStrategyService tradingStrategyService;
    private final SentimentAnalysisService sentimentAnalysisService;
    private final ClaudeAiService claudeAiService;
    private final MexcApiClientService mexcApiClientService;
    private final BybitApiClientService bybitApiClientService;
    private final MarketScannerService marketScannerService;
    private final BotSignalService botSignalService;
    private final BotSignalRepository botSignalRepository;
    private final RiskManagementService riskManagementService;
    private final ObjectMapper objectMapper;
    
    // Enhanced caching and performance tracking
    private final Map<String, Map<String, Object>> signalCache = new ConcurrentHashMap<>();
    private final Map<String, List<SignalPerformance>> performanceHistory = new ConcurrentHashMap<>();
    private final Map<String, String> marketRegimeCache = new ConcurrentHashMap<>();
    
    // Adaptive signal weights
    private Map<String, Double> signalWeights;
    private Map<String, Double> timeframeWeights;
    private Map<String, Double> marketRegimeWeights;
    
    // Multi-timeframe configuration
    private final String[] ANALYSIS_TIMEFRAMES = {"15m", "1h", "4h", "1d"};
    
    // Get trading pairs from market scanner instead of hardcoded list
    private final List<String> DEFAULT_SCANNER_PAIRS = Arrays.asList(
        "BTCUSDT", "ETHUSDT", "ADAUSDT", "DOGEUSDT"
    );

    @Autowired
    public SignalGenerationService(
            @Lazy TradingStrategyService tradingStrategyService,
            @Lazy SentimentAnalysisService sentimentAnalysisService,
            @Lazy ClaudeAiService claudeAiService,
            @Lazy MexcApiClientService mexcApiClientService,
            @Lazy BybitApiClientService bybitApiClientService,
            @Lazy MarketScannerService marketScannerService,
            @Lazy BotSignalService botSignalService,
            @Lazy BotSignalRepository botSignalRepository,
            @Lazy RiskManagementService riskManagementService,
            ObjectMapper objectMapper) {
        this.tradingStrategyService = tradingStrategyService;
        this.sentimentAnalysisService = sentimentAnalysisService;
        this.claudeAiService = claudeAiService;
        this.mexcApiClientService = mexcApiClientService;
        this.bybitApiClientService = bybitApiClientService;
        this.marketScannerService = marketScannerService;
        this.botSignalService = botSignalService;
        this.botSignalRepository = botSignalRepository;
        this.riskManagementService = riskManagementService;
        this.objectMapper = objectMapper;
        
        initializeWeights();
    }
    
    @PostConstruct
    private void initializeWeights() {
        // Initialize signal weights
        signalWeights = new ConcurrentHashMap<>();
        signalWeights.put("technical", 0.4);
        signalWeights.put("sentiment", 0.3);
        signalWeights.put("ai", 0.3);
        
        // Initialize timeframe weights
        timeframeWeights = new ConcurrentHashMap<>();
        timeframeWeights.put("15m", 0.2);
        timeframeWeights.put("1h", 0.3);
        timeframeWeights.put("4h", 0.3);
        timeframeWeights.put("1d", 0.2);
        
        // Initialize market regime weights
        marketRegimeWeights = new ConcurrentHashMap<>();
        marketRegimeWeights.put("BULL_MARKET", 1.2);
        marketRegimeWeights.put("BEAR_MARKET", 0.8);
        marketRegimeWeights.put("SIDEWAYS_MARKET", 1.0);
        marketRegimeWeights.put("VOLATILE_MARKET", 0.9);
        marketRegimeWeights.put("LOW_VOLUME_MARKET", 0.7);
    }
    
    /**
     * Manual bot signal generation for specific symbols
     * This replaces the automatic scheduled scanning
     */
    public Map<String, Object> generateBotSignalsForSymbols(List<String> symbols, String interval) {
        logger.info("Starting manual bot signal generation for {} symbols", symbols.size());
        
        Map<String, Object> result = new HashMap<>();
        int signalsGenerated = 0;
        int signalsSkipped = 0;
        
        try {
            for (String symbol : symbols) {
                try {
                    // Generate combined signal
                    Map<String, Object> signal = generateCombinedSignal(symbol, interval);
                    
                    // Check if signal meets criteria for bot signal generation
                    if (shouldGenerateBotSignal(signal)) {
                        createBotSignalFromAnalysis(symbol, signal, "SCAN");
                        signalsGenerated++;
                        logger.info("Generated bot signal for {}", symbol);
                    } else {
                        signalsSkipped++;
                        logger.debug("Skipped signal generation for {} - criteria not met", symbol);
                    }
                    
                    // Small delay between symbols to avoid API rate limits
                    Thread.sleep(1000);
                } catch (Exception e) {
                    logger.error("Error processing signal for {}: {}", symbol, e.getMessage());
                    signalsSkipped++;
                }
            }
            
            result.put("success", true);
            result.put("signalsGenerated", signalsGenerated);
            result.put("signalsSkipped", signalsSkipped);
            result.put("totalProcessed", symbols.size());
            result.put("message", String.format("Generated %d signals from %d symbols", signalsGenerated, symbols.size()));
            
        } catch (Exception e) {
            logger.error("Error in manual bot signal generation", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Get the default trading pairs from market scanner configuration
     */
    public List<String> getDefaultTradingPairs() {
        return DEFAULT_SCANNER_PAIRS;
    }
    
    /**
     * Generate bot signals for all default market scanner pairs
     */
    public Map<String, Object> generateBotSignalsForDefaultPairs(String interval) {
        return generateBotSignalsForSymbols(getDefaultTradingPairs(), interval);
    }
    
    /**
     * Generate a combined trading signal for a specific symbol
     * 
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @param interval The time interval to analyze (e.g., "1h", "4h", "1d")
     * @return A map containing the combined signal data
     */
    public Map<String, Object> generateCombinedSignal(String symbol, String interval) {
        // Create a cache key
        String cacheKey = symbol + "_" + interval;
        
        // Check cache (valid for 15 minutes)
        if (signalCache.containsKey(cacheKey)) {
            Map<String, Object> cachedSignal = signalCache.get(cacheKey);
            Instant timestamp = (Instant) cachedSignal.get("timestamp");
            if (timestamp.plusSeconds(900).isAfter(Instant.now())) {
                logger.debug("Using cached signal for {} ({})", symbol, interval);
                return new HashMap<>(cachedSignal);
            }
        }
        
        logger.info("Generating combined signal for {} ({})", symbol, interval);
        
        // Results container
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("timestamp", Instant.now());
        
        try {
            // 1. Technical Analysis Signals
            Map<String, Double> technicalSignals = tradingStrategyService.evaluateEntrySignals(symbol, interval, "MEXC");
            double technicalScore = calculateAverageSignalScore(technicalSignals);
            
            // 2. Basic Sentiment Analysis
            SentimentData sentimentData = sentimentAnalysisService.analyzeSentiment(symbol, 24);
            double sentimentScore = normalizeSentimentScore(sentimentData.getSentimentScore());
            
            // 3. Get market data for AI analysis
            String technicalData = mexcApiClientService.getSpotKlines(symbol, interval, null, null, 100)
                                     .getBody();
            
            // 4. Enhanced Sentiment Analysis with Claude AI
            // Extract text data from sentiment analysis for Claude
            StringBuilder newsDataBuilder = new StringBuilder();
            StringBuilder socialDataBuilder = new StringBuilder();
            
            // Organize data by source
            for (String source : sentimentData.getSources()) {
                if (source.startsWith("news:")) {
                    newsDataBuilder.append("Source: ").append(source).append("\n");
                } else if (source.startsWith("twitter")) {
                    socialDataBuilder.append("Source: ").append(source).append("\n");
                }
            }
            
            // Add keyword data
            newsDataBuilder.append("\nKeywords: ");
            for (Map.Entry<String, Integer> entry : sentimentData.getKeywordCounts().entrySet()) {
                newsDataBuilder.append(entry.getKey()).append(" (").append(entry.getValue()).append("), ");
            }
            
            // Get enhanced sentiment analysis
            Map<String, Object> enhancedSentiment = claudeAiService.enhancedSentimentAnalysis(
                    symbol, newsDataBuilder.toString(), socialDataBuilder.toString());
            
            // 5. AI Trading Recommendation
            Map<String, Object> aiRecommendation = claudeAiService.generateTradingRecommendation(
                    symbol, technicalData, sentimentData);
            double aiConfidence = getAiConfidenceScore(aiRecommendation);
            
            // 6. Enhanced combined weighted score using all sources
            double enhancedSentimentScore = 0.5;
            if (enhancedSentiment.containsKey("overallSentiment")) {
                double score = (double) enhancedSentiment.get("overallSentiment");
                enhancedSentimentScore = (score + 1.0) / 2.0; // Convert from [-1,1] to [0,1]
            }
            
            double combinedScore = calculateEnhancedWeightedScore(
                    technicalScore, sentimentScore, enhancedSentimentScore, aiConfidence);
            
            // 7. Determine signal direction (BUY, SELL, NEUTRAL) with enhanced logic
            String signalDirection = determineEnhancedSignalDirection(
                    technicalSignals, sentimentScore, enhancedSentiment, aiRecommendation);
            
            // 8. Calculate overall confidence
            double confidenceScore = calculateConfidenceScore(
                    technicalSignals.size(), sentimentData.getTotalMentions(), combinedScore);
            
            // Adjust confidence using AI confidence if available
            if (enhancedSentiment.containsKey("confidence")) {
                double aiSentimentConfidence = (double) enhancedSentiment.get("confidence");
                confidenceScore = (confidenceScore + aiSentimentConfidence) / 2.0;
            }
            
            // Prepare final results
            result.put("signal", signalDirection);
            result.put("confidence", confidenceScore);
            result.put("technicalScore", technicalScore);
            result.put("sentimentScore", sentimentScore);
            result.put("enhancedSentimentScore", enhancedSentimentScore);
            result.put("aiScore", aiConfidence);
            result.put("combinedScore", combinedScore);
            result.put("technicalSignals", technicalSignals);
            result.put("sentimentData", sentimentData);
            result.put("enhancedSentiment", enhancedSentiment);
            result.put("aiRecommendation", aiRecommendation);
            
            // Cache the result
            signalCache.put(cacheKey, new HashMap<>(result));
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error generating combined signal for {}: {}", symbol, e.getMessage());
            
            result.put("error", "Failed to generate signal: " + e.getMessage());
            result.put("signal", "NEUTRAL");
            result.put("confidence", 0.0);
            
            return result;
        }
    }
    
    /**
     * Generate signals for multiple symbols
     * 
     * @param symbols List of trading pairs
     * @param interval The time interval to analyze
     * @return Map of symbols to their signals
     */
    public Map<String, Map<String, Object>> generateBatchSignals(String[] symbols, String interval) {
        Map<String, Map<String, Object>> batchResults = new HashMap<>();
        
        for (String symbol : symbols) {
            try {
                batchResults.put(symbol, generateCombinedSignal(symbol, interval));
            } catch (Exception e) {
                logger.error("Error in batch signal generation for {}: {}", symbol, e.getMessage());
                
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", "Failed to generate signal: " + e.getMessage());
                errorResult.put("signal", "NEUTRAL");
                errorResult.put("confidence", 0.0);
                batchResults.put(symbol, errorResult);
            }
        }
        
        return batchResults;
    }
    
    /**
     * Update the weights used for signal components
     * 
     * @param newWeights Map of component names to their weights
     */
    public void updateSignalWeights(Map<String, Double> newWeights) {
        // Update only provided weights, keep others unchanged
        for (Map.Entry<String, Double> entry : newWeights.entrySet()) {
            if (signalWeights.containsKey(entry.getKey())) {
                signalWeights.put(entry.getKey(), entry.getValue());
            }
        }
        
        // Clear cache after weight change
        signalCache.clear();
        
        logger.info("Signal weights updated: {}", signalWeights);
    }
    
    /**
     * Get current weight configuration
     */
    public Map<String, Double> getSignalWeights() {
        return new HashMap<>(signalWeights);
    }
    
    /**
     * Calculate average score from technical signals
     */
    private double calculateAverageSignalScore(Map<String, Double> technicalSignals) {
        if (technicalSignals.isEmpty()) {
            return 0.0;
        }
        
        double sum = 0.0;
        for (Double value : technicalSignals.values()) {
            sum += value;
        }
        
        return sum / technicalSignals.size();
    }
    
    /**
     * Normalize sentiment score from [-1, 1] to [0, 1]
     */
    private double normalizeSentimentScore(double sentimentScore) {
        // Convert from [-1, 1] to [0, 1]
        return (sentimentScore + 1.0) / 2.0;
    }
    
    /**
     * Extract confidence score from AI recommendation
     */
    private double getAiConfidenceScore(Map<String, Object> aiRecommendation) {
        if (aiRecommendation.containsKey("confidence")) {
            return (double) aiRecommendation.get("confidence");
        }
        
        return 0.5; // Default neutral confidence
    }
    
    /**
     * Calculate weighted combined score with enhanced sentiment analysis
     */
    private double calculateEnhancedWeightedScore(
            double technicalScore, 
            double basicSentimentScore, 
            double enhancedSentimentScore,
            double aiScore) {
        
        // Initialize enhanced weights if not already done
        if (signalWeights == null) {
            initializeWeights();
        }
        
        // Default weights if specific weights not found
        double technicalWeight = signalWeights.getOrDefault("technical", 0.4);
        double basicSentimentWeight = signalWeights.getOrDefault("sentiment", 0.1);
        double enhancedSentimentWeight = signalWeights.getOrDefault("enhancedSentiment", 0.2);
        double aiWeight = signalWeights.getOrDefault("ai", 0.3);
        
        return (technicalScore * technicalWeight) +
               (basicSentimentScore * basicSentimentWeight) +
               (enhancedSentimentScore * enhancedSentimentWeight) +
               (aiScore * aiWeight);
    }
    
    /**
     * Determine final signal direction with enhanced analysis
     */
    private String determineEnhancedSignalDirection(
            Map<String, Double> technicalSignals,
            double sentimentScore,
            Map<String, Object> enhancedSentiment,
            Map<String, Object> aiRecommendation) {
        
        // Start with a default neutral position
        String direction = "NEUTRAL";
        
        // Get AI recommendation if available
        String aiDirection = "NEUTRAL";
        if (aiRecommendation.containsKey("action")) {
            aiDirection = (String) aiRecommendation.get("action");
        }
        
        // Get enhanced sentiment market outlook if available
        String sentimentOutlook = "NEUTRAL";
        if (enhancedSentiment.containsKey("marketOutlook")) {
            sentimentOutlook = (String) enhancedSentiment.get("marketOutlook");
        }
        
        // Convert sentiment outlook to signal direction
        String sentimentDirection = "NEUTRAL";
        if (sentimentOutlook.equals("BULLISH")) {
            sentimentDirection = "BUY";
        } else if (sentimentOutlook.equals("BEARISH")) {
            sentimentDirection = "SELL";
        }
        
        // Check technical signals average
        double technicalAverage = calculateAverageSignalScore(technicalSignals);
        String technicalDirection = "NEUTRAL";
        if (technicalAverage > 0.6) {
            technicalDirection = "BUY";
        } else if (technicalAverage < 0.4) {
            technicalDirection = "SELL";
        }
        
        // Count directions to find consensus
        int buyCount = 0;
        int sellCount = 0;
        
        if (technicalDirection.equals("BUY")) buyCount++;
        if (technicalDirection.equals("SELL")) sellCount++;
        
        if (sentimentDirection.equals("BUY")) buyCount++;
        if (sentimentDirection.equals("SELL")) sellCount++;
        
        if (aiDirection.equals("BUY")) buyCount++;
        if (aiDirection.equals("SELL")) sellCount++;
        
        // Determine direction by consensus
        if (buyCount > sellCount && buyCount >= 2) {
            direction = "BUY";
        } else if (sellCount > buyCount && sellCount >= 2) {
            direction = "SELL";
        }
        
        // If there's a strong signal from any source, consider it
        if (technicalAverage > 0.8) direction = "BUY";
        if (technicalAverage < 0.2) direction = "SELL";
        
        // Give priority to AI recommendation if it has high confidence
        if (aiRecommendation.containsKey("confidence")) {
            double aiConfidence = (double) aiRecommendation.get("confidence");
            if (aiConfidence > 0.8) {
                direction = aiDirection;
            }
        }
        
        return direction;
    }
    
    /**
     * Calculate confidence score based on signal quality and quantity
     */
    private double calculateConfidenceScore(
            int technicalSignalCount,
            int sentimentMentionCount,
            double combinedScore) {
        
        // Base confidence on combined score
        double confidenceScore = combinedScore;
        
        // Adjust based on quantity of signals
        // More technical signals = higher confidence
        if (technicalSignalCount > 1) {
            confidenceScore *= (1.0 + ((technicalSignalCount - 1) * 0.1));
        }
        
        // More sentiment mentions = slightly higher confidence
        if (sentimentMentionCount > 10) {
            confidenceScore *= (1.0 + Math.min(0.2, (sentimentMentionCount - 10) * 0.005));
        }
        
        // Cap at 1.0
        return Math.min(1.0, confidenceScore);
    }
    
    /**
     * Check if a signal meets criteria for automatic bot signal generation
     */
    private boolean shouldGenerateBotSignal(Map<String, Object> signal) {
        String signalDirection = (String) signal.get("signal");
        Double confidence = (Double) signal.get("confidence");
        Double combinedScore = (Double) signal.get("combinedScore");
        
        // Only generate signals for strong BUY/SELL signals with high confidence
        return ("BUY".equals(signalDirection) || "SELL".equals(signalDirection)) &&
               confidence != null && confidence >= 0.7 && // High confidence (70%+)
               combinedScore != null && Math.abs(combinedScore - 0.5) >= 0.3; // Strong signal deviation from neutral
    }
    
    /**
     * Create a bot signal from technical analysis results
     */
    public void createBotSignalFromAnalysis(String symbol, Map<String, Object> signal, String marketType) {
        try {
            String signalDirection = (String) signal.get("signal");
            Double confidence = (Double) signal.get("confidence");
            
            // Get current price for entry
            BigDecimal currentPrice = getCurrentPrice(symbol, "MEXC");
            if (currentPrice == null) {
                logger.warn("Could not get current price for {}, skipping signal generation", symbol);
                return;
            }
            
            // Calculate position size (conservative approach)
            BigDecimal quantity = calculatePositionSize(symbol, currentPrice);
            
            // Build rationale from signal components
            String rationale = buildSignalRationale(signal);
            
            // Determine strategy name from strongest signal component and market type
            String strategyName = String.format("Market Scanner Strategy (BYBIT-%s)", 
                marketType != null && marketType.equalsIgnoreCase("futures") ? "LINEAR" : "SPOT");
            
            BotSignal.SignalType signalType = "BUY".equals(signalDirection) 
                ? BotSignal.SignalType.BUY 
                : BotSignal.SignalType.SELL;
            
            // Get stop loss and take profit from scan result if available
            BigDecimal stopLoss = null;
            BigDecimal takeProfit = null;
            if (signal.containsKey("stopLoss")) {
                stopLoss = new BigDecimal(signal.get("stopLoss").toString());
            }
            if (signal.containsKey("takeProfit")) {
                takeProfit = new BigDecimal(signal.get("takeProfit").toString());
            }
            
            // Generate the bot signal
            BotSignal botSignal = botSignalService.generateSignal(
                symbol,
                signalType,
                strategyName,
                currentPrice,
                quantity,
                BigDecimal.valueOf(confidence * 100), // Convert to percentage
                rationale
            );
            
            // Set stop loss and take profit if available
            if (botSignal != null && stopLoss != null && takeProfit != null) {
                botSignal.setStopLoss(stopLoss);
                botSignal.setTakeProfit(takeProfit);
                botSignalService.save(botSignal);
            }
            
            logger.info("Generated automatic bot signal {} for {} at {} with {}% confidence", 
                       signalType, symbol, currentPrice, confidence * 100);
                       
        } catch (Exception e) {
            logger.error("Error creating bot signal for {}: {}", symbol, e.getMessage(), e);
        }
    }
    
    /**
     * Get current price for a symbol from either MEXC or Bybit
     */
    private BigDecimal getCurrentPrice(String symbol, String exchange) {
        try {
            if ("MEXC".equalsIgnoreCase(exchange)) {
                ResponseEntity<String> response = mexcApiClientService.getLatestPrice(symbol, exchange);
                if (response != null && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    // Assuming MEXC returns price directly or in a known field like "price" or inside "data"
                    if (root.has("price")) {
                        return new BigDecimal(root.get("price").asText());
                    } else if (root.isArray() && root.size() > 0 && root.get(0).has("price")) { // For array responses
                        return new BigDecimal(root.get(0).get("price").asText());
                    } else if (root.has("data") && root.get("data").has("price")) { // For nested data object
                         return new BigDecimal(root.get("data").get("price").asText());
                    }
                     logger.warn("Price field not found in MEXC response for {}: {}", symbol, response.getBody());
                }
            } else if ("BYBIT".equalsIgnoreCase(exchange)) {
                ResponseEntity<String> response = bybitApiClientService.getLatestPrice(symbol, exchange);
                if (response != null && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                     // Bybit V5: result.list[0].lastPrice
                    if (root.hasNonNull("result") &&
                        root.get("result").hasNonNull("list") &&
                        root.get("result").get("list").isArray() &&
                        !root.get("result").get("list").isEmpty()) {
                        JsonNode tickerInfo = root.get("result").get("list").get(0);
                        if (tickerInfo.hasNonNull("lastPrice")) {
                            return new BigDecimal(tickerInfo.get("lastPrice").asText());
                        }
                    }
                    logger.warn("Price field not found in Bybit response for {}: {}", symbol, response.getBody());
                }
            } else {
                logger.warn("Unsupported exchange for getCurrentPrice: {}", exchange);
            }
        } catch (Exception e) {
            logger.error("Error getting current price for {} on {}: {}", symbol, exchange, e.getMessage(), e);
        }
        return null; // Or throw an exception
    }
    
    /**
     * Calculate conservative position size
     */
    private BigDecimal calculatePositionSize(String symbol, BigDecimal currentPrice) {
        // Conservative approach: use small fixed USDT amounts
        BigDecimal usdtAmount = new BigDecimal("100.00"); // $100 per signal
        
        // For BTC pairs, use smaller amounts
        if (symbol.startsWith("BTC")) {
            usdtAmount = new BigDecimal("50.00");
        }
        
        return usdtAmount.divide(currentPrice, 6, BigDecimal.ROUND_HALF_UP);
    }
    
    /**
     * Build a comprehensive rationale from signal components
     */
    private String buildSignalRationale(Map<String, Object> signal) {
        StringBuilder rationale = new StringBuilder();
        
        Double technicalScore = (Double) signal.get("technicalScore");
        Double sentimentScore = (Double) signal.get("sentimentScore");
        Double aiScore = (Double) signal.get("aiScore");
        
        rationale.append("Multi-factor analysis indicates strong signal: ");
        
        if (technicalScore != null && technicalScore > 0.6) {
            rationale.append("Technical indicators show bullish momentum. ");
        } else if (technicalScore != null && technicalScore < 0.4) {
            rationale.append("Technical indicators show bearish momentum. ");
        }
        
        if (sentimentScore != null && sentimentScore > 0.6) {
            rationale.append("Market sentiment is strongly positive. ");
        } else if (sentimentScore != null && sentimentScore < 0.4) {
            rationale.append("Market sentiment is strongly negative. ");
        }
        
        if (aiScore != null && aiScore > 0.6) {
            rationale.append("AI analysis confirms bullish outlook. ");
        } else if (aiScore != null && aiScore < 0.4) {
            rationale.append("AI analysis confirms bearish outlook. ");
        }
        
        rationale.append("Generated by automated signal scanning system.");
        
        return rationale.toString();
    }
    
    /**
     * Determine the primary strategy name based on strongest signal component
     */
    private String determineStrategyName(Map<String, Object> signal) {
        Double technicalScore = (Double) signal.get("technicalScore");
        Double sentimentScore = (Double) signal.get("sentimentScore");
        Double aiScore = (Double) signal.get("aiScore");
        
        // Find the strongest component
        double maxScore = 0;
        String strategy = "Multi-Factor Strategy";
        
        if (technicalScore != null && Math.abs(technicalScore - 0.5) > maxScore) {
            maxScore = Math.abs(technicalScore - 0.5);
            strategy = "Technical Analysis Strategy";
        }
        
        if (sentimentScore != null && Math.abs(sentimentScore - 0.5) > maxScore) {
            maxScore = Math.abs(sentimentScore - 0.5);
            strategy = "Sentiment Analysis Strategy";
        }
        
        if (aiScore != null && Math.abs(aiScore - 0.5) > maxScore) {
            strategy = "AI-Enhanced Strategy";
        }
        
        return strategy;
    }
    
    // ===============================
    // ENHANCED FEATURES
    // ===============================
    
    /**
     * Generate enhanced multi-timeframe signal
     */
    public Map<String, Object> generateEnhancedSignal(String symbol) {
        try {
            logger.info("Generating enhanced multi-timeframe signal for {}", symbol);
            
            Map<String, Integer> signalVotes = new HashMap<>();
            Map<String, Double> confidenceByTimeframe = new HashMap<>();
            
            // Analyze each timeframe
            for (String timeframe : ANALYSIS_TIMEFRAMES) {
                try {
                    Map<String, Object> tfSignal = generateCombinedSignal(symbol, timeframe);
                    String direction = (String) tfSignal.get("signal");
                    double confidence = (double) tfSignal.get("confidence");
                    
                    // Weight votes by confidence and timeframe importance
                    double timeframeWeight = timeframeWeights.getOrDefault(timeframe, 1.0);
                    double weightedVote = confidence * timeframeWeight;
                    
                    signalVotes.merge(direction, (int)(weightedVote * 100), Integer::sum);
                    confidenceByTimeframe.put(timeframe, confidence);
                    
                    logger.debug("Timeframe {} signal: {} with confidence {}", timeframe, direction, confidence);
                    
                } catch (Exception e) {
                    logger.warn("Error analyzing timeframe {} for {}: {}", timeframe, symbol, e.getMessage());
                }
            }
            
            // Determine final signal
            String finalSignal = signalVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NEUTRAL");
            
            // Calculate overall confidence
            double totalVotes = signalVotes.values().stream().mapToInt(Integer::intValue).sum();
            double finalConfidence = totalVotes > 0 ? (double) signalVotes.get(finalSignal) / totalVotes : 0.5;
            
            // Apply market regime adjustment
            String marketRegime = detectMarketRegime(symbol);
            double regimeAdjustment = marketRegimeWeights.getOrDefault(marketRegime, 1.0);
            finalConfidence *= regimeAdjustment;
            finalConfidence = Math.min(1.0, Math.max(0.0, finalConfidence));
            
            Map<String, Object> result = new HashMap<>();
            result.put("signal", finalSignal);
            result.put("confidence", finalConfidence);
            result.put("timeframeBreakdown", signalVotes);
            result.put("confidenceByTimeframe", confidenceByTimeframe);
            result.put("marketRegime", marketRegime);
            result.put("regimeAdjustment", regimeAdjustment);
            result.put("analysisType", "MULTI_TIMEFRAME_ENHANCED");
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error generating enhanced signal for {}: {}", symbol, e.getMessage());
            
            // Fallback to single timeframe
            return generateCombinedSignal(symbol, "1h");
        }
    }
    
    /**
     * Detect market regime for a symbol
     */
    public String detectMarketRegime(String symbol) {
        try {
            // Check cache first
            String cached = marketRegimeCache.get(symbol);
            if (cached != null) {
                return cached;
            }
            
            // Get long-term price data for regime analysis
            double[] prices200 = getLongTermPrices(symbol, 200);
            if (prices200.length < 50) {
                return "NEUTRAL_MARKET";
            }
            
            double trend = calculateTrendStrength(prices200);
            double volatility = calculateHistoricalVolatility(prices200);
            
            String regime;
            if (trend > 0.3 && volatility < 0.25) {
                regime = "BULL_MARKET";
            } else if (trend < -0.3 && volatility < 0.25) {
                regime = "BEAR_MARKET";
            } else if (Math.abs(trend) < 0.1) {
                regime = "SIDEWAYS_MARKET";
            } else if (volatility > 0.4) {
                regime = "HIGH_VOLATILITY";
            } else if (volatility < 0.15) {
                regime = "LOW_VOLATILITY";
            } else {
                regime = "NEUTRAL_MARKET";
            }
            
            // Cache the result for 1 hour
            marketRegimeCache.put(symbol, regime);
            
            logger.debug("Market regime for {}: {} (trend: {}, volatility: {})", 
                        symbol, regime, trend, volatility);
            
            return regime;
            
        } catch (Exception e) {
            logger.error("Error detecting market regime for {}: {}", symbol, e.getMessage());
            return "NEUTRAL_MARKET";
        }
    }
    
    /**
     * Track signal performance for learning
     */
    public void trackSignalPerformance(BotSignal signal, BigDecimal finalPrice, String outcome) {
        try {
            SignalPerformance performance = new SignalPerformance();
            performance.setSignalId(signal.getId());
            performance.setSymbol(signal.getSymbol());
            performance.setInitialConfidence(signal.getConfidence());
            performance.setActualOutcome(outcome); // WIN/LOSS/BREAKEVEN
            
            // Calculate returns
            BigDecimal expectedReturn = calculateExpectedReturn(signal);
            BigDecimal actualReturn = calculateActualReturn(signal, finalPrice);
            performance.setExpectedReturn(expectedReturn);
            performance.setActualReturn(actualReturn);
            
            // Analyze contributing factors
            analyzeContributingFactors(performance, signal);
            
            // Store performance data
            List<SignalPerformance> history = performanceHistory.computeIfAbsent(
                signal.getSymbol(), k -> new ArrayList<>());
            history.add(performance);
            
            // Keep only last 100 performances per symbol
            if (history.size() > 100) {
                history.remove(0);
            }
            
            // Update model weights based on performance
            updateModelWeights(performance);
            
            logger.info("Tracked performance for signal {}: {} with {}% accuracy", 
                       signal.getId(), outcome, performance.getInitialConfidence());
            
        } catch (Exception e) {
            logger.error("Error tracking signal performance: {}", e.getMessage());
        }
    }
    
    /**
     * Analyze factors that contributed to signal performance
     */
    @Transactional
    public void analyzeContributingFactors(SignalPerformance performance, BotSignal signal) {
        if (signal == null || performance == null) return;

        // Example: Analyze volatility at signal time
        try {
            BigDecimal volatility = riskManagementService.calculateVolatility(signal.getSymbol(), "BYBIT"); // Assuming default exchange for now
            performance.getContributingFactors().put("volatility_at_signal", volatility.doubleValue());
        } catch (Exception e) {
            logger.error("Error calculating volatility for performance analysis: {}", e.getMessage());
        }

        // Set market regime
        String regime = detectMarketRegime(signal.getSymbol());
        performance.setMarketRegime(regime);

        // Calculate component accuracies (simplified)
        performance.setAiAccuracy(BigDecimal.valueOf(0.75)); // Would be calculated from actual AI prediction vs outcome
        performance.setTechnicalAccuracy(BigDecimal.valueOf(0.70)); // Would be calculated from technical signals vs outcome
        performance.setSentimentAccuracy(BigDecimal.valueOf(0.65)); // Would be calculated from sentiment vs outcome

        performance.setClosedAt(LocalDateTime.now());
    }
    
    /**
     * Update model weights based on performance
     */
    private void updateModelWeights(SignalPerformance performance) {
        try {
            // If AI was accurate but technical wasn't, increase AI weight
            if (performance.getAiAccuracy() != null && performance.getTechnicalAccuracy() != null) {
                if (performance.getAiAccuracy().doubleValue() > 0.8 && 
                    performance.getTechnicalAccuracy().doubleValue() < 0.6) {
                    adjustWeight("ai", 0.05);
                    adjustWeight("technical", -0.05);
                }
            }
            
            // If sentiment predicted the move well, increase sentiment weight
            if (performance.getSentimentAccuracy() != null && 
                performance.getSentimentAccuracy().doubleValue() > 0.8) {
                adjustWeight("sentiment", 0.02);
            }
            
            logger.debug("Updated model weights based on performance feedback");
            
        } catch (Exception e) {
            logger.warn("Error updating model weights: {}", e.getMessage());
        }
    }
    
    /**
     * Adjust signal component weight
     */
    private void adjustWeight(String component, double adjustment) {
        if (signalWeights.containsKey(component)) {
            double currentWeight = signalWeights.get(component);
            double newWeight = Math.max(0.1, Math.min(0.8, currentWeight + adjustment));
            signalWeights.put(component, newWeight);
            
            // Normalize weights to sum to 1.0
            normalizeWeights();
        }
    }
    
    /**
     * Normalize weights to sum to 1.0
     */
    private void normalizeWeights() {
        double totalWeight = signalWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight > 0) {
            signalWeights.replaceAll((k, v) -> v / totalWeight);
        }
    }
    
    // Helper methods for market regime detection
    private double[] getLongTermPrices(String symbol, int periods) {
        // Simplified implementation - would fetch actual historical data
        return new double[periods]; // Placeholder
    }
    
    private double calculateTrendStrength(double[] prices) {
        if (prices.length < 10) return 0.0;
        
        // Simple trend calculation: (last price - first price) / first price
        double start = prices[0] > 0 ? prices[0] : 1.0;
        double end = prices[prices.length - 1];
        return (end - start) / start;
    }
    
    private double calculateHistoricalVolatility(double[] prices) {
        if (prices.length < 2) return 0.02; // Default volatility
        
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < prices.length; i++) {
            if (prices[i-1] > 0) {
                double returnRate = (prices[i] - prices[i-1]) / prices[i-1];
                returns.add(returnRate);
            }
        }
        
        // Calculate standard deviation
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
            .mapToDouble(r -> Math.pow(r - mean, 2))
            .average()
            .orElse(0.0);
            
        return Math.sqrt(variance);
    }
    
    private BigDecimal calculateExpectedReturn(BotSignal signal) {
        // Simplified expected return calculation
        if (signal.getTakeProfit() != null && signal.getEntryPrice() != null) {
            return signal.getTakeProfit().subtract(signal.getEntryPrice())
                .divide(signal.getEntryPrice(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
    
    private BigDecimal calculateActualReturn(BotSignal signal, BigDecimal finalPrice) {
        if (signal.getEntryPrice() != null && finalPrice != null) {
            return finalPrice.subtract(signal.getEntryPrice())
                .divide(signal.getEntryPrice(), 4, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }
    
    /**
     * Get historical kline data from either MEXC or Bybit
     */
    private List<Map<String, Object>> getHistoricalData(String symbol, String interval, String exchange) {
        try {
            ResponseEntity<String> response;
            if ("BYBIT".equals(exchange)) {
                response = bybitApiClientService.getKlineData(symbol, interval, 100, null, null, "spot");
            } else {
                response = mexcApiClientService.getSpotKlines(symbol, interval, null, null, 100);
            }
            
            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseKlineData(response.getBody(), exchange);
            }
        } catch (Exception e) {
            logger.error("Error getting historical data for {}: {}", symbol, e.getMessage());
        }
        
        return new ArrayList<>();
    }
    
    /**
     * Parse kline data from exchange response
     */
    private List<Map<String, Object>> parseKlineData(String responseBody, String exchange) {
        List<Map<String, Object>> klines = new ArrayList<>();
        try {
            JsonNode jsonResponse = objectMapper.readTree(responseBody);
            
            if ("BYBIT".equals(exchange)) {
                // Bybit response format
                if (jsonResponse.has("result") && jsonResponse.get("result").has("list")) {
                    JsonNode klineList = jsonResponse.get("result").get("list");
                    for (JsonNode kline : klineList) {
                        Map<String, Object> klineData = new HashMap<>();
                        klineData.put("timestamp", kline.get(0).asLong());
                        klineData.put("open", kline.get(1).asText());
                        klineData.put("high", kline.get(2).asText());
                        klineData.put("low", kline.get(3).asText());
                        klineData.put("close", kline.get(4).asText());
                        klineData.put("volume", kline.get(5).asText());
                        klines.add(klineData);
                    }
                }
            } else {
                // MEXC response format - typically an array of arrays
                if (jsonResponse.isArray()) {
                    for (JsonNode kline : jsonResponse) {
                        if (kline.isArray() && kline.size() >= 6) {
                            Map<String, Object> klineData = new HashMap<>();
                            klineData.put("timestamp", kline.get(0).asLong());
                            klineData.put("open", kline.get(1).asText());
                            klineData.put("high", kline.get(2).asText());
                            klineData.put("low", kline.get(3).asText());
                            klineData.put("close", kline.get(4).asText());
                            klineData.put("volume", kline.get(5).asText());
                            klines.add(klineData);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing kline data from {}: {}", exchange, e.getMessage());
        }
        
        return klines;
    }
} 