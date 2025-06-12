package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.service.SentimentAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sentiment")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class SentimentController {

    private static final Logger logger = LoggerFactory.getLogger(SentimentController.class);
    
    private final SentimentAnalysisService sentimentAnalysisService;
    
    public SentimentController(SentimentAnalysisService sentimentAnalysisService) {
        this.sentimentAnalysisService = sentimentAnalysisService;
    }
    
    /**
     * Get sentiment data for a specific symbol
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<SentimentData> getSentiment(
            @PathVariable String symbol,
            @RequestParam(required = false, defaultValue = "24") int lookbackHours) {
        
        logger.info("Analyzing sentiment for {} with {} hour lookback", symbol, lookbackHours);
        SentimentData sentimentData = sentimentAnalysisService.analyzeSentiment(symbol, lookbackHours);
        return ResponseEntity.ok(sentimentData);
    }
    
    /**
     * Get sentiment data for multiple symbols simultaneously
     */
    @GetMapping("/batch")
    public ResponseEntity<Map<String, SentimentData>> getBatchSentiment(
            @RequestParam String[] symbols,
            @RequestParam(required = false, defaultValue = "24") int lookbackHours) {
        
        logger.info("Analyzing sentiment for {} symbols with {} hour lookback", symbols.length, lookbackHours);
        
        Map<String, SentimentData> results = new HashMap<>();
        for (String symbol : symbols) {
            results.put(symbol, sentimentAnalysisService.analyzeSentiment(symbol, lookbackHours));
        }
        
        return ResponseEntity.ok(results);
    }
    
    /**
     * Get keyword trends across multiple symbols
     */
    @GetMapping("/keywords")
    public ResponseEntity<Map<String, Map<String, Integer>>> getKeywordTrends(
            @RequestParam String[] symbols,
            @RequestParam(required = false, defaultValue = "24") int lookbackHours) {
        
        logger.info("Analyzing keyword trends for {} symbols", symbols.length);
        
        Map<String, Map<String, Integer>> keywordTrends = new HashMap<>();
        
        for (String symbol : symbols) {
            SentimentData sentimentData = sentimentAnalysisService.analyzeSentiment(symbol, lookbackHours);
            keywordTrends.put(symbol, sentimentData.getKeywordCounts());
        }
        
        return ResponseEntity.ok(keywordTrends);
    }
    
    /**
     * Get sentiment comparison between multiple symbols
     */
    @GetMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareSentiment(
            @RequestParam String[] symbols,
            @RequestParam(required = false, defaultValue = "24") int lookbackHours) {
        
        logger.info("Comparing sentiment between {} symbols", symbols.length);
        
        Map<String, Object> comparison = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        Map<String, String> categories = new HashMap<>();
        Map<String, Integer> mentions = new HashMap<>();
        
        for (String symbol : symbols) {
            SentimentData sentimentData = sentimentAnalysisService.analyzeSentiment(symbol, lookbackHours);
            scores.put(symbol, sentimentData.getSentimentScore());
            categories.put(symbol, sentimentData.getSentimentCategory());
            mentions.put(symbol, sentimentData.getTotalMentions());
        }
        
        comparison.put("scores", scores);
        comparison.put("categories", categories);
        comparison.put("mentions", mentions);
        
        return ResponseEntity.ok(comparison);
    }
} 