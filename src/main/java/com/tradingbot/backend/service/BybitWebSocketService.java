package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.BybitApiConfig;
import com.tradingbot.backend.model.*;
import com.tradingbot.backend.service.analysis.TradeAnalysisService;
import com.tradingbot.backend.util.SignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BybitWebSocketService extends TextWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(BybitWebSocketService.class);
    private final BybitApiConfig bybitApiConfig;
    private final ObjectMapper objectMapper;
    private final TradeAnalysisService tradeAnalysisService;
    private final Map<String, Position> activePositions = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> orderbookCache = new ConcurrentHashMap<>();
    private WebSocketSession session;

    public BybitWebSocketService(BybitApiConfig bybitApiConfig, 
                               ObjectMapper objectMapper,
                               TradeAnalysisService tradeAnalysisService) {
        this.bybitApiConfig = bybitApiConfig;
        this.objectMapper = objectMapper;
        this.tradeAnalysisService = tradeAnalysisService;
    }

    @PostConstruct
    public void connect() {
        subscribeToPositions();
        subscribeToOrderbook();
    }

    private void subscribeToPositions() {
        try {
            WebSocketClient client = new StandardWebSocketClient();
            Map<String, Object> auth = createAuthParams();
            
            client.doHandshake(this, bybitApiConfig.getWsUrl())
                .addCallback(
                    result -> {
                        session = result;
                        // Subscribe to position updates
                        sendMessage(Map.of(
                            "op", "subscribe",
                            "args", List.of("position")
                        ));
                        logger.info("Successfully subscribed to position updates");
                    },
                    ex -> logger.error("WebSocket connection failed: {}", ex.getMessage())
                );
        } catch (Exception e) {
            logger.error("Error subscribing to positions: {}", e.getMessage());
        }
    }

    private void subscribeToOrderbook() {
        try {
            // Subscribe to orderbook for active positions
            activePositions.keySet().forEach(symbol -> {
                sendMessage(Map.of(
                    "op", "subscribe",
                    "args", List.of("orderbook.50." + symbol)
                ));
                logger.info("Subscribed to orderbook for {}", symbol);
            });
        } catch (Exception e) {
            logger.error("Error subscribing to orderbook: {}", e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, org.springframework.web.socket.TextMessage message) {
        try {
            JsonNode data = objectMapper.readTree(message.getPayload());
            String topic = data.path("topic").asText();

            if ("position".equals(topic)) {
                handlePositionUpdate(data);
            } else if (topic.startsWith("orderbook")) {
                handleOrderbookUpdate(data);
            }
        } catch (Exception e) {
            logger.error("Error handling WebSocket message: {}", e.getMessage());
        }
    }

    private void handlePositionUpdate(JsonNode message) {
        try {
            JsonNode data = message.get("data");
            for (JsonNode posNode : data) {
                Position position = Position.builder()
                    .symbol(posNode.get("symbol").asText())
                    .side(posNode.get("side").asText())
                    .quantity(new BigDecimal(posNode.get("size").asText()))
                    .entryPrice(new BigDecimal(posNode.get("entryPrice").asText()))
                    .leverage(new BigDecimal(posNode.get("leverage").asText()))
                    .unrealizedPnl(new BigDecimal(posNode.get("unrealisedPnl").asText()))
                    .currentPrice(new BigDecimal(posNode.get("markPrice").asText()))
                    .liquidationPrice(new BigDecimal(posNode.get("liqPrice").asText()))
                    .build();

                String symbol = position.getSymbol();
                activePositions.put(symbol, position);

                // Perform AI analysis if we have orderbook data
                if (orderbookCache.containsKey(symbol)) {
                    performAnalysis(position, orderbookCache.get(symbol));
                }
            }
        } catch (Exception e) {
            logger.error("Error handling position update: {}", e.getMessage());
        }
    }

    private void handleOrderbookUpdate(JsonNode message) {
        try {
            String symbol = message.get("data").get("s").asText();
            orderbookCache.put(symbol, message.get("data"));

            // Perform analysis if we have an active position for this symbol
            if (activePositions.containsKey(symbol)) {
                performAnalysis(activePositions.get(symbol), message.get("data"));
            }
        } catch (Exception e) {
            logger.error("Error handling orderbook update: {}", e.getMessage());
        }
    }

    private void performAnalysis(Position position, JsonNode orderbook) {
        try {
            // Get AI-powered analysis
            TradeAnalysis analysis = tradeAnalysisService.analyzePosition(position, orderbook);

            // Broadcast analysis results
            broadcastAnalysis(position.getSymbol(), analysis);
        } catch (Exception e) {
            logger.error("Error performing analysis: {}", e.getMessage());
        }
    }

    private void broadcastAnalysis(String symbol, TradeAnalysis analysis) {
        try {
            Map<String, Object> message = Map.of(
                "type", "analysis",
                "symbol", symbol,
                "data", analysis
            );
            sendMessage(message);
        } catch (Exception e) {
            logger.error("Error broadcasting analysis: {}", e.getMessage());
        }
    }

    private void sendMessage(Map<String, Object> message) {
        try {
            if (session != null && session.isOpen()) {
                String payload = objectMapper.writeValueAsString(message);
                session.sendMessage(new org.springframework.web.socket.TextMessage(payload));
            }
        } catch (Exception e) {
            logger.error("Error sending message: {}", e.getMessage());
        }
    }

    private Map<String, Object> createAuthParams() {
        long timestamp = System.currentTimeMillis();
        String signature = generateSignature(timestamp);
        
        return Map.of(
            "op", "auth",
            "args", List.of(
                bybitApiConfig.getApiKey(),
                timestamp,
                signature
            )
        );
    }

    private String generateSignature(long timestamp) {
        String payload = timestamp + bybitApiConfig.getApiKey() + bybitApiConfig.getRecvWindow();
        return SignatureUtil.generateSignature(payload, bybitApiConfig.getSecretKey());
    }
} 