package com.tradingbot.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.backend.model.Order;
import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.OrderManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final OrderManagementService orderManagementService;

    public OrderController(MexcApiClientService mexcApiClientService, OrderManagementService orderManagementService) {
        this.mexcApiClientService = mexcApiClientService;
        this.orderManagementService = orderManagementService;
    }

    /**
     * Place a new spot order
     */
    @PostMapping("/spot")
    public ResponseEntity<Order> placeSpotOrder(@RequestBody OrderRequest request) {
        try {
            // Use MEXC as default for spot orders through this endpoint
            Order order = orderManagementService.placeOrder(request, "MEXC");
            return ResponseEntity.ok(order);
        } catch (JsonProcessingException e) {
            logger.error("Error placing order: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Place a new spot order (legacy method using request parameters)
     */
    @PostMapping("/spot/legacy")
    public ResponseEntity<String> placeSpotOrderLegacy(
            @RequestParam String symbol,
            @RequestParam String side,
            @RequestParam String type,
            @RequestParam(required = false) String timeInForce,
            @RequestParam(required = false) String quantity,
            @RequestParam(required = false) String price,
            @RequestParam(required = false) String newClientOrderId) {
        return mexcApiClientService.placeSpotOrder(symbol, side, type, timeInForce, quantity, price, newClientOrderId);
    }

    /**
     * Cancel an existing spot order
     */
    @DeleteMapping("/spot/{orderId}")
    public ResponseEntity<Order> cancelSpotOrder(
            @PathVariable String orderId,
            @RequestParam String symbol) {
        try {
            Order canceledOrder = orderManagementService.cancelOrder(symbol, orderId);
            return ResponseEntity.ok(canceledOrder);
        } catch (JsonProcessingException e) {
            logger.error("Error canceling order: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Cancel an existing spot order (legacy method)
     */
    @DeleteMapping("/spot/legacy")
    public ResponseEntity<String> cancelSpotOrderLegacy(
            @RequestParam String symbol,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String origClientOrderId) {
        return mexcApiClientService.cancelSpotOrder(symbol, orderId, origClientOrderId);
    }

    /**
     * Get account information including balances
     */
    @GetMapping("/account")
    public ResponseEntity<JsonNode> getAccountInfo() {
        try {
            JsonNode accountInfo = orderManagementService.getAccountInfo();
            return ResponseEntity.ok(accountInfo);
        } catch (JsonProcessingException e) {
            logger.error("Error getting account info: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get all open orders
     * 
     * @param symbol Optional filter by symbol
     * @return List of open orders
     */
    @GetMapping("/open")
    public ResponseEntity<List<Order>> getOpenOrders(
            @RequestParam(required = false) String symbol) {
        try {
            List<Order> openOrders = orderManagementService.getOpenOrders(symbol);
            return ResponseEntity.ok(openOrders);
        } catch (Exception e) {
            logger.error("Error getting open orders", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get order history for a symbol
     * 
     * @param symbol Trading pair
     * @return List of historical orders
     */
    @GetMapping("/history")
    public ResponseEntity<List<Order>> getOrderHistory(
            @RequestParam(required = false) String symbol) {
        try {
            List<Order> orderHistory = orderManagementService.getOrderHistory(symbol);
            return ResponseEntity.ok(orderHistory);
        } catch (Exception e) {
            logger.error("Error getting order history", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Place a new order
     * 
     * @param request Order request details
     * @return The created order
     */
    @PostMapping
    public ResponseEntity<Order> placeOrder(
            @RequestBody OrderRequest request,
            @RequestParam(defaultValue = "MEXC") String exchange) {
        try {
            Order order = orderManagementService.placeOrder(request, exchange);
            return ResponseEntity.ok(order);
        } catch (Exception e) {
            logger.error("Error placing order", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Cancel an existing order
     * 
     * @param orderId Order ID to cancel
     * @param symbol Trading pair
     * @return The canceled order
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<Order> cancelOrder(
            @PathVariable String orderId,
            @RequestParam String symbol) {
        try {
            Order canceledOrder = orderManagementService.cancelOrder(symbol, orderId);
            return ResponseEntity.ok(canceledOrder);
        } catch (Exception e) {
            logger.error("Error canceling order", e);
            return ResponseEntity.badRequest().build();
        }
    }
} 