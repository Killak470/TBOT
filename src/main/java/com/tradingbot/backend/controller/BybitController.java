package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.service.BybitApiClientService;
import com.tradingbot.backend.service.OrderManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bybit")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class BybitController {

    private static final Logger logger = LoggerFactory.getLogger(BybitController.class);

    @Autowired
    private BybitApiClientService bybitApiClientService;

    @Autowired
    private OrderManagementService orderManagementService;

    // === MARKET DATA ENDPOINTS ===

    /**
     * Get latest price for a symbol
     */
    @GetMapping("/ticker/{symbol}")
    public ResponseEntity<String> getTickerPrice(@PathVariable String symbol) {
        try {
            logger.info("Getting Bybit ticker price for: {}", symbol);
            String formattedSymbol = bybitApiClientService.formatSymbolForSpot(symbol);
            return bybitApiClientService.getLatestPrice(formattedSymbol);
        } catch (Exception e) {
            logger.error("Error getting Bybit ticker price for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Get kline/candlestick data
     */
    @GetMapping("/klines/{symbol}")
    public ResponseEntity<String> getKlineData(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String interval,
            @RequestParam(defaultValue = "100") Integer limit,
            @RequestParam(required = false) Long startTime) {
        try {
            logger.info("Getting Bybit kline data for: {} with interval: {}", symbol, interval);
            String formattedSymbol = bybitApiClientService.formatSymbolForSpot(symbol);
            return bybitApiClientService.getKlineData(formattedSymbol, interval, limit, startTime, null, "spot");
        } catch (Exception e) {
            logger.error("Error getting Bybit kline data for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    /**
     * Get kline data for charts (simplified endpoint)
     */
    @GetMapping("/klines")
    public ResponseEntity<String> getKlines(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "100") Integer limit) {
        try {
            logger.info("Getting Bybit kline data for chart: {} with interval: {}", symbol, interval);
            String formattedSymbol = bybitApiClientService.formatSymbolForSpot(symbol);
            
            // Get current time and calculate start time
            long endTime = System.currentTimeMillis();
            
            return bybitApiClientService.getKlineData(formattedSymbol, interval, limit, null, endTime, "spot");
        } catch (Exception e) {
            logger.error("Error getting Bybit kline data for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    /**
     * Get futures kline data for charts
     */
    @GetMapping("/futures/klines")
    public ResponseEntity<String> getFuturesKlines(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "100") Integer limit) {
        try {
            logger.info("Getting Bybit futures kline data for chart: {} with interval: {}", symbol, interval);
            String formattedSymbol = bybitApiClientService.formatSymbolForFutures(symbol);
            
            // Get current time and calculate start time
            long endTime = System.currentTimeMillis();
            
            // Use linear category for futures
            return bybitApiClientService.getKlineData(formattedSymbol, interval, limit, null, endTime, "linear");
        } catch (Exception e) {
            logger.error("Error getting Bybit futures kline data for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // === ORDER MANAGEMENT ENDPOINTS ===

    /**
     * Place a spot order
     */
    @PostMapping("/order/spot")
    public ResponseEntity<?> placeSpotOrder(@RequestBody OrderRequest orderRequest) {
        try {
            logger.info("Placing Bybit spot order: {} {} {} at {}", 
                orderRequest.getSide(), 
                orderRequest.getQuantity(), 
                orderRequest.getSymbol(),
                orderRequest.getPrice() != null ? orderRequest.getPrice() : "MARKET");

            // Use the multi-exchange order management service
            return ResponseEntity.ok(orderManagementService.placeOrder(orderRequest, "BYBIT"));
        } catch (Exception e) {
            logger.error("Error placing Bybit spot order: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    /**
     * Cancel an order
     */
    @DeleteMapping("/order/{symbol}/{orderId}")
    public ResponseEntity<String> cancelOrder(
            @PathVariable String symbol,
            @PathVariable String orderId) {
        try {
            logger.info("Cancelling Bybit order: {} for symbol: {}", orderId, symbol);
            String formattedSymbol = bybitApiClientService.formatSymbolForSpot(symbol);
            return bybitApiClientService.cancelSpotOrder(formattedSymbol, orderId);
        } catch (Exception e) {
            logger.error("Error cancelling Bybit order {}: {}", orderId, e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Get open orders
     */
    @GetMapping("/orders/open")
    public ResponseEntity<String> getOpenOrders(@RequestParam(required = false) String symbol) {
        try {
            logger.info("Getting Bybit open orders for symbol: {}", symbol != null ? symbol : "ALL");
            String formattedSymbol = symbol != null ? bybitApiClientService.formatSymbolForSpot(symbol) : null;
            return bybitApiClientService.getCurrentOpenOrders(formattedSymbol);
        } catch (Exception e) {
            logger.error("Error getting Bybit open orders: {}", e.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // === UTILITY ENDPOINTS ===

    /**
     * Test connection to Bybit API
     */
    @GetMapping("/ping")
    public ResponseEntity<?> testConnection() {
        try {
            logger.info("Testing Bybit API connection");
            
            // Test with a simple ticker request
            ResponseEntity<String> response = bybitApiClientService.getLatestPrice("BTCUSDT");
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "connected");
            result.put("exchange", "Bybit");
            result.put("timestamp", System.currentTimeMillis());
            result.put("apiStatus", response.getStatusCode().value());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Bybit API connection test failed: {}", e.getMessage());
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Connection failed: " + e.getMessage());
            errorResponse.put("exchange", "Bybit");
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    /**
     * Get exchange information
     */
    @GetMapping("/info")
    public ResponseEntity<?> getExchangeInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("exchange", "Bybit");
        info.put("version", "V5");
        info.put("baseUrl", bybitApiClientService.getConfig().getSpotApiUrl());
        info.put("testMode", bybitApiClientService.getConfig().isTestMode());
        info.put("features", new String[]{"spot", "futures", "options"});
        
        return ResponseEntity.ok(info);
    }
} 