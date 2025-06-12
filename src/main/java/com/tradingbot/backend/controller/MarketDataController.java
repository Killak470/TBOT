package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.MexcFuturesApiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@RestController
@RequestMapping("/api/mexc")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class MarketDataController {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataController.class);
    private final MexcApiClientService mexcApiClientService;
    private final MexcFuturesApiService mexcFuturesApiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketDataController(MexcApiClientService mexcApiClientService, MexcFuturesApiService mexcFuturesApiService) {
        this.mexcApiClientService = mexcApiClientService;
        this.mexcFuturesApiService = mexcFuturesApiService;
    }

    // --- SPOT V3 --- //
    @GetMapping(value = "/spot/time", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSpotServerTime() {
        logger.debug("Fetching spot server time");
        ResponseEntity<String> response = mexcApiClientService.getSpotServerTime();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }

    @GetMapping(value = "/spot/exchangeInfo", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSpotExchangeInfo(@RequestParam(required = false) String symbol) {
        logger.debug("Fetching spot exchange info for symbol: {}", symbol);
        ResponseEntity<String> response = mexcApiClientService.getSpotExchangeInfo(symbol);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }

    @GetMapping(value = "/spot/klines", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getSpotKlines(@RequestParam String symbol,
                                                @RequestParam String interval,
                                                @RequestParam(required = false) Long startTime,
                                                @RequestParam(required = false) Long endTime,
                                                @RequestParam(required = false) Integer limit) {
        logger.debug("Fetching spot klines for symbol: {}, interval: {}", symbol, interval);
        ResponseEntity<String> response = mexcApiClientService.getSpotKlines(symbol, interval, startTime, endTime, limit);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }

    // --- FUTURES V1 --- //
    @GetMapping(value = "/futures/time", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getFuturesServerTime() {
        logger.debug("Fetching futures server time using specialized service");
        ResponseEntity<String> response = mexcFuturesApiService.getFuturesServerTime();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }

    @GetMapping(value = "/futures/contract/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getFuturesContractDetail(@RequestParam(required = false) String symbol) {
        logger.debug("Fetching futures contract detail for symbol: {} using specialized service", symbol);
        ResponseEntity<String> response = mexcFuturesApiService.getFuturesContractDetail(symbol);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getBody());
    }

    @GetMapping(value = "/futures/klines", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getFuturesKlines(@RequestParam String symbol,
                                                   @RequestParam String interval,
                                                   @RequestParam(required = false) Long start,
                                                   @RequestParam(required = false) Long end) {
        logger.debug("Fetching futures klines for symbol: {}, interval: {} using specialized service with improved error handling", symbol, interval);
        
        try {
            ResponseEntity<String> response = mexcFuturesApiService.getFuturesKlines(symbol, interval, start, end);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String transformedData = transformFuturesKlinesResponse(response.getBody());
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(transformedData);
            }
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Failed to fetch futures data\"}");
        } catch (Exception e) {
            logger.error("Error processing futures klines for {}: {}", symbol, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Failed to fetch futures data\"}");
        }
    }
    
    /**
     * Transforms MEXC futures klines response from array-based format to object-based format
     * that the frontend expects.
     * 
     * MEXC Futures format:
     * {
     *   "success": true,
     *   "code": 0,
     *   "data": {
     *     "time": [1609740600],
     *     "open": [33016.5],
     *     "close": [33040.5],
     *     "high": [33094.0],
     *     "low": [32995.0],
     *     "vol": [67332.0],
     *     "amount": [222515.85925]
     *   }
     * }
     * 
     * Frontend expected format:
     * {
     *   "success": true,
     *   "code": 0,
     *   "data": [
     *     {
     *       "time": 1609740600,
     *       "open": 33016.5,
     *       "high": 33094.0,
     *       "low": 32995.0,
     *       "close": 33040.5,
     *       "volume": 67332.0
     *     }
     *   ]
     * }
     */
    private String transformFuturesKlinesResponse(String rawResponse) {
        try {
            JsonNode rootNode = objectMapper.readTree(rawResponse);
            
            // Check if this is a successful MEXC futures response
            if (rootNode.has("success") && rootNode.get("success").asBoolean() && 
                rootNode.has("data") && rootNode.get("data").isObject()) {
                
                JsonNode dataNode = rootNode.get("data");
                
                // Extract arrays from the data object
                JsonNode timeArray = dataNode.get("time");
                JsonNode openArray = dataNode.get("open");
                JsonNode highArray = dataNode.get("high");
                JsonNode lowArray = dataNode.get("low");
                JsonNode closeArray = dataNode.get("close");
                JsonNode volArray = dataNode.get("vol");
                
                if (timeArray != null && timeArray.isArray() && openArray != null && openArray.isArray()) {
                    ArrayNode transformedDataArray = objectMapper.createArrayNode();
                    
                    // Transform each data point
                    for (int i = 0; i < timeArray.size(); i++) {
                        ObjectNode klineObject = objectMapper.createObjectNode();
                        klineObject.put("time", timeArray.get(i).asLong());
                        klineObject.put("open", openArray.get(i).asDouble());
                        klineObject.put("high", highArray.get(i).asDouble());
                        klineObject.put("low", lowArray.get(i).asDouble());
                        klineObject.put("close", closeArray.get(i).asDouble());
                        klineObject.put("volume", volArray.get(i).asDouble());
                        
                        transformedDataArray.add(klineObject);
                    }
                    
                    // Create the response structure
                    ObjectNode responseNode = objectMapper.createObjectNode();
                    responseNode.put("success", true);
                    responseNode.put("code", 0);
                    responseNode.set("data", transformedDataArray);
                    
                    return objectMapper.writeValueAsString(responseNode);
                }
            }
            
            // If transformation fails, return original response
            logger.warn("Could not transform futures response, returning original: {}", rawResponse);
            return rawResponse;
            
        } catch (Exception e) {
            logger.error("Error transforming futures klines response: {}", e.getMessage());
            return rawResponse; // Return original on error
        }
    }
}

