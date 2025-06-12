package com.tradingbot.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatusCode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ClaudeProvider implements AIProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${ai.claude.api.key:}")
    private String apiKey;

    @Value("${ai.claude.api.url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    @Value("${ai.claude.model:claude-3-opus-20240229}")
    private String model;

    @Value("${ai.claude.max.retries:3}")
    private int maxRetries;

    @Autowired
    public ClaudeProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getProviderName() {
        return "CLAUDE";
    }

    @Override
    public int getMaxRetries() {
        return maxRetries;
    }

    @Override
    public String executeChatCompletion(String systemPrompt, String userPrompt, int maxTokens, double temperature)
            throws IOException, InterruptedException, HttpClientErrorException {

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            requestBody.put("system", systemPrompt);
        }

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        String requestBodyString = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyString))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        HttpStatusCode statusCode = HttpStatusCode.valueOf(response.statusCode());

        if (statusCode.isError()) {
            throw new HttpClientErrorException(statusCode, response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        JsonNode contentArray = responseJson.path("content");

        if (contentArray.isArray() && !contentArray.isEmpty()) {
            return contentArray.get(0).path("text").asText();
        }

        throw new IOException("Failed to extract content from Claude response: " + response.body());
    }
} 