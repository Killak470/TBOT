package com.tradingbot.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service to manage exchange context and prevent cross-exchange API calls
 * during exchange-specific operations like Bybit scans
 */
@Service
public class ExchangeContextService {
    
    private static final Logger logger = LoggerFactory.getLogger(ExchangeContextService.class);
    
    // Thread-local storage for current exchange context
    private final ThreadLocal<String> currentExchange = new ThreadLocal<>();
    
    // Global operation context
    private final AtomicReference<String> globalActiveExchange = new AtomicReference<>();
    
    // Active scan operations by thread
    private final ConcurrentHashMap<Long, String> activeScanOperations = new ConcurrentHashMap<>();
    
    /**
     * Set the current exchange context for the current thread
     */
    public void setCurrentExchange(String exchange) {
        if (exchange != null) {
            currentExchange.set(exchange.toUpperCase());
            activeScanOperations.put(Thread.currentThread().getId(), exchange.toUpperCase());
            logger.debug("Set exchange context for thread {}: {}", Thread.currentThread().getId(), exchange);
        }
    }
    
    /**
     * Get the current exchange context for the current thread
     */
    public String getCurrentExchange() {
        return currentExchange.get();
    }
    
    /**
     * Clear the current exchange context for the current thread
     */
    public void clearCurrentExchange() {
        Long threadId = Thread.currentThread().getId();
        currentExchange.remove();
        activeScanOperations.remove(threadId);
        logger.debug("Cleared exchange context for thread {}", threadId);
    }
    
    /**
     * Set global active exchange (used for major operations like scans)
     */
    public void setGlobalActiveExchange(String exchange) {
        if (exchange != null) {
            globalActiveExchange.set(exchange.toUpperCase());
            logger.info("Set global active exchange: {}", exchange);
        }
    }
    
    /**
     * Get global active exchange
     */
    public String getGlobalActiveExchange() {
        return globalActiveExchange.get();
    }
    
    /**
     * Clear global active exchange
     */
    public void clearGlobalActiveExchange() {
        String previousExchange = globalActiveExchange.getAndSet(null);
        if (previousExchange != null) {
            logger.info("Cleared global active exchange: {}", previousExchange);
        }
    }
    
    /**
     * Check if we should skip background operations for the given exchange
     * to avoid cross-exchange calls during active scans
     */
    public boolean shouldSkipBackgroundOperation(String targetExchange) {
        String globalActive = getGlobalActiveExchange();
        String currentThread = getCurrentExchange();
        
        // If there's a global active exchange and it's different from target, skip
        if (globalActive != null && !globalActive.equalsIgnoreCase(targetExchange)) {
            logger.debug("Skipping {} background operation due to active {} scan", targetExchange, globalActive);
            return true;
        }
        
        // If current thread has different exchange context, skip
        if (currentThread != null && !currentThread.equalsIgnoreCase(targetExchange)) {
            logger.debug("Skipping {} background operation due to thread context: {}", targetExchange, currentThread);
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if any scan operations are currently active
     */
    public boolean hasPendingScanOperations() {
        return !activeScanOperations.isEmpty() || globalActiveExchange.get() != null;
    }
    
    /**
     * Get active scan operations count
     */
    public int getActiveScanCount() {
        return activeScanOperations.size();
    }
    
    /**
     * Execute an operation with exchange context
     */
    public <T> T withExchangeContext(String exchange, java.util.function.Supplier<T> operation) {
        try {
            setCurrentExchange(exchange);
            return operation.get();
        } finally {
            clearCurrentExchange();
        }
    }
    
    /**
     * Execute an operation with global exchange context
     */
    public <T> T withGlobalExchangeContext(String exchange, java.util.function.Supplier<T> operation) {
        try {
            setGlobalActiveExchange(exchange);
            return operation.get();
        } finally {
            clearGlobalActiveExchange();
        }
    }
} 