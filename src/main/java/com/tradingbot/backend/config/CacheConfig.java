package com.tradingbot.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CLAUDE_ANALYSIS_CACHE = "claudeAnalysisCache";
    // Define other cache names here if needed
    // public static final String MARKET_SCAN_RESULTS_CACHE = "marketScanResultsCache";


    @Bean
    @Primary // Default CacheManager
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        // Default cache configuration (can be overridden by specific caches below)
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(50)
                .maximumSize(100)
                .expireAfterWrite(60, TimeUnit.MINUTES) // Default expiry for other caches
                .recordStats());
        return cacheManager;
    }

    @Bean("claudeCacheManager") // A specific CacheManager if you want to manage caches separately
    public CacheManager claudeCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(CLAUDE_ANALYSIS_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .initialCapacity(10)
                .maximumSize(50) // Max 50 cached Claude analyses
                .expireAfterWrite(15, TimeUnit.MINUTES) // Claude analysis specific expiry: 15 minutes
                .recordStats());
        return cacheManager;
    }
    
    // If using different cache managers for different caches, 
    // you need to specify the cacheManager attribute in @Cacheable, e.g.,
    // @Cacheable(value = CLAUDE_ANALYSIS_CACHE, key = "...", cacheManager = "claudeCacheManager")
    // For simplicity, if we want all caches to go through one manager but have different specs,
    // we can register them with the primary manager.
    // The following approach customizes the primary cache manager with specific settings for named caches.

    /*
    // Alternative: Configure specific caches on the primary cache manager
    @Bean
    @Primary
    public CacheManager customCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        
        // Default for any cache not explicitly configured
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats());

        // Specific configuration for claudeAnalysisCache
        manager.registerCustomCache(CLAUDE_ANALYSIS_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(3, TimeUnit.MINUTES)
                .maximumSize(50) // Max 50 cached Claude analyses
                .recordStats()
                .build());
        
        // Specific configuration for marketScanResultsCache (example)
        // manager.registerCustomCache(MARKET_SCAN_RESULTS_CACHE, Caffeine.newBuilder()
        //         .expireAfterWrite(10, TimeUnit.MINUTES)
        //         .maximumSize(10)
        //         .recordStats()
        //         .build());
        
        return manager;
    }
    */
} 