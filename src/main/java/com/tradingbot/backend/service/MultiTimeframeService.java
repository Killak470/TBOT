package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MultiTimeframeService {
    
    private static final Logger logger = LoggerFactory.getLogger(MultiTimeframeService.class);
    
    // Removed circular dependency - use API services directly
    @Autowired
    private BybitApiClientService bybitApiClientService;
    
    @Autowired
    private MexcApiClientService mexcApiClientService;
    
    @Autowired
    private MexcFuturesApiService mexcFuturesApiService;
    
    @Autowired
    private SignalWeightingService signalWeightingService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    // Standard timeframes for analysis
    private static final List<String> ANALYSIS_TIMEFRAMES = 
        Arrays.asList("15m", "1h", "4h", "1d");
    
    // Minimum confluence percentage required
    private static final double MIN_CONFLUENCE_PERCENTAGE = 0.6; // 60% agreement
    
    public enum MTFAConfirmationStatus {
        STRONG_CONFIRMATION, // All higher TFs align
        WEAK_CONFIRMATION,   // Some higher TFs align, none contradict
        NO_CONFIRMATION,     // No clear alignment or contradiction (e.g., all neutral)
        CONTRADICTION,       // At least one higher TF contradicts
        NOT_APPLICABLE,      // Not enough data or primary TF is too high
        SERVICE_ERROR        // Error during MTFA
    }
    
    public static class TimeframeAnalysis {
        private String timeframe;
        private String signal;
        private double confidence;
        private Map<String, TechnicalIndicator> indicators;
        private String aiAnalysis;
        
        public TimeframeAnalysis(String timeframe, String signal, double confidence) {
            this.timeframe = timeframe;
            this.signal = signal;
            this.confidence = confidence;
        }
        
        // Getters and setters
        public String getTimeframe() { return timeframe; }
        public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
        public String getSignal() { return signal; }
        public void setSignal(String signal) { this.signal = signal; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public Map<String, TechnicalIndicator> getIndicators() { return indicators; }
        public void setIndicators(Map<String, TechnicalIndicator> indicators) { this.indicators = indicators; }
        public String getAiAnalysis() { return aiAnalysis; }
        public void setAiAnalysis(String aiAnalysis) { this.aiAnalysis = aiAnalysis; }
    }
    
    public static class ConfluenceResult {
        private String finalSignal;
        private double confluenceStrength;
        private List<TimeframeAnalysis> timeframeAnalyses;
        private boolean hasConfluence;
        private String confluenceReason;
        
        public ConfluenceResult() {
            this.timeframeAnalyses = new ArrayList<>();
        }
        
        // Getters and setters
        public String getFinalSignal() { return finalSignal; }
        public void setFinalSignal(String finalSignal) { this.finalSignal = finalSignal; }
        public double getConfluenceStrength() { return confluenceStrength; }
        public void setConfluenceStrength(double confluenceStrength) { this.confluenceStrength = confluenceStrength; }
        public List<TimeframeAnalysis> getTimeframeAnalyses() { return timeframeAnalyses; }
        public void setTimeframeAnalyses(List<TimeframeAnalysis> timeframeAnalyses) { this.timeframeAnalyses = timeframeAnalyses; }
        public boolean isHasConfluence() { return hasConfluence; }
        public void setHasConfluence(boolean hasConfluence) { this.hasConfluence = hasConfluence; }
        public String getConfluenceReason() { return confluenceReason; }
        public void setConfluenceReason(String confluenceReason) { this.confluenceReason = confluenceReason; }
    }
    
    /**
     * Analyze multiple timeframes and determine if there's confluence
     */
    public ConfluenceResult analyzeMultiTimeframeConfluence(String symbol, String exchange, ScanConfig.MarketType marketType) {
        ConfluenceResult result = new ConfluenceResult();
        
        try {
            logger.info("Starting multi-timeframe confluence analysis for {}", symbol);
            
            // Analyze each timeframe
            List<TimeframeAnalysis> analyses = new ArrayList<>();
            
            for (String timeframe : ANALYSIS_TIMEFRAMES) {
                try {
                    TimeframeAnalysis analysis = analyzeTimeframe(symbol, timeframe, exchange, marketType);
                    if (analysis != null) {
                        analyses.add(analysis);
                    }
                } catch (Exception e) {
                    logger.warn("Error analyzing timeframe {} for {}: {}", timeframe, symbol, e.getMessage());
                }
            }
            
            result.setTimeframeAnalyses(analyses);
            
            // Calculate confluence
            calculateConfluence(result);
            
            logger.info("Multi-timeframe analysis completed for {}: confluence={}, signal={}", 
                       symbol, result.getConfluenceStrength(), result.getFinalSignal());
            
        } catch (Exception e) {
            logger.error("Error in multi-timeframe confluence analysis for {}: {}", symbol, e.getMessage());
            result.setHasConfluence(false);
            result.setFinalSignal("NEUTRAL");
            result.setConfluenceReason("Analysis failed: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Analyze a single timeframe - direct analysis without MarketScannerService
     */
    private TimeframeAnalysis analyzeTimeframe(String symbol, String timeframe, String exchange, ScanConfig.MarketType marketType) {
        try {
            logger.debug("Analyzing timeframe {} for {}", timeframe, symbol);
            
            // Get kline data directly
            JsonNode klineData = fetchKlineDataDirect(symbol, timeframe, 100, exchange, marketType);
            
            if (klineData == null || !klineData.isArray() || klineData.size() < 30) {
                logger.warn("Insufficient kline data for {} on timeframe {}", symbol, timeframe);
                return null;
            }
            
            // Process price data
            double[] closePrices = new double[klineData.size()];
            double[] highPrices = new double[klineData.size()];
            double[] lowPrices = new double[klineData.size()];
            double[] volumes = new double[klineData.size()];
            
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // Bybit format: [startTime, openPrice, highPrice, lowPrice, closePrice, volume, turnover]
                for (int i = 0; i < klineData.size(); i++) {
                    JsonNode candle = klineData.get(i);
                    if (candle.isArray() && candle.size() >= 6) {
                        closePrices[i] = candle.get(4).asDouble();
                        highPrices[i] = candle.get(2).asDouble();
                        lowPrices[i] = candle.get(3).asDouble();
                        volumes[i] = candle.get(5).asDouble();
                    }
                }
            } else if (marketType == ScanConfig.MarketType.FUTURES) {
                // MEXC Futures format: {time, open, high, low, close, volume}
                for (int i = 0; i < klineData.size(); i++) {
                    JsonNode candle = klineData.get(i);
                    closePrices[i] = candle.get("close").asDouble();
                    highPrices[i] = candle.get("high").asDouble();
                    lowPrices[i] = candle.get("low").asDouble();
                    volumes[i] = candle.get("volume").asDouble();
                }
            } else {
                // MEXC Spot format: [time, open, high, low, close, volume, ...]
                for (int i = 0; i < klineData.size(); i++) {
                    JsonNode candle = klineData.get(i);
                    closePrices[i] = candle.get(4).asDouble();
                    highPrices[i] = candle.get(2).asDouble();
                    lowPrices[i] = candle.get(3).asDouble();
                    volumes[i] = candle.get(5).asDouble();
                }
            }
            
            // Calculate simplified technical indicators
            Map<String, TechnicalIndicator> indicators = calculateSimplifiedIndicators(closePrices, highPrices, lowPrices, volumes);
            
            // Generate signal from indicators
            String signal = generateSignalFromIndicators(indicators);
            
            // Calculate confidence
            double confidence = calculateConfidenceFromIndicators(indicators, signal);
            
            // Create analysis
            TimeframeAnalysis analysis = new TimeframeAnalysis(timeframe, signal, confidence);
            analysis.setIndicators(indicators);
            analysis.setAiAnalysis("Timeframe " + timeframe + " analysis: " + signal);
            
            return analysis;
            
        } catch (Exception e) {
            logger.error("Error analyzing timeframe {} for {}: {}", timeframe, symbol, e.getMessage());
            return null;
        }
    }
    
    /**
     * Fetch kline data directly from APIs
     */
    private JsonNode fetchKlineDataDirect(String symbol, String interval, int limit, String exchange, ScanConfig.MarketType marketType) {
        try {
            long endTime = System.currentTimeMillis();
            long startTime = endTime - (limit * getIntervalMillis(interval));
            
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                String category = marketType == ScanConfig.MarketType.LINEAR ? "linear" : "spot";
                ResponseEntity<String> response = bybitApiClientService.getKlineData(
                    symbol, interval, limit, startTime, endTime, category);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode responseJson = objectMapper.readTree(response.getBody());
                    if (responseJson.has("result") && responseJson.get("result").has("list")) {
                        return responseJson.get("result").get("list");
                    }
                }
            } else if (marketType == ScanConfig.MarketType.FUTURES) {
                ResponseEntity<String> response = mexcFuturesApiService.getFuturesKlines(
                    symbol, interval, startTime / 1000, endTime / 1000);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode responseJson = objectMapper.readTree(response.getBody());
                    if (responseJson.has("data")) {
                        return responseJson.get("data");
                    }
                }
            } else {
                ResponseEntity<String> response = mexcApiClientService.getSpotKlines(
                    symbol, interval, startTime, endTime, limit);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode responseJson = objectMapper.readTree(response.getBody());
                    if (responseJson.isArray()) {
                        return responseJson;
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Error fetching kline data for {} {}: {}", symbol, interval, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Calculate simplified technical indicators
     */
    private Map<String, TechnicalIndicator> calculateSimplifiedIndicators(double[] close, double[] high, double[] low, double[] volume) {
        Map<String, TechnicalIndicator> indicators = new HashMap<>();
        
        try {
            // RSI (14)
            indicators.put("RSI_14", calculateSimpleRSI(close, 14));
            
            // MA (20, 50)
            indicators.put("MA_20", calculateSimpleMA(close, 20));
            indicators.put("MA_50", calculateSimpleMA(close, 50));
            
            // Simple trend analysis
            indicators.put("TREND", calculateSimpleTrend(close));
            
        } catch (Exception e) {
            logger.error("Error calculating simplified indicators: {}", e.getMessage());
        }
        
        return indicators;
    }
    
    /**
     * Calculate simple RSI
     */
    private TechnicalIndicator calculateSimpleRSI(double[] prices, int period) {
        if (prices.length <= period) {
            return createNeutralIndicator("RSI_" + period);
        }
        
        try {
            double[] gains = new double[prices.length - 1];
            double[] losses = new double[prices.length - 1];
            
            for (int i = 1; i < prices.length; i++) {
                double change = prices[i] - prices[i - 1];
                gains[i - 1] = Math.max(0, change);
                losses[i - 1] = Math.max(0, -change);
            }
            
            // Average gains and losses
            double avgGain = 0, avgLoss = 0;
            for (int i = Math.max(0, gains.length - period); i < gains.length; i++) {
                avgGain += gains[i];
                avgLoss += losses[i];
            }
            avgGain /= period;
            avgLoss /= period;
            
            double rs = avgLoss == 0 ? 100 : avgGain / avgLoss;
            double rsi = 100 - (100 / (1 + rs));
            
            TechnicalIndicator indicator = new TechnicalIndicator();
            indicator.setName("RSI_" + period);
            indicator.setValue(rsi);
            
            if (rsi > 70) indicator.setSignal("OVERBOUGHT");
            else if (rsi < 30) indicator.setSignal("OVERSOLD");
            else indicator.setSignal("NEUTRAL");
            
            return indicator;
            
        } catch (Exception e) {
            return createNeutralIndicator("RSI_" + period);
        }
    }
    
    /**
     * Calculate simple moving average
     */
    private TechnicalIndicator calculateSimpleMA(double[] prices, int period) {
        if (prices.length < period) {
            return createNeutralIndicator("MA_" + period);
        }
        
        try {
            double sum = 0;
            for (int i = prices.length - period; i < prices.length; i++) {
                sum += prices[i];
            }
            double ma = sum / period;
            double currentPrice = prices[prices.length - 1];
            
            TechnicalIndicator indicator = new TechnicalIndicator();
            indicator.setName("MA_" + period);
            indicator.setValue(ma);
            
            if (currentPrice > ma * 1.02) indicator.setSignal("BULLISH");
            else if (currentPrice < ma * 0.98) indicator.setSignal("BEARISH");
            else indicator.setSignal("NEUTRAL");
            
            return indicator;
            
        } catch (Exception e) {
            return createNeutralIndicator("MA_" + period);
        }
    }
    
    /**
     * Calculate simple trend
     */
    private TechnicalIndicator calculateSimpleTrend(double[] prices) {
        if (prices.length < 10) {
            return createNeutralIndicator("TREND");
        }
        
        try {
            double recentAvg = 0, oldAvg = 0;
            int halfLength = prices.length / 2;
            
            // Recent half average
            for (int i = halfLength; i < prices.length; i++) {
                recentAvg += prices[i];
            }
            recentAvg /= halfLength;
            
            // Old half average
            for (int i = 0; i < halfLength; i++) {
                oldAvg += prices[i];
            }
            oldAvg /= halfLength;
            
            double trend = (recentAvg - oldAvg) / oldAvg;
            
            TechnicalIndicator indicator = new TechnicalIndicator();
            indicator.setName("TREND");
            indicator.setValue(trend);
            
            if (trend > 0.05) indicator.setSignal("BULLISH");
            else if (trend < -0.05) indicator.setSignal("BEARISH");
            else indicator.setSignal("NEUTRAL");
            
            return indicator;
            
        } catch (Exception e) {
            return createNeutralIndicator("TREND");
        }
    }
    
    /**
     * Create neutral indicator
     */
    private TechnicalIndicator createNeutralIndicator(String name) {
        TechnicalIndicator indicator = new TechnicalIndicator();
        indicator.setName(name);
        indicator.setValue(0);
        indicator.setSignal("NEUTRAL");
        return indicator;
    }
    
    /**
     * Generate signal from indicators
     */
    private String generateSignalFromIndicators(Map<String, TechnicalIndicator> indicators) {
        int bullish = 0, bearish = 0, neutral = 0;
        
        for (TechnicalIndicator indicator : indicators.values()) {
            String signal = indicator.getSignal();
            if ("BULLISH".equals(signal) || "OVERSOLD".equals(signal)) {
                bullish++;
            } else if ("BEARISH".equals(signal) || "OVERBOUGHT".equals(signal)) {
                bearish++;
            } else {
                neutral++;
            }
        }
        
        if (bullish > bearish + neutral) return "STRONG_BUY";
        else if (bullish > bearish) return "BUY";
        else if (bearish > bullish + neutral) return "STRONG_SELL";
        else if (bearish > bullish) return "SELL";
        else return "NEUTRAL";
    }
    
    /**
     * Calculate confidence from indicators
     */
    private double calculateConfidenceFromIndicators(Map<String, TechnicalIndicator> indicators, String mainSignal) {
        if (indicators.isEmpty()) return 0.5;
        
        int agreementCount = 0;
        for (TechnicalIndicator indicator : indicators.values()) {
            String signal = indicator.getSignal();
            if (signalsAgree(mainSignal, signal)) {
                agreementCount++;
            }
        }
        
        return (double) agreementCount / indicators.size();
    }
    
    /**
     * Check if signals agree
     */
    private boolean signalsAgree(String mainSignal, String indicatorSignal) {
        if (mainSignal.contains("BUY")) {
            return indicatorSignal.equals("BULLISH") || indicatorSignal.equals("OVERSOLD");
        } else if (mainSignal.contains("SELL")) {
            return indicatorSignal.equals("BEARISH") || indicatorSignal.equals("OVERBOUGHT");
        } else {
            return indicatorSignal.equals("NEUTRAL");
        }
    }
    
    /**
     * Get interval in milliseconds
     */
    private long getIntervalMillis(String interval) {
        interval = interval.toLowerCase();
        switch (interval) {
            case "1m": return 60_000L;
            case "5m": return 300_000L;
            case "15m": return 900_000L;
            case "30m": return 1_800_000L;
            case "1h": return 3_600_000L;
            case "4h": return 14_400_000L;
            case "1d": return 86_400_000L;
            default: return 3_600_000L;
        }
    }
    
    /**
     * Calculate confidence score from scan result
     */
    private double calculateConfidenceFromScanResult(ScanResult scanResult) {
        try {
            // Base confidence on signal strength and indicator agreement
            String signal = scanResult.getSignal();
            Map<String, TechnicalIndicator> indicators = scanResult.getIndicators();
            
            double baseConfidence = getBaseConfidenceFromSignal(signal);
            
            // Adjust based on indicator agreement
            if (indicators != null && !indicators.isEmpty()) {
                double indicatorAgreement = calculateIndicatorAgreement(indicators, signal);
                baseConfidence = (baseConfidence + indicatorAgreement) / 2.0;
            }
            
            // Boost confidence if AI analysis agrees
            if (scanResult.getAiAnalysis() != null) {
                double aiScore = signalWeightingService.convertAiToScore(scanResult.getAiAnalysis());
                double aiAgreement = calculateAiAgreement(aiScore, signal);
                baseConfidence = (baseConfidence * 0.7) + (aiAgreement * 0.3);
            }
            
            return Math.max(0.0, Math.min(1.0, baseConfidence)); // Clamp between 0 and 1
            
        } catch (Exception e) {
            logger.error("Error calculating confidence from scan result: {}", e.getMessage());
            return 0.5; // Default neutral confidence
        }
    }
    
    /**
     * Get base confidence from signal type
     */
    private double getBaseConfidenceFromSignal(String signal) {
        if (signal == null) return 0.5;
        
        switch (signal.toUpperCase()) {
            case "STRONG_BUY":
            case "STRONG_SELL":
                return 0.9;
            case "BUY":
            case "SELL":
                return 0.7;
            case "NEUTRAL":
            default:
                return 0.5;
        }
    }
    
    /**
     * Calculate how much indicators agree with the main signal
     */
    private double calculateIndicatorAgreement(Map<String, TechnicalIndicator> indicators, String mainSignal) {
        if (indicators.isEmpty()) return 0.5;
        
        int agreeingIndicators = 0;
        int totalIndicators = 0;
        
        boolean isMainSignalBullish = mainSignal.contains("BUY") || mainSignal.contains("BULLISH");
        
        for (TechnicalIndicator indicator : indicators.values()) {
            String indicatorSignal = indicator.getSignal();
            boolean isIndicatorBullish = indicatorSignal.contains("BULLISH") || 
                                       indicatorSignal.contains("BUY") || 
                                       indicatorSignal.equals("OVERSOLD");
            
            if (isMainSignalBullish == isIndicatorBullish) {
                agreeingIndicators++;
            }
            totalIndicators++;
        }
        
        return totalIndicators > 0 ? (double) agreeingIndicators / totalIndicators : 0.5;
    }
    
    /**
     * Calculate how much AI analysis agrees with the main signal
     */
    private double calculateAiAgreement(double aiScore, String mainSignal) {
        boolean isMainSignalBullish = mainSignal.contains("BUY") || mainSignal.contains("BULLISH");
        boolean isAiBullish = aiScore > 0.5;
        
        if (isMainSignalBullish == isAiBullish) {
            return Math.abs(aiScore - 0.5) * 2; // Scale to 0-1 based on how far from neutral
        } else {
            return 1.0 - (Math.abs(aiScore - 0.5) * 2); // Inverse if disagreeing
        }
    }
    
    /**
     * Calculate confluence from multiple timeframe analyses
     */
    private void calculateConfluence(ConfluenceResult result) {
        List<TimeframeAnalysis> analyses = result.getTimeframeAnalyses();
        
        if (analyses.size() < 2) {
            result.setHasConfluence(false);
            result.setFinalSignal("NEUTRAL");
            result.setConfluenceStrength(0.0);
            result.setConfluenceReason("Insufficient timeframe data");
            return;
        }
        
        // Count signals by type
        Map<String, List<TimeframeAnalysis>> signalGroups = analyses.stream()
            .collect(Collectors.groupingBy(TimeframeAnalysis::getSignal));
        
        // Find the most common signal
        String dominantSignal = signalGroups.entrySet().stream()
            .max(Map.Entry.comparingByValue((list1, list2) -> Integer.compare(list1.size(), list2.size())))
            .map(Map.Entry::getKey)
            .orElse("NEUTRAL");
        
        List<TimeframeAnalysis> dominantGroup = signalGroups.get(dominantSignal);
        double confluencePercentage = (double) dominantGroup.size() / analyses.size();
        
        // Calculate weighted confluence strength
        double weightedConfluence = calculateWeightedConfluence(dominantGroup, analyses);
        
        result.setFinalSignal(dominantSignal);
        result.setConfluenceStrength(weightedConfluence);
        result.setHasConfluence(confluencePercentage >= MIN_CONFLUENCE_PERCENTAGE);
        
        // Set confluence reason
        if (result.isHasConfluence()) {
            result.setConfluenceReason(String.format(
                "Strong confluence: %d/%d timeframes agree on %s signal (%.1f%% weighted confidence)", 
                dominantGroup.size(), analyses.size(), dominantSignal, weightedConfluence * 100
            ));
        } else {
            result.setConfluenceReason(String.format(
                "Weak confluence: Only %d/%d timeframes agree (%.1f%% < %.1f%% required)", 
                dominantGroup.size(), analyses.size(), confluencePercentage * 100, MIN_CONFLUENCE_PERCENTAGE * 100
            ));
        }
        
        logger.debug("Confluence calculated for dominant signal {}: strength={}, hasConfluence={}", 
                    dominantSignal, weightedConfluence, result.isHasConfluence());
    }
    
    /**
     * Calculate weighted confluence strength based on timeframe importance and confidence
     */
    private double calculateWeightedConfluence(List<TimeframeAnalysis> dominantGroup, List<TimeframeAnalysis> allAnalyses) {
        // Timeframe weights (higher timeframes are more important)
        Map<String, Double> timeframeWeights = Map.of(
            "15m", 0.1,
            "1h", 0.2,
            "4h", 0.35,
            "1d", 0.35
        );
        
        double totalWeightedScore = 0.0;
        double totalPossibleWeight = 0.0;
        
        for (TimeframeAnalysis analysis : allAnalyses) {
            String timeframe = analysis.getTimeframe();
            double weight = timeframeWeights.getOrDefault(timeframe, 0.2); // Default weight
            double confidence = analysis.getConfidence();
            
            totalPossibleWeight += weight;
            
            // Only add to score if this analysis is in the dominant group
            if (dominantGroup.contains(analysis)) {
                totalWeightedScore += weight * confidence;
            }
        }
        
        return totalPossibleWeight > 0 ? totalWeightedScore / totalPossibleWeight : 0.0;
    }
    
    /**
     * Get signals that have multi-timeframe confluence
     */
    public List<ConfluenceResult> getConfluenceSignals(List<String> symbols, String exchange, ScanConfig.MarketType marketType) {
        List<ConfluenceResult> confluenceResults = new ArrayList<>();
        
        for (String symbol : symbols) {
            try {
                ConfluenceResult result = analyzeMultiTimeframeConfluence(symbol, exchange, marketType);
                if (result.isHasConfluence()) {
                    confluenceResults.add(result);
                }
            } catch (Exception e) {
                logger.error("Error getting confluence signals for {}: {}", symbol, e.getMessage());
            }
        }
        
        // Sort by confluence strength
        confluenceResults.sort((r1, r2) -> Double.compare(r2.getConfluenceStrength(), r1.getConfluenceStrength()));
        
        return confluenceResults;
    }

    public MTFAConfirmationStatus getHigherTimeframeConfirmation(String symbol, String primaryInterval, String primarySide, String exchange, ScanConfig.MarketType marketType) {
        logger.info("MTFA: Getting higher timeframe confirmation for {} on primary interval {}, primary side {}, exchange {}, market type {}",
                symbol, primaryInterval, primarySide, exchange, marketType);

        List<String> higherTimeframesToCheck = new ArrayList<>();
        String primaryIntervalNormalized = primaryInterval.toLowerCase();

        if (primaryIntervalNormalized.equals("1m") || primaryIntervalNormalized.equals("3m") || primaryIntervalNormalized.equals("5m") || primaryIntervalNormalized.equals("15m") || primaryIntervalNormalized.equals("30m") || primaryIntervalNormalized.equals("1h")) {
            higherTimeframesToCheck.add("4h");
            higherTimeframesToCheck.add("1d");
        } else if (primaryIntervalNormalized.equals("4h")) {
            higherTimeframesToCheck.add("1d");
        } else {
            logger.info("MTFA: Primary interval {} is too high or not configured for higher TF confirmation.", primaryInterval);
            return MTFAConfirmationStatus.NOT_APPLICABLE;
        }

        if (higherTimeframesToCheck.isEmpty()) {
            logger.info("MTFA: No higher timeframes configured for primary interval {}.", primaryInterval);
            return MTFAConfirmationStatus.NOT_APPLICABLE;
        }

        int aligningSignals = 0;
        int contradictingSignals = 0;
        int neutralSignals = 0;
        int errorSignals = 0;

        for (String htInterval : higherTimeframesToCheck) {
            try {
                TimeframeAnalysis htAnalysis = analyzeTimeframe(symbol, htInterval, exchange, marketType);
                if (htAnalysis == null || htAnalysis.getSignal() == null) {
                    logger.warn("MTFA: Analysis for higher timeframe {} for {} returned null or no signal.", htInterval, symbol);
                    errorSignals++;
                    continue;
                }

                String htSignal = htAnalysis.getSignal().toUpperCase();
                logger.info("MTFA: Symbol {}, Higher TF {}: Signal = {}", symbol, htInterval, htSignal);

                if (("BUY".equalsIgnoreCase(primarySide) && "BUY".equals(htSignal)) ||
                    ("SELL".equalsIgnoreCase(primarySide) && "SELL".equals(htSignal))) {
                    aligningSignals++;
                } else if (("BUY".equalsIgnoreCase(primarySide) && "SELL".equals(htSignal)) ||
                           ("SELL".equalsIgnoreCase(primarySide) && "BUY".equals(htSignal))) {
                    contradictingSignals++;
                } else { // NEUTRAL or other
                    neutralSignals++;
                }
            } catch (Exception e) {
                logger.error("MTFA: Error analyzing higher timeframe {} for {}: {}", htInterval, symbol, e.getMessage(), e);
                errorSignals++;
            }
        }

        if (errorSignals > 0 && errorSignals == higherTimeframesToCheck.size()) {
            return MTFAConfirmationStatus.SERVICE_ERROR;
        }

        if (contradictingSignals > 0) {
            logger.info("MTFA: Contradiction found for {} {}. Align: {}, Contradict: {}, Neutral: {}", symbol, primarySide, aligningSignals, contradictingSignals, neutralSignals);
            return MTFAConfirmationStatus.CONTRADICTION;
        }
        
        if (aligningSignals == higherTimeframesToCheck.size() - errorSignals) { // All valid signals align
             logger.info("MTFA: Strong confirmation for {} {}. Align: {}, Contradict: {}, Neutral: {}", symbol, primarySide, aligningSignals, contradictingSignals, neutralSignals);
            return MTFAConfirmationStatus.STRONG_CONFIRMATION;
        }
        
        if (aligningSignals > 0) { // Some align, none contradict
            logger.info("MTFA: Weak confirmation for {} {}. Align: {}, Contradict: {}, Neutral: {}", symbol, primarySide, aligningSignals, contradictingSignals, neutralSignals);
            return MTFAConfirmationStatus.WEAK_CONFIRMATION;
        }

        logger.info("MTFA: No confirmation for {} {}. Align: {}, Contradict: {}, Neutral: {}", symbol, primarySide, aligningSignals, contradictingSignals, neutralSignals);
        return MTFAConfirmationStatus.NO_CONFIRMATION;
    }
} 