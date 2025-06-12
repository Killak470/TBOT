package com.tradingbot.backend.service;

import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.service.cache.PositionCacheService.PositionUpdateData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for integrating with Claude AI to generate market insights
 */
@Service
public class ClaudeAiService {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeAiService.class);
    
    // DTO for structured AI Hedging Analysis Response
    public static class AiHedgeDecision {
        public boolean shouldHedge;
        public double hedgeConfidence;
        public String hedgeReason;
        public CriticalLevels criticalSupportResistance; // Nested DTO for levels
        public BigDecimal adversePriceTarget;

        public static class CriticalLevels {
            public BigDecimal support;
            public BigDecimal resistance;
        }

        // Getters and (optionally) setters can be added if needed,
        // or direct field access if kept as a simple public inner class.
        // For Jackson deserialization, public fields or getters/setters are needed.
    }
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    // Claude API configuration
    private final String claudeApiKey;
    private final String claudeApiUrl = "https://api.anthropic.com/v1/messages";
    
    @Value("${ai.claude.model:claude-3-7-sonnet-20250219}")
    private String defaultClaudeModel;
    
    @Value("${ai.claude.max_tokens:64000}")
    private int maxTokens;
    
    @Value("${ai.claude.temperature:1.0}")
    private double temperature;
    
    // Cache for API responses to limit API usage
    private final Map<String, String> responseCache = new ConcurrentHashMap<>();
    
    public ClaudeAiService(RestTemplate restTemplate, @Value("${ai.claude.api.key:}") String claudeApiKey) {
        this.restTemplate = restTemplate;
        this.claudeApiKey = claudeApiKey;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Generate market analysis based on technical indicators and sentiment data
     * 
     * @param symbol The trading pair to analyze
     * @param technicalData JSON string containing technical indicators
     * @param sentimentData Sentiment data from the sentiment analysis service
     * @return AI-generated analysis of the market situation
     */
    public String generateMarketAnalysis(String symbol, String technicalData, SentimentData sentimentData) {
        // Create a cache key based on the inputs
        String cacheKey = symbol + "_" + technicalData.hashCode() + "_" + sentimentData.hashCode();
        
        // Check if we have a cached response
        if (responseCache.containsKey(cacheKey)) {
            logger.debug("Using cached Claude response for {}", symbol);
            return responseCache.get(cacheKey);
        }
        
        // Check if API key is configured
        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            logger.warn("Claude API key not configured, returning simulated response");
            return generateSimulatedAnalysis(symbol, technicalData, sentimentData);
        }
        
        try {
            // Create the prompt for Claude
            String prompt = createMarketAnalysisPrompt(symbol, technicalData, sentimentData);
            
            // Set up headers with API key
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", claudeApiKey);
            headers.set("anthropic-version", "2023-06-01");
            
            // Create request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", defaultClaudeModel);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);
            
            // Add system prompt as a top-level parameter
            requestBody.put("system", "You are Claude, an expert in cryptocurrency market analysis. " +
                    "Your task is to analyze technical indicators and sentiment data to provide a concise, " +
                    "insightful market analysis. Focus on key indicators, notable patterns, and potential " +
                    "trading opportunities. Be specific, direct, and avoid overly cautious language. " +
                    "Provide a clear market outlook (bullish, bearish, or neutral) based on the data provided. " +
                    "When suggesting trading levels, please use the following exact formats:\n" +
                    "*   For entry prices:\n" +
                    "    *   \"Entry Price: [price]\"\n" +
                    "    *   \"Entry Range: [price1] - [price2]\"\n" +
                    "*   For stop-loss levels:\n" +
                    "    *   \"Stop Loss: [price]\"\n" +
                    "*   For take-profit levels:\n" +
                    "    *   \"Take Profit 1: [price]\"\n" +
                    "    *   \"Take Profit 2: [price]\" (if multiple)\n" +
                    "    *   \"Primary Take Profit: [price]\"\n\n" +
                    "Example:\n" +
                    "Entry Price: 105.50\n" +
                    "Stop Loss: 103.20\n" +
                    "Take Profit 1: 107.00\n" +
                    "Take Profit 2: 108.50\n\n" +
                    "If you provide multiple \"Entry Options\", please clearly label each with \"Entry Price:\" or \"Entry Range:\" if applicable.\n" +
                    "If you provide multiple \"Targets\", please clearly label each with \"Take Profit [number]:\" or \"Primary Take Profit:\".");
            
            // Add messages array with only user message (using standard Java collections)
            List<Map<String, Object>> messages = new ArrayList<>();
            
            // User message with the prompt
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            
            requestBody.put("messages", messages);
            
            // Make the API call
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(claudeApiUrl, requestEntity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                JsonNode contentNode = jsonResponse.get("content");
                
                String content;
                if (contentNode.isArray() && contentNode.size() > 0) {
                    // Handle array response format
                    content = contentNode.get(0).get("text").asText();
                } else if (contentNode.isObject()) {
                    // Handle object response format
                    content = contentNode.get("text").asText();
                } else {
                    content = contentNode.asText();
                }
                
                // Cache the response
                responseCache.put(cacheKey, content);
                
                return content;
            } else {
                logger.error("Error from Claude API: {}", response.getStatusCode());
                return "Error: Unable to generate market analysis at this time.";
            }
            
        } catch (Exception e) {
            logger.error("Error calling Claude API: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
    
    /**
     * Generate trading recommendation based on technical and sentiment analysis
     * 
     * @param symbol The trading pair
     * @param technicalData JSON string containing technical indicators
     * @param sentimentData Sentiment data
     * @return AI-generated trading recommendation
     */
    public Map<String, Object> generateTradingRecommendation(String symbol, String technicalData, SentimentData sentimentData) {
        Map<String, Object> recommendation = new HashMap<>();
        
        // Check if API key is configured
        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            logger.warn("Claude API key not configured, returning simulated recommendation");
            return generateSimulatedRecommendation(symbol, technicalData, sentimentData);
        }
        
        try {
            // Create the prompt for Claude
            String prompt = createTradingRecommendationPrompt(symbol, technicalData, sentimentData);
            
            // Set up headers with API key
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", claudeApiKey);
            headers.set("anthropic-version", "2023-06-01");
            
            // Create request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", defaultClaudeModel);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);
            
            // Add system prompt as a top-level parameter
            requestBody.put("system", "You are Claude, an expert in cryptocurrency trading. " +
                    "Your task is to analyze technical indicators and sentiment data to provide " +
                    "a structured trading recommendation. Your response should be in JSON format " +
                    "with the following structure: {\"action\": \"BUY\"|\"SELL\"|\"HOLD\", " +
                    "\"confidence\": 0.0-1.0, \"timeFrame\": \"SHORT\"|\"MEDIUM\"|\"LONG\", " +
                    "\"reasoning\": \"explanation\", \"riskLevel\": \"LOW\"|\"MEDIUM\"|\"HIGH\", " +
                    "\"targetPrice\": number, \"stopLoss\": number}");
            
            // Add messages array with only user message (using standard Java collections)
            List<Map<String, Object>> messages = new ArrayList<>();
            
            // User message with the prompt
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            
            requestBody.put("messages", messages);
            
            // Make the API call
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(claudeApiUrl, requestEntity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                JsonNode contentNode = jsonResponse.get("content");
                
                String content;
                if (contentNode.isArray() && contentNode.size() > 0) {
                    // Handle array response format
                    content = contentNode.get(0).get("text").asText();
                } else if (contentNode.isObject()) {
                    // Handle object response format
                    content = contentNode.get("text").asText();
                } else {
                    content = contentNode.asText();
                }
                
                // Parse the JSON response
                // Find the JSON object in the response (it might be within other text)
                int startIndex = content.indexOf("{");
                int endIndex = content.lastIndexOf("}") + 1;
                
                if (startIndex >= 0 && endIndex > startIndex) {
                    String jsonString = content.substring(startIndex, endIndex);
                    JsonNode recommendationJson = objectMapper.readTree(jsonString);
                    
                    // Convert JSON to Map
                    recommendation.put("action", recommendationJson.get("action").asText());
                    recommendation.put("confidence", recommendationJson.get("confidence").asDouble());
                    recommendation.put("timeFrame", recommendationJson.get("timeFrame").asText());
                    recommendation.put("reasoning", recommendationJson.get("reasoning").asText());
                    recommendation.put("riskLevel", recommendationJson.get("riskLevel").asText());
                    recommendation.put("targetPrice", recommendationJson.get("targetPrice").asDouble());
                    recommendation.put("stopLoss", recommendationJson.get("stopLoss").asDouble());
                    
                    return recommendation;
                }
            }
            
            logger.error("Error parsing Claude API response");
            return generateSimulatedRecommendation(symbol, technicalData, sentimentData);
            
        } catch (Exception e) {
            logger.error("Error calling Claude API: {}", e.getMessage());
            return generateSimulatedRecommendation(symbol, technicalData, sentimentData);
        }
    }
    
    /**
     * Create a prompt for market analysis
     */
    private String createMarketAnalysisPrompt(String symbol, String technicalData, SentimentData sentimentData) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Please analyze the following data for ").append(symbol).append(":\n\n");
        prompt.append("TECHNICAL INDICATORS:\n").append(technicalData).append("\n\n");
        
        prompt.append("SENTIMENT DATA:\n");
        prompt.append("Overall Sentiment Score: ").append(sentimentData.getSentimentScore()).append(" (ranges from -1 to 1)\n");
        prompt.append("Sentiment Category: ").append(sentimentData.getSentimentCategory()).append("\n");
        prompt.append("Total Mentions: ").append(sentimentData.getTotalMentions()).append("\n\n");
        
        prompt.append("Top Keywords:\n");
        for (Map.Entry<String, Integer> entry : sentimentData.getKeywordCounts().entrySet()) {
            prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        prompt.append("\n");
        
        prompt.append("Aspect Sentiment:\n");
        for (Map.Entry<String, Double> entry : sentimentData.getAspectScores().entrySet()) {
            prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        prompt.append("\n");
        
        prompt.append("Please provide a concise market analysis for ").append(symbol)
              .append(" based on this data. Focus on:\n");
        prompt.append("1. Key technical indicators and what they suggest\n");
        prompt.append("2. Overall market sentiment and noteworthy trends\n");
        prompt.append("3. Potential trading opportunities or risks\n");
        prompt.append("4. A clear market outlook (bullish, bearish, or neutral)\n\n");
        
        prompt.append("Provide a response of approximately 300-500 words. Be specific and direct in your analysis.");
        
        return prompt.toString();
    }
    
    /**
     * Create a prompt for trading recommendation
     */
    private String createTradingRecommendationPrompt(String symbol, String technicalData, SentimentData sentimentData) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Please analyze the following data for ").append(symbol).append(" and provide a trading recommendation:\n\n");
        prompt.append("TECHNICAL INDICATORS:\n").append(technicalData).append("\n\n");
        
        prompt.append("SENTIMENT DATA:\n");
        prompt.append("Overall Sentiment Score: ").append(sentimentData.getSentimentScore()).append(" (ranges from -1 to 1)\n");
        prompt.append("Sentiment Category: ").append(sentimentData.getSentimentCategory()).append("\n");
        prompt.append("Total Mentions: ").append(sentimentData.getTotalMentions()).append("\n\n");
        
        prompt.append("Based on this data, provide a structured trading recommendation in JSON format with the following fields:\n");
        prompt.append("- action: Either \"BUY\", \"SELL\", or \"HOLD\"\n");
        prompt.append("- confidence: A number between 0.0 and 1.0 indicating your confidence in this recommendation\n");
        prompt.append("- timeFrame: Either \"SHORT\" (days), \"MEDIUM\" (weeks), or \"LONG\" (months)\n");
        prompt.append("- reasoning: A brief explanation of your recommendation\n");
        prompt.append("- riskLevel: Either \"LOW\", \"MEDIUM\", or \"HIGH\"\n");
        prompt.append("- targetPrice: A price target for the recommended action\n");
        prompt.append("- stopLoss: A suggested stop loss price to limit risk\n\n");
        
        prompt.append("Respond with ONLY the JSON object, nothing else. Example format:\n");
        prompt.append("{\n");
        prompt.append("  \"action\": \"BUY\",\n");
        prompt.append("  \"confidence\": 0.8,\n");
        prompt.append("  \"timeFrame\": \"MEDIUM\",\n");
        prompt.append("  \"reasoning\": \"Strong bullish indicators with positive sentiment\",\n");
        prompt.append("  \"riskLevel\": \"MEDIUM\",\n");
        prompt.append("  \"targetPrice\": 50000,\n");
        prompt.append("  \"stopLoss\": 45000\n");
        prompt.append("}");
        
        return prompt.toString();
    }
    
    /**
     * Generate a simulated analysis when the API key is not available
     */
    private String generateSimulatedAnalysis(String symbol, String technicalData, SentimentData sentimentData) {
        // A simple simulation based on sentiment score
        double sentimentScore = sentimentData.getSentimentScore();
        
        StringBuilder analysis = new StringBuilder();
        
        analysis.append("# Market Analysis for ").append(symbol).append("\n\n");
        
        if (sentimentScore > 0.5) {
            analysis.append("## Overall: BULLISH\n\n");
            analysis.append("The market sentiment for ").append(symbol).append(" is strongly positive, suggesting bullish momentum. ");
            analysis.append("Social media mentions show increasing interest, with keywords like 'buy', 'moon', and 'bullish' trending. ");
            analysis.append("Technical indicators align with this bullish sentiment, showing positive momentum and potential upside.\n\n");
        } else if (sentimentScore > 0) {
            analysis.append("## Overall: SLIGHTLY BULLISH\n\n");
            analysis.append("The market sentiment for ").append(symbol).append(" is cautiously positive. ");
            analysis.append("While there's some optimism in social discussions, the enthusiasm is tempered. ");
            analysis.append("Technical indicators show mixed signals but with a slight bullish bias.\n\n");
        } else if (sentimentScore > -0.5) {
            analysis.append("## Overall: NEUTRAL TO BEARISH\n\n");
            analysis.append("The market sentiment for ").append(symbol).append(" is leaning negative. ");
            analysis.append("Social discussions reflect concerns and uncertainty about future price action. ");
            analysis.append("Technical indicators suggest potential downside risks in the short term.\n\n");
        } else {
            analysis.append("## Overall: BEARISH\n\n");
            analysis.append("The market sentiment for ").append(symbol).append(" is decidedly negative. ");
            analysis.append("Social media shows significant bearish sentiment with keywords like 'sell', 'dump', and 'crash' trending. ");
            analysis.append("Technical indicators confirm this bearish outlook, suggesting further downside potential.\n\n");
        }
        
        analysis.append("## Key Observations\n\n");
        analysis.append("- Current sentiment score: ").append(sentimentScore).append("\n");
        analysis.append("- Total mentions: ").append(sentimentData.getTotalMentions()).append("\n");
        analysis.append("- Trading volume has been ").append(Math.random() > 0.5 ? "increasing" : "decreasing").append(" over the past 24 hours\n");
        analysis.append("- Market volatility is ").append(Math.random() > 0.7 ? "high" : "moderate").append("\n\n");
        
        analysis.append("*Note: This is a simulated analysis generated without Claude AI API access.*");
        
        return analysis.toString();
    }
    
    /**
     * Generate a simulated trading recommendation when the API key is not available
     */
    private Map<String, Object> generateSimulatedRecommendation(String symbol, String technicalData, SentimentData sentimentData) {
        Map<String, Object> recommendation = new HashMap<>();
        
        // Use sentiment score to determine recommendation
        double sentimentScore = sentimentData.getSentimentScore();
        
        if (sentimentScore > 0.3) {
            recommendation.put("action", "BUY");
            recommendation.put("confidence", 0.6 + (sentimentScore * 0.2));
            recommendation.put("timeFrame", "MEDIUM");
            recommendation.put("reasoning", "Positive sentiment and technical indicators suggest upward momentum");
            recommendation.put("riskLevel", "MEDIUM");
            recommendation.put("targetPrice", 100.0 * (1 + (sentimentScore * 0.2)));
            recommendation.put("stopLoss", 100.0 * (1 - 0.05));
        } else if (sentimentScore > -0.3) {
            recommendation.put("action", "HOLD");
            recommendation.put("confidence", 0.5);
            recommendation.put("timeFrame", "SHORT");
            recommendation.put("reasoning", "Mixed signals suggest waiting for clearer market direction");
            recommendation.put("riskLevel", "MEDIUM");
            recommendation.put("targetPrice", 100.0);
            recommendation.put("stopLoss", 100.0 * 0.93);
        } else {
            recommendation.put("action", "SELL");
            recommendation.put("confidence", 0.6 + (Math.abs(sentimentScore) * 0.2));
            recommendation.put("timeFrame", "SHORT");
            recommendation.put("reasoning", "Negative sentiment and technical weakness suggest downward pressure");
            recommendation.put("riskLevel", "HIGH");
            recommendation.put("targetPrice", 100.0 * (1 - (Math.abs(sentimentScore) * 0.15)));
            recommendation.put("stopLoss", 100.0 * 1.05);
        }
        
        recommendation.put("note", "This is a simulated recommendation generated without Claude AI API access.");
        
        return recommendation;
    }

    /**
     * Perform detailed sentiment analysis using Claude AI's capabilities
     * 
     * @param symbol The trading pair to analyze
     * @param newsData Text data from news sources
     * @param socialData Text data from social media
     * @return Enhanced sentiment analysis with AI-powered insights
     */
    public Map<String, Object> enhancedSentimentAnalysis(String symbol, String newsData, String socialData) {
        Map<String, Object> result = new HashMap<>();
        
        // Check if API key is configured
        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            logger.warn("Claude API key not configured, returning simulated sentiment analysis");
            return generateSimulatedSentimentAnalysis(symbol);
        }
        
        try {
            // Create the prompt for Claude
            String prompt = createSentimentAnalysisPrompt(symbol, newsData, socialData);
            
            // Set up headers with API key
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", claudeApiKey);
            headers.set("anthropic-version", "2023-06-01");
            
            // Create request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", defaultClaudeModel);
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);
            
            // Add system prompt as a top-level parameter
            requestBody.put("system", "You are Claude, an expert in financial sentiment analysis and cryptocurrency markets. " +
                    "Your task is to analyze news and social media data to extract sentiment, key themes, and market signals. " +
                    "Provide a structured JSON response with the following fields: " +
                    "{\"overallSentiment\": [-1.0 to 1.0], \"confidence\": [0.0 to 1.0], " +
                    "\"keyThemes\": [array of strings], \"potentialImpact\": \"HIGH/MEDIUM/LOW\", " +
                    "\"marketOutlook\": \"BULLISH/BEARISH/NEUTRAL\", \"riskFactors\": [array of strings], " +
                    "\"summary\": \"brief analysis\"}");
            
            // Add messages array with only user message (using standard Java collections)
            List<Map<String, Object>> messages = new ArrayList<>();
            
            // User message with the prompt
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            messages.add(userMessage);
            
            requestBody.put("messages", messages);
            
            // Make the API call
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(claudeApiUrl, requestEntity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                JsonNode contentNode = jsonResponse.get("content");
                
                String content;
                if (contentNode.isArray() && contentNode.size() > 0) {
                    // Handle array response format
                    content = contentNode.get(0).get("text").asText();
                } else if (contentNode.isObject()) {
                    // Handle object response format
                    content = contentNode.get("text").asText();
                } else {
                    content = contentNode.asText();
                }
                
                // Parse the JSON response
                // Find the JSON object in the response (it might be within other text)
                int startIndex = content.indexOf("{");
                int endIndex = content.lastIndexOf("}") + 1;
                
                if (startIndex >= 0 && endIndex > startIndex) {
                    String jsonString = content.substring(startIndex, endIndex);
                    JsonNode sentimentJson = objectMapper.readTree(jsonString);
                    
                    // Convert JSON to Map
                    result.put("overallSentiment", sentimentJson.get("overallSentiment").asDouble());
                    result.put("confidence", sentimentJson.get("confidence").asDouble());
                    
                    // Convert JSONArray to List
                    JsonNode themesArray = sentimentJson.get("keyThemes");
                    List<String> themes = new ArrayList<>();
                    for (JsonNode theme : themesArray) {
                        themes.add(theme.asText());
                    }
                    result.put("keyThemes", themes);
                    
                    result.put("potentialImpact", sentimentJson.get("potentialImpact").asText());
                    result.put("marketOutlook", sentimentJson.get("marketOutlook").asText());
                    
                    // Convert JSONArray to List
                    JsonNode riskArray = sentimentJson.get("riskFactors");
                    List<String> risks = new ArrayList<>();
                    for (JsonNode risk : riskArray) {
                        risks.add(risk.asText());
                    }
                    result.put("riskFactors", risks);
                    
                    result.put("summary", sentimentJson.get("summary").asText());
                    
                    return result;
                }
            }
            
            logger.error("Error parsing Claude API response for sentiment analysis");
            return generateSimulatedSentimentAnalysis(symbol);
            
        } catch (Exception e) {
            logger.error("Error calling Claude API for sentiment analysis: {}", e.getMessage());
            return generateSimulatedSentimentAnalysis(symbol);
        }
    }
    
    /**
     * Create a prompt for sentiment analysis
     */
    private String createSentimentAnalysisPrompt(String symbol, String newsData, String socialData) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Please analyze the following data for ").append(symbol).append(":\n\n");
        prompt.append("NEWS DATA:\n").append(newsData).append("\n\n");
        prompt.append("SOCIAL MEDIA DATA:\n").append(socialData).append("\n\n");
        
        prompt.append("Please provide a detailed sentiment analysis of this data. Your analysis should include:\n");
        prompt.append("1. Overall sentiment score (-1.0 to 1.0)\n");
        prompt.append("2. Confidence level in your assessment (0.0 to 1.0)\n");
        prompt.append("3. Key themes or topics mentioned\n");
        prompt.append("4. Potential impact on the market (HIGH/MEDIUM/LOW)\n");
        prompt.append("5. Market outlook (BULLISH/BEARISH/NEUTRAL)\n");
        prompt.append("6. Potential risk factors\n");
        prompt.append("7. A brief summary of your analysis\n\n");
        
        prompt.append("Please format your response as a JSON object.");
        
        return prompt.toString();
    }
    
    /**
     * Generate simulated sentiment analysis when API is not available
     */
    private Map<String, Object> generateSimulatedSentimentAnalysis(String symbol) {
        Map<String, Object> result = new HashMap<>();
        
        // Extract currency from symbol
        String currency = symbol.replaceAll("USDT$|USD$|BTC$", "").toUpperCase();
        
        // Generate simulated values based on currency
        double sentiment = 0.0;
        String outlook = "NEUTRAL";
        String impact = "MEDIUM";
        
        switch (currency) {
            case "BTC":
                sentiment = 0.35;
                outlook = "BULLISH";
                impact = "HIGH";
                break;
            case "ETH":
                sentiment = 0.28;
                outlook = "BULLISH";
                impact = "MEDIUM";
                break;
            case "XRP":
                sentiment = -0.12;
                outlook = "BEARISH";
                impact = "MEDIUM";
                break;
            case "DOGE":
                sentiment = 0.45;
                outlook = "BULLISH";
                impact = "MEDIUM";
                break;
            default:
                sentiment = 0.1;
                outlook = "NEUTRAL";
                impact = "LOW";
        }
        
        // Add some randomness
        sentiment += (Math.random() * 0.2) - 0.1;
        sentiment = Math.max(-1.0, Math.min(1.0, sentiment));
        
        result.put("overallSentiment", sentiment);
        result.put("confidence", 0.7);
        
        List<String> themes = new ArrayList<>();
        themes.add("Price movement");
        themes.add("Market adoption");
        if (currency.equals("BTC")) {
            themes.add("Institutional investment");
            themes.add("Regulatory updates");
        } else if (currency.equals("ETH")) {
            themes.add("Technical developments");
            themes.add("DeFi applications");
        }
        result.put("keyThemes", themes);
        
        result.put("potentialImpact", impact);
        result.put("marketOutlook", outlook);
        
        List<String> risks = new ArrayList<>();
        risks.add("Market volatility");
        risks.add("Regulatory uncertainty");
        result.put("riskFactors", risks);
        
        result.put("summary", "Simulated sentiment analysis for " + symbol);
        
        return result;
    }

    /**
     * Generate AI analysis specifically for deciding whether to hedge an active position.
     *
     * @param position The active position to evaluate.
     * @param symbol The trading pair symbol.
     * @param technicalData JSON string of current technical indicators for the symbol.
     * @param sentimentData Current sentiment data for the symbol.
     * @param currentMarketPriceStr Current market price of the symbol as a string.
     * @return An AiHedgeDecision object or null if an error occurs.
     */
    public AiHedgeDecision generateHedgingAnalysis(PositionUpdateData position, String symbol, String technicalData, 
                                                 SentimentData sentimentData, String currentMarketPriceStr) {
        String cacheKey = "hedge_" + symbol + "_" + position.getEntryPrice() + "_" + position.getSide() + "_" + technicalData.hashCode() + "_" + sentimentData.hashCode();
        // Simplified cache check for now, ideally use a proper cache service that handles DTOs
        // if (responseCache.containsKey(cacheKey)) { 
        //     try {
        //         return objectMapper.readValue(responseCache.get(cacheKey), AiHedgeDecision.class);
        //     } catch (Exception e) { logger.warn("Cache hit for {} but failed to parse: {}", cacheKey, e.getMessage()); }
        // }

        if (claudeApiKey == null || claudeApiKey.isEmpty()) {
            logger.warn("HEDGING_AI: Claude API key not configured. Returning null for hedging analysis.");
            return null; // Or a simulated neutral decision
        }

        try {
            // Construct the user prompt
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("Please analyze the following active trading position for hedging needs:\n");
            userPrompt.append("- Symbol: ").append(symbol).append("\n");
            userPrompt.append("- Position Side: ").append(position.getSide()).append("\n");
            userPrompt.append("- Entry Price: ").append(position.getEntryPrice()).append("\n");
            userPrompt.append("- Current Market Price: ").append(currentMarketPriceStr).append("\n");
            userPrompt.append("- Unrealized PnL: ").append(position.getUnrealisedPnl()).append(" (")
                      .append(position.getPositionValue() != null && position.getPositionValue().compareTo(BigDecimal.ZERO) > 0 ? 
                                position.getUnrealisedPnl().divide(position.getPositionValue(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).toPlainString() + "%" : "N/A")
                      .append(")\n");
            userPrompt.append("- Position Size: ").append(position.getSize()).append("\n");
            // userPrompt.append("- Position Age: [calculate age if available/needed]\n");
            // userPrompt.append("- Initial Strategy Rationale: [fetch if available/logged]\n\n");

            userPrompt.append("Current Technical Indicators for ").append(symbol).append(":\n");
            userPrompt.append(technicalData).append("\n\n");

            userPrompt.append("Current Sentiment Data for ").append(symbol).append(":\n");
            userPrompt.append("Overall Sentiment Score: ").append(sentimentData.getSentimentScore()).append(" (").append(sentimentData.getSentimentCategory()).append(")\n");
            userPrompt.append("Total Mentions: ").append(sentimentData.getTotalMentions()).append("\n\n");

            userPrompt.append("Based on all the above, should this specific position be hedged? Provide your analysis in the specified JSON format.");

            // System Prompt
            String systemPrompt = "You are Claude, an expert trading risk analyst. You are given details of an active trading position and current market data. " +
                                "Your task is to assess whether this specific position should be hedged to mitigate further losses.\n" +
                                "Focus on:\n" +
                                "1. The continued validity of the original trade idea given the current market conditions and the position's performance.\n" +
                                "2. Signs of reversal or trend exhaustion that might specifically impact this active position.\n" +
                                "3. Whether current volatility or market regime suggests an increased risk for this particular entry.\n" +
                                "Provide a structured JSON response with the following fields:\n" +
                                "{\n" +
                                "  \"shouldHedge\": boolean, \n" +
                                "  \"hedgeConfidence\": double, \n" +
                                "  \"hedgeReason\": \"[textual explanation of why a hedge is or isn't recommended, focusing on the active position]\", \n" +
                                "  \"criticalSupportResistance\": { \"support\": [price], \"resistance\": [price] }, \n" +
                                "  \"adversePriceTarget\": [price] \n" +
                                "}";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", claudeApiKey);
            headers.set("anthropic-version", "2023-06-01");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", defaultClaudeModel);
            requestBody.put("max_tokens", maxTokens); // Consider a smaller max_tokens for this focused query
            requestBody.put("temperature", 0.5); // Lower temperature for more deterministic/factual assessment
            requestBody.put("system", systemPrompt);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt.toString());
            messages.add(userMessage);
            requestBody.put("messages", messages);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            logger.info("HEDGING_AI: Requesting hedging analysis from Claude for {}. Prompt (first 200 chars): {}", symbol, userPrompt.toString().substring(0, Math.min(userPrompt.length(), 200)));

            ResponseEntity<String> response = restTemplate.postForEntity(claudeApiUrl, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                JsonNode contentNode = jsonResponse.get("content");
                String aiRawResponse = "";

                if (contentNode.isArray() && contentNode.size() > 0) {
                    aiRawResponse = contentNode.get(0).get("text").asText();
                } else if (contentNode.isObject()) {
                    aiRawResponse = contentNode.get("text").asText();
                } else {
                    aiRawResponse = contentNode.asText();
                }
                
                logger.debug("HEDGING_AI: Raw response for {}: {}", symbol, aiRawResponse);

                // Attempt to parse the raw response string into AiHedgeDecision DTO
                try {
                    AiHedgeDecision decision = objectMapper.readValue(aiRawResponse, AiHedgeDecision.class);
                    // responseCache.put(cacheKey, aiRawResponse); // Cache successful DTO string representation
                    return decision;
                } catch (Exception e) {
                    logger.error("HEDGING_AI: Failed to parse Claude's JSON response for hedging analysis on {}: {}. Raw response: {}", symbol, e.getMessage(), aiRawResponse);
                    return null;
                }
            } else {
                logger.error("HEDGING_AI: Error from Claude API (hedging analysis) for {}: {}", symbol, response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            logger.error("HEDGING_AI: Error calling Claude API (hedging analysis) for {}: {}", symbol, e.getMessage(), e);
            return null;
        }
    }
} 