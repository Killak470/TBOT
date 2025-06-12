package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.service.ClaudeAiService;
import com.tradingbot.backend.service.SentimentAnalysisService;
import com.tradingbot.backend.service.SignalGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for AI-powered analysis endpoints
 */
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class AIAnalysisController {

    private static final Logger logger = LoggerFactory.getLogger(AIAnalysisController.class);
    
    private final ClaudeAiService claudeAiService;
    private final SentimentAnalysisService sentimentAnalysisService;
    private final SignalGenerationService signalGenerationService;
    
    public AIAnalysisController(
            ClaudeAiService claudeAiService,
            SentimentAnalysisService sentimentAnalysisService,
            SignalGenerationService signalGenerationService) {
        this.claudeAiService = claudeAiService;
        this.sentimentAnalysisService = sentimentAnalysisService;
        this.signalGenerationService = signalGenerationService;
    }
    
    /**
     * Get AI-powered market analysis for a specific trading pair
     * 
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @param technicalData Optional JSON string with technical data (if not provided, will be fetched)
     * @return AI-generated market analysis
     */
    @GetMapping("/market-analysis")
    public ResponseEntity<Map<String, Object>> getMarketAnalysis(
            @RequestParam String symbol,
            @RequestParam(required = false) String technicalData) {
            
        try {
            logger.info("Generating market analysis for {}", symbol);
            
            // Get sentiment data
            SentimentData sentimentData = sentimentAnalysisService.analyzeSentiment(symbol, 24);
            
            // Generate market analysis
            String analysis = claudeAiService.generateMarketAnalysis(symbol, technicalData, sentimentData);
            
            Map<String, Object> response = new HashMap<>();
            response.put("symbol", symbol);
            response.put("analysis", analysis);
            response.put("sentimentData", sentimentData);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error generating market analysis for {}: {}", symbol, e.getMessage());
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate market analysis: " + e.getMessage());
            return ResponseEntity.ok(errorResponse);
        }
    }
    
    /**
     * Get enhanced sentiment analysis with AI insights
     * 
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @return Enhanced sentiment analysis
     */
    @GetMapping("/sentiment-analysis")
    public ResponseEntity<Map<String, Object>> getEnhancedSentimentAnalysis(
            @RequestParam String symbol) {
            
        try {
            logger.info("Generating enhanced sentiment analysis for {}", symbol);
            
            // Get basic sentiment data
            SentimentData sentimentData = sentimentAnalysisService.analyzeSentiment(symbol, 24);
            
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
            
            // Create response
            Map<String, Object> response = new HashMap<>();
            response.put("symbol", symbol);
            response.put("basicSentiment", sentimentData);
            response.put("enhancedSentiment", enhancedSentiment);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error generating enhanced sentiment analysis for {}: {}", symbol, e.getMessage());
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate enhanced sentiment analysis: " + e.getMessage());
            return ResponseEntity.ok(errorResponse);
        }
    }
    
    /**
     * Get trading recommendations with AI insights
     * 
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @param interval Time interval for analysis (e.g., "1h", "4h", "1d")
     * @return Trading recommendations
     */
    @GetMapping("/trading-recommendation")
    public ResponseEntity<Map<String, Object>> getTradingRecommendation(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1h") String interval) {
            
        try {
            logger.info("Generating trading recommendation for {} ({})", symbol, interval);
            
            // Use the combined signal generation service
            Map<String, Object> signal = signalGenerationService.generateCombinedSignal(symbol, interval);
            
            return ResponseEntity.ok(signal);
            
        } catch (Exception e) {
            logger.error("Error generating trading recommendation for {}: {}", symbol, e.getMessage());
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate trading recommendation: " + e.getMessage());
            return ResponseEntity.ok(errorResponse);
        }
    }
    
    /**
     * Get combined trading signals for multiple symbols
     * 
     * @param symbols Comma-separated list of trading pairs
     * @param interval Time interval for analysis
     * @return Map of symbols to their trading signals
     */
    @GetMapping("/batch-signals")
    public ResponseEntity<Map<String, Object>> getBatchSignals(
            @RequestParam String symbols,
            @RequestParam(defaultValue = "1h") String interval) {
            
        try {
            String[] symbolArray = symbols.split(",");
            logger.info("Generating batch signals for {} symbols", symbolArray.length);
            
            Map<String, Map<String, Object>> signals = 
                signalGenerationService.generateBatchSignals(symbolArray, interval);
            
            Map<String, Object> response = new HashMap<>();
            response.put("signals", signals);
            response.put("count", symbolArray.length);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error generating batch signals: {}", e.getMessage());
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate batch signals: " + e.getMessage());
            return ResponseEntity.ok(errorResponse);
        }
    }
} 