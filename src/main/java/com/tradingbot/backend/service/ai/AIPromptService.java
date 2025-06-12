package com.tradingbot.backend.service.ai;

import com.tradingbot.backend.model.ScanResult;

/**
 * Service dedicated to constructing prompts for various AI providers.
 * This abstracts the "prompt engineering" away from the AI orchestration service.
 */
public interface AIPromptService {

    /**
     * Builds the main user prompt for market analysis based on a scan result.
     * Can be tailored to the specific provider.
     *
     * @param scanResult The market data and technical indicators.
     * @param providerName The name of the AI provider (e.g., "CLAUDE", "OPENAI").
     * @return A formatted string to be used as the user prompt.
     */
    String buildMarketAnalysisPrompt(ScanResult scanResult, String providerName);

    /**
     * Gets the system prompt to be used for general market analysis.
     * Can be tailored to the specific provider.
     *
     * @param providerName The name of the AI provider (e.g., "CLAUDE", "OPENAI").
     * @return A formatted string to be used as the system prompt.
     */
    String getMarketAnalysisSystemPrompt(String providerName);

} 