package com.tradingbot.backend.service;

import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Common interface for exchange API clients.
 * This interface defines methods that must be implemented by all exchange-specific API clients.
 */
public interface ExchangeApiClientService {
    
    /**
     * Get historical kline/candlestick data
     * 
     * @param symbol The trading pair
     * @param interval The time interval (e.g., "1h", "4h", "1d")
     * @param startTime Optional start time in milliseconds
     * @param endTime Optional end time in milliseconds
     * @param limit Maximum number of records to return
     * @return Response containing kline data in JSON format
     */
    ResponseEntity<String> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit);
    
    /**
     * Get latest price for a symbol
     * 
     * @param symbol The trading pair
     * @param exchange The exchange name (e.g., "BYBIT", "MEXC")
     * @return Response containing the latest price data
     */
    ResponseEntity<String> getLatestPrice(String symbol, String exchange);
    
    /**
     * Get account balance
     * 
     * @return Response containing account balance data
     */
    ResponseEntity<String> getAccountBalance();
    
    /**
     * Place a new order
     * 
     * @param symbol The trading pair
     * @param side BUY or SELL
     * @param type Order type (e.g., MARKET, LIMIT)
     * @param quantity Order quantity
     * @param price Optional limit price
     * @param clientOrderId Optional client order ID
     * @return Response containing the order details
     */
    ResponseEntity<String> placeOrder(String symbol, String side, String type, BigDecimal quantity, 
                                    BigDecimal price, String clientOrderId);
    
    /**
     * Cancel an existing order
     * 
     * @param symbol The trading pair
     * @param orderId The order ID to cancel
     * @return Response containing the cancellation result
     */
    ResponseEntity<String> cancelOrder(String symbol, String orderId);
    
    /**
     * Get order status
     * 
     * @param symbol The trading pair
     * @param orderId The order ID to query
     * @return Response containing the order status
     */
    ResponseEntity<String> getOrderStatus(String symbol, String orderId);
    
    /**
     * Get open orders for a symbol
     * 
     * @param symbol The trading pair
     * @return Response containing open orders
     */
    ResponseEntity<String> getOpenOrders(String symbol);
    
    /**
     * Get position information for a symbol
     * 
     * @param symbol The trading pair
     * @return Response containing position information
     */
    ResponseEntity<String> getPosition(String symbol);
    
    /**
     * Get exchange information (trading rules, symbol info, etc.)
     * 
     * @return Response containing exchange information
     */
    ResponseEntity<String> getExchangeInfo();
    
    /**
     * Get server time
     * 
     * @return Response containing server time
     */
    ResponseEntity<String> getServerTime();
    
    /**
     * Get exchange-specific configuration
     * 
     * @return Map containing configuration parameters
     */
    Map<String, Object> getConfiguration();
    
    /**
     * Update exchange-specific configuration
     * 
     * @param configuration New configuration parameters
     */
    void updateConfiguration(Map<String, Object> configuration);
    
    /**
     * Get the exchange name
     * 
     * @return The exchange name (e.g., "MEXC", "BYBIT")
     */
    String getExchangeName();

    ResponseEntity<String> getOrderStatus(String symbol, String orderId, String category);
} 