package com.tradingbot.backend.service.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.service.cache.PositionCacheService;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

@Service
public class BybitPositionWebSocketClient extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(BybitPositionWebSocketClient.class);

    @Value("${bybit.websocket.private.url:wss://stream.bybit.com/v5/private}")
    private String bybitWebSocketUrl;

    @Value("${bybit.api.key}")
    private String apiKey;

    @Value("${bybit.api.secret}")
    private String apiSecret;

    private final ObjectMapper objectMapper;
    private final PositionCacheService positionCacheService;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pingTaskFuture;
    private volatile boolean isConnecting = false;
    private static final int RECONNECT_DELAY_SECONDS = 10;
    private boolean explicitlyClosed = false;

    @Autowired
    public BybitPositionWebSocketClient(ObjectMapper objectMapper, PositionCacheService positionCacheService,
                                        @Value("${bybit.websocket.private.url:wss://stream.bybit.com/v5/private}") String serverUri) throws URISyntaxException {
        super(new URI(serverUri));
        this.objectMapper = objectMapper;
        this.positionCacheService = positionCacheService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @PostConstruct
    public synchronized void initConnection() {
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            logger.warn("Bybit API key/secret not configured. WebSocket client will not connect.");
            return;
        }
        logger.info("Initializing Bybit Position WebSocket client connection to {}...", uri);
        this.connect(); // This is now a method from WebSocketClient superclass
    }
    
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("Bybit WebSocket connection opened. Status: {}, Message: {}", handshakedata.getHttpStatus(), handshakedata.getHttpStatusMessage());
        isConnecting = false; // Successfully connected
        authenticateAndSubscribe();
        startPingTask(); // Start sending pings
        explicitlyClosed = false;
    }

    @Override
    public void onMessage(String message) {
        logger.debug("Received WebSocket message: {}", message);
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String topic = rootNode.path("topic").asText();

            if ("position".equals(topic)) {
                JsonNode dataArray = rootNode.path("data");
                if (dataArray.isArray()) {
                    for (JsonNode positionDataNode : dataArray) {
                        logger.info("Processing position update for symbol {} (BYBIT): {}", positionDataNode.path("symbol").asText(), positionDataNode.toString());
                        positionCacheService.updatePosition(positionDataNode, "BYBIT");
                    }
                }
            } else if (rootNode.has("op")) {
                String opType = rootNode.path("op").asText();
                if ("auth".equals(opType)) {
                    boolean success = rootNode.path("success").asBoolean(false) || (rootNode.has("ret_code") && rootNode.path("ret_code").asInt() == 0);
                    if (success) {
                        logger.info("WebSocket authentication successful.");
                    } else {
                        logger.error("WebSocket authentication failed: {}", message);
                        // Consider closing the connection or retrying auth after a delay
                    }
                } else if ("subscribe".equals(opType)) {
                    boolean success = rootNode.path("success").asBoolean(false) || (rootNode.has("ret_code") && rootNode.path("ret_code").asInt() == 0);
                    if (success) {
                        logger.info("WebSocket subscription successful for args: {}", rootNode.path("args").toString());
                    } else {
                        logger.error("WebSocket subscription failed: {}", message);
                    }
                } else if ("ping".equals(opType)) { // Handling server ping
                     long serverTime = rootNode.path("timestamp_e6").asLong(System.currentTimeMillis()*1000) / 1000; // Bybit ping might have timestamp_e6
                     String pongResponse = String.format("{\"op\":\"pong\",\"timestamp_e6\":%d}", serverTime);
                     send(pongResponse); // Use send method from WebSocketClient
                     logger.debug("Responded to server PING with PONG: {}", pongResponse);
                } else if ("pong".equals(opType)){
                     logger.debug("Received PONG from server: {}", message);
                }
            }

        } catch (JsonProcessingException e) {
            logger.error("Error processing WebSocket message: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error processing WebSocket message: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("Bybit WebSocket connection closed. Code: {}, Reason: '{}', Remote: {}", code, reason, remote);
        isConnecting = false;
        stopPingTask();
        if (!explicitlyClosed) {
            attemptReconnection();
        }
    }

    @Override
    public void onError(Exception ex) {
        logger.error("Bybit WebSocket error occurred: {}", ex.getMessage(), ex);
        isConnecting = false; // Reset connection flag
        stopPingTask();
        // Reconnection is typically handled by onClose, but if onError is called without onClose,
        // we might want to ensure reconnection is attempted.
        if (!isOpen() && !explicitlyClosed) { // Check if connection is not open
             attemptReconnection();
        }
    }

    private void startPingTask() {
        if (pingTaskFuture == null || pingTaskFuture.isDone()) {
            pingTaskFuture = scheduler.scheduleAtFixedRate(this::sendPing, 20, 20, TimeUnit.SECONDS);
            logger.info("WebSocket Ping task started.");
        }
    }

    private void stopPingTask() {
        if (pingTaskFuture != null && !pingTaskFuture.isDone()) {
            pingTaskFuture.cancel(false);
            logger.info("WebSocket Ping task stopped.");
        }
    }

    private synchronized void attemptReconnection() {
        if (isOpen() || isConnecting) { // Check if already open or connecting
            logger.info("WebSocket is already connected or a connection attempt is in progress. Skipping reconnection.");
            return;
        }
        logger.info("Attempting to reconnect to Bybit WebSocket in {} seconds...", RECONNECT_DELAY_SECONDS);
        try {
            // Ensure the client is properly closed before reconnecting if it wasn't a clean close.
            if (this.getReadyState() != org.java_websocket.enums.ReadyState.CLOSED) {
                 this.closeBlocking(); // Use a blocking close to ensure it's closed before new attempt
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while closing WebSocket before reconnect: {}", e.getMessage());
            Thread.currentThread().interrupt(); // Restore interruption status
            return; // Don't proceed with reconnect if interrupted
        } catch (Exception e) {
            logger.error("Exception closing WebSocket before reconnect: {}", e.getMessage(), e);
        }
        
        // Re-initialize for reconnection
        // super(this.uri); // Re-setting URI if needed, usually not for same endpoint.
                           // The WebSocketClient needs to be a new instance or use reconnect()/reconnectBlocking()

        scheduler.schedule(() -> {
            logger.info("Executing scheduled reconnection to Bybit WebSocket.");
            try {
                // We need to create a new URI object if the old one is stale after close
                // However, the WebSocketClient.reconnect() or connect() should handle this.
                // Forcing a new URI for clarity
                URI currentUri = this.uri; // Store current URI before closing
                if(this.isClosed()){ // ensure it's closed
                    logger.info("WebSocket confirmed closed, attempting to reconnect to {}", currentUri);
                     // The Java-WebSocket library's reconnect() method reuses the existing URI.
                    this.reconnect(); // This will attempt to re-establish the connection.
                } else {
                    logger.warn("WebSocket was not fully closed. Attempting connect() instead of reconnect().");
                    this.connect();
                }
            } catch (Exception e) {
                logger.error("Error during scheduled reconnection attempt: {}", e.getMessage(), e);
                // Schedule another attempt if this one fails.
                scheduler.schedule(this::attemptReconnection, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void authenticateAndSubscribe() {
        if (isOpen()) { // Check if WebSocket is open using superclass method
            try {
                long expires = System.currentTimeMillis() + 10000; // Expires in 10 seconds
                String signatureString = "GET/realtime" + expires;
                String signature = generateWsSignature(apiSecret, signatureString); // Renamed for clarity

                String authRequest = String.format("{\"op\": \"auth\", \"args\": [\"%s\", %d, \"%s\"]}", apiKey, expires, signature);
                send(authRequest); // Use send method from WebSocketClient

                String subscribeRequest = String.format("{\"op\":\"subscribe\",\"args\":[\"position\"]}");
                send(subscribeRequest); // Use send method from WebSocketClient

            } catch (Exception e) {
                logger.error("Error during WebSocket authentication/subscription: {}", e.getMessage(), e);
            }
        } else {
            logger.warn("WebSocket is not connected. Cannot authenticate and subscribe.");
        }
    }

    // Renamed to avoid conflict if there's another generateSignature method elsewhere.
    private String generateWsSignature(String secret, String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    public void sendPing() {
        if (isOpen()) {
            try {
                // Bybit specific ping format (if any), or a generic one
                // Bybit v5 requires a specific ping format with op: "ping"
                String pingMessage = String.format("{\"op\":\"ping\",\"req_id\":\"pid_%d\"}", System.currentTimeMillis());
                send(pingMessage);
                logger.debug("Sent WebSocket PING: {}", pingMessage);
            } catch (Exception e) {
                logger.error("Error sending WebSocket PING: {}", e.getMessage(), e);
            }
        } else {
            logger.warn("Cannot send PING, WebSocket is not connected.");
        }
    }

    @PreDestroy
    public void disconnect() {
        logger.info("Disconnecting Bybit Position WebSocket client...");
        explicitlyClosed = true;
        stopPingTask();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (isOpen()) { // Check if it's open before trying to close
                this.closeBlocking(); // Use blocking close for a clean shutdown
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while closing WebSocket connection: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error disconnecting WebSocket: {}", e.getMessage(), e);
        }
        logger.info("Bybit Position WebSocket client disconnected.");
    }
} 