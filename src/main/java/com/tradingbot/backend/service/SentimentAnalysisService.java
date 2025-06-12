package com.tradingbot.backend.service;

import com.tradingbot.backend.model.SentimentData;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Service to analyze sentiment from news sources and social media
 */
@Service
public class SentimentAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(SentimentAnalysisService.class);
    
    private final RestTemplate restTemplate;
    
    // API keys for various data sources
    private final String cryptoCompareApiKey;
    private final String twitterBearerToken;
    private final String twitterApiKey;
    private final String twitterApiSecret;
    
    // Lists of positive and negative sentiment words
    private final List<String> positiveWords;
    private final List<String> negativeWords;
    
    // Cache for sentiment results to avoid repeated API calls
    private final Map<String, SentimentData> sentimentCache = new HashMap<>();
    
    public SentimentAnalysisService(
            RestTemplate restTemplate,
            @Value("${crypto.compare.api.key}") String cryptoCompareApiKey,
            @Value("${twitter.api.bearer.token}") String twitterBearerToken,
            @Value("${twitter.api.key}") String twitterApiKey,
            @Value("${twitter.api.secret}") String twitterApiSecret) {
        this.restTemplate = restTemplate;
        this.cryptoCompareApiKey = cryptoCompareApiKey;
        this.twitterBearerToken = twitterBearerToken;
        this.twitterApiKey = twitterApiKey;
        this.twitterApiSecret = twitterApiSecret;
        
        // Initialize sentiment lexicons
        this.positiveWords = initializePositiveWords();
        this.negativeWords = initializeNegativeWords();
        
        logger.info("SentimentAnalysisService initialized with Twitter API credentials");
        logger.debug("Twitter Bearer Token length: {}", twitterBearerToken.length());
    }
    
    /**
     * Analyze sentiment for a specific cryptocurrency
     * 
     * @param symbol The trading pair or currency symbol (e.g., "BTC", "ETH")
     * @param lookbackHours Hours to look back for data
     * @return SentimentData containing the analysis results
     */
    public SentimentData analyzeSentiment(String symbol, int lookbackHours) {
        String cacheKey = symbol + "_" + lookbackHours;
        
        // Check cache first
        if (sentimentCache.containsKey(cacheKey)) {
            SentimentData cachedData = sentimentCache.get(cacheKey);
            // Only use cache if it's less than 1 hour old
            if (cachedData.getTimestamp().isAfter(Instant.now().minus(1, ChronoUnit.HOURS))) {
                logger.debug("Using cached sentiment data for {}", symbol);
                return cachedData;
            }
        }
        
        // Extract the base currency from trading pair (e.g., "BTCUSDT" -> "BTC")
        String currency = extractCurrencyFromSymbol(symbol);
        
        // Create new sentiment data object
        SentimentData sentimentData = new SentimentData(symbol);
        
        try {
            // Collect news sentiment
            SentimentData newsData = analyzeNewsSentiment(currency, lookbackHours);
            
            // Collect Twitter sentiment (if API key is provided)
            SentimentData twitterData = new SentimentData();
            if (!twitterBearerToken.isEmpty()) {
                twitterData = analyzeTwitterSentiment(currency, lookbackHours);
            }
            
            // Combine data from different sources with appropriate weighting
            double combinedScore = (newsData.getSentimentScore() * 0.7) + 
                                  (twitterData.getSentimentScore() * 0.3);
            
            sentimentData.setSentimentScore(combinedScore);
            sentimentData.setKeywordCounts(newsData.getKeywordCounts());
            sentimentData.getSources().addAll(newsData.getSources());
            sentimentData.getSources().addAll(twitterData.getSources());
            sentimentData.setTotalMentions(newsData.getTotalMentions() + twitterData.getTotalMentions());
            
            // Merge aspect scores
            Map<String, Double> mergedAspectScores = new HashMap<>(newsData.getAspectScores());
            for (Map.Entry<String, Double> entry : twitterData.getAspectScores().entrySet()) {
                String aspect = entry.getKey();
                Double score = entry.getValue();
                
                if (mergedAspectScores.containsKey(aspect)) {
                    // Average the scores if both sources have scores for this aspect
                    mergedAspectScores.put(aspect, (mergedAspectScores.get(aspect) + score) / 2);
                } else {
                    mergedAspectScores.put(aspect, score);
                }
            }
            sentimentData.setAspectScores(mergedAspectScores);
            
            // Cache the result
            sentimentCache.put(cacheKey, sentimentData);
            
            return sentimentData;
            
        } catch (Exception e) {
            logger.error("Error analyzing sentiment for {}: {}", symbol, e.getMessage());
            // Return default neutral sentiment
            sentimentData.setSentimentScore(0.0);
            return sentimentData;
        }
    }
    
    /**
     * Analyze sentiment from news sources
     * 
     * @param currency The currency symbol (e.g., "BTC")
     * @param lookbackHours Hours to look back for news
     * @return SentimentData from news analysis
     */
    private SentimentData analyzeNewsSentiment(String currency, int lookbackHours) {
        SentimentData sentimentData = new SentimentData(currency);
        
        try {
            // Build URL for CryptoCompare News API
            String url = UriComponentsBuilder
                .fromHttpUrl("https://min-api.cryptocompare.com/data/v2/news/")
                .queryParam("categories", currency)
                .queryParam("lang", "EN")
                .queryParam("api_key", cryptoCompareApiKey)
                .build()
                .toUriString();
            
            HttpHeaders headers = new HttpHeaders();
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JSONObject jsonResponse = new JSONObject(response.getBody());
                JSONArray news = jsonResponse.getJSONArray("Data");
                
                // Variables for sentiment calculation
                double totalSentiment = 0.0;
                int newsCount = 0;
                
                Map<String, Integer> keywordCounts = new HashMap<>();
                Map<String, Double> aspectScores = new HashMap<>();
                aspectScores.put("price", 0.0);
                aspectScores.put("adoption", 0.0);
                aspectScores.put("technology", 0.0);
                aspectScores.put("regulation", 0.0);
                
                for (int i = 0; i < news.length(); i++) {
                    JSONObject article = news.getJSONObject(i);
                    String title = article.getString("title");
                    String body = article.getString("body");
                    long publishedOn = article.getLong("published_on");
                    
                    // Skip if article is older than lookback period
                    Instant publishTime = Instant.ofEpochSecond(publishedOn);
                    if (publishTime.isBefore(Instant.now().minus(lookbackHours, ChronoUnit.HOURS))) {
                        continue;
                    }
                    
                    // Combine title and body for analysis
                    String content = (title + " " + body).toLowerCase();
                    
                    // Calculate sentiment for this article
                    double articleSentiment = calculateTextSentiment(content);
                    totalSentiment += articleSentiment;
                    newsCount++;
                    
                    // Track keywords
                    extractAndCountKeywords(content, keywordCounts);
                    
                    // Track aspect sentiment
                    categorizeAspectSentiment(content, aspectScores, articleSentiment);
                    
                    // Add source
                    sentimentData.addSource("news:" + article.getString("source"));
                }
                
                // Calculate average sentiment
                if (newsCount > 0) {
                    double averageSentiment = totalSentiment / newsCount;
                    sentimentData.setSentimentScore(averageSentiment);
                    
                    // Normalize aspect scores
                    for (Map.Entry<String, Double> entry : aspectScores.entrySet()) {
                        if (newsCount > 0) {
                            aspectScores.put(entry.getKey(), entry.getValue() / newsCount);
                        }
                    }
                    
                    sentimentData.setKeywordCounts(keywordCounts);
                    sentimentData.setAspectScores(aspectScores);
                    sentimentData.setTotalMentions(newsCount);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error analyzing news sentiment for {}: {}", currency, e.getMessage());
        }
        
        return sentimentData;
    }
    
    /**
     * Analyze sentiment from Twitter
     * 
     * @param currency The currency symbol (e.g., "BTC")
     * @param lookbackHours Hours to look back for tweets
     * @return SentimentData from Twitter analysis
     */
    private SentimentData analyzeTwitterSentiment(String currency, int lookbackHours) {
        SentimentData sentimentData = new SentimentData(currency);
        
        // If no Twitter API token, return empty data
        if (twitterBearerToken.isEmpty()) {
            logger.warn("Twitter API bearer token is empty, skipping Twitter sentiment analysis");
            return sentimentData;
        }
        
        try {
            logger.info("Analyzing Twitter sentiment for {} with lookback of {} hours", currency, lookbackHours);
            
            // Build search query - find tweets about the cryptocurrency
            String query = currency + " OR #" + currency + " OR $" + currency + " crypto -is:retweet lang:en";
            
            // Build URL for Twitter API v2 recent search endpoint
            String url = UriComponentsBuilder
                .fromHttpUrl("https://api.twitter.com/2/tweets/search/recent")
                .queryParam("query", query)
                .queryParam("max_results", 100) // Maximum allowed by API
                .queryParam("tweet.fields", "created_at,public_metrics,entities")
                .queryParam("expansions", "author_id")
                .queryParam("user.fields", "verified,followers_count")
                .build()
                .toUriString();
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + twitterBearerToken);
            
            logger.debug("Calling Twitter API with URL: {}", url);
            
            ResponseEntity<String> response = restTemplate.exchange(
                url, 
                org.springframework.http.HttpMethod.GET, 
                new org.springframework.http.HttpEntity<>(headers), 
                String.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JSONObject jsonResponse = new JSONObject(response.getBody());
                logger.debug("Received response from Twitter API: {}", jsonResponse.toString().substring(0, Math.min(100, jsonResponse.toString().length())) + "...");
                
                // Check if we have tweets in the response
                if (!jsonResponse.has("data")) {
                    logger.info("No tweets found for {}", currency);
                    return sentimentData;
                }
                
                JSONArray tweets = jsonResponse.getJSONArray("data");
                logger.info("Found {} tweets for {}", tweets.length(), currency);
                
                // Variables for sentiment calculation
                double totalSentiment = 0.0;
                int tweetCount = 0;
                
                Map<String, Integer> keywordCounts = new HashMap<>();
                Map<String, Double> aspectScores = new HashMap<>();
                aspectScores.put("price", 0.0);
                aspectScores.put("adoption", 0.0);
                aspectScores.put("technology", 0.0);
                aspectScores.put("regulation", 0.0);
                
                // Extract and analyze tweets
                for (int i = 0; i < tweets.length(); i++) {
                    JSONObject tweet = tweets.getJSONObject(i);
                    String text = tweet.getString("text");
                    
                    // Get metrics if available
                    int likeCount = 0;
                    int retweetCount = 0;
                    int replyCount = 0;
                    
                    if (tweet.has("public_metrics")) {
                        JSONObject metrics = tweet.getJSONObject("public_metrics");
                        likeCount = metrics.optInt("like_count", 0);
                        retweetCount = metrics.optInt("retweet_count", 0);
                        replyCount = metrics.optInt("reply_count", 0);
                    }
                    
                    // Calculate engagement score (simple version)
                    double engagementScore = 1.0 + ((likeCount + retweetCount * 2 + replyCount) / 10.0);
                    // Cap at reasonable value
                    engagementScore = Math.min(3.0, engagementScore);
                    
                    // Calculate sentiment for this tweet
                    double tweetSentiment = calculateTextSentiment(text);
                    
                    // Weight sentiment by engagement
                    tweetSentiment *= engagementScore;
                    
                    totalSentiment += tweetSentiment;
                    tweetCount++;
                    
                    // Track keywords
                    extractAndCountKeywords(text, keywordCounts);
                    
                    // Track aspect sentiment
                    categorizeAspectSentiment(text, aspectScores, tweetSentiment);
                    
                    // Add source
                    sentimentData.addSource("twitter");
                    
                    if (i < 3) {
                        logger.debug("Tweet {}: '{}' - Sentiment: {}", i+1, 
                            text.substring(0, Math.min(50, text.length())) + "...", 
                            String.format("%.2f", tweetSentiment));
                    }
                }
                
                // Calculate average sentiment
                if (tweetCount > 0) {
                    double averageSentiment = totalSentiment / tweetCount;
                    sentimentData.setSentimentScore(averageSentiment);
                    
                    // Normalize aspect scores
                    for (Map.Entry<String, Double> entry : aspectScores.entrySet()) {
                        if (tweetCount > 0) {
                            aspectScores.put(entry.getKey(), entry.getValue() / tweetCount);
                        }
                    }
                    
                    sentimentData.setKeywordCounts(keywordCounts);
                    sentimentData.setAspectScores(aspectScores);
                    sentimentData.setTotalMentions(tweetCount);
                    
                    logger.info("Twitter sentiment analysis for {}: Score={}, Mentions={}", 
                        currency, String.format("%.2f", averageSentiment), tweetCount);
                }
            } else {
                logger.error("Error response from Twitter API: {} - {}", 
                    response.getStatusCode(), response.getBody());
            }
            
        } catch (Exception e) {
            logger.error("Error analyzing Twitter sentiment for {}: {}", currency, e.getMessage());
        }
        
        return sentimentData;
    }
    
    /**
     * Calculates sentiment score for a text based on positive and negative word counts
     * 
     * @param text The text to analyze
     * @return A sentiment score between -1.0 and 1.0
     */
    private double calculateTextSentiment(String text) {
        int positiveCount = 0;
        int negativeCount = 0;
        
        // Convert to lowercase and tokenize
        String[] words = text.toLowerCase().split("\\s+");
        
        for (String word : words) {
            // Remove punctuation
            word = word.replaceAll("[^a-zA-Z]", "");
            
            if (positiveWords.contains(word)) {
                positiveCount++;
            } else if (negativeWords.contains(word)) {
                negativeCount++;
            }
        }
        
        // Calculate sentiment
        int totalSentimentWords = positiveCount + negativeCount;
        if (totalSentimentWords == 0) {
            return 0.0; // Neutral if no sentiment words
        }
        
        return (double)(positiveCount - negativeCount) / totalSentimentWords;
    }
    
    /**
     * Extracts keywords from text and updates the count map
     * 
     * @param text The text to analyze
     * @param keywordCounts Map to update with keyword counts
     */
    private void extractAndCountKeywords(String text, Map<String, Integer> keywordCounts) {
        // List of crypto-related keywords to track
        String[] keywords = {"bull", "bear", "rally", "crash", "moon", "dump", "pump", 
                            "hodl", "buy", "sell", "support", "resistance", "breakout",
                            "trend", "volume", "adoption", "regulation", "ban", "future"};
        
        for (String keyword : keywords) {
            // Count occurrences (case insensitive)
            int count = 0;
            int index = text.indexOf(keyword);
            while (index != -1) {
                count++;
                index = text.indexOf(keyword, index + 1);
            }
            
            if (count > 0) {
                keywordCounts.put(keyword, keywordCounts.getOrDefault(keyword, 0) + count);
            }
        }
    }
    
    /**
     * Categorizes text into different aspects and updates aspect sentiment scores
     * 
     * @param text The text to analyze
     * @param aspectScores Map of aspect categories to sentiment scores
     * @param overallSentiment The overall sentiment score for the text
     */
    private void categorizeAspectSentiment(String text, Map<String, Double> aspectScores, double overallSentiment) {
        // Check if text contains words related to different aspects
        
        // Price-related terms
        if (containsAny(text, Arrays.asList("price", "value", "worth", "cost", "expensive", "cheap", 
                                          "market", "trading", "chart", "trend"))) {
            aspectScores.put("price", aspectScores.get("price") + overallSentiment);
        }
        
        // Adoption-related terms
        if (containsAny(text, Arrays.asList("adoption", "use", "user", "mainstream", "acceptance", 
                                          "business", "payment", "store", "merchant", "wallet"))) {
            aspectScores.put("adoption", aspectScores.get("adoption") + overallSentiment);
        }
        
        // Technology-related terms
        if (containsAny(text, Arrays.asList("technology", "blockchain", "protocol", "network", 
                                          "development", "update", "fork", "code", "github"))) {
            aspectScores.put("technology", aspectScores.get("technology") + overallSentiment);
        }
        
        // Regulation-related terms
        if (containsAny(text, Arrays.asList("regulation", "law", "legal", "government", "ban", 
                                          "sec", "compliance", "tax", "kyc", "aml"))) {
            aspectScores.put("regulation", aspectScores.get("regulation") + overallSentiment);
        }
    }
    
    /**
     * Checks if a text contains any of the given terms
     * 
     * @param text The text to check
     * @param terms List of terms to look for
     * @return true if any term is found in the text
     */
    private boolean containsAny(String text, List<String> terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Extracts the base currency from a trading pair symbol
     * 
     * @param symbol The trading pair (e.g., "BTCUSDT")
     * @return The base currency (e.g., "BTC")
     */
    private String extractCurrencyFromSymbol(String symbol) {
        // Common quote currencies in crypto
        String[] quoteCurrencies = {"USDT", "USD", "USDC", "BUSD", "BTC", "ETH"};
        
        for (String quote : quoteCurrencies) {
            if (symbol.endsWith(quote)) {
                return symbol.substring(0, symbol.length() - quote.length());
            }
        }
        
        // If no known quote currency, return as is
        return symbol;
    }
    
    /**
     * Initialize list of positive sentiment words
     */
    private List<String> initializePositiveWords() {
        return Arrays.asList(
            "bullish", "bull", "buy", "moon", "mooning", "surge", "rally", "rallying", "soar", "gain",
            "positive", "optimistic", "confidence", "potential", "growth", "grow", "promising",
            "strong", "strength", "adoption", "support", "innovation", "breakthrough", "success",
            "successful", "progress", "solution", "opportunity", "partnership", "collaboration",
            "institutional", "mainstream", "adoption", "hodl", "hold", "accumulate"
        );
    }
    
    /**
     * Initialize list of negative sentiment words
     */
    private List<String> initializeNegativeWords() {
        return Arrays.asList(
            "bearish", "bear", "sell", "dump", "dumping", "crash", "plunge", "plummet", "drop",
            "negative", "pessimistic", "fear", "concern", "uncertainty", "risk", "risky", "warning",
            "weak", "weakness", "scam", "fraud", "hack", "attack", "vulnerable", "failure", "fail",
            "regulation", "ban", "illegal", "criminal", "investigation", "bubble", "correction",
            "decline", "resistance", "overvalued", "overbought", "selling", "anxiety"
        );
    }
    
    /**
     * Get sentiment data for a specific symbol with lookback period
     * This is a convenience method that wraps analyzeSentiment
     * 
     * @param symbol The trading pair or currency symbol
     * @param lookbackHours Hours to look back for data
     * @return Map containing the sentiment analysis
     */
    public Map<String, Object> getSymbolSentiment(String symbol, int lookbackHours) {
        SentimentData sentimentData = analyzeSentiment(symbol, lookbackHours);
        Map<String, Object> result = new HashMap<>();
        
        // Convert SentimentData to Map
        result.put("symbol", sentimentData.getSymbol());
        result.put("averageSentiment", sentimentData.getSentimentScore());
        result.put("sampleSize", sentimentData.getTotalMentions());
        result.put("timestamp", sentimentData.getTimestamp().toString());
        result.put("category", sentimentData.getSentimentCategory());
        result.put("keywordCounts", sentimentData.getKeywordCounts());
        result.put("aspectScores", sentimentData.getAspectScores());
        result.put("sources", sentimentData.getSources());
        
        return result;
    }
} 