package com.tradingbot.backend.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model class to store sentiment analysis data for a cryptocurrency
 */
public class SentimentData {
    
    private String symbol;
    private double sentimentScore;
    private Map<String, Integer> keywordCounts;
    private Map<String, Double> aspectScores;
    private List<String> sources;
    private int totalMentions;
    private Instant timestamp;
    
    /**
     * Constructor with default values
     */
    public SentimentData() {
        this.sentimentScore = 0.0;
        this.keywordCounts = new HashMap<>();
        this.aspectScores = new HashMap<>();
        this.sources = new ArrayList<>();
        this.totalMentions = 0;
        this.timestamp = Instant.now();
    }
    
    /**
     * Constructor with symbol
     * 
     * @param symbol The cryptocurrency symbol
     */
    public SentimentData(String symbol) {
        this();
        this.symbol = symbol;
    }
    
    /**
     * Get the cryptocurrency symbol
     * 
     * @return The symbol
     */
    public String getSymbol() {
        return symbol;
    }
    
    /**
     * Set the cryptocurrency symbol
     * 
     * @param symbol The symbol to set
     */
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    
    /**
     * Get the sentiment score (-1.0 to 1.0)
     * 
     * @return The sentiment score
     */
    public double getSentimentScore() {
        return sentimentScore;
    }
    
    /**
     * Set the sentiment score
     * 
     * @param sentimentScore The sentiment score to set
     */
    public void setSentimentScore(double sentimentScore) {
        this.sentimentScore = sentimentScore;
    }
    
    /**
     * Get counts of keywords found in analysis
     * 
     * @return Map of keywords to their counts
     */
    public Map<String, Integer> getKeywordCounts() {
        return keywordCounts;
    }
    
    /**
     * Set keyword counts
     * 
     * @param keywordCounts The keyword counts to set
     */
    public void setKeywordCounts(Map<String, Integer> keywordCounts) {
        this.keywordCounts = keywordCounts;
    }
    
    /**
     * Get sentiment scores for different aspects
     * 
     * @return Map of aspects to their sentiment scores
     */
    public Map<String, Double> getAspectScores() {
        return aspectScores;
    }
    
    /**
     * Set aspect scores
     * 
     * @param aspectScores The aspect scores to set
     */
    public void setAspectScores(Map<String, Double> aspectScores) {
        this.aspectScores = aspectScores;
    }
    
    /**
     * Get list of data sources
     * 
     * @return List of source identifiers
     */
    public List<String> getSources() {
        return sources;
    }
    
    /**
     * Add a source to the list
     * 
     * @param source The source to add
     */
    public void addSource(String source) {
        this.sources.add(source);
    }
    
    /**
     * Get the total number of mentions
     * 
     * @return The count of mentions
     */
    public int getTotalMentions() {
        return totalMentions;
    }
    
    /**
     * Set the total number of mentions
     * 
     * @param totalMentions The count to set
     */
    public void setTotalMentions(int totalMentions) {
        this.totalMentions = totalMentions;
    }
    
    /**
     * Get the timestamp of when this data was generated
     * 
     * @return The timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get a categorical label for the sentiment score
     * 
     * @return String representation of sentiment category
     */
    public String getSentimentCategory() {
        if (sentimentScore >= 0.6) {
            return "Very Bullish";
        } else if (sentimentScore >= 0.2) {
            return "Bullish";
        } else if (sentimentScore >= -0.2) {
            return "Neutral";
        } else if (sentimentScore >= -0.6) {
            return "Bearish";
        } else {
            return "Very Bearish";
        }
    }
} 