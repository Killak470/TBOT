package com.tradingbot.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.service.ai.AIProvider;
import com.tradingbot.backend.service.ai.ClaudeProvider;
import com.tradingbot.backend.service.ai.OpenAIProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProviderConfig {

    @Bean
    public AIProvider openAIProvider(ObjectMapper objectMapper) {
        return new OpenAIProvider(objectMapper);
    }

    @Bean
    public AIProvider claudeProvider(ObjectMapper objectMapper) {
        return new ClaudeProvider(objectMapper);
    }
} 