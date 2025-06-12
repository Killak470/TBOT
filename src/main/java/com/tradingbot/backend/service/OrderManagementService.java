package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.config.MexcApiConfig;
import com.tradingbot.backend.config.BybitApiConfig;
import com.tradingbot.backend.model.Order;
import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.repository.OrderRepository;
import com.tradingbot.backend.repository.PositionRepository;
import com.tradingbot.backend.service.client.HttpClientService;
import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.BybitApiClientService;
import com.tradingbot.backend.service.cache.PositionCacheService;
import com.tradingbot.backend.service.util.SignatureUtil;
import com.tradingbot.backend.service.util.OrderUtil; // Added import
import com.tradingbot.backend.service.InstrumentInfoService; // Added

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing and tracking orders and positions
 */
@Service
public class OrderManagementService {
    private static final Logger logger = LoggerFactory.getLogger(OrderManagementService.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final MexcFuturesApiService mexcFuturesApiService;
    private final BybitApiClientService bybitApiClientService;
    private final BybitFuturesApiService bybitFuturesApiService;
    private final BybitSpotApiService bybitSpotApiService;
    private final BybitPositionService bybitPositionService;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final PositionCacheService positionCacheService;
    private final InstrumentInfoService instrumentInfoService; // Added
    
    private static final int MAX_ORDER_PLACEMENT_RETRIES = 3;
    private static final long ORDER_PLACEMENT_RETRY_DELAY_MS = 2000; // 2 seconds
    
    // In-memory cache for faster access (can be removed later when fully migrated to DB)
    private final Map<Long, Order> orderCache = new ConcurrentHashMap<>();
    private final Map<Long, Position> positionCache = new ConcurrentHashMap<>();
    private final Map<String, List<Order>> orderHistoryCache = new ConcurrentHashMap<>();
    private final List<Long> activeOrderIds = new ArrayList<>();
    private final List<Long> activePositionIds = new ArrayList<>();
    
    @Autowired
    private PositionUpdateService positionUpdateService;
    
    public OrderManagementService(
            MexcApiClientService mexcApiClientService,
            MexcFuturesApiService mexcFuturesApiService,
            BybitApiClientService bybitApiClientService,
            BybitFuturesApiService bybitFuturesApiService,
            BybitSpotApiService bybitSpotApiService,
            BybitPositionService bybitPositionService,
            ObjectMapper objectMapper,
            OrderRepository orderRepository,
            PositionRepository positionRepository,
            PositionCacheService positionCacheService,
            InstrumentInfoService instrumentInfoService) {
        this.mexcApiClientService = mexcApiClientService;
        this.mexcFuturesApiService = mexcFuturesApiService;
        this.bybitApiClientService = bybitApiClientService;
        this.bybitFuturesApiService = bybitFuturesApiService;
        this.bybitSpotApiService = bybitSpotApiService;
        this.bybitPositionService = bybitPositionService;
        this.objectMapper = objectMapper;
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.positionCacheService = positionCacheService;
        this.instrumentInfoService = instrumentInfoService; // Added
        
        // Initialize caches from database
        initCachesFromDatabase();
    }
    
    /**
     * Initialize caches from database
     */
    private void initCachesFromDatabase() {
        try {
            logger.info("Initializing caches from database");
            orderRepository.findAll().forEach(order -> orderCache.put(order.getOrderId(), order));
            positionRepository.findAll().forEach(position -> positionCache.put(position.getId(), position));
            
            // Initialize order history cache
            List<Order> allOrders = orderRepository.findAll();
            Map<String, List<Order>> ordersBySymbol = allOrders.stream()
                .collect(Collectors.groupingBy(Order::getSymbol));
            
            orderHistoryCache.putAll(ordersBySymbol);
            
            logger.info("Initialized caches with {} orders and {} positions", 
                orderCache.size(), positionCache.size());
        } catch (Exception e) {
            logger.error("Error initializing caches from database", e);
        }
    }
    
    /**
     * Get MexcFuturesApiService for leverage setting
     */
    public MexcFuturesApiService getMexcFuturesApiService() {
        return mexcFuturesApiService;
    }
    
    /**
     * Get BybitFuturesApiService for leverage setting or order actions specific to Bybit futures
     */
    public BybitFuturesApiService getBybitFuturesApiService() {
        return bybitFuturesApiService;
    }
    
    /**
     * Get BybitSpotApiService for spot trading
     */
    public BybitSpotApiService getBybitSpotApiService() {
        return bybitSpotApiService;
    }
    
    /**
     * Get MexcApiClientService for spot trading
     */
    public MexcApiClientService getMexcApiClientService() {
        return mexcApiClientService;
    }
    
    /**
     * Place a new order using the specified exchange API
     * 
     * @param request Order request details
     * @param exchange Exchange to use ("MEXC" or "BYBIT")
     * @return The created order
     * @throws JsonProcessingException If there's an error processing the JSON response
     */
    @Transactional
    public Order placeOrder(OrderRequest request, String exchange) throws JsonProcessingException {
        logger.info("Placing order for {} on {} with marketType {}", request.getSymbol(), exchange, request.getMarketType());

        if ("MEXC".equalsIgnoreCase(exchange)) {
            logger.warn("MEXC orders are temporarily disabled. Order placement for {} on MEXC aborted.", request.getSymbol());
            // Or throw new UnsupportedOperationException("MEXC orders are temporarily disabled.");
            return null; // Or handle as an error appropriate for your application flow
        }
        
        int attempt = 0;
        Order placedOrder = null;
        Exception lastException = null;

        while (attempt < MAX_ORDER_PLACEMENT_RETRIES) {
            attempt++;
            try {
                logger.info("Attempt {}/{} to place order for {} on {}", attempt, MAX_ORDER_PLACEMENT_RETRIES, request.getSymbol(), exchange);
                
                // Determine if this is a futures order based on symbol format or explicit market type
                boolean isFuturesOrder = "linear".equalsIgnoreCase(request.getMarketType().getValue()) || 
                                       "futures".equalsIgnoreCase(request.getMarketType().getValue()) ||
                                       (request.getSymbol() != null && (request.getSymbol().contains("_") || 
                                       request.getSymbol().endsWith("_PERP") || 
                                       request.getSymbol().endsWith("_UMCBL")));
                
                String apiResponseString;
                if ("BYBIT".equalsIgnoreCase(exchange)) {
                    String formattedSymbol = request.getSymbol(); // Bybit V5 API generally handles symbol formats well.
                    String category;

                    if (isFuturesOrder) {
                        category = "linear"; // Assuming USDT-margined futures. Could be "inverse" for coin-margined.
                        // Bybit V5 futures symbols are typically like BTCUSDT, ETHUSDT without underscores.
                        if (formattedSymbol.contains("_")) {
                            formattedSymbol = formattedSymbol.split("_")[0] + "USDT"; // Example: BTC_PERP -> BTCUSDT
                            logger.debug("Normalized Bybit futures symbol from {} to {}", request.getSymbol(), formattedSymbol);
                        } else if (!formattedSymbol.endsWith("USDT") && !formattedSymbol.endsWith("PERP")) {
                             // If it's a plain symbol like "BTC", append "USDT" for linear futures.
                             // This logic might need refinement based on how symbols are consistently fed.
                            formattedSymbol += "USDT";
                        }
                        logger.info("Placing BYBIT FUTURES order for {} (category {}) (attempt {}). Request: Side={}, Type={}, Qty={}, Price={}, ClientID={}", 
                            formattedSymbol, category, attempt, request.getSide(), request.getType(), request.getQuantity(), request.getPrice(), request.getClientOrderId());
                    } else {
                        category = "spot";
                        // Bybit V5 spot symbols are typically like BTCUSDT, ETHUSDT. Remove underscores if any.
                        if (formattedSymbol.contains("_")) {
                            formattedSymbol = formattedSymbol.replace("_", "");
                            logger.debug("Normalized Bybit spot symbol from {} to {}", request.getSymbol(), formattedSymbol);
                        }
                         logger.info("Placing BYBIT SPOT order for {} (category {}) (attempt {}). Request: Side={}, Type={}, Qty={}, Price={}, ClientID={}", 
                            formattedSymbol, category, attempt, request.getSide(), request.getType(), request.getQuantity(), request.getPrice(), request.getClientOrderId());
                    }

                    // Use the unified bybitApiClientService.createOrder
                    ResponseEntity<String> bybitResponse = bybitApiClientService.createOrder(
                        category,
                        formattedSymbol,
                        request.getSide(),
                        request.getType(),
                        request.getQuantity().toString(),
                        request.getPrice() != null ? request.getPrice().toString() : null,
                        request.getTimeInForce(), // Ensure OrderRequest has getTimeInForce() or use a default like "GTC"
                        request.getClientOrderId(),
                        request.getStopLossPrice() != null ? request.getStopLossPrice().toString() : null,     // Pass SL if available
                        request.getTakeProfitPrice() != null ? request.getTakeProfitPrice().toString() : null, // Pass TP if available
                        null, // tpslMode (e.g., "Full" or "Partial") - can be added to OrderRequest if needed
                        null, // slTriggerBy (e.g., "LastPrice", "MarkPrice", "IndexPrice")
                        null  // tpTriggerBy
                    );
                    apiResponseString = bybitResponse.getBody();

                    // The response from createOrder should ideally contain the orderId directly.
                    // No separate getOrderDetails immediately needed if the response is sufficient.
                    // Ensure extractOrderData handles the response from createOrder correctly.

                } else { // MEXC - This block will now be skipped due to the check above
                    logger.warn("MEXC order placement is currently disabled. Skipping call for symbol {}.", request.getSymbol());
                    apiResponseString = null; // Ensure it doesn't proceed
                    // String symbolToUse = isFuturesOrder ? request.getSymbol() : convertToSpotSymbol(request.getSymbol());
                    // if (isFuturesOrder) {
                    //     logger.info("Placing MEXC FUTURES order for {} (attempt {}). Request: Side={}, Type={}, Qty={}, Price={}", 
                    //         symbolToUse, attempt, request.getSide(), request.getType(), request.getQuantity(), request.getPrice());
                    //     ResponseEntity<String> mexcResponse = mexcFuturesApiService.placeFuturesOrder(
                    //         symbolToUse,
                    //         request.getSide(),
                    //         request.getType(),
                    //         request.getQuantity().toString(),
                    //         request.getPrice() != null ? request.getPrice().toString() : null
                    //         // Removed clientOrderId from this call as well
                    //     );
                    //     apiResponseString = mexcResponse.getBody();
                    // } else {
                    //     logger.info("Placing MEXC SPOT order for {} (attempt {}). Request: Side={}, Type={}, Qty={}, Price={}", 
                    //         symbolToUse, attempt, request.getSide(), request.getType(), request.getQuantity(), request.getPrice());
                    //     ResponseEntity<String> mexcResponse = mexcApiClientService.placeSpotOrder(
                    //         symbolToUse,
                    //         request.getSide(),
                    //         request.getType(),
                    //         request.getTimeInForce(),
                    //         request.getQuantity().toString(),
                    //         request.getPrice() != null ? request.getPrice().toString() : null,
                    //         request.getClientOrderId() // Keep clientOrderId here if MexcApiClientService.placeSpotOrder supports it
                    //     );
                    //     apiResponseString = mexcResponse.getBody();
                    // }
                }
                
                if (apiResponseString != null && !apiResponseString.isEmpty()) {
                    JsonNode jsonResponse = objectMapper.readTree(apiResponseString);
                    JsonNode orderDataNode = extractOrderData(jsonResponse, exchange);

                    if (orderDataNode == null || orderDataNode.isMissingNode()){
                        logger.error("Failed to extract order data from {} response (attempt {}): {}. Full Response: {}", exchange, attempt, jsonResponse.path("msg").asText("No error message"), apiResponseString);
                        throw new RuntimeException("Failed to extract order data from " + exchange + " response: " + jsonResponse.path("msg").asText("No message field"));
                    }

                    Order order = objectMapper.treeToValue(orderDataNode, Order.class);
                    
                    // Ensure crucial fields like orderId are present after parsing
                    if (order.getOrderId() == null) { // Bybit might return orderId as string, ensure Order model handles Long/String or parse here
                        String strOrderId = orderDataNode.path(exchange.equalsIgnoreCase("BYBIT") ? "orderId" : "id").asText(null);
                        if (strOrderId != null) order.setOrderId(Long.parseLong(strOrderId)); // Basic conversion, needs care
                        else throw new RuntimeException("Parsed order object missing orderId.");
                    }

                    order.setBotId(request.getBotId());
                    order.setStrategyName(request.getStrategyName());
                    order.setPriceAtCreation(request.getPrice()); // Assuming this is the intended limit price
                    order.setMarketType(isFuturesOrder ? "linear" : "spot");
                    order.setExchange(exchange.toUpperCase());
                    order.setSymbol(request.getSymbol()); // Ensure symbol from original request is used for consistency
                    if (order.getStatus() == null) order.setStatus("NEW"); // Default if not provided by API
                    
                    // This part was in updatePositionFromOrder, but seems to be for order placement logic where orderDataNode comes from API response.
                    // Ensure this logic is correctly placed within placeOrder context.
                    if (order.getPrice() == null) {
                        // Fallback to market price
                        try {
                            // Use the exchange parameter and determined market type for this fallback price fetch
                            String marketTypeForPriceFetch = isFuturesOrder ? "LINEAR" : "SPOT";
                            logger.warn("OMS_PLACE_ORDER: Market order for symbol {} (clientOrderId: {}) has no avg price from execution, fetching current price from {} {} market.", 
                                request.getSymbol(), request.getClientOrderId() != null ? request.getClientOrderId() : "N/A", exchange, marketTypeForPriceFetch);
                            order.setPrice(getCurrentPrice(request.getSymbol(), exchange, marketTypeForPriceFetch));
                        } catch (Exception e) {
                            logger.error("OMS_PLACE_ORDER: Error getting current price for market order on symbol {} (clientOrderId: {}): {}", 
                                request.getSymbol(), request.getClientOrderId() != null ? request.getClientOrderId() : "N/A", e.getMessage());
                            // If fallback price fails, we might not be able to proceed or might proceed with a null price
                            // Depending on policy, could throw or let it be handled by later logic that checks for null price
                            // For now, let it proceed, but log error. The order creation will likely fail if price is null and required.
                        }
                         if (order.getPrice() == null) {
                            logger.error("OMS_PLACE_ORDER: Failed to get fallback current price for market order on symbol {} (clientOrderId: {}). Order might be inaccurate or fail if price is strictly needed.", 
                                request.getSymbol(), request.getClientOrderId() != null ? request.getClientOrderId() : "N/A");
                            // Depending on requirements, might throw an exception here if price is absolutely necessary.
                        }
                    }
                    
                    Order savedOrder = orderRepository.save(order);
                    orderCache.put(savedOrder.getOrderId(), savedOrder);
                    activeOrderIds.add(savedOrder.getOrderId());
                    updateOrderHistory(savedOrder);
                    
                    logger.info("Order {} for {} placed successfully on {} (attempt {}). Order ID: {}", 
                        order.getType(), order.getSymbol(), exchange, attempt, savedOrder.getOrderId());
                    placedOrder = savedOrder;
                    break; // Success, exit retry loop
                } else {
                    logger.warn("Empty API response on order placement for {} on {} (attempt {}). Retrying if possible.", request.getSymbol(), exchange, attempt);
                    lastException = new RuntimeException("Empty API response from " + exchange);
                }
            } catch (JsonProcessingException jsonEx) { // This is a fatal parsing error, rethrow
                logger.error("Fatal JSON processing error during order placement for {} on {} (attempt {}): {}", 
                             request.getSymbol(), exchange, attempt, jsonEx.getMessage(), jsonEx);
                throw jsonEx; 
            } catch (Exception e) {
                lastException = e;
                logger.warn("Attempt {}/{} failed to place order for {} on {}: {}. Retrying in {}ms...", 
                            attempt, MAX_ORDER_PLACEMENT_RETRIES, request.getSymbol(), exchange, e.getMessage(), ORDER_PLACEMENT_RETRY_DELAY_MS, e);
                if (attempt < MAX_ORDER_PLACEMENT_RETRIES) {
                    try {
                        Thread.sleep(ORDER_PLACEMENT_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("Order placement retry sleep interrupted for {}. Aborting retries.", request.getSymbol());
                        throw new RuntimeException("Order placement retry interrupted", ie);
                    }
                }
            }
        }

        if (placedOrder == null) {
            String errorMsg = String.format("Failed to place order for %s on %s after %d attempts.", 
                                            request.getSymbol(), exchange, MAX_ORDER_PLACEMENT_RETRIES);
            if (lastException != null) {
                logger.error(errorMsg + " Last error: {}", lastException.getMessage(), lastException);
                // Consider wrapping lastException or creating a specific OrderPlacementFailedException
                throw new RuntimeException(errorMsg + " Last error: " + lastException.getMessage(), lastException);
            } else {
                logger.error(errorMsg + " No specific exception recorded.");
                throw new RuntimeException(errorMsg + " No specific exception recorded.");
            }
        }
        return placedOrder;
    }

    private JsonNode extractOrderData(JsonNode response, String exchange) {
        if (response == null) return null;
        if ("BYBIT".equalsIgnoreCase(exchange)) {
            // Bybit V5 order placement often returns data directly in result object if successful
            // or result.list[0] if it were a query for existing orders.
            // For place order, the structure is usually { retCode: 0, retMsg: "OK", result: { orderId: "...", ... } }
            if (response.hasNonNull("result")) {
                return response.get("result");
            }
        } else { // MEXC
            // MEXC Futures: { code: 0, data: { orderId: "...", ... } }
            // MEXC Spot: { code: 200, data: { orderId: "...", ...} } or directly the orderId in `data` for successful market orders.
            // This needs to be robust based on specific MEXC API endpoint responses for spot vs futures.
            if (response.hasNonNull("data")) {
                return response.get("data");
            } else if (response.hasNonNull("orderId")) { // MEXC spot sometimes returns orderId directly in root for success
                return response; 
            }
        }
        // Fallback if common structures aren't found but response might still contain orderId at root for some cases
        if (response.hasNonNull("orderId") || response.hasNonNull("id")) return response; 
        return null; // Could not identify the order data node
    }
    
    /**
     * Determine if a symbol is for futures trading based on format
     */
    private boolean isFuturesSymbol(String symbol) {
        // Futures symbols typically contain underscores (ETH_USDT, BTC_USDT)
        // Spot symbols typically don't (ETHUSDT, BTCUSDT)
        return symbol != null && symbol.contains("_");
    }
    
    /**
     * Convert futures symbol format to spot symbol format
     */
    private String convertToSpotSymbol(String symbol) {
        // Remove _PERP or _UMCBL suffix for spot trading
        return symbol.replaceAll("_PERP|_UMCBL", "");
    }
    
    /**
     * Cancel an existing order
     * 
     * @param symbol Trading pair
     * @param orderId Order ID to cancel
     * @return The canceled order
     * @throws JsonProcessingException If there's an error processing the JSON response
     */
    @Transactional
    public Order cancelOrder(String symbol, String orderId) throws JsonProcessingException {
        logger.info("Cancelling order ID: {} for symbol: {}", orderId, symbol);
        
        // Call the MEXC API to cancel the order
        ResponseEntity<String> response = mexcFuturesApiService.cancelFuturesOrder(symbol, orderId);
        
        // Parse the response
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            
            // Convert to Order object
            Order canceledOrder = objectMapper.treeToValue(jsonResponse, Order.class);
            
            // Update the order in our tracking
            Order existingOrder = orderCache.get(Long.parseLong(orderId));
            if (existingOrder != null) {
                // Preserve our tracking fields
                canceledOrder.setBotId(existingOrder.getBotId());
                canceledOrder.setStrategyName(existingOrder.getStrategyName());
                canceledOrder.setPriceAtCreation(existingOrder.getPriceAtCreation());
                
                // Update in database and cache
                Order savedOrder = orderRepository.save(canceledOrder);
                orderCache.put(savedOrder.getOrderId(), savedOrder);
                
                // Update order history
                updateOrderHistory(savedOrder);
            }
            
            return canceledOrder;
        } else {
            logger.error("Failed to cancel order: {}", response.getBody());
            throw new RuntimeException("Failed to cancel order: " + response.getBody());
        }
    }
    
    /**
     * Get all open orders
     * 
     * @param symbol Optional trading pair (if null, returns orders for all symbols)
     * @return List of open orders
     * @throws JsonProcessingException If there's an error processing the JSON response
     */
    public List<Order> getOpenOrders(String symbol) throws JsonProcessingException {
        logger.info("Getting open orders for symbol: {}", symbol != null ? symbol : "ALL");
        
        // Call the MEXC API to get open orders
        ResponseEntity<String> response = mexcFuturesApiService.getCurrentOpenOrders(symbol);
        
        // Parse the response
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            // Response is an array of orders
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());
            List<Order> openOrders = new ArrayList<>();
            
            if (jsonResponse.isArray()) {
                for (JsonNode orderNode : jsonResponse) {
                    Order order = objectMapper.treeToValue(orderNode, Order.class);
                    
                    // If we already have this order in our cache, preserve tracking info
                    Order existingOrder = orderCache.get(order.getOrderId());
                    if (existingOrder != null) {
                        order.setBotId(existingOrder.getBotId());
                        order.setStrategyName(existingOrder.getStrategyName());
                        order.setPriceAtCreation(existingOrder.getPriceAtCreation());
                    }
                    
                    // Update database and cache
                    Order savedOrder = orderRepository.save(order);
                    orderCache.put(savedOrder.getOrderId(), savedOrder);
                    openOrders.add(savedOrder);
                }
            }
            
            return openOrders;
        } else {
            logger.error("Failed to get open orders: {}", response.getBody());
            throw new RuntimeException("Failed to get open orders: " + response.getBody());
        }
    }
    
    /**
     * Get account information including balances
     * 
     * @return Account information as a JsonNode
     * @throws JsonProcessingException If there's an error processing the JSON response
     */
    public JsonNode getAccountInfo() throws JsonProcessingException {
        logger.info("Getting account information");
        
        // Call the MEXC API to get account information
        ResponseEntity<String> response = mexcFuturesApiService.getAccountInformation();
        
        // Parse the response
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return objectMapper.readTree(response.getBody());
        } else {
            logger.error("Failed to get account information: {}", response.getBody());
            throw new RuntimeException("Failed to get account information: " + response.getBody());
        }
    }
    
    /**
     * Get all positions (open and closed)
     * 
     * @param status Optional filter by status (OPEN, CLOSED)
     * @param exchange Optional filter by exchange (MEXC, BYBIT)
     * @return List of positions
     */
    public List<Position> getPositions(String status, String exchange) {
        List<Position> positions;
        if (status != null) {
            positions = positionRepository.findByStatus(status);
        } else {
            positions = positionRepository.findAll();
        }
        
        if (exchange != null) {
            return positions.stream()
                .filter(p -> exchange.equalsIgnoreCase(p.getExchange()))
                .collect(Collectors.toList());
        }
        return positions;
    }
    
    /**
     * Get positions for a specific symbol
     * 
     * @param symbol Trading pair
     * @param status Optional filter by status (OPEN, CLOSED)
     * @param exchange Optional filter by exchange (MEXC, BYBIT)
     * @return List of positions for the symbol
     */
    public List<Position> getPositionsBySymbol(String symbol, String status, String exchange) {
        List<Position> positions;
        if (status != null) {
            positions = positionRepository.findBySymbolAndStatus(symbol, status);
        } else {
            positions = positionRepository.findBySymbol(symbol);
        }
        
        if (exchange != null) {
            return positions.stream()
                .filter(p -> exchange.equalsIgnoreCase(p.getExchange()))
                .collect(Collectors.toList());
        }
        return positions;
    }
    
    /**
     * Get a position for a specific symbol
     * 
     * @param symbol Trading pair
     * @return The position for the symbol, or null if not found
     */
    public Position getPositionBySymbol(String symbol) {
        List<Position> positions = positionRepository.findBySymbolAndStatus(symbol, "OPEN");
        if (positions != null && !positions.isEmpty()) {
            return positions.get(0);
        }
        return null;
    }
    
    /**
     * Get order history for a symbol
     * 
     * @param symbol Trading pair
     * @return List of historical orders
     */
    public List<Order> getOrderHistory(String symbol) {
        return orderRepository.findBySymbol(symbol);
    }
    
    /**
     * Scheduled task to update the status of open orders
     * Currently disabled because MEXC API requires specific symbol parameter for openOrders endpoint
     */
    // @Scheduled(fixedDelay = 60000) // Disabled - MEXC doesn't support getting all open orders
    @Transactional
    public void updateOpenOrders() {
        // Temporarily disabled due to MEXC API limitations
        // The /openOrders endpoint requires a specific symbol parameter
        // and doesn't support getting all orders at once
        
        logger.debug("Open orders update is currently disabled due to MEXC API limitations");
        
        /*
        try {
            logger.debug("Updating open orders status");
            // Pass "ALL" explicitly to get all open orders
            List<Order> openOrders = getOpenOrders("ALL");
            
            // Process each open order (already saved to DB in getOpenOrders)
            for (Order order : openOrders) {
                if ("FILLED".equals(order.getStatus()) || "PARTIALLY_FILLED".equals(order.getStatus())) {
                    updatePositionFromOrder(order);
                }
            }
        } catch (Exception e) {
            logger.error("Error updating open orders", e);
        }
        */
    }
    
    /**
     * Scheduled task to update position information
     */
    @Scheduled(fixedDelay = 60000) // Run every minute
    @Transactional
    public void updatePositions() {
        logger.info("OMS: Starting scheduled position update and reconciliation process.");
        List<Position> exchangeReportedPositions = new ArrayList<>();

        // 1. Fetch Bybit Linear (Futures) Positions
        try {
            logger.debug("OMS: Fetching Bybit Linear positions...");
            List<com.tradingbot.backend.service.BybitPositionService.Position> bybitLinearServicePositions = 
                bybitPositionService.getOpenPositions("linear");
            
            if (bybitLinearServicePositions != null) {
                for (com.tradingbot.backend.service.BybitPositionService.Position bybitPosInternal : bybitLinearServicePositions) {
                    Position p = transformBybitServicePosition(bybitPosInternal, "BYBIT", "linear");
                    if (p != null) {
                       exchangeReportedPositions.add(p);
                    }
                }
                logger.info("OMS: Fetched {} Bybit Linear positions.", bybitLinearServicePositions.size());
            } else {
                logger.warn("OMS: Received null from Bybit Linear positions API call.");
            }
        } catch (Exception e) {
            logger.error("OMS: Error fetching Bybit Linear positions: {}", e.getMessage(), e);
        }

        // 2. Fetch Bybit Spot Positions - Temporarily commented out as per user request
        /*
        try {
            logger.debug("OMS: Fetching Bybit Spot positions...");
            List<com.tradingbot.backend.service.BybitPositionService.Position> bybitSpotServicePositions = 
                bybitPositionService.getOpenPositions("spot"); // This was causing errors as spot is not supported by the endpoint

            if (bybitSpotServicePositions != null) {
                for (com.tradingbot.backend.service.BybitPositionService.Position bybitPosInternal : bybitSpotServicePositions) {
                     Position p = transformBybitServicePosition(bybitPosInternal, "BYBIT", "SPOT");
                    if (p != null) {
                       exchangeReportedPositions.add(p);
                    }
                }
                logger.info("OMS: Fetched {} Bybit Spot positions.", bybitSpotServicePositions.size());
            }
        } catch (Exception e) {
            logger.error("OMS: Error fetching Bybit Spot positions: {}", e.getMessage(), e);
        }
        */
        
        // 3. Fetch positions from MEXC (Futures)
        try {
            logger.debug("OMS: Fetching MEXC Futures positions. (Currently Disabled)");
            /*
            ResponseEntity<String> mexcFuturesResponse = mexcFuturesApiService.getOpenFuturesPositions();
            if (mexcFuturesResponse.getStatusCode().is2xxSuccessful() && mexcFuturesResponse.getBody() != null) {
                JsonNode mexcFuturesData = objectMapper.readTree(mexcFuturesResponse.getBody());
                // MEXC /api/v1/private/position/open_positions returns { \"success\": true, \"code\": 0, \"data\": [...] }
                // where data is an array of position objects.
                JsonNode positionsNode = mexcFuturesData.has("data") ? mexcFuturesData.get("data") : null;

                if (positionsNode != null && positionsNode.isArray()) {
                    int count = 0;
                    for (JsonNode mexcPosNode : positionsNode) {
                        Position p = transformMexcFuturesPosition(mexcPosNode);
                        if (p != null) {
                            exchangeReportedPositions.add(p);
                            count++;
                        }
                    }
                    logger.info("OMS: Fetched {} MEXC Futures positions.", count);
                } else {
                     logger.warn("OMS: MEXC Futures positions 'data' node not found or not an array. Response: {}", mexcFuturesResponse.getBody());
                }
            } else {
                logger.error("OMS: Error fetching MEXC Futures positions. Status: {}, Body: {}", mexcFuturesResponse.getStatusCode(), mexcFuturesResponse.getBody());
            }
            */
        } catch (Exception e) {
            logger.error("OMS: Error processing MEXC Futures positions (call was disabled): {}", e.getMessage(), e);
        }

        // 4. Fetch MEXC Spot Positions (TODO: Implement if API becomes available)
        logger.warn("OMS: MEXC Spot position reconciliation is not yet implemented and currently disabled.");

        // 5. Reconciliation
        logger.info("OMS: Starting reconciliation. Found {} positions reported by exchanges.", exchangeReportedPositions.size());
        List<Position> localOpenPositionsInDb = positionRepository.findByStatus("OPEN");
        
        // Create a map of local positions for quick lookup. Key: exchange_marketType_symbol_side
        Map<String, Position> localPositionsMap = new HashMap<>();
        for (Position p : localOpenPositionsInDb) {
            String key = p.getExchange() + "_" + p.getMarketType() + "_" + p.getSymbol() + "_" + p.getSide();
            if (localPositionsMap.containsKey(key)) {
                logger.warn("OMS: Duplicate open position found in local DB for key: {}. Position ID1: {}, Position ID2: {}. Prioritizing first encountered.", 
                            key, localPositionsMap.get(key).getId(), p.getId());
            } else {
                localPositionsMap.put(key, p);
            }
        }

        Set<Long> processedLocalDbIds = new HashSet<>(); // To track which local positions were matched/updated

        for (Position exchangePos : exchangeReportedPositions) {
            String key = exchangePos.getExchange() + "_" + exchangePos.getMarketType() + "_" + exchangePos.getSymbol() + "_" + exchangePos.getSide();
            Position localDbPosition = localPositionsMap.get(key);

            if (localDbPosition != null) { // Existing position found in local DB
                logger.debug("OMS: Reconciling existing local position ID {} for key: {}", localDbPosition.getId(), key);
                localDbPosition.setEntryPrice(exchangePos.getEntryPrice()); 
                localDbPosition.setQuantity(exchangePos.getQuantity());
                if (exchangePos.getUnrealizedPnl() != null) { 
                    localDbPosition.setUnrealizedPnl(exchangePos.getUnrealizedPnl());
                }
                if (exchangePos.getPositionId() != null && !exchangePos.getPositionId().isEmpty()) {
                     localDbPosition.setPositionId(exchangePos.getPositionId()); // Update with exchange's ID
                }
                if (exchangePos.getLeverage() != null) {
                    localDbPosition.setLeverage(exchangePos.getLeverage());
                }
                // TODO: Update other relevant fields from exchangePos if available (e.g., margin, markPrice, liquidationPrice)
                localDbPosition.setUpdateTime(Instant.now());
                localDbPosition.setStatus("OPEN"); // Ensure it's marked OPEN
                
                Position saved = positionRepository.save(localDbPosition);
                positionCache.put(saved.getId(), saved);
                processedLocalDbIds.add(saved.getId());
            } else { // New position reported by exchange, not found locally
                logger.info("OMS: New position found on exchange for key {}, creating locally.", key);
                exchangePos.setStatus("OPEN");
                exchangePos.setOpenTime(LocalDateTime.now()); // Or try to get createdTime from exchange if available
                exchangePos.setUpdateTime(Instant.now());
                // exchangePos already has symbol, side, entryPrice, quantity, exchange, marketType, positionId (if avail), unrealizedPnl (if avail)
                Position newSavedPosition = positionRepository.save(exchangePos);
                positionCache.put(newSavedPosition.getId(), newSavedPosition);
                 logger.info("OMS: Created new local position ID {} for key {}", newSavedPosition.getId(), key);
            }
        }

        // Positions that were in local DB as OPEN but not found in the exchangeReportedPositions list
        for (Position localDbPos : localOpenPositionsInDb) {
            if (!processedLocalDbIds.contains(localDbPos.getId())) {
                logger.info("OMS: Position ID {} ({} {} {} {}) was OPEN in DB but not found on exchange. Marking as CLOSED.", 
                    localDbPos.getId(), localDbPos.getExchange(), localDbPos.getMarketType(), localDbPos.getSymbol(), localDbPos.getSide());
                localDbPos.setStatus("CLOSED");
                localDbPos.setCloseTime(LocalDateTime.now());
                localDbPos.setExitReason("RECONCILIATION_CLOSED_NOT_ON_EXCHANGE");
                localDbPos.setUpdateTime(Instant.now());
                
                if (localDbPos.getUnrealizedPnl() != null) {
                     localDbPos.setRealizedPnl(localDbPos.getUnrealizedPnl());
                } else {
                    localDbPos.setRealizedPnl(BigDecimal.ZERO); 
                }
                Position savedClosedPosition = positionRepository.save(localDbPos);
                positionCache.put(localDbPos.getId(), savedClosedPosition); // Update OMS internal cache

                // Update shared PositionCacheService for the closed position
                PositionCacheService.PositionUpdateData closedUpdateData = transformToPositionUpdateData(savedClosedPosition);
                if (closedUpdateData != null) {
                     // Setting size to zero explicitly signals closure to PositionCacheService's update logic
                    closedUpdateData.setSize(BigDecimal.ZERO);
                    positionCacheService.updatePosition(closedUpdateData.getSymbol(), closedUpdateData);
                     logger.info("OMS: Updated shared PositionCacheService for CLOSED position ID {} (Symbol {}).", savedClosedPosition.getId(), savedClosedPosition.getSymbol());
                }
            }
        }
        
        // 6. Post-Reconciliation Updates (SL/TP, P&L from current price for all truly open positions)
        logger.info("OMS: Starting post-reconciliation updates for all currently OPEN positions.");
        List<Position> finalOpenPositions = positionRepository.findByStatus("OPEN");
        logger.info("OMS: Found {} positions marked OPEN in DB after reconciliation.", finalOpenPositions.size());

        for (Position position : finalOpenPositions) {
            try {
                BigDecimal currentPrice = getCurrentPrice(position.getSymbol(), position.getExchange(), position.getMarketType());
                
                if (currentPrice == null) {
                    logger.warn("OMS_POST_RECON: Could not get current price for position ID {} ({} {} {}). Skipping P&L/SLTP update for this cycle.", 
                        position.getId(), position.getExchange(), position.getMarketType(), position.getSymbol());
                    // Update time anyway
                    position.setUpdateTime(Instant.now());
                    positionRepository.save(position); // Save timestamp update
                    positionCache.put(position.getId(), position);
                    continue; 
                }
                position.setCurrentPrice(currentPrice); 
                
                // Calculate/Update unrealized PnL based on fetched currentPrice
                if (position.getEntryPrice() != null && position.getQuantity() != null) {
                    BigDecimal priceChange;
                    if ("LONG".equalsIgnoreCase(position.getSide())) {
                        priceChange = currentPrice.subtract(position.getEntryPrice());
                    } else { // SHORT
                        priceChange = position.getEntryPrice().subtract(currentPrice);
                    }
                    BigDecimal unrealizedPnl = priceChange.multiply(position.getQuantity());
                    position.setUnrealizedPnl(unrealizedPnl);

                    if (position.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal pnlPercentage = priceChange
                            .divide(position.getEntryPrice(), 8, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                        position.setPnlPercentage(pnlPercentage);
                    } else {
                        position.setPnlPercentage(BigDecimal.ZERO);
                    }
                } else {
                    logger.warn("OMS_POST_RECON: Missing entryPrice or quantity for position ID {}. Cannot calculate P&L.", position.getId());
                }

                // Update highest and lowest prices seen while position is open
                if (position.getHighestPrice() == null || currentPrice.compareTo(position.getHighestPrice()) > 0) {
                    position.setHighestPrice(currentPrice);
                }
                if (position.getLowestPrice() == null || currentPrice.compareTo(position.getLowestPrice()) < 0) {
                    position.setLowestPrice(currentPrice);
                }
                
                // TODO: Add Drawdown calculation based on updated high/low for this period if required.
                                
                position.setUpdateTime(Instant.now());
                
                // Apply secure profit stop loss if conditions are met
                applySecureProfitStopLoss(position);
                
                checkStopLossTakeProfit(position); // This method might change position status to CLOSED
                
                Position savedPosition = positionRepository.save(position);
                positionCache.put(savedPosition.getId(), savedPosition);
                
                // Update shared PositionCacheService
                PositionCacheService.PositionUpdateData updateData = transformToPositionUpdateData(savedPosition);
                if (updateData != null) {
                    positionCacheService.updatePosition(updateData.getSymbol(), updateData);
                    logger.debug("OMS_POST_RECON: Updated shared PositionCacheService for position ID {} (Symbol {}).", savedPosition.getId(), savedPosition.getSymbol());
                }
                
            } catch (Exception e) {
                logger.error("OMS_POST_RECON: Error in post-reconciliation update for position ID {}: {}", position.getId(), e.getMessage(), e);
            }
        }
        
        // Broadcast all positions that are currently considered open by the system *after* all updates
        List<Position> finalOpenPositionsForBroadcast = positionRepository.findByStatus("OPEN");
        positionUpdateService.broadcastPositionUpdate(finalOpenPositionsForBroadcast);
        logger.info("OMS: Finished scheduled position update and reconciliation. {} positions broadcasted as OPEN.", finalOpenPositionsForBroadcast.size());
    }

    private void applySecureProfitStopLoss(Position position) {
        if (position == null || !"OPEN".equalsIgnoreCase(position.getStatus()) || position.getEntryPrice() == null || position.getCurrentPrice() == null) {
            return; // Not an open position or essential data missing
        }

        PositionCacheService.PositionUpdateData cachedPosition = positionCacheService.getPosition(position.getSymbol());
        if (cachedPosition != null && cachedPosition.isSecureProfitSlApplied()) {
            return; // Secure profit SL already applied for this trade instance
        }

        BigDecimal profitPercentage;
        if (position.getEntryPrice().compareTo(BigDecimal.ZERO) == 0) {
            return; // Avoid division by zero if entry price is zero
        }

        if ("LONG".equalsIgnoreCase(position.getSide())) {
            profitPercentage = position.getCurrentPrice().subtract(position.getEntryPrice())
                                .divide(position.getEntryPrice(), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
        } else if ("SHORT".equalsIgnoreCase(position.getSide())) {
            profitPercentage = position.getEntryPrice().subtract(position.getCurrentPrice())
                                .divide(position.getEntryPrice(), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100));
        } else {
            return; // Unknown side
        }

        // Check if profit is >= 30%
        if (profitPercentage.compareTo(new BigDecimal("30")) >= 0) {
            BigDecimal newStopLossPrice;
            // Set SL to 0.1% profit from entry price
            BigDecimal profitMargin = new BigDecimal("0.001"); // 0.1%

            if ("LONG".equalsIgnoreCase(position.getSide())) {
                newStopLossPrice = position.getEntryPrice().multiply(BigDecimal.ONE.add(profitMargin));
            } else { // SHORT
                newStopLossPrice = position.getEntryPrice().multiply(BigDecimal.ONE.subtract(profitMargin));
            }
            
            // Round to appropriate precision for the symbol/exchange (assuming 8 decimal places for now)
            // This might need to be dynamic based on symbol specs
            String categoryForTickSize = "linear"; // Default to linear for futures
            if ("spot".equalsIgnoreCase(position.getMarketType())) {
                categoryForTickSize = "spot";
            }
            String tickSize = instrumentInfoService.getTickSize(position.getSymbol(), categoryForTickSize);
            int priceScale = OrderUtil.getPriceScaleFromTickSize(tickSize);

            newStopLossPrice = newStopLossPrice.setScale(priceScale, RoundingMode.HALF_UP);

            logger.info("OMS_SECURE_PROFIT_SL: Position ID {} ({} {} {}) reached >= 30% profit ({}%). Applying secure SL at {}.",
                        position.getId(), position.getExchange(), position.getSymbol(), position.getSide(),
                        profitPercentage.setScale(2, RoundingMode.HALF_UP), newStopLossPrice);

            try {
                if ("BYBIT".equalsIgnoreCase(position.getExchange())) {
                    String category = "linear"; // Assuming linear futures. Adjust if spot or inverse is possible here.
                    if (position.getMarketType() != null && "spot".equalsIgnoreCase(position.getMarketType())) {
                        category = "spot";
                    }
                    
                    // For Bybit, positionIdx 0 usually means one-way mode.
                    // If using hedge mode, this might need adjustment (1 for buy-side, 2 for sell-side).
                    // We'll assume one-way mode (positionIdx=0) as it's common.
                    Integer positionIdx = 0; // Default to one-way mode
                    if (position.getPositionIdx() != null) { // Prioritize positionIdx from the Position entity
                        positionIdx = position.getPositionIdx();
                    } else if (cachedPosition != null && cachedPosition.getSide() != null) { // Fallback to inference from cache if not on entity
                        logger.warn("OMS_SECURE_PROFIT_SL: position.getPositionIdx() is null for position ID {}. Falling back to inferring from cachedPosition.Side for Bybit positionIdx.", position.getId());
                        if ("Buy".equalsIgnoreCase(cachedPosition.getSide())) positionIdx = 1; // Hedge mode Buy side
                        else if ("Sell".equalsIgnoreCase(cachedPosition.getSide())) positionIdx = 2; // Hedge mode Sell side
                    } else {
                        logger.warn("OMS_SECURE_PROFIT_SL: position.getPositionIdx() is null and cachedPosition info is insufficient for position ID {}. Defaulting Bybit positionIdx to 0 (one-way).", position.getId());
                    }

                    ResponseEntity<String> response = bybitApiClientService.setTradingStop(
                        category,
                        position.getSymbol(),
                        null, // takeProfit - not changing TP here
                        newStopLossPrice.toPlainString(), // stopLoss - our new secure SL
                        null, // trailingStop - not using Bybit's native trailing stop here
                        null, // tpTriggerBy
                        "MarkPrice", // slTriggerBy - recommend MarkPrice for futures to avoid liquidation on wicks
                        null, // activePrice
                        null, // tpSize
                        null, // slSize
                        positionIdx 
                    );

                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        JsonNode responseNode = objectMapper.readTree(response.getBody());
                        if (responseNode.has("retCode") && responseNode.get("retCode").asInt() == 0) {
                            logger.info("OMS_SECURE_PROFIT_SL: Successfully set secure SL for position ID {} on Bybit. New SL: {}", position.getId(), newStopLossPrice);
                            position.setStopLossPrice(newStopLossPrice); // Update local Position entity
                            if (cachedPosition != null) {
                                positionCacheService.updateStrategyPositionInfo(position.getSymbol(), newStopLossPrice, cachedPosition.isFirstProfitTargetTaken(), true);
                            }
                        } else {
                            logger.error("OMS_SECURE_PROFIT_SL: Failed to set secure SL for position ID {} on Bybit. Response: {}", position.getId(), response.getBody());
                        }
                    } else {
                        logger.error("OMS_SECURE_PROFIT_SL: Error response from Bybit when setting secure SL for position ID {}. Status: {}, Body: {}",
                                     position.getId(), response.getStatusCode(), response.getBody());
                    }

                } else if ("MEXC".equalsIgnoreCase(position.getExchange())) {
                    // TODO: Implement secure SL for MEXC if/when their API supports modifying SL of an open position easily.
                    // MEXC might require cancelling the old SL order and placing a new one.
                    logger.warn("OMS_SECURE_PROFIT_SL: Secure SL for MEXC not yet implemented. Position ID: {}", position.getId());
                } else {
                    logger.warn("OMS_SECURE_PROFIT_SL: Secure SL not implemented for exchange: {}. Position ID: {}", position.getExchange(), position.getId());
                }
            } catch (Exception e) {
                logger.error("OMS_SECURE_PROFIT_SL: Exception while applying secure SL for position ID {}: {}", position.getId(), e.getMessage(), e);
            }
        }
    }

    // Helper method to transform BybitPositionService.Position to our internal Position model
    private Position transformBybitServicePosition(com.tradingbot.backend.service.BybitPositionService.Position servicePos, String exchange, String marketType) {
        if (servicePos == null) return null;
        Position p = new Position(); // Our main model Position
        p.setSymbol(servicePos.getSymbol());
        p.setExchange(exchange);
        p.setMarketType(marketType);
        
        String side = "Buy".equalsIgnoreCase(servicePos.getSide()) ? "LONG" : ("Sell".equalsIgnoreCase(servicePos.getSide()) ? "SHORT" : null);
        if (side == null) {
            logger.warn("OMS_TRANSFORM_BYBIT: Unknown side from Bybit position: {} for symbol {}", servicePos.getSide(), servicePos.getSymbol());
            return null; 
        }
        p.setSide(side);
        
        p.setEntryPrice(servicePos.getEntryPrice()); // Already BigDecimal
        p.setQuantity(servicePos.getSize());         // Already BigDecimal
        if (servicePos.getUnrealizedPnl() != null) {
             p.setUnrealizedPnl(servicePos.getUnrealizedPnl()); // Already BigDecimal
        }
        // Bybit V5 position list does not seem to have a unique 'positionId' field directly in the list items.
        // 'positionIdx' (0 for one-way, 1 for Buy hedge, 2 for Sell hedge) is not unique for the position itself.
        // We might need to rely on symbol+side for uniqueness or investigate if another ID is available.
        // For now, creating a composite key or using symbol+side implicitly.
        // The 'positionId' field in our model could store an exchange-specific unique ID if one becomes available.
        // p.setPositionId(servicePos.getPositionIdx() != null ? servicePos.getPositionIdx().toString() : null); // Example
        
        if (servicePos.getPositionIdx() != null) { // Added: Set positionIdx from the Bybit service DTO
            p.setPositionIdx(servicePos.getPositionIdx());
        }

        if (servicePos.getLeverage() != null) {
            p.setLeverage(servicePos.getLeverage());
        }
        // TODO: Populate other fields like margin, markPrice if available from servicePos or related calls
        if (servicePos.getMarkPrice() != null) { // Assuming BybitPositionService.Position now has getMarkPrice()
            try {
                p.setMarkPrice(new BigDecimal(servicePos.getMarkPrice()));
            } catch (NumberFormatException e) {
                logger.warn("OMS_TRANSFORM_BYBIT: Could not parse markPrice '{}' for symbol {}. Setting to null.", servicePos.getMarkPrice(), servicePos.getSymbol(), e);
                p.setMarkPrice(null);
            }
        }
        if (servicePos.getLiqPrice() != null && !servicePos.getLiqPrice().isEmpty()) {
            try {
                p.setLiquidationPrice(new BigDecimal(servicePos.getLiqPrice()));
            } catch (NumberFormatException e) {
                logger.warn("OMS_TRANSFORM_BYBIT: Could not parse liqPrice '{}' for symbol {}. Setting to null.", servicePos.getLiqPrice(), servicePos.getSymbol(), e);
                p.setLiquidationPrice(null);
            }
        }
        if (servicePos.getPositionValue() != null && !servicePos.getPositionValue().isEmpty()) {
            try {
                p.setPositionValue(new BigDecimal(servicePos.getPositionValue()));
            } catch (NumberFormatException e) {
                logger.warn("OMS_TRANSFORM_BYBIT: Could not parse positionValue '{}' for symbol {}. Setting to null.", servicePos.getPositionValue(), servicePos.getSymbol(), e);
                p.setPositionValue(null);
            }
        }

        p.setUpdateTime(Instant.now());
        p.setStatus("FROM_EXCHANGE"); // Temporary status to indicate it's raw from exchange before full reconciliation
        return p;
    }

    // Helper method to transform MEXC Futures JsonNode to our internal Position model
    private Position transformMexcFuturesPosition(JsonNode mexcPosNode) {
        if (mexcPosNode == null || !mexcPosNode.isObject()) return null;
        Position p = new Position(); // Our main model Position
        try {
            p.setSymbol(mexcPosNode.get("symbol").asText()); // e.g., "BTC_USDT"
            p.setExchange("MEXC");
            p.setMarketType("LINEAR"); // Or "FUTURES" - ensure consistency

            // MEXC positionSide: 1 for Long, 2 for Short
            int posSideCode = mexcPosNode.get("positionSide").asInt();
            String side = posSideCode == 1 ? "LONG" : (posSideCode == 2 ? "SHORT" : null);
            if (side == null) {
                logger.warn("OMS_TRANSFORM_MEXC: Unknown positionSide from MEXC Futures: {} for symbol {}", posSideCode, p.getSymbol());
                return null;
            }
            p.setSide(side);

            p.setEntryPrice(new BigDecimal(mexcPosNode.get("avgEntryPrice").asText()));
            p.setQuantity(new BigDecimal(mexcPosNode.get("holdVol").asText())); 
            if (mexcPosNode.hasNonNull("unrealisedPnl")) { // MEXC uses "unrealisedPnl"
                p.setUnrealizedPnl(new BigDecimal(mexcPosNode.get("unrealisedPnl").asText()));
            }
            if (mexcPosNode.hasNonNull("positionId")) { // MEXC provides "positionId"
                 p.setPositionId(String.valueOf(mexcPosNode.get("positionId").asLong()));
            }
            if (mexcPosNode.hasNonNull("leverage")) {
                p.setLeverage(new BigDecimal(mexcPosNode.get("leverage").asText()));
            }
            // TODO: Populate other fields like margin, markPrice if available
        } catch (Exception e) {
            logger.error("OMS_TRANSFORM_MEXC: Error parsing MEXC Futures position node {}: {}", mexcPosNode.toString(), e.getMessage(), e);
            return null;
        }
        p.setUpdateTime(Instant.now());
        p.setStatus("FROM_EXCHANGE"); // Temporary status
        return p;
    }
    
    /**
     * Get current price for a symbol, considering the exchange and market type.
     * @param symbol The trading symbol (e.g., BTCUSDT)
     * @param exchange The exchange name (e.g., "BYBIT", "MEXC")
     * @param marketType The market type (e.g., "SPOT", "LINEAR" for linear futures)
     * @return The current price as BigDecimal, or null if not found or error.
     */
    private BigDecimal getCurrentPrice(String symbol, String exchange, String marketType) {
        ResponseEntity<String> responseEntity = null;
        String priceField = "price"; // Default for MEXC futures and assumed for MEXC spot for now
        String categoryForBybit = null;

        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                categoryForBybit = "spot".equalsIgnoreCase(marketType) ? "spot" : "linear";
                responseEntity = bybitApiClientService.getTickers(symbol, categoryForBybit);
                // Bybit ticker response: result.list[0].lastPrice
                priceField = "lastPrice"; 
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                logger.warn("MEXC price fetching is temporarily disabled for symbol {}.", symbol);
                return null;
                /*
                if ("futures".equalsIgnoreCase(marketType) || "linear".equalsIgnoreCase(marketType)) { // Assuming "linear" implies futures for MEXC too
                    responseEntity = mexcFuturesApiService.getLatestPrice(symbol); // Already returns parsed or specific price endpoint
                } else { // Spot
                    // Assuming mexcApiClientService.getLatestPrice or a similar spot ticker endpoint exists
                    // This part might need adjustment based on actual MexcApiClientService capabilities for spot price
                    responseEntity = mexcApiClientService.getLatestPrice(symbol, "MEXC"); 
                }
                */
            } else {
                logger.warn("Unsupported exchange for getCurrentPrice: {}", exchange);
                return null;
            }

            if (responseEntity != null && responseEntity.getStatusCode().is2xxSuccessful() && responseEntity.getBody() != null) {
                JsonNode jsonResponse = objectMapper.readTree(responseEntity.getBody());
                
                if ("BYBIT".equalsIgnoreCase(exchange)) {
                    if (jsonResponse.has("result") && 
                        jsonResponse.get("result").has("list") && 
                        jsonResponse.get("result").get("list").isArray() && 
                        jsonResponse.get("result").get("list").size() > 0) {
                        JsonNode tickerNode = jsonResponse.get("result").get("list").get(0);
                        if (tickerNode.has(priceField)) {
                            return new BigDecimal(tickerNode.get(priceField).asText());
                        }
                    }
                } else { // MEXC
                    if (jsonResponse.has(priceField)) { // For futures direct price
                        return new BigDecimal(jsonResponse.get(priceField).asText());
                    } else if (jsonResponse.has("data") && jsonResponse.get("data").isArray() && jsonResponse.get("data").size() > 0) {
                        // Handling for MEXC spot if getLatestPrice returns an array like some ticker endpoints
                        JsonNode tickerData = jsonResponse.get("data").get(0);
                        if (tickerData.has(priceField)) {
                             return new BigDecimal(tickerData.get(priceField).asText());
                        }
                    } else if (jsonResponse.has("data") && jsonResponse.get("data").has(priceField)) {
                        // Handling for MEXC spot if getLatestPrice returns a data object with price
                        return new BigDecimal(jsonResponse.get("data").get(priceField).asText());
                    }
                }
                logger.warn("Price field '{}' not found in response for {} on {} {}. Response: {}", priceField, symbol, exchange, marketType, jsonResponse.toString());
            } else {
                logger.warn("Failed to get current price for {} on {} {}. Status: {}, Body: {}", 
                    symbol, exchange, marketType, responseEntity != null ? responseEntity.getStatusCode() : "N/A", responseEntity != null ? responseEntity.getBody() : "N/A");
            }
        } catch (JsonProcessingException e) {
            logger.error("Error parsing price response for {} on {} {}: {}", symbol, exchange, marketType, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error getting price for {} on {} {}: {}", symbol, exchange, marketType, e.getMessage(), e);
        }
        return null; // Return null if price couldn't be fetched or parsed
    }
    
    /**
     * Check if stop loss or take profit levels have been hit
     */
    private void checkStopLossTakeProfit(Position position) {
        BigDecimal currentPrice = position.getCurrentPrice();
        
        // Skip if price not available
        if (currentPrice == null) return;
        
        // Check stop loss for long positions
        if ("LONG".equals(position.getSide()) 
                && position.getStopLossPrice() != null 
                && currentPrice.compareTo(position.getStopLossPrice()) <= 0) {
            try {
                logger.info("Stop loss triggered for long position {} at price {}", 
                    position.getId(), currentPrice);
                closePosition(position, "STOP_LOSS");
            } catch (Exception e) {
                logger.error("Error closing position at stop loss: {}", e.getMessage());
            }
        }
        
        // Check take profit for long positions
        else if ("LONG".equals(position.getSide()) 
                && position.getTakeProfitPrice() != null 
                && currentPrice.compareTo(position.getTakeProfitPrice()) >= 0) {
            try {
                logger.info("Take profit triggered for long position {} at price {}", 
                    position.getId(), currentPrice);
                closePosition(position, "TAKE_PROFIT");
            } catch (Exception e) {
                logger.error("Error closing position at take profit: {}", e.getMessage());
            }
        }
        
        // Check stop loss for short positions
        else if ("SHORT".equals(position.getSide()) 
                && position.getStopLossPrice() != null 
                && currentPrice.compareTo(position.getStopLossPrice()) >= 0) {
            try {
                logger.info("Stop loss triggered for short position {} at price {}", 
                    position.getId(), currentPrice);
                closePosition(position, "STOP_LOSS");
            } catch (Exception e) {
                logger.error("Error closing position at stop loss: {}", e.getMessage());
            }
        }
        
        // Check take profit for short positions
        else if ("SHORT".equals(position.getSide()) 
                && position.getTakeProfitPrice() != null 
                && currentPrice.compareTo(position.getTakeProfitPrice()) <= 0) {
            try {
                logger.info("Take profit triggered for short position {} at price {}", 
                    position.getId(), currentPrice);
                closePosition(position, "TAKE_PROFIT");
            } catch (Exception e) {
                logger.error("Error closing position at take profit: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Close a position by placing an order in the opposite direction
     */
    @Transactional
    public void closePosition(Position position, String reason) throws JsonProcessingException {
        logger.info("Closing position {} for reason: {}", position.getId(), reason);
        
        // Create an order to close the position
        OrderRequest request = new OrderRequest();
        request.setSymbol(position.getSymbol());
        request.setQuantity(position.getQuantity());
        request.setType("MARKET");
        
        // Set order side to the opposite of the position
        if ("LONG".equals(position.getSide())) {
            request.setSide("SELL");
        } else {
            request.setSide("BUY");
        }
        
        // Place the order
        Order closeOrder = placeOrder(request, "MEXC");
        
        // Update position status
        position.setStatus("CLOSED");
        position.setCloseTime(LocalDateTime.now());
        position.getExitOrderIds().add(String.valueOf(closeOrder.getOrderId()));
        
        // Calculate realized PnL
        BigDecimal entryValue = position.getEntryPrice().multiply(position.getQuantity());
        BigDecimal exitValue = closeOrder.getPrice().multiply(closeOrder.getExecutedQty());
        
        if ("LONG".equals(position.getSide())) {
            position.setRealizedPnl(exitValue.subtract(entryValue));
            
            // Calculate gross profit
            position.setGrossProfit(exitValue.subtract(entryValue));
        } else {
            position.setRealizedPnl(entryValue.subtract(exitValue));
            
            // Calculate gross profit
            position.setGrossProfit(entryValue.subtract(exitValue));
        }
        
        // Calculate fees if available
        if (closeOrder.getCummulativeQuoteQty() != null) {
            BigDecimal closeFees = closeOrder.getCummulativeQuoteQty().multiply(BigDecimal.valueOf(0.001)); // Assuming 0.1% fee
            
            if (position.getFees() == null) {
                position.setFees(closeFees);
            } else {
                position.setFees(position.getFees().add(closeFees));
            }
        }
        
        // Calculate net profit (gross profit minus fees)
        if (position.getGrossProfit() != null && position.getFees() != null) {
            position.setNetProfit(position.getGrossProfit().subtract(position.getFees()));
        } else {
            position.setNetProfit(position.getGrossProfit());
        }
        
        // Calculate maximum drawdown
        if (position.getHighestPrice() != null && position.getLowestPrice() != null) {
            BigDecimal maxPriceChange = position.getHighestPrice().subtract(position.getLowestPrice());
            if (position.getHighestPrice().compareTo(BigDecimal.ZERO) > 0) {
                position.setMaxDrawdown(maxPriceChange.divide(position.getHighestPrice(), 8, RoundingMode.HALF_UP));
            }
        }
        
        // Calculate performance metrics
        calculatePositionPerformanceMetrics(position, closeOrder.getPrice());
        
        // Save position
        Position savedPosition = positionRepository.save(position);
        positionCache.put(savedPosition.getId(), savedPosition);
    }
    
    /**
     * Calculate performance metrics for a position
     */
    private void calculatePositionPerformanceMetrics(Position position, BigDecimal closePrice) {
        // Calculate holding period (in days)
        if (position.getOpenTime() != null && position.getCloseTime() != null) {
            // Convert LocalDateTime to Instant for time calculations
            long holdingPeriodMillis = position.getCloseTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() 
                - position.getOpenTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            double holdingPeriodDays = holdingPeriodMillis / (1000.0 * 60 * 60 * 24);
            
            // Calculate annualized return
            if (holdingPeriodDays > 0 && position.getRealizedPnl() != null && position.getCostBasis() != null 
                    && position.getCostBasis().compareTo(BigDecimal.ZERO) > 0) {
                
                double totalReturn = position.getRealizedPnl().divide(position.getCostBasis(), 8, RoundingMode.HALF_UP).doubleValue();
                double annualizedReturn = Math.pow(1 + totalReturn, 365 / holdingPeriodDays) - 1;
                
                // Calculate Sharpe ratio (simplified)
                if (position.getVolatility() != null && position.getVolatility().compareTo(BigDecimal.ZERO) > 0) {
                    double volatility = position.getVolatility().doubleValue();
                    double riskFreeRate = 0.02; // Assuming 2% annual risk-free rate
                    double sharpeRatio = (annualizedReturn - riskFreeRate) / volatility;
                    
                    position.setSharpeRatio(BigDecimal.valueOf(sharpeRatio));
                }
            }
        }
        
        // Calculate risk-reward ratio if stop loss was set
        if (position.getStopLossPrice() != null && position.getEntryPrice() != null) {
            BigDecimal risk = position.getEntryPrice().subtract(position.getStopLossPrice()).abs();
            
            if (risk.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal reward = closePrice.subtract(position.getEntryPrice()).abs();
                position.setRiskRewardRatio(reward.divide(risk, 4, RoundingMode.HALF_UP));
            }
        }
    }
    
    /**
     * Update position based on an order
     */
    @Transactional
    private void updatePositionFromOrder(Order order) {
        // Only process filled or partially filled orders
        if (!"FILLED".equals(order.getStatus()) && !"PARTIALLY_FILLED".equals(order.getStatus())) {
            return;
        }
        
        String symbol = order.getSymbol();
        String side = order.getSide();
        BigDecimal price = order.getPrice();
        BigDecimal quantity = order.getExecutedQty();
        // Ensure exchange and marketType are derived from the order, with sensible defaults if null
        String orderExchange = order.getExchange() != null ? order.getExchange().toUpperCase() : "MEXC"; 
        String orderMarketType = order.getMarketType() != null ? order.getMarketType().toUpperCase() : (symbol.contains("_") ? "LINEAR" : "SPOT");
        
        // For market orders, use cummulativeQuoteQty and executedQty to determine price
        if (price == null || price.compareTo(BigDecimal.ZERO) == 0) {
            if (order.getCummulativeQuoteQty() != null && order.getExecutedQty() != null 
                    && order.getExecutedQty().compareTo(BigDecimal.ZERO) > 0) {
                price = order.getCummulativeQuoteQty().divide(order.getExecutedQty(), 8, RoundingMode.HALF_UP);
            } else {
                // Fallback to current market price if average price cannot be determined from order details
                logger.warn("OMS_UPDATE_POS_FROM_ORDER: Market order {} for {} ({} {}) has no avg price from execution details, fetching current price.", 
                    order.getOrderId(), symbol, orderExchange, orderMarketType);
                try {
                    price = getCurrentPrice(symbol, orderExchange, orderMarketType);
                } catch (Exception e) {
                    logger.error("OMS_UPDATE_POS_FROM_ORDER: Error getting current price for market order {}: {}", order.getOrderId(), e.getMessage(), e);
                    // Cannot proceed without a price for the order that affected the position
                    return; 
                }
                 if (price == null) {
                    logger.error("OMS_UPDATE_POS_FROM_ORDER: Failed to get fallback current price for market order {}. Cannot accurately update position based on this order.", order.getOrderId());
                    // Cannot proceed without a price for the order that affected the position
                    return; 
                }
            }
        }
        
        // Get open positions for this symbol, exchange, and market type
        List<Position> openPositions = positionRepository.findBySymbolAndStatusAndExchangeAndMarketType(symbol, "OPEN", orderExchange, orderMarketType);
        if (openPositions == null) openPositions = new ArrayList<>(); // Ensure list is not null
        
        // Buy order: either open a new long position or close/reduce a short position
        if ("BUY".equals(side)) {
            Optional<Position> shortPositionOpt = openPositions.stream()
                .filter(p -> "SHORT".equals(p.getSide()))
                .findFirst();
            
            if (shortPositionOpt.isPresent()) {
                Position position = shortPositionOpt.get(); // This is the SHORT position we are buying to close/reduce
                BigDecimal remainingQty = position.getQuantity().subtract(quantity);
                
                if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    // Close the position completely
                    try {
                        position.getExitOrderIds().add(String.valueOf(order.getOrderId()));
                        closePosition(position, "ORDER_FILLED"); // This method should handle DB and OMS cache update
                        
                        // Update shared PositionCacheService for the closed position
                        // Re-fetch to ensure we have the final state (e.g., status CLOSED, realized PnL)
                        Position closedPositionState = positionRepository.findById(position.getId()).orElse(null);
                        if (closedPositionState != null) {
                            PositionCacheService.PositionUpdateData updateDataForClosed = transformToPositionUpdateData(closedPositionState);
                            if (updateDataForClosed != null) {
                                // Ensure size is zero for closed positions if not already set by transform
                                updateDataForClosed.setSize(BigDecimal.ZERO); 
                                positionCacheService.updatePosition(updateDataForClosed.getSymbol(), updateDataForClosed);
                                logger.info("OMS_UPDATE_POS_FROM_ORDER: Updated shared PositionCacheService for SHORT position (ID {}) closed by BUY order {}. Symbol: {}", 
                                    closedPositionState.getId(), order.getOrderId(), closedPositionState.getSymbol());
                            }
                        } else {
                             logger.warn("OMS_UPDATE_POS_FROM_ORDER: Could not re-fetch closed short position ID {} after closing. Shared cache might be stale.", position.getId());
                        }
                    } catch (Exception e) {
                        logger.error("OMS_UPDATE_POS_FROM_ORDER: Error closing short position {}: {}", position.getId(), e.getMessage(), e);
                    }
                } else {
                    // Reduce the position
                    position.setQuantity(remainingQty);
                    position.getExitOrderIds().add(String.valueOf(order.getOrderId()));
                    
                    try {
                        BigDecimal currentPriceVal = getCurrentPrice(symbol, position.getExchange(), position.getMarketType());
                        if (currentPriceVal != null) {
                            position.setCurrentPrice(currentPriceVal);
                            BigDecimal priceChange = position.getEntryPrice().subtract(currentPriceVal); // For short, entry - current
                            position.setUnrealizedPnl(priceChange.multiply(position.getQuantity()));
                        }
                        position.setUpdateTime(Instant.now());
                        Position savedReducedPosition = positionRepository.save(position);
                        this.positionCache.put(savedReducedPosition.getId(), savedReducedPosition); // Update OMS internal cache

                        // Update shared PositionCacheService
                        PositionCacheService.PositionUpdateData updateDataForReduced = transformToPositionUpdateData(savedReducedPosition);
                        if (updateDataForReduced != null) {
                            positionCacheService.updatePosition(updateDataForReduced.getSymbol(), updateDataForReduced);
                            logger.info("OMS_UPDATE_POS_FROM_ORDER: Updated shared PositionCacheService for reduced SHORT position ID {} (Symbol {}).", savedReducedPosition.getId(), savedReducedPosition.getSymbol());
                        }
                    } catch (Exception e) {
                        logger.error("OMS_UPDATE_POS_FROM_ORDER: Error updating reduced short position {}: {}", position.getId(), e.getMessage(), e);
                    }
                }
            } else {
                // Open a new long position
                Position newPosition = createNewPositionObject(order, "LONG", price, quantity, orderExchange, orderMarketType);
                Position savedPosition = positionRepository.save(newPosition);
                this.positionCache.put(savedPosition.getId(), savedPosition); // Update OMS internal cache
                
                // Update shared PositionCacheService
                PositionCacheService.PositionUpdateData updateDataForNewLong = transformToPositionUpdateData(savedPosition);
                if (updateDataForNewLong != null) {
                    positionCacheService.updatePosition(updateDataForNewLong.getSymbol(), updateDataForNewLong);
                    logger.info("OMS_UPDATE_POS_FROM_ORDER: Updated shared PositionCacheService for new LONG position ID {} (Symbol {}).", savedPosition.getId(), savedPosition.getSymbol());
                }
                logger.info("Opened new LONG position {} for symbol {} at price {}", savedPosition.getId(), symbol, price);
            }
        } else if ("SELL".equals(side)) {
            Optional<Position> longPositionOpt = openPositions.stream()
                .filter(p -> "LONG".equals(p.getSide()))
                .findFirst();
            
            if (longPositionOpt.isPresent()) {
                Position position = longPositionOpt.get(); // This is the LONG position we are selling to close/reduce
                BigDecimal remainingQty = position.getQuantity().subtract(quantity);
                
                if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    // Close the position completely
                    try {
                        position.getExitOrderIds().add(String.valueOf(order.getOrderId()));
                        closePosition(position, "ORDER_FILLED"); // This method should handle DB and OMS cache update

                        // Update shared PositionCacheService for the closed position
                        Position closedPositionState = positionRepository.findById(position.getId()).orElse(null);
                        if (closedPositionState != null) {
                            PositionCacheService.PositionUpdateData updateDataForClosed = transformToPositionUpdateData(closedPositionState);
                            if (updateDataForClosed != null) {
                                updateDataForClosed.setSize(BigDecimal.ZERO);
                                positionCacheService.updatePosition(updateDataForClosed.getSymbol(), updateDataForClosed);
                                logger.info("OMS_UPDATE_POS_FROM_ORDER: Updated shared PositionCacheService for LONG position (ID {}) closed by SELL order {}. Symbol: {}", 
                                    closedPositionState.getId(), order.getOrderId(), closedPositionState.getSymbol());
                            }
                        } else {
                            logger.warn("OMS_UPDATE_POS_FROM_ORDER: Could not re-fetch closed long position ID {} after closing. Shared cache might be stale.", position.getId());
                        }
                    } catch (Exception e) {
                        logger.error("OMS_UPDATE_POS_FROM_ORDER: Error closing long position {}: {}", position.getId(), e.getMessage(), e);
                    }
                } else {
                    // Reduce the position
                    position.setQuantity(remainingQty);
                    position.getExitOrderIds().add(String.valueOf(order.getOrderId()));

                    try {
                        BigDecimal currentPriceVal = getCurrentPrice(symbol, position.getExchange(), position.getMarketType());
                        if (currentPriceVal != null) {
                            position.setCurrentPrice(currentPriceVal);
                            BigDecimal priceChange = currentPriceVal.subtract(position.getEntryPrice()); // For long, current - entry
                            position.setUnrealizedPnl(priceChange.multiply(position.getQuantity()));
                        }
                        position.setUpdateTime(Instant.now());
                        Position savedReducedPosition = positionRepository.save(position);
                        this.positionCache.put(savedReducedPosition.getId(), savedReducedPosition); // Update OMS internal cache

                        // Update shared PositionCacheService
                        PositionCacheService.PositionUpdateData updateDataForReduced = transformToPositionUpdateData(savedReducedPosition);
                        if (updateDataForReduced != null) {
                            positionCacheService.updatePosition(updateDataForReduced.getSymbol(), updateDataForReduced);
                            logger.info("OMS_UPDATE_POS_FROM_ORDER: Updated shared PositionCacheService for reduced LONG position ID {} (Symbol {}).", savedReducedPosition.getId(), savedReducedPosition.getSymbol());
                        }
                    } catch (Exception e) {
                        logger.error("OMS_UPDATE_POS_FROM_ORDER: Error updating reduced long position {}: {}", position.getId(), e.getMessage(), e);
                    }
                }
            } else {
                // Open a new short position
                Position newPosition = createNewPositionObject(order, "SHORT", price, quantity, orderExchange, orderMarketType);
                Position savedPosition = positionRepository.save(newPosition);
                this.positionCache.put(savedPosition.getId(), savedPosition); // Update OMS internal cache

                // Update shared PositionCacheService
                PositionCacheService.PositionUpdateData updateDataForNewShort = transformToPositionUpdateData(savedPosition);
                if (updateDataForNewShort != null) {
                    positionCacheService.updatePosition(updateDataForNewShort.getSymbol(), updateDataForNewShort);
                    logger.info("OMS_UPDATE_POS_FROM_ORDER: Updated shared PositionCacheService for new SHORT position ID {} (Symbol {}).", savedPosition.getId(), savedPosition.getSymbol());
                }
                logger.info("Opened new SHORT position {} for symbol {} at price {}", savedPosition.getId(), symbol, price);
            }
        }
    }

    // Helper method to create a new Position object, reducing boilerplate
    private Position createNewPositionObject(Order order, String side, BigDecimal entryPrice, BigDecimal quantity, String exchange, String marketType) {
        Position newPosition = new Position();
        newPosition.setId(generatePositionId()); 
        newPosition.setSymbol(order.getSymbol());
        newPosition.setExchange(exchange);
        newPosition.setMarketType(marketType);
        newPosition.setSide(side);
        newPosition.setEntryPrice(entryPrice);
        newPosition.setQuantity(quantity);
        newPosition.setCurrentPrice(entryPrice); // Initial current price is entry price
        newPosition.setUnrealizedPnl(BigDecimal.ZERO);
        newPosition.setRealizedPnl(BigDecimal.ZERO);
        newPosition.setPnlPercentage(BigDecimal.ZERO);
        newPosition.setOpenTime(LocalDateTime.now());
        newPosition.setUpdateTime(Instant.now());
        newPosition.setStatus("OPEN");
        newPosition.setBotId(order.getBotId());
        newPosition.setStrategyName(order.getStrategyName());
        newPosition.setLeverage(order.getLeverage() != null ? order.getLeverage() : null); // Set leverage from order if available
        
        List<String> entryOrderIds = new ArrayList<>();
        entryOrderIds.add(String.valueOf(order.getOrderId()));
        newPosition.setEntryOrderIds(entryOrderIds);
        newPosition.setExitOrderIds(new ArrayList<>()); // Initialize empty exit orders
        return newPosition;
    }
    
    /**
     * Update order history
     */
    private void updateOrderHistory(Order order) {
        String symbol = order.getSymbol();
        List<Order> symbolOrders = orderHistoryCache.computeIfAbsent(symbol, k -> new ArrayList<>());
        symbolOrders.add(order);
        
        // Keep only last 100 orders per symbol
        if (symbolOrders.size() > 100) {
            symbolOrders = symbolOrders.subList(symbolOrders.size() - 100, symbolOrders.size());
            orderHistoryCache.put(symbol, symbolOrders);
        }
    }
    
    /**
     * Close a position
     * 
     * @param positionId Position ID to close
     * @param reason Reason for closing
     * @param exchange Exchange where the position is held (MEXC, BYBIT)
     * @return The closed position
     */
    public Position closePosition(String positionId, String reason, String exchange) {
        Long idToFind;
        try {
            idToFind = Long.parseLong(positionId);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid positionId format: " + positionId, e);
        }
        Position position = positionRepository.findById(idToFind)
            .orElseThrow(() -> new RuntimeException("Position not found: " + positionId));
        
        if (!exchange.equalsIgnoreCase(position.getExchange())) {
            throw new RuntimeException("Position " + positionId + " is not on exchange " + exchange);
        }
        
        try {
            closePosition(position, reason);
            return position;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to close position: " + e.getMessage());
        }
    }

    private void updateOrderStatus(Order order, Position position) {
        try {
            Long orderId = order.getOrderId();
            Long positionId = position.getId();
            
            orderCache.put(orderId, order);
            positionCache.put(positionId, position);
            
            // Update order history
            updateOrderHistory(order);
            
            // Update position if order is filled
            if ("FILLED".equals(order.getStatus())) {
                updatePositionFromOrder(order);
            }
        } catch (Exception e) {
            logger.error("Error updating order status for order {} and position {}: {}", 
                order.getOrderId(), position.getId(), e.getMessage());
            throw new RuntimeException("Failed to update order status", e);
        }
    }
    
    private Order saveOrder(OrderRequest request) {
        Order order = new Order();
        order.setSymbol(request.getSymbol());
        order.setSide(request.getSide());
        order.setType(request.getType());
        order.setQuantity(request.getQuantity());
        order.setPrice(request.getPrice());
        order.setTimeInForce(request.getTimeInForce());
        order.setBotId(request.getBotId());
        order.setStrategyName(request.getStrategyName());
        order.setPriceAtCreation(request.getPrice());
        
        Order savedOrder = orderRepository.save(order);
        logger.info("Saved order with ID: {}", savedOrder.getOrderId().toString());
        
        if (savedOrder.getStatus() != null) {
            logger.info("Order status: {}", savedOrder.getStatus());
        }
        
        return savedOrder;
    }
    
    private void updateExistingOrder(Order existingOrder, Order order) {
        existingOrder.setBotId(order.getBotId());
        existingOrder.setStrategyName(order.getStrategyName());
        existingOrder.setPriceAtCreation(order.getPriceAtCreation());
        
        Order savedOrder = orderRepository.save(existingOrder);
        logger.info("Updated order with ID: {}", savedOrder.getOrderId().toString());
    }
    
    private void updatePosition(Position position, BigDecimal currentPrice) {
        position.setCurrentPrice(currentPrice);
        position.setUpdatedAt(LocalDateTime.now());
        
        // Calculate unrealized PnL
        if ("LONG".equals(position.getSide())) {
            BigDecimal priceChange = currentPrice.subtract(position.getEntryPrice());
            BigDecimal pnl = priceChange.multiply(position.getQuantity());
            position.setUnrealizedPnl(pnl);
            
            // Calculate PnL percentage
            if (position.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pnlPercentage = priceChange
                    .divide(position.getEntryPrice(), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                position.setPnlPercentage(pnlPercentage);
            }
        } else {
            BigDecimal priceChange = position.getEntryPrice().subtract(currentPrice);
            BigDecimal pnl = priceChange.multiply(position.getQuantity());
            position.setUnrealizedPnl(pnl);
            
            // Calculate PnL percentage
            if (position.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pnlPercentage = priceChange
                    .divide(position.getEntryPrice(), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
                position.setPnlPercentage(pnlPercentage);
            }
        }
        
        // Calculate drawdown
        if (position.getHighestPrice() != null && position.getHighestPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal currentDrawdown;
            if ("LONG".equals(position.getSide())) {
                currentDrawdown = position.getHighestPrice().subtract(currentPrice)
                    .divide(position.getHighestPrice(), 8, RoundingMode.HALF_UP);
            } else {
                currentDrawdown = currentPrice.subtract(position.getLowestPrice())
                    .divide(position.getLowestPrice(), 8, RoundingMode.HALF_UP);
            }
            
            // Set the new drawdownPercentage field
            position.setDrawdownPercentage(currentDrawdown.multiply(BigDecimal.valueOf(100)));
            
            // Update max drawdown if current drawdown is larger
            if (position.getMaxDrawdown() == null || currentDrawdown.compareTo(position.getMaxDrawdown()) > 0) {
                position.setMaxDrawdown(currentDrawdown);
            }
        }
        
        Position savedPosition = positionRepository.save(position);
        positionCache.put(savedPosition.getId(), savedPosition);
    }
    
    private void saveNewPosition(String orderId, String positionId, Position newPosition) {
        Long posId = Long.parseLong(positionId);
        newPosition.setId(posId);
        newPosition.setUpdatedAt(LocalDateTime.now());
        Position savedPosition = positionRepository.save(newPosition);
        
        positionCache.put(savedPosition.getId(), savedPosition);
        activePositionIds.add(savedPosition.getId());
        
        logger.info("Saved new position with ID: {}", savedPosition.getId());
        logger.info("Associated with order ID: {}", orderId);
    }
    
    private void updateExistingPosition(String orderId, String positionId, Position existingPosition) {
        Long posId = Long.parseLong(positionId);
        existingPosition.setId(posId);
        existingPosition.setUpdatedAt(LocalDateTime.now());
        Position savedPosition = positionRepository.save(existingPosition);
        
        positionCache.put(savedPosition.getId(), savedPosition);
        
        logger.info("Updated position with ID: {}", savedPosition.getId());
        logger.info("Associated with order ID: {}", orderId);
    }

    // Helper method to generate position ID
    private Long generatePositionId() {
        return System.currentTimeMillis(); // Simple implementation, could be improved
    }

    /**
     * Get the status of a specific order from the exchange.
     *
     * @param orderId The ID of the order to check.
     * @param symbol The symbol of the order.
     * @param exchange The exchange the order was placed on.
     * @return A string representing the order status (e.g., "FILLED", "NEW", "CANCELED"), or null if an error occurs.
     */
    public String getOrderStatus(String orderId, String symbol, String exchange) {
        logger.debug("Fetching order status for orderId: {}, symbol: {}, exchange: {}", orderId, symbol, exchange);
        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // Infer category for Bybit (e.g., "spot" or "linear")
                // This logic should ideally come from the original OrderRequest or stored Order entity if available
                // For a general status check, we might need to fetch the order from DB first to get its marketType
                // Or, the caller (strategy) should provide the marketType/category.
                // Simple inference for now:
                Order orderFromDb = orderRepository.findById(Long.parseLong(orderId)).orElse(null);
                String category = "spot"; // Default
                if (orderFromDb != null && orderFromDb.getMarketType() != null) {
                    if (orderFromDb.getMarketType().toLowerCase().contains("future") || orderFromDb.getMarketType().toLowerCase().contains("linear")) {
                        category = "linear";
                    }
                } else {
                     // Fallback if order not in DB or has no market type: use symbol heuristics
                    category = bybitApiClientService.isLikelyPerpetualSymbol(symbol) ? "linear" : "spot";
                    logger.warn("Could not determine category from DB for order {}, symbol {}. Inferred as: {}", orderId, symbol, category);
                }
                
                ResponseEntity<String> response = bybitApiClientService.getOrderStatus(symbol, orderId, category);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode responseNode = objectMapper.readTree(response.getBody());
                    // Bybit /v5/order/history returns a list. We expect one order if querying by orderId.
                    JsonNode orderListNode = responseNode.path("result").path("list");
                    if (orderListNode.isArray() && orderListNode.size() > 0) {
                        JsonNode orderData = orderListNode.get(0); // Get the first order in the list
                        String status = orderData.path("orderStatus").asText();
                        logger.info("Order status for {} on Bybit: {}", orderId, status);
                        return status;
                    } else {
                        logger.warn("Order {} not found in Bybit history response or list is empty. Response: {}", orderId, response.getBody());
                        return "NOT_FOUND"; // Or some other indicator
                    }
                } else {
                    logger.error("Error fetching order status from Bybit for order {}: {} - {}", orderId, response.getStatusCode(), response.getBody());
                    return null;
                }
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                // TODO: Implement getOrderStatus for MEXC
                // MexcApiClientService and MexcFuturesApiService would need getOrderStatus methods.
                // These would call MEXC's query order endpoint (e.g., /api/v3/order for spot, /openApi/contract/v1/order/{order_id} for futures)
                logger.warn("getOrderStatus not yet implemented for MEXC and MEXC is temporarily disabled.");
                return null;
            } else {
                logger.warn("Unsupported exchange for getOrderStatus: {}", exchange);
                return null;
            }
        } catch (Exception e) {
            logger.error("Exception in getOrderStatus for orderId {}: {}", orderId, e.getMessage(), e);
            return null;
        }
    }

    private PositionCacheService.PositionUpdateData transformToPositionUpdateData(Position omsPosition) {
        if (omsPosition == null) {
            return null;
        }

        // Determine side for PositionCacheService ("Buy" for LONG, "Sell" for SHORT)
        String pcsSide = "None";
        if ("LONG".equalsIgnoreCase(omsPosition.getSide())) {
            pcsSide = "Buy";
        } else if ("SHORT".equalsIgnoreCase(omsPosition.getSide())) {
            pcsSide = "Sell";
        }

        // Leverage might be null in omsPosition if it's a SPOT position or not set.
        // PositionUpdateData expects Integer for leverage.
        Integer leverage = null;
        if (omsPosition.getLeverage() != null) {
            try {
                leverage = omsPosition.getLeverage().intValueExact();
            } catch (ArithmeticException e) {
                logger.warn("OMS_TRANSFORM: Could not convert leverage {} to int for symbol {}. Setting leverage to null in cache.", omsPosition.getLeverage(), omsPosition.getSymbol());
            }
        }
        
        // Ensure all necessary fields for the PositionUpdateData constructor are present or have defaults.
        // Note: PositionCacheService.PositionUpdateData constructor requires realisedPnl, stopLoss, takeProfit, positionStatus, updatedTimestamp.
        // Some of these might not be directly available or always up-to-date in omsPosition during all transformation contexts.
        // The PositionCacheService.updatePosition method handles merging, so sending available data is key.

        PositionCacheService.PositionUpdateData pud = new PositionCacheService.PositionUpdateData(
            omsPosition.getSymbol(),
            pcsSide,
            omsPosition.getQuantity() != null ? omsPosition.getQuantity() : BigDecimal.ZERO,
            omsPosition.getEntryPrice(),
            omsPosition.getCurrentPrice(), // markPrice equivalent from OMS position
            omsPosition.getPositionValue(), // positionValue
            omsPosition.getUnrealizedPnl(),
            omsPosition.getRealizedPnl() != null ? omsPosition.getRealizedPnl() : BigDecimal.ZERO, // realisedPnl
            omsPosition.getStopLossPrice() != null ? omsPosition.getStopLossPrice().toString() : "", // stopLoss
            omsPosition.getTakeProfitPrice() != null ? omsPosition.getTakeProfitPrice().toString() : "", // takeProfit
            omsPosition.getStatus(), // positionStatus (e.g., "OPEN", "CLOSED")
            omsPosition.getUpdateTime() != null ? omsPosition.getUpdateTime().toEpochMilli() : System.currentTimeMillis(), // updatedTimestamp
            omsPosition.getExchange(),
            null // liqPrice - Added null as omsPosition doesn't have liqPrice yet
        );
        pud.setLeverage(leverage);
        
        // Carry over strategy-specific fields if they exist in omsPosition (though they primarily live in PositionCacheService)
        // These are typically not managed by OMS.Position directly.
        // If PositionCacheService is the sole owner of these, this part might not be needed,
        // as PositionCacheService.updatePosition should preserve existing strategy fields.
        // For now, we assume PositionCacheService.updatePosition's merging logic is robust.

        // Added: Set positionIdx on the PositionUpdateData DTO
        if (omsPosition.getPositionIdx() != null) {
            pud.setPositionIdx(omsPosition.getPositionIdx());
        }

        return pud;
    }
} 