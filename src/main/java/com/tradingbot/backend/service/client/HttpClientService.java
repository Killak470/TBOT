package com.tradingbot.backend.service.client;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Service
public class HttpClientService {

    private final RestTemplate restTemplate;

    public HttpClientService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Make a GET request
     */
    public <T> ResponseEntity<T> get(String url, HttpHeaders headers, Class<T> responseType, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (params != null) {
            params.forEach(builder::queryParam);
        }
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(
            builder.build().toUri(),
            HttpMethod.GET,
            entity,
            responseType
        );
    }

    /**
     * Make a POST request
     */
    public <T> ResponseEntity<T> post(String url, HttpHeaders headers, Object body, Class<T> responseType) {
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(
            url,
            HttpMethod.POST,
            entity,
            responseType
        );
    }
    
    /**
     * Make a DELETE request
     */
    public <T> ResponseEntity<T> delete(String url, HttpHeaders headers, Class<T> responseType, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (params != null) {
            params.forEach(builder::queryParam);
        }
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(
            builder.build().toUri(),
            HttpMethod.DELETE,
            entity,
            responseType
        );
    }
}

