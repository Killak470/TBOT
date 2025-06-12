package com.tradingbot.backend.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(10))
            .setReadTimeout(Duration.ofSeconds(30))
            .additionalRequestCustomizers(request -> {
                request.getHeaders().add("User-Agent", "MEXC-Trading-Bot/1.0");
                request.getHeaders().add("Accept", "application/json");
                request.getHeaders().add("Cache-Control", "no-cache");
            })
            .build();
    }
}

