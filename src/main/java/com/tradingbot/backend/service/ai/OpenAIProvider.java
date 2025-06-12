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

public class OpenAIProvider implements AIProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${ai.openai.api.key:}")
    private String apiKey;

    @Value("${ai.openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${ai.openai.model:gpt-4o}")
    private String model;

    @Autowired
    public OpenAIProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getProviderName() {
        return "OPENAI";
    }

    @Override
    public String executeChatCompletion(String systemPrompt, String userPrompt, int maxTokens, double temperature)
            throws IOException, InterruptedException, HttpClientErrorException {

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        ArrayNode messages = requestBody.putArray("messages");
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemMessage = messages.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
        }

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        String requestBodyString = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyString))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        HttpStatusCode statusCode = HttpStatusCode.valueOf(response.statusCode());

        if (statusCode.isError()) {
            throw new HttpClientErrorException(statusCode, response.body());
        }

        JsonNode responseJson = objectMapper.readTree(response.body());
        JsonNode choicesArray = responseJson.path("choices");

        if (choicesArray.isArray() && !choicesArray.isEmpty()) {
            JsonNode firstChoice = choicesArray.get(0);
            return firstChoice.path("message").path("content").asText();
        }

        throw new IOException("Failed to extract content from OpenAI response: " + response.body());
    }
} 