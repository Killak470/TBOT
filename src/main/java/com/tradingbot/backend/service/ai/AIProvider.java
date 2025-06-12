package com.tradingbot.backend.service.ai;

import org.springframework.web.client.HttpClientErrorException;
import java.io.IOException;

/**
 * Defines the contract for an AI service provider, allowing for interchangeable
 * implementations like Claude, OpenAI, etc.
 */
public interface AIProvider {

    /**
     * Gets the unique name of the provider (e.g., "CLAUDE", "OPENAI").
     * This should match the values in the application configuration.
     * @return The provider's name.
     */
    String getProviderName();

    /**
     * Executes a chat completion call to the specific AI provider.
     *
     * @param systemPrompt The system prompt to guide the AI's behavior.
     * @param userPrompt The user's specific request or question.
     * @param maxTokens The maximum number of tokens to generate in the response.
     * @param temperature The sampling temperature for the generation.
     * @return The raw text content of the AI's response.
     * @throws IOException If there is an issue with the underlying HTTP client.
     * @throws InterruptedException If the request is interrupted.
     * @throws HttpClientErrorException If the API returns an HTTP error (4xx or 5xx).
     */
    String executeChatCompletion(String systemPrompt, String userPrompt, int maxTokens, double temperature)
            throws IOException, InterruptedException, HttpClientErrorException;
} 