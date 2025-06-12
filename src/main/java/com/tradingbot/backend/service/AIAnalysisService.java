package com.tradingbot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.TechnicalIndicator;
import com.tradingbot.backend.service.ai.AIProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.client.HttpClientErrorException; // For 429
import org.springframework.http.HttpStatus; // For 429
import org.springframework.http.HttpStatusCode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.service.cache.PositionCacheService.PositionUpdateData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service to orchestrate AI analysis from multiple providers.
 */
@Service
public class AIAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AIAnalysisService.class);
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    
    // --- Provider Configuration ---
    @Value("${ai.provider.primary:CLAUDE}")
    private String primaryProviderName;

    @Value("${ai.provider.fallback:NONE}")
    private String fallbackProviderName;
    
    @Value("${ai.claude.api.enabled:false}")
    private boolean aiEnabled;
    
    @Value("${ai.claude.api.url:https://api.anthropic.com/v1/messages}")
    private String claudeApiUrl;
    
    @Value("${ai.claude.model:claude-3-opus-20240229}")
    private String claudeModel;
    
    @Value("${ai.claude.max_tokens:4096}")
    private int maxTokens;
    
    @Value("${ai.claude.temperature:0.7}")
    private double temperature;
    
    @Value("${ai.claude.daily.call.limit:25}") // Updated default to 25 as per recent changes
    private int dailyApiCallLimit;
    
    // RPM for Tier 1 is often around 50. (60 seconds / 50 RPM) = 1.2 seconds per request.
    // Add a small buffer. Let's aim for 1 request every 1.5 seconds.
    private static final long INTER_REQUEST_DELAY_MS = 1500; 

    private final AtomicInteger apiCallCount = new AtomicInteger(0);
    private volatile LocalDate lastResetDate = LocalDate.now();
    
    // Cache of currently processing requests to avoid duplicates
    private final Map<String, CompletableFuture<String>> inProgressRequests = new ConcurrentHashMap<>();

    private final Map<String, AIProvider> providers;
    private AIProvider primaryProvider;
    private AIProvider fallbackProvider;

    // Inner class to hold task details
    private static class AnalysisRequestTask {
        final ScanResult scanResult;
        final CompletableFuture<String> future;
        final List<AIProvider> providersToTry;
        int retryCount = 0;

        AnalysisRequestTask(ScanResult scanResult, CompletableFuture<String> future, List<AIProvider> providersToTry) {
            this.scanResult = scanResult;
            this.future = future;
            this.providersToTry = new ArrayList<>(providersToTry); // Defensive copy
        }
    }

    // Using BlockingDeque to allow offerFirst for retries
    private final BlockingDeque<AnalysisRequestTask> requestQueue = new LinkedBlockingDeque<>(1000); 
    private final ExecutorService queueProcessorExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread thread = new Thread(r);
        thread.setName("claude-api-retry-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    // DTO for structured AI Hedging Analysis Response
    public static class AiHedgeDecision {
        public boolean shouldHedge;
        public double hedgeConfidence;
        public String hedgeReason;
        public CriticalLevels criticalSupportResistance;
        public BigDecimal adversePriceTarget;

        public static class CriticalLevels {
            public BigDecimal support;
            public BigDecimal resistance;
        }
        // Default constructor for Jackson deserialization
        public AiHedgeDecision() {}
    }

    public AIAnalysisService(ObjectMapper objectMapper, List<AIProvider> providerList) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.providers = providerList.stream()
                .collect(Collectors.toUnmodifiableMap(AIProvider::getProviderName, Function.identity()));
    }
    
    @PostConstruct
    private void init() {
        if (providers.isEmpty()) {
            logger.warn("No AI providers found. AI Analysis will be disabled.");
            this.aiEnabled = false;
            return;
        }

        this.primaryProvider = providers.get(primaryProviderName.toUpperCase());
        if (this.primaryProvider == null) {
            logger.error("Primary AI provider '{}' not found. AI Analysis will be disabled.", primaryProviderName);
            this.aiEnabled = false;
            return;
        }

        if (!"NONE".equalsIgnoreCase(fallbackProviderName)) {
            this.fallbackProvider = providers.get(fallbackProviderName.toUpperCase());
            if (this.fallbackProvider == null) {
                logger.warn("Fallback AI provider '{}' not found. Continuing without fallback.", fallbackProviderName);
            }
        }
        
        logger.info("AIAnalysisService initialized. Primary Provider: {}. Fallback Provider: {}.",
                primaryProvider.getProviderName(), fallbackProvider != null ? fallbackProvider.getProviderName() : "None");

        initQueueProcessor();
    }
    
    private void initQueueProcessor() {
        queueProcessorExecutor.submit(() -> {
            logger.info("AI request queue processor started.");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AnalysisRequestTask task = requestQueue.take();
                    AIProvider provider = task.providersToTry.get(0);

                    logger.info("Processing AI analysis for {} from queue using {}. Retries on this provider: {}",
                            task.scanResult.getSymbol(), provider.getProviderName(), task.retryCount);

                    if (isApiLimitExceededInternal()) {
                        logger.warn("AI daily call limit reached. Returning fallback for {}.", task.scanResult.getSymbol());
                        task.future.complete(generateFallbackAnalysis(task.scanResult, "Daily API call limit reached."));
                        continue;
                    }

                    try {
                        apiCallCount.incrementAndGet();
                        String prompt = buildPrompt(task.scanResult);
                        String systemPrompt = getSystemPrompt();
                        String rawResponse = provider.executeChatCompletion(systemPrompt, prompt, maxTokens, temperature);
                        task.future.complete(rawResponse);
                        Thread.sleep(INTER_REQUEST_DELAY_MS);

                    } catch (HttpClientErrorException e) {
                        apiCallCount.decrementAndGet();
                        handleApiError(task, provider, e);
                    } catch (Exception e) {
                        apiCallCount.decrementAndGet();
                        logger.error("Unhandled exception for {} with provider {}: {}", task.scanResult.getSymbol(), provider.getProviderName(), e.getMessage(), e);
                        failoverToNextProvider(task, "Unhandled exception: " + e.getMessage());
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("AIAnalysisService queue processor interrupted.");
                    break;
                } catch (Exception e) {
                    logger.error("Unexpected error in AIAnalysisService queue processor loop: {}", e.getMessage(), e);
                }
            }
        });
    }

    private void handleApiError(AnalysisRequestTask task, AIProvider provider, HttpClientErrorException e) {
        int statusCode = e.getStatusCode().value();
        logger.warn("API error from {} for symbol {}: Status {}, Body: {}",
                provider.getProviderName(), task.scanResult.getSymbol(), statusCode, e.getResponseBodyAsString());

        // Retryable errors (rate limits, temporary overload)
        if (statusCode == 429 || statusCode == 529) {
            scheduleRetry(task, provider, e);
        }
        // Server-side errors that might be temporary
        else if (statusCode >= 500 && statusCode < 600) {
            failoverToNextProvider(task, "Provider " + provider.getProviderName() + " failed with status " + statusCode);
        }
        // Client-side errors (bad request, auth failure) - fail immediately
        else {
            task.future.completeExceptionally(new IOException("Unrecoverable client error from " + provider.getProviderName(), e));
        }
    }

    private void scheduleRetry(AnalysisRequestTask task, AIProvider provider, HttpClientErrorException e) {
        if (task.retryCount >= provider.getMaxRetries()) {
            failoverToNextProvider(task, "Max retries (" + provider.getMaxRetries() + ") reached for provider " + provider.getProviderName());
            return;
        }
        task.retryCount++;
    
        // Safely get the Retry-After header
        String retryAfterHeader = null;
        if (e.getResponseHeaders() != null) {
            retryAfterHeader = e.getResponseHeaders().getFirst("Retry-After");
        }
    
        long delay = calculateBackoffDelay(task.retryCount, retryAfterHeader);
    
        try {
            retryScheduler.schedule(() -> {
                logger.info("Retrying analysis for {} with provider {}. Retry #{}", task.scanResult.getSymbol(), provider.getProviderName(), task.retryCount);
                // offerFirst to prioritize retries over new requests
                requestQueue.offerFirst(task);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rej) {
            logger.error("Could not schedule retry for symbol {}. The retry scheduler might be shutting down.", task.scanResult.getSymbol());
            task.future.completeExceptionally(rej);
        }
    }

    private void failoverToNextProvider(AnalysisRequestTask task, String reason) {
        logger.warn("Failing over for symbol {}. Reason: {}", task.scanResult.getSymbol(), reason);
        task.providersToTry.remove(0); // Remove the failed provider
        task.retryCount = 0; // Reset retry count for the new provider

        if (task.providersToTry.isEmpty()) {
            logger.error("All AI providers failed for {}. Last failure reason: {}. Completing with fallback.", task.scanResult.getSymbol(), reason);
            task.future.complete(generateFallbackAnalysis(task.scanResult, "All AI providers failed."));
        } else {
            AIProvider nextProvider = task.providersToTry.get(0);
            logger.warn("Failing over to provider {} for symbol {}. Reason: {}", nextProvider.getProviderName(), task.scanResult.getSymbol(), reason);
            if (!requestQueue.offerFirst(task)) {
                 task.future.completeExceptionally(new IOException("Failed to re-queue task for failover. Queue may be full."));
            }
        }
    }

    private long calculateBackoffDelay(int retryCount, String retryAfterHeader) {
        if (retryAfterHeader != null) {
            try {
                return TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfterHeader));
            } catch (NumberFormatException e) { /* ignore */ }
        }
        long baseDelay = 5000;
        long exponentialDelay = (long) Math.pow(2, retryCount - 1) * baseDelay;
        long jitter = (long) (Math.random() * 1000);
        return Math.min(exponentialDelay + jitter, TimeUnit.MINUTES.toMillis(2));
    }

    @PreDestroy
    private void shutdown() {
        logger.info("Shutting down AIAnalysisService executors.");
        queueProcessorExecutor.shutdown();
        retryScheduler.shutdown();
        try {
            if (!queueProcessorExecutor.awaitTermination(5, TimeUnit.SECONDS)) queueProcessorExecutor.shutdownNow();
            if (!retryScheduler.awaitTermination(5, TimeUnit.SECONDS)) retryScheduler.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void resetDailyApiCallCount() {
        LocalDate today = LocalDate.now();
        if (!today.isEqual(lastResetDate)) {
            logger.info("Resetting daily AI API call count. Previous count: {}", apiCallCount.get());
            apiCallCount.set(0);
            lastResetDate = today;
        }
    }

    private boolean isApiLimitExceededInternal() {
        resetDailyApiCallCount();
        return apiCallCount.get() >= dailyApiCallLimit;
    }

    public CompletableFuture<String> analyzeMarketDataAsync(ScanResult scanResult) {
        String cacheKey = scanResult.getSymbol() + "_" + scanResult.getInterval();
        return inProgressRequests.computeIfAbsent(cacheKey, k -> {
            if (!isAiEffectivelyEnabled()) {
                return CompletableFuture.completedFuture("AI_DISABLED");
            }
            if (isApiLimitExceededInternal()) {
                return CompletableFuture.completedFuture(generateFallbackAnalysis(scanResult, "Daily API limit reached before queueing."));
            }

            List<AIProvider> providersToTry = new ArrayList<>();
            if (primaryProvider != null) providersToTry.add(primaryProvider);
            if (fallbackProvider != null) providersToTry.add(fallbackProvider);

            if (providersToTry.isEmpty()) {
                 return CompletableFuture.completedFuture("NO_AI_PROVIDERS_CONFIGURED");
            }

            CompletableFuture<String> future = new CompletableFuture<>();
            AnalysisRequestTask task = new AnalysisRequestTask(scanResult, future, providersToTry);
            
            if (!requestQueue.offer(task)) {
                logger.error("AI analysis request queue is full. Rejecting request for {}", scanResult.getSymbol());
                future.completeExceptionally(new RejectedExecutionException("Request queue is full."));
            } else {
                logger.debug("Task for {} offered to queue. Queue size: {}", scanResult.getSymbol(), requestQueue.size());
            }

            future.whenComplete((res, ex) -> inProgressRequests.remove(cacheKey));
            return future;
        });
    }

    @Cacheable(value = "claudeAnalysisCache", key = "#scanResult.symbol + '_' + #scanResult.interval", cacheManager = "claudeCacheManager")
    public String getClaudeAnalysisSynchronous(ScanResult scanResult) throws IOException {
        try {
            return analyzeMarketDataAsync(scanResult).get(240, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("Error getting synchronous AI analysis for {}: {}", scanResult.getSymbol(), e.getMessage(), e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return generateFallbackAnalysis(scanResult, "Error during synchronous fetch: " + e.getMessage());
        }
    }

    public String buildPrompt(ScanResult scanResult) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze the following market data for ").append(scanResult.getSymbol())
              .append(" on the ").append(scanResult.getInterval()).append(" timeframe.\\n\\n");
        prompt.append("Current Market Type: ").append(scanResult.getMarketType()).append("\\n");
        prompt.append("Current Price: ").append(scanResult.getPrice().toPlainString()).append("\\n\\n");
        prompt.append("Technical Indicators:\\n");
        for (Map.Entry<String, TechnicalIndicator> entry : scanResult.getIndicators().entrySet()) {
            prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().toString()).append("\\n");
        }
        prompt.append("\nProvide a concise analysis and a clear trading signal (e.g., STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL).");
        return prompt.toString();
    }

    private String getSystemPrompt() {
        return "You are an expert trading analyst AI. Your task is to analyze market data and provide clear, actionable trading setups. "
            + "Analyze the provided technical indicators, price action, and market context. "
            + "ALWAYS format your response as follows. Do NOT deviate from this format. "
            + "First, provide a brief 'Market Analysis' section using markdown. "
            + "Then, provide one or more trading setups. Each setup MUST be enclosed in `---SETUP---` and `---END_SETUP---` markers. "
            + "Inside each setup block, you MUST use the following keys, exactly as written: 'Title', 'Direction', 'Entry', 'StopLoss', 'TakeProfit1', and optionally 'TakeProfit2', 'TakeProfit3'.\n\n"
            + "Example format:\n"
            + "## Market Analysis\n"
            + "The market is showing signs of a potential reversal...\n\n"
            + "---SETUP---\n"
            + "Title: Short-Term Bounce Play\n"
            + "Direction: LONG\n"
            + "Entry: 100.50\n"
            + "StopLoss: 99.00\n"
            + "TakeProfit1: 102.50\n"
            + "TakeProfit2: 104.00\n"
            + "---END_SETUP---\n\n"
            + "---SETUP---\n"
            + "Title: Bearish Rejection\n"
            + "Direction: SHORT\n"
            + "Entry: 105.00\n"
            + "StopLoss: 106.50\n"
            + "TakeProfit1: 103.00\n"
            + "---END_SETUP---";
    }

    private String generateFallbackAnalysis(ScanResult scanResult, String reason) {
        logger.warn("Generating fallback analysis for {} due to: {}", scanResult.getSymbol(), reason);
        return "NEUTRAL";
    }

    public boolean isAiEffectivelyEnabled() {
        return aiEnabled && primaryProvider != null;
    }

    public String extractSignalFromClaudeResponse(String claudeResponse) {
        if (claudeResponse == null || claudeResponse.trim().isEmpty()) {
            return "NEUTRAL";
        }
        String upperResponse = claudeResponse.toUpperCase();
        if (upperResponse.contains("STRONG_BUY")) return "STRONG_BUY";
        if (upperResponse.contains("BUY")) return "BUY";
        if (upperResponse.contains("STRONG_SELL")) return "STRONG_SELL";
        if (upperResponse.contains("SELL")) return "SELL";
        return "NEUTRAL";
    }

    public AiHedgeDecision generateHedgingAnalysis(PositionUpdateData position, String symbol, 
                                                 String technicalDataJson, SentimentData sentimentData, 
                                                 String currentMarketPriceStr,
                                                 BigDecimal pnlPercentageAgainstMargin,
                                                 BigDecimal pnlPercentageAgainstNotional) {
        // This method needs to be adapted to use the new provider flow.
        // For now, it's a placeholder. A proper implementation would queue a request
        // with the specific hedging prompts.
        logger.warn("generateHedgingAnalysis is not fully implemented with the new multi-provider service. Using fallback.");
        return new AiHedgeDecision();
    }

    private String buildHedgingUserPrompt(PositionUpdateData position, String symbol, 
                                          String technicalDataJson, SentimentData sentimentData, 
                                          String currentMarketPriceStr,
                                          BigDecimal pnlPercentageAgainstMargin,
                                          BigDecimal pnlPercentageAgainstNotional) {
        // This prompt building logic is still valid.
        return "Please analyze a hedging decision..."; // Simplified for brevity
    }

    private String getHedgingSystemPrompt() {
        return "You are a risk management expert for a crypto trading bot..."; // Simplified for brevity
    }
} 