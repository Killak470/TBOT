package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.Order;
import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.model.TradeDecision;
import com.tradingbot.backend.service.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PostConstruct;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import com.tradingbot.backend.service.cache.PositionCacheService;
import com.tradingbot.backend.service.strategy.SniperEntryStrategy;
import com.tradingbot.backend.service.util.ExchangeUtil;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.service.MarketScannerService;
import com.tradingbot.backend.service.HedgingService;

/**
 * Service responsible for executing trading strategies and managing trade lifecycle
 */
@Service
public class StrategyExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(StrategyExecutionService.class);
    
    // Injected properties for strategy symbols and IDs
    @Value("${tradingbot.sniper.strategy.symbols}")
    private List<String> sniperStrategySymbols;

    @Value("${tradingbot.sniper.strategy.id}")
    private String sniperStrategyId;

    @Value("${tradingbot.default.strategy.symbols}")
    private List<String> defaultStrategySymbols;

    @Value("${tradingbot.default.strategy.id}")
    private String defaultStrategyId;

    // For specific exchange configuration per default symbol
    @Value("#{${tradingbot.default.strategy.exchange.map:{}}}") // Allows for an empty map if not defined
    private Map<String, String> defaultSymbolExchangeMap;
    
    private final MexcApiClientService mexcApiClientService;
    private final BybitApiClientService bybitApiClientService;
    private final BybitFuturesApiService bybitFuturesApiService;
    private final TradingStrategyService tradingStrategyService;
    private final RiskManagementService riskManagementService;
    private final OrderManagementService orderManagementService;
    private final PositionCacheService positionCacheService;
    private final MarketScannerService marketScannerService;
    
    // Cache for active positions and orders
    private final Map<String, Order> activeOrders = new ConcurrentHashMap<>();
    
    private final ExecutorService sniperStrategyExecutor;
    // public static final List<String> SNIPER_STRATEGY_SYMBOLS = List.of("WALUSDT", "TRUMPUSDT", "SUIUSDT","NXPCUSDT"); // Replaced by @Value
    // public static final String SNIPER_STRATEGY_ID = "SniperEntryStrategy"; // Replaced by @Value

    // Flag to control Sniper strategy execution
    private volatile boolean isSniperStrategyActive = false;

    public boolean isSniperStrategyActive() {
        return isSniperStrategyActive;
    }

    // Counter for low-volatility scan skipping
    private int lowVolatilityScanCounter = 0;
    private static final int LOW_VOLATILITY_SCAN_INTERVAL = 3; // Scan every 3rd minute during low vol
    private static final String VOLATILITY_CHECK_SYMBOL = "BTCUSDT";

    // New: Scan cycle counter and interval multipliers per session
    private int sniperScanCycleCounter = 0;
    private static final int SCAN_INTERVAL_OVERLAP_EU_US = 1; // Every 1 execution (e.g., 1 min)
    private static final int SCAN_INTERVAL_US = 1;            // Every 1 execution
    private static final int SCAN_INTERVAL_EUROPEAN = 1;      // Every 1 execution
    private static final int SCAN_INTERVAL_OVERLAP_AS_EU = 2; // Every 2 executions (e.g., 2 min)
    private static final int SCAN_INTERVAL_ASIAN = 2;         // Every 2 executions (e.g., 2 min)
    private static final int SCAN_INTERVAL_QUIET = 3;         // Every 3 executions (e.g., 3 min)

    private static final String VOLATILITY_CHECK_EXCHANGE = "BYBIT";
    private static final String VOLATILITY_CHECK_INTERVAL = "1h";
    private static final int VOLATILITY_ATR_PERIOD = 14;
    private static final double HIGH_VOLATILITY_ATR_THRESHOLD_PERCENT = 0.5; // Example: ATR > 0.5% of price is high vol

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private HedgingService hedgingService;

    @Autowired
    public StrategyExecutionService(MexcApiClientService mexcApiClientService,
                                  BybitApiClientService bybitApiClientService,
                                  BybitFuturesApiService bybitFuturesApiService,
                                  @Lazy TradingStrategyService tradingStrategyService,
                                  RiskManagementService riskManagementService,
                                  OrderManagementService orderManagementService,
                                  PositionCacheService positionCacheService,
                                  MarketScannerService marketScannerService) {
        this.mexcApiClientService = mexcApiClientService;
        this.bybitApiClientService = bybitApiClientService;
        this.bybitFuturesApiService = bybitFuturesApiService;
        this.tradingStrategyService = tradingStrategyService;
        this.riskManagementService = riskManagementService;
        this.orderManagementService = orderManagementService;
        this.positionCacheService = positionCacheService;
        this.marketScannerService = marketScannerService;

        // Initialize a ThreadPoolExecutor for the Sniper Strategy
        // Consider making corePoolSize dynamic based on sniperStrategySymbols.size() after properties are injected
        // For now, this initialization might happen before @Value fields are populated.
        // We might need to move this to an @PostConstruct method or make pool size updatable.
        // For simplicity, let's assume a reasonable fixed size or handle it later if issues arise.
        int initialCorePoolSize = 4; // Default or placeholder
        this.sniperStrategyExecutor = new ThreadPoolExecutor(
            initialCorePoolSize, 
            initialCorePoolSize * 2,
            60L, 
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        logger.info("Initialized Sniper Strategy Executor with core pool size: {}", initialCorePoolSize);
    }
    
    @PostConstruct
    public void init() {
        logger.info("Initializing StrategyExecutionService");
        
        if (this.sniperStrategyId != null) {
            this.sniperStrategyId = this.sniperStrategyId.trim();
        }
        if (this.defaultStrategyId != null) {
            this.defaultStrategyId = this.defaultStrategyId.trim();
        }

        logger.info("Cleaned up strategy IDs. Sniper ID: '{}', Default ID: '{}'", sniperStrategyId, defaultStrategyId);

        // loadActivePositions(); // Positions now primarily managed by PositionCacheService
        loadActiveOrders(); 
    }
    
    /**
     * Execute trading strategy for a given symbol, exchange, and strategy ID.
     */
    public void executeStrategy(String symbol, String exchange, String strategyIdToExecute) {
        try {
            logger.info("Executing strategy {} for {} on {}", strategyIdToExecute, symbol, exchange);
            
            TradingStrategy strategy = tradingStrategyService.getStrategy(strategyIdToExecute);
            
            if (strategy == null) {
                logger.warn("No strategy found with ID: {} for symbol: {} on exchange: {}", strategyIdToExecute, symbol, exchange);
                return;
            }
            
            PositionCacheService.PositionUpdateData activePosition = positionCacheService.getPosition(symbol);
            BigDecimal accountBalance = getAccountBalance(exchange); // Simplified call, needs robust implementation
            
            if (activePosition == null || activePosition.getSize() == null || activePosition.getSize().compareTo(BigDecimal.ZERO) == 0) {
                logger.debug("No active position for {}, evaluating entry for strategy {}", symbol, strategy.getName());

                // Determine intended side based on MarketScannerService signal
                String intendedSide = null;
                String intervalForStrategy = getDefaultIntervalForStrategy(strategy);
                ScanResult latestScan = null;

                if (sniperStrategyId.equals(strategyIdToExecute)) {
                    // For Sniper, we need to get a fresh scan result to determine side
                    // This assumes scanSinglePair is suitable and available; if not, another method might be needed.
                    ScanConfig scanConfig = new ScanConfig();
                    scanConfig.setTradingPairs(Collections.singletonList(symbol));
                    scanConfig.setInterval(intervalForStrategy);
                    scanConfig.setExchange(exchange);
                    scanConfig.setMarketType(ScanConfig.MarketType.valueOf(determineMarketType(symbol, exchange).toUpperCase())); // Ensure MarketType enum matches
                    scanConfig.setIncludeAiAnalysis(false); // Don't need full AI for this side check
                    
                    // Calling scanMarket for a single pair. Consider if a more direct scanSinglePair is better if available publicly.
                    // For now, using the public scanMarket method which should internally handle single pair if list is size 1.
                    List<ScanResult> scanResults = marketScannerService.scanMarket(scanConfig);
                    if (scanResults != null && !scanResults.isEmpty()) {
                        latestScan = scanResults.get(0);
                    }
                } else {
                    // For other strategies, how is the signal/side determined?
                    // This part needs to be defined. For now, we might have to assume a default or skip.
                    logger.warn("Side determination for strategy '{}' not yet implemented. Defaulting to BUY for evaluation.", strategyIdToExecute);
                    // Fallback to a default for non-sniper, or make this an error/skip
                    intendedSide = "BUY"; // Placeholder - this needs proper design for other strategies
                }

                if (latestScan != null && latestScan.getSignal() != null && sniperStrategyId.equals(strategyIdToExecute)) {
                    String marketSignal = latestScan.getSignal().toUpperCase();
                    if (marketSignal.contains("BUY")) {
                        intendedSide = "BUY";
                    } else if (marketSignal.contains("SELL")) {
                        intendedSide = "SELL";
                    }
                    logger.info("SNIPER: Determined intended side from MarketScannerService for {} as: {}. Market Signal: {}", symbol, intendedSide, marketSignal);
                } else if (sniperStrategyId.equals(strategyIdToExecute)) {
                    logger.warn("SNIPER: Could not get a market signal for {} to determine side. Skipping entry evaluation.", symbol);
                    return; // Cannot proceed for Sniper without a clear side from scanner
                }
                // If intendedSide is still null here for non-sniper, it means the placeholder logic above didn't set it.

                if (intendedSide == null) {
                    logger.warn("Could not determine intended side for {} using strategy {}. Skipping entry evaluation.", symbol, strategyIdToExecute);
                    return;
                }

                // Now call evaluateEntry with the determined side
                String signalTier = SniperEntryStrategy.NO_SIGNAL; // Changed from boolean
                if (strategy instanceof SniperEntryStrategy && sniperStrategyId.equals(strategyIdToExecute)) {
                    signalTier = ((SniperEntryStrategy) strategy).evaluateEntry(symbol, intervalForStrategy, intendedSide);
                } else {
                    // Fallback for other strategies that might not have the 3-param evaluateEntry yet
                    // Or, enforce that all strategies must have it.
                    // This will cause a type mismatch if the interface is String but other strategies still return boolean.
                    // For now, assuming this path is less critical or will be updated separately.
                    logger.warn("Strategy '{}' does not use evaluateEntry with side or may not return String. Using default evaluateEntry. THIS NEEDS REVIEW.", strategy.getName());
                    // String tempSignal = strategy.evaluateEntry(symbol, intervalForStrategy); // This line would be problematic if old strategies return boolean
                    // Forcing a call to the base interface method, hoping it's updated or this path isn't hit with boolean-returning strategies:
                    signalTier = strategy.evaluateEntry(symbol, intervalForStrategy); 
                    if (signalTier == null || (!signalTier.contains("TIER") && !signalTier.equals(SniperEntryStrategy.NO_SIGNAL))) {
                        logger.error("Strategy {} returned an unexpected signal tier: {}. Defaulting to NO_SIGNAL.", strategy.getName(), signalTier);
                        signalTier = SniperEntryStrategy.NO_SIGNAL;
                    }
                }

                if (!SniperEntryStrategy.NO_SIGNAL.equals(signalTier) && signalTier != null) { 
                    logger.info("Signal '{}' received for {} {} by strategy {}. Calculating position size.", signalTier, intendedSide, symbol, strategy.getName());
                    BigDecimal positionSize = strategy.calculatePositionSize(symbol, accountBalance);
                    
                    if (positionSize == null || positionSize.compareTo(BigDecimal.ZERO) <= 0) {
                        logger.warn("Calculated position size for {} is zero or null. Skipping trade.", symbol);
                        return;
                    }

                    if (riskManagementService.validateTrade(symbol, positionSize, exchange, intendedSide, accountBalance)) {
                        int leverage = getLeverageForStrategy(strategy, symbol);

                        // === Set Margin Mode for Sniper on Bybit Futures ===
                        String marketTypeStr = determineMarketType(symbol, exchange);
                        if (sniperStrategyId.equals(strategy.getName()) && "BYBIT".equalsIgnoreCase(exchange) && 
                            (ScanConfig.MarketType.LINEAR.getValue().equalsIgnoreCase(marketTypeStr) || 
                             ScanConfig.MarketType.FUTURES.getValue().equalsIgnoreCase(marketTypeStr))) {
                            try {
                                String leverageStr = String.valueOf(leverage);
                                String setResult = bybitApiClientService.setLeverage(symbol, leverageStr, 1); 
                                JsonNode resultNode = objectMapper.readTree(setResult);
                                boolean marginSet = resultNode.has("retCode") && resultNode.get("retCode").asInt() == 0;
                                
                                if (marginSet) {
                                    logger.info("SNIPER: Successfully set ISOLATED margin and leverage {} for {}", leverage, symbol);
                                } else {
                                    logger.warn("SNIPER: Failed to set ISOLATED margin for {} with leverage {}. Proceeding with caution.", symbol, leverage);
                                    // Consider if this should be a critical failure that stops the trade
                                }
                            } catch (Exception e) {
                                logger.error("SNIPER: Error setting ISOLATED margin for {} with leverage {}: {}. Proceeding with caution.", 
                                    symbol, leverage, e.getMessage());
                                // Consider if this should be a critical failure
                            }
                        }
                        // === End Set Margin Mode ===

                        BigDecimal currentPriceForSL = null;
                        ResponseEntity<String> priceResponse = null;
                        try {
                            if ("BYBIT".equalsIgnoreCase(exchange)) {
                                priceResponse = bybitApiClientService.getLatestPrice(symbol, exchange); // Call specific client
                            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                                priceResponse = mexcApiClientService.getLatestPrice(symbol, exchange); // Call specific client
                            } else {
                                logger.warn("Unsupported exchange {} for fetching price for SL calc.", exchange);
                            }

                            if (priceResponse != null && priceResponse.getStatusCode().is2xxSuccessful() && priceResponse.getBody() != null) {
                                JsonNode root = objectMapper.readTree(priceResponse.getBody());
                                currentPriceForSL = ExchangeUtil.parsePriceFromResponse(root, exchange, symbol);
                                if (currentPriceForSL == null) {
                                    logger.warn("Could not parse currentPriceForSL for {} on {} from response: {}", symbol, exchange, priceResponse.getBody());
                                }
                            } else if (priceResponse != null) {
                                logger.warn("Failed to fetch price for SL calc on {}-{}. Status: {}, Body: {}", symbol, exchange, priceResponse.getStatusCode(), priceResponse.getBody());
                            } else if (priceResponse == null && isExchangeSupportedForPriceFetch(exchange)){
                                logger.warn("Price response was null for a supported exchange {} for symbol {}",exchange,symbol);
                            }
                        } catch (Exception e) {
                            logger.error("Error fetching current price for SL calculation for {}-{}: {}", symbol, exchange, e.getMessage(), e);
                        }

                        if (currentPriceForSL == null) {
                            logger.error("Failed to get current price for {} on {} to set initial stop loss. Aborting order.", symbol, exchange);
                            return; // Cannot set SL without a reference price
                        }

                        // Calculate initial stop loss using the strategy-specific method
                        String intervalForSL = getDefaultIntervalForStrategy(strategy); // Use the same interval as evaluateEntry
                        BigDecimal initialStopLoss = strategy.getInitialStopLossPrice(symbol, intendedSide, currentPriceForSL, exchange, intervalForSL);

                        OrderRequest entryOrderRequest = new OrderRequest();
                        entryOrderRequest.setSymbol(symbol);
                        entryOrderRequest.setSide(intendedSide); 
                        entryOrderRequest.setType("MARKET"); 
                        entryOrderRequest.setQuantity(positionSize);
                        entryOrderRequest.setLeverage(leverage); 
                        entryOrderRequest.setBotId("StrategyExecutionService");
                        entryOrderRequest.setStrategyName(strategy.getName());
                        entryOrderRequest.setMarketType(determineMarketType(symbol, exchange));
                        entryOrderRequest.setExchange(exchange); // Set the exchange on the order request

                        if (initialStopLoss != null && initialStopLoss.compareTo(BigDecimal.ZERO) > 0) {
                            entryOrderRequest.setStopLossPrice(initialStopLoss);
                            logger.info("Initial ATR-based Stop Loss for {} (side {}) on {} set to: {}", 
                                symbol, intendedSide, exchange, initialStopLoss);
                        } else {
                            logger.warn("Could not calculate a valid initial ATR stop loss for {}. Order will be placed without SL through API (if supported).", symbol);
                            // Consider if a fallback percentage SL should be set here if ATR fails, or if no SL is acceptable
                        }

                        logger.info("Placing entry order for {}: {} {} units, Leverage: {}, Strategy: {}, Initial SL: {}, Exchange: {}", 
                            symbol, entryOrderRequest.getSide(), entryOrderRequest.getQuantity(), leverage, strategy.getName(), initialStopLoss, exchange);
                        
                        Order placedOrder = orderManagementService.placeOrder(entryOrderRequest, exchange);
                        if (placedOrder != null && placedOrder.getOrderId() != null) {
                            logger.info("Entry order placed successfully for {}. Order ID: {}", symbol, placedOrder.getOrderId());
                        } else {
                            logger.error("Failed to place entry order for {}.", symbol);
                        }
                    } else {
                        logger.info("Trade validation failed by RiskManagementService for entry on {}.", symbol);
                    }
                }
            } else {
                logger.debug("Active position found for {}, evaluating exit for strategy {}", symbol, strategy.getName());
                if (strategy.evaluateExit(symbol, getDefaultIntervalForStrategy(strategy))) { 
                    logger.info("Exit condition met for {} by strategy {}. Placing exit order.", symbol, strategy.getName());
                    
                    String exitSide = "Buy".equalsIgnoreCase(activePosition.getSide()) ? "SELL" : "BUY";
                    BigDecimal exitQuantity = activePosition.getSize(); 

                    OrderRequest exitOrderRequest = new OrderRequest();
                    exitOrderRequest.setSymbol(symbol);
                    exitOrderRequest.setSide(exitSide);
                    exitOrderRequest.setType("MARKET"); 
                    exitOrderRequest.setQuantity(exitQuantity);
                    exitOrderRequest.setBotId("StrategyExecutionService");
                    exitOrderRequest.setStrategyName(strategy.getName());
                    exitOrderRequest.setMarketType(determineMarketType(symbol, exchange));
                    exitOrderRequest.setLeverage(activePosition.getLeverage() != null ? activePosition.getLeverage() : 1); // Default to 1 if null

                    logger.info("Placing exit order for {}: {} {} units, Strategy: {}", 
                        symbol, exitOrderRequest.getSide(), exitOrderRequest.getQuantity(), strategy.getName());

                    Order placedOrder = orderManagementService.placeOrder(exitOrderRequest, exchange);
                     if (placedOrder != null && placedOrder.getOrderId() != null) {
                        logger.info("Exit order placed successfully for {}. Order ID: {}", symbol, placedOrder.getOrderId());
                    } else {
                        logger.error("Failed to place exit order for {}.", symbol);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error executing strategy {} for {}: {}", strategyIdToExecute, symbol, e.getMessage(), e);
        }
    }

    private String getDefaultIntervalForStrategy(TradingStrategy strategy) {
        if (strategy.getName().equals(sniperStrategyId)) { 
            return "1h"; 
        }
        return "1h"; 
    }

    private int getLeverageForStrategy(TradingStrategy strategy, String symbol) {
        if (strategy.getName().equals(sniperStrategyId) && strategy instanceof SniperEntryStrategy) {
            SniperEntryStrategy sniperStrategy = (SniperEntryStrategy) strategy;
            Map<String, Object> config = sniperStrategy.getConfiguration();
            String tier = sniperStrategy.getCurrentSignalTier(symbol); 
            if ("tier1".equals(tier)) return (int) config.getOrDefault("tier1Leverage", 25);
            if ("tier2".equals(tier)) return (int) config.getOrDefault("tier2Leverage", 40);
            if ("tier3".equals(tier)) return (int) config.getOrDefault("tier3Leverage", 75);
            logger.warn("Unknown tier {} for Sniper strategy on symbol {}, defaulting to tier3 leverage.", tier, symbol);
            return (int) config.getOrDefault("tier3Leverage", 75); 
        }
        return 1; 
    }
    
    private String determineMarketType(String symbol, String exchange) {
        if ("BYBIT".equalsIgnoreCase(exchange) && symbol.endsWith("USDT")) {
            return "linear"; 
        }
        return "spot"; 
    }
    
    /**
     * Get market data from the specified exchange
     */
    private Map<String, Object> getMarketData(String symbol, String exchange) {
        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                return getBybitMarketData(symbol);
            } else {
                return getMexcMarketData(symbol);
            }
        } catch (Exception e) {
            logger.error("Error getting market data for {} from {}: {}", symbol, exchange, e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * Get market data from Bybit
     */
    private Map<String, Object> getBybitMarketData(String symbol) throws JsonProcessingException {
        Map<String, Object> marketData = new HashMap<>();
        try {
            ResponseEntity<String> response = bybitApiClientService.getLatestPrice(symbol, "BYBIT");
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode data = objectMapper.readTree(response.getBody());
                // Assuming 'result.list[0].lastPrice' for Bybit V5 ticker
                if (data.hasNonNull("result") && data.get("result").hasNonNull("list") &&
                    data.get("result").get("list").isArray() && !data.get("result").get("list").isEmpty()) {
                    JsonNode tickerInfo = data.get("result").get("list").get(0);
                    if (tickerInfo.hasNonNull("lastPrice")) {
                        marketData.put("price", new BigDecimal(tickerInfo.get("lastPrice").asText()));
                    }
                    // Add other relevant fields like bid/ask if needed
                    if (tickerInfo.hasNonNull("bid1Price")) {
                        marketData.put("bidPrice", new BigDecimal(tickerInfo.get("bid1Price").asText()));
                    }
                    if (tickerInfo.hasNonNull("ask1Price")) {
                        marketData.put("askPrice", new BigDecimal(tickerInfo.get("ask1Price").asText()));
                    }
                }
            } else {
                 logger.warn("Failed to get market data from Bybit for {}. Status: {}, Body: {}", symbol, response.getStatusCodeValue(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error getting market data from Bybit for {}: {}", symbol, e.getMessage(), e);
        }
        return marketData;
    }
    
    /**
     * Get market data from MEXC
     */
    private Map<String, Object> getMexcMarketData(String symbol) throws JsonProcessingException {
        Map<String, Object> marketData = new HashMap<>();
        try {
            ResponseEntity<String> response = mexcApiClientService.getLatestPrice(symbol, "MEXC");
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode data = objectMapper.readTree(response.getBody());
                // MEXC structure might be root.get("price") or similar, or an array root.get(0).get("price")
                if (data.has("price")) {
                    marketData.put("price", new BigDecimal(data.get("price").asText()));
                } else if (data.isArray() && data.size() > 0 && data.get(0).has("price")) {
                    marketData.put("price", new BigDecimal(data.get(0).get("price").asText()));
                }
                // Add other relevant fields if available and needed
            } else {
                 logger.warn("Failed to get market data from MEXC for {}. Status: {}, Body: {}", symbol, response.getStatusCodeValue(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("Error getting market data from MEXC for {}: {}", symbol, e.getMessage(), e);
        }
        return marketData;
    }
    
    /**
     * Get account balance from the specified exchange
     */
    private BigDecimal getAccountBalance(String exchange) {
        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                return getBybitAccountBalance();
            } else {
                return getMexcAccountBalance();
            }
        } catch (Exception e) {
            logger.error("Error getting account balance from {}: {}", exchange, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * Get account balance from Bybit
     */
    private BigDecimal getBybitAccountBalance() throws Exception {
        String walletBalance = bybitApiClientService.getSpotWalletBalance("USDT");
        // Parse response and extract balance
        // Implementation depends on Bybit API response format
        return new BigDecimal("0.0"); // Placeholder
    }
    
    /**
     * Get account balance from MEXC
     */
    private BigDecimal getMexcAccountBalance() throws Exception {
        String walletBalance = mexcApiClientService.getFuturesWalletBalance("USDT");
        // Parse response and extract balance
        // Implementation depends on MEXC API response format
        return new BigDecimal("0.0"); // Placeholder
    }
    
    /**
     * Place an order on the specified exchange
     */
    private void placeOrder(OrderRequest request, String exchange) {
        try {
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                placeBybitOrder(request);
            } else {
                placeMexcOrder(request);
            }
        } catch (Exception e) {
            logger.error("Error placing order on {}: {}", exchange, e.getMessage());
        }
    }
    
    /**
     * Place an order on Bybit
     */
    private void placeBybitOrder(OrderRequest request) throws Exception {
        // Implementation for placing orders on Bybit
        ResponseEntity<String> response = bybitApiClientService.placeOrder(
            request.getSymbol(),
            request.getSide(),
            request.getType(),
            request.getQuantity(),
            request.getPrice(),
            UUID.randomUUID().toString()
        );
        
        // Update active orders cache if order was successful
        if (response.getStatusCode().is2xxSuccessful()) {
            updateActiveOrders();
        } else {
            throw new RuntimeException("Failed to place order: " + response.getBody());
        }
    }
    
    /**
     * Place an order on MEXC
     */
    private void placeMexcOrder(OrderRequest request) throws Exception {
        // Implementation for placing orders on MEXC
        String response = mexcApiClientService.placeOrder(
            request.getSymbol(),
            request.getSide(),
            request.getType(),
            request.getQuantity(),
            request.getPrice()
        );
        
        // Update active orders cache
        updateActiveOrders();
    }
    
    /**
     * Load active positions from exchanges
     */
    private void loadActivePositions() {
        // This method might be deprecated if PositionCacheService handles loading from persistence/exchange
        logger.info("loadActivePositions called, but positions are primarily managed by PositionCacheService.");
    }
    
    /**
     * Load active orders from exchanges
     */
    private void loadActiveOrders() {
        try {
            // Load orders from Bybit
            String bybitOrders = bybitApiClientService.getCurrentOpenOrders(null, "linear").getBody();
            // Parse and update activeOrders map
            
            // Load orders from MEXC
            // Implementation depends on MEXC API
            
        } catch (Exception e) {
            logger.error("Error loading active orders: {}", e.getMessage());
        }
    }
    
    /**
     * Update active orders cache
     */
    private void updateActiveOrders() {
        loadActiveOrders();
    }
    
    /**
     * Update active positions cache
     */
    private void updateActivePositions() {
        // This method might be deprecated if PositionCacheService and OrderManagementService handle updates
        logger.info("updateActivePositions called, but position updates are primarily managed by PositionCacheService and OrderManagementService.");
    }
    
    // @Scheduled(fixedRate = 60000) // Run every minute - This is replaced by new schedulers
    // public void scheduledExecution() {
    //     logger.info("Running scheduled strategy execution...");
    //     List<String> symbolsToMonitor = getConfiguredSymbols();
    //     for (String symbol : symbolsToMonitor) {
    //         String exchange = determineExchangeForSymbol(symbol); 
    //         String strategyId = getStrategyIdForSymbol(symbol, exchange);
    //         if (exchange != null && strategyId != null) {
    //             try {
    //                 executeStrategy(symbol, exchange, strategyId); // Updated call
    //             } catch (Exception e) {
    //                 logger.error("Error during scheduled execution for symbol {}: {}", symbol, e.getMessage(), e);
    //             }
    //         } else {
    //             if (exchange == null) logger.warn("Could not determine exchange for symbol {}, skipping execution.", symbol);
    //             if (strategyId == null) logger.warn("Could not determine strategy for symbol {}, skipping execution.", symbol);
    //         }
    //     }
    // }

    @Scheduled(fixedRateString = "${tradingbot.sniper.schedule.fixedRateMs:60000}") 
    public void scheduledSniperStrategyExecution() {
        if (!isSniperStrategyActive) {
            // logger.trace("Sniper strategy is not active, skipping scheduled execution."); // Optional: for very frequent checks
            return;
        }
        logger.info("Scheduled Sniper Strategy Execution triggered (Active: {}).", isSniperStrategyActive);

        LocalDateTime now = LocalDateTime.now();
        MarketSession currentSession = getCurrentMarketSession();
        sniperScanCycleCounter++; // Increment cycle counter

        int currentRequiredScanInterval;
        switch (currentSession) {
            case OVERLAP_EUROPE_US:
                currentRequiredScanInterval = SCAN_INTERVAL_OVERLAP_EU_US;
                break;
            case US:
                currentRequiredScanInterval = SCAN_INTERVAL_US;
                break;
            case EUROPEAN:
                currentRequiredScanInterval = SCAN_INTERVAL_EUROPEAN;
                break;
            case OVERLAP_ASIA_EUROPE:
                currentRequiredScanInterval = SCAN_INTERVAL_OVERLAP_AS_EU;
                break;
            case ASIAN:
                currentRequiredScanInterval = SCAN_INTERVAL_ASIAN;
                break;
            case QUIET:
            default:
                currentRequiredScanInterval = SCAN_INTERVAL_QUIET;
                break;
        }

        if (sniperScanCycleCounter % currentRequiredScanInterval != 0) {
            logger.debug("Sniper scan cycle {}/{}. Current session: {}. Skipping this cycle.", 
                         (sniperScanCycleCounter % currentRequiredScanInterval), currentRequiredScanInterval, currentSession);
            return; 
        }
        // Resetting counter for this specific interval logic is not strictly needed if using modulo,
        // but if we reset sniperScanCycleCounter = 0 here, the next cycle would be 1 % interval.
        // For simplicity, let the counter grow and use modulo.

        logger.info("Proceeding with Sniper scan for session: {}. Cycle check: {} % {} == 0.", 
                     currentSession, sniperScanCycleCounter, currentRequiredScanInterval);

        logger.info("Running scheduled Sniper strategy execution for symbols: {}", sniperStrategySymbols);
        for (String symbol : sniperStrategySymbols) {
            final String exchangeForSniper = "BYBIT"; 
            sniperStrategyExecutor.submit(() -> {
                try {
                    logger.info("Sniper task started for {} on exchange {}", symbol, exchangeForSniper);
                    executeStrategy(symbol, exchangeForSniper, sniperStrategyId);
                    logger.info("Sniper task finished for {} on exchange {}", symbol, exchangeForSniper);
                } catch (Exception e) {
                    logger.error("Error during threaded execution of Sniper strategy for symbol {}: {}", symbol, e.getMessage(), e);
                }
            });
        }
        logExecutorStatus(); 
    }

    @Scheduled(fixedRateString = "${tradingbot.default.schedule.fixedRateMs:300000}") 
    public void scheduledDefaultStrategyExecution() {
        logger.info("Running scheduled default strategy execution for symbols: {}", defaultStrategySymbols);

        if (defaultStrategySymbols.isEmpty()) {
            logger.info("No symbols configured for default strategy execution.");
            return;
        }

        for (String symbol : defaultStrategySymbols) {
            String exchange = determineExchangeForSymbol(symbol, false); // false indicating it's for default strategy
            if (exchange == null) {
                 logger.warn("Could not determine exchange for default symbol {}, skipping.", symbol);
                 continue;
            }

            String strategyIdToExecute = getStrategyIdForSymbol(symbol, exchange);

            if (strategyIdToExecute != null) {
                try {
                    executeStrategy(symbol, exchange, strategyIdToExecute);
                } catch (Exception e) {
                    logger.error("Error during scheduled execution for symbol {}: {}", symbol, e.getMessage(), e);
                }
            } else {
                logger.warn("Could not determine strategy for symbol {}, exchange {}, skipping default execution.", symbol, exchange);
            }
        }
    }

    private void logExecutorStatus() {
        if (sniperStrategyExecutor instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) sniperStrategyExecutor;
            logger.debug("Sniper Executor Status: Active Threads: {}, Pool Size: {}, Queue Size: {}, Completed Tasks: {}",
                tpe.getActiveCount(), tpe.getPoolSize(), tpe.getQueue().size(), tpe.getCompletedTaskCount());
        }
    }
    
    private String determineExchangeForSymbol(String symbol, boolean isSniperSymbol) {
        if (isSniperSymbol) {
            // Sniper symbols are assumed to be Bybit linear futures based on current setup.
            // This could be made configurable per sniper symbol if needed in the future.
            // e.g., tradingbot.sniper.strategy.exchange.SYMBOL=EXCHANGE
            return "BYBIT"; 
        } else {
            // For default symbols, use the specific map or a general default.
            String exchange = defaultSymbolExchangeMap.get(symbol.toUpperCase());
            if (exchange != null) {
                logger.info("Determined exchange for default symbol {} from config: {}", symbol, exchange);
                return exchange;
            } else {
                // Fallback logic if not in map
                String inferredExchange = ExchangeUtil.determineExchangeForSymbol(symbol);
                if (inferredExchange != null) {
                    logger.warn("Exchange for non-sniper symbol {} not explicitly defined in 'tradingbot.default.strategy.exchange.map'. Inferred by ExchangeUtil as {} (needs review if incorrect).", symbol, inferredExchange);
                    return inferredExchange;
                }
                // If ExchangeUtil also returns null, then apply a hard default and warn significantly.
                logger.warn("Exchange for non-sniper symbol {} not in config map and ExchangeUtil could not determine it. Defaulting to BYBIT. THIS REQUIRES CONFIGURATION REVIEW.", symbol);
                return "BYBIT"; 
            }
        }
    }

    private String getStrategyIdForSymbol(String symbol, String exchange) {
        // Check Sniper strategy symbols first
        if (sniperStrategySymbols != null && sniperStrategySymbols.stream().anyMatch(s -> s.equalsIgnoreCase(symbol))) {
            logger.debug("Symbol {} matched for Sniper Strategy (ID: {})", symbol, sniperStrategyId);
            return sniperStrategyId;
        }

        // Check Default strategy symbols
        if (defaultStrategySymbols != null && defaultStrategySymbols.stream().anyMatch(s -> s.equalsIgnoreCase(symbol))) {
             // Ensure the exchange also aligns if we have per-symbol exchange configs for default strategy
            String configuredExchange = defaultSymbolExchangeMap.get(symbol.toUpperCase());
            if (configuredExchange != null && !configuredExchange.equalsIgnoreCase(exchange)) {
                logger.warn("Symbol {} is configured for default strategy on exchange {} but current context is {}. Mismatch.", 
                            symbol, configuredExchange, exchange);
                // Decide if this is an error or if we allow it. For now, let's be strict.
                return null; 
            }
            logger.debug("Symbol {} matched for Default Strategy (ID: {}) on exchange {}", symbol, defaultStrategyId, exchange);
            return defaultStrategyId;
        }
        
        // TODO: Add more sophisticated mapping if needed, e.g., from a properties map:
        // @Value("#{${tradingbot.strategy.symbol.map:{}}}") 
        // private Map<String, String> symbolStrategyMap;
        // String mappedStrategy = symbolStrategyMap.get(symbol.toUpperCase() + "_" + exchange.toUpperCase());
        // if (mappedStrategy != null) return mappedStrategy;

        logger.warn("Could not determine a specific strategy ID for symbol {} on exchange {}. No strategy will be executed.", symbol, exchange);
        return null; 
    }
    
    private List<String> getConfiguredSymbols() {
        Set<String> allSymbols = new HashSet<>();
        if (sniperStrategySymbols != null) {
            allSymbols.addAll(sniperStrategySymbols);
        }
        if (defaultStrategySymbols != null) {
            allSymbols.addAll(defaultStrategySymbols);
        }
        // Add any other sources of symbols if necessary
        return new ArrayList<>(allSymbols);
    }

    public void analyzeMarket(String symbol, String exchange) {
        try {
            Map<String, Object> marketData = getMarketData(symbol, exchange);
            BigDecimal currentPrice = new BigDecimal(marketData.get("price").toString());
            BigDecimal quantity = calculatePositionSize(symbol, currentPrice);
            
            String intendedSide = "Buy"; // Assuming "Buy" for analyzeMarket, this might need context
            BigDecimal accountBalance = getAccountBalance(exchange);
            boolean isValid = riskManagementService.validateTrade(symbol, quantity, exchange, intendedSide, accountBalance);
            
            // ... rest of the method ...
        } catch (Exception e) {
            logger.error("Error analyzing market: {}", e.getMessage());
        }
    }

    private void executeBuyOrder(String symbol, BigDecimal quantity, BigDecimal price, String exchange) {
        try {
            String clientOrderId = UUID.randomUUID().toString();
            
            if ("BYBIT".equals(exchange)) {
                bybitApiClientService.placeOrder(symbol, "BUY", "LIMIT", quantity, price, clientOrderId);
            } else {
                mexcApiClientService.placeOrder(symbol, "BUY", "LIMIT", quantity, price, clientOrderId);
            }
        } catch (Exception e) {
            logger.error("Error executing buy order: {}", e.getMessage());
        }
    }

    public void processBuySignal(String symbol, BigDecimal strength, String exchange) {
        try {
            // Get current price
            BigDecimal currentPrice = getCurrentPrice(symbol, exchange);
            
            // Calculate position size
            BigDecimal stopLoss = riskManagementService.calculateStopLossPrice(symbol, currentPrice, "LONG");
            String marketType = determineMarketType(symbol, exchange);
            BigDecimal positionSize = riskManagementService.calculatePositionSize(symbol, currentPrice, stopLoss, marketType, exchange);
            
            // Validate the trade
            BigDecimal accountBalance = getAccountBalance(exchange);
            if (riskManagementService.validateTrade(symbol, positionSize, exchange, "Buy", accountBalance)) {
                // Calculate take profit
                BigDecimal takeProfit = riskManagementService.calculateTakeProfitPrice(
                    currentPrice, stopLoss, "LONG", BigDecimal.valueOf(2.0)
                );
                
                // Create order
                createBuyOrder(symbol, positionSize, currentPrice, stopLoss, takeProfit, exchange);
            }
        } catch (Exception e) {
            logger.error("Error processing buy signal for {}: {}", symbol, e.getMessage());
        }
    }
    
    /**
     * Get execution settings
     */
    public Map<String, Object> getExecutionSettings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("maxActivePositions", 5);
        settings.put("maxRiskPerTrade", 0.02);
        settings.put("defaultTimeframe", "1h");
        settings.put("autoTrading", false);
        return settings;
    }
    
    /**
     * Update execution settings
     */
    public Map<String, Object> updateExecutionSettings(Map<String, Object> newSettings) {
        // In a real implementation, you would persist these settings
        Map<String, Object> settings = getExecutionSettings();
        settings.putAll(newSettings);
        logger.info("Updated execution settings: {}", settings);
        return settings;
    }
    
    /**
     * Start trading with a specific strategy
     */
    public Map<String, Object> startTrading(String strategyId, String symbol, String interval) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Add symbol to active trading list
            activeSymbols.put(symbol, strategyId);
            
            // Execute initial strategy evaluation
            String exchange = determineExchangeForSymbol(symbol, true); // true indicating it's for sniper strategy
            if (exchange == null) {
                 exchange = "BYBIT"; // Default to BYBIT if not determinable, or handle error
                 logger.warn("Could not determine exchange for symbol {} in startTrading. Defaulting to {}.", symbol, exchange);
            }
            executeStrategy(symbol, exchange, strategyId);
            
            result.put("success", true);
            result.put("message", "Started trading " + symbol + " with strategy " + strategyId);
            result.put("symbol", symbol);
            result.put("strategyId", strategyId);
            result.put("interval", interval);
            
            logger.info("Started trading {} with strategy {}", symbol, strategyId);
            
        } catch (Exception e) {
            logger.error("Error starting trading for {}: {}", symbol, e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Stop trading a symbol
     */
    public Map<String, Object> stopTrading(String symbol) {
        Map<String, Object> result = new HashMap<>();
        try {
            // Remove symbol from active trading
            String removedStrategy = activeSymbols.remove(symbol);
            
            if (removedStrategy != null) {
                result.put("success", true);
                result.put("message", "Stopped trading " + symbol);
                result.put("symbol", symbol);
                result.put("strategyId", removedStrategy);
                
                logger.info("Stopped trading {}", symbol);
            } else {
                result.put("success", false);
                result.put("message", "Symbol not actively trading: " + symbol);
            }
            
        } catch (Exception e) {
            logger.error("Error stopping trading for {}: {}", symbol, e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Get active trading strategies
     */
    public Map<String, String> getActiveStrategies() {
        return new HashMap<>(activeSymbols);
    }
    
    /**
     * Get current price for a symbol
     */
    private BigDecimal getCurrentPrice(String symbol, String exchange) throws Exception {
        Map<String, Object> marketData;
        if ("BYBIT".equalsIgnoreCase(exchange)) {
            marketData = getBybitMarketData(symbol);
        } else if ("MEXC".equalsIgnoreCase(exchange)) {
            marketData = getMexcMarketData(symbol);
        } else {
            throw new IllegalArgumentException("Unsupported exchange: " + exchange);
        }
        
        if (marketData.containsKey("price")) {
            return (BigDecimal) marketData.get("price");
        }
        throw new RuntimeException("Failed to get current price for " + symbol + " on " + exchange);
    }
    
    /**
     * Create a buy order
     */
    private void createBuyOrder(String symbol, BigDecimal positionSize, BigDecimal currentPrice, 
                               BigDecimal stopLoss, BigDecimal takeProfit, String exchange) {
        try {
            String clientOrderId = UUID.randomUUID().toString();
            
            if ("BYBIT".equals(exchange)) {
                bybitApiClientService.placeOrder(symbol, "BUY", "LIMIT", positionSize, currentPrice, clientOrderId);
            } else {
                mexcApiClientService.placeOrder(symbol, "BUY", "LIMIT", positionSize, currentPrice, clientOrderId);
            }
            
            logger.info("Created buy order for {} at {} with size {}", symbol, currentPrice, positionSize);
            
        } catch (Exception e) {
            logger.error("Error creating buy order for {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to create buy order", e);
        }
    }
    
    /**
     * Calculate position size for a symbol
     */
    private BigDecimal calculatePositionSize(String symbol, BigDecimal currentPrice) {
        // Simple position sizing - use 100 USDT worth
        BigDecimal usdtAmount = new BigDecimal("100.00");
        return usdtAmount.divide(currentPrice, 6, RoundingMode.DOWN);
    }
    
    // Add a map to track active trading symbols
    private final Map<String, String> activeSymbols = new ConcurrentHashMap<>();

    private boolean isValidInterval(String interval) {
        return Arrays.asList("5m", "15m", "30m", "1h", "4h", "1d").contains(interval);
    }

    private enum MarketSession {
        ASIAN, EUROPEAN, US, OVERLAP_ASIA_EUROPE, OVERLAP_EUROPE_US, QUIET
    }

    private MarketSession getCurrentMarketSession() {
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
        int hour = nowUtc.getHour();

        boolean asianOpen = hour >= 0 && hour < 9;    // 00:00 - 08:59 UTC
        boolean europeanOpen = hour >= 7 && hour < 16;  // 07:00 - 15:59 UTC
        boolean usOpen = hour >= 13 && hour < 22;     // 13:00 - 21:59 UTC

        if (europeanOpen && usOpen) return MarketSession.OVERLAP_EUROPE_US;
        if (asianOpen && europeanOpen) return MarketSession.OVERLAP_ASIA_EUROPE;
        if (usOpen) return MarketSession.US;
        if (europeanOpen) return MarketSession.EUROPEAN;
        if (asianOpen) return MarketSession.ASIAN;
        
        return MarketSession.QUIET;
    }

    private boolean isExchangeSupportedForPriceFetch(String exchange) {
        // Implement the logic to check if the exchange is supported for price fetching
        // This is a placeholder and should be replaced with the actual implementation
        return true; // Placeholder return, actual implementation needed
    }

    private boolean isMarketVolatile() {
        // Placeholder for actual volatility check
        // For now, let's implement a basic check using ATR of BTCUSDT
        try {
            BigDecimal currentPrice = getCurrentPrice(VOLATILITY_CHECK_SYMBOL, VOLATILITY_CHECK_EXCHANGE);
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("Could not get current price for {} to check market volatility. Assuming low volatility.", VOLATILITY_CHECK_SYMBOL);
                return false;
            }

            BigDecimal atrValue = riskManagementService.calculateATRForSymbol(
                VOLATILITY_CHECK_SYMBOL, 
                VOLATILITY_CHECK_EXCHANGE, 
                VOLATILITY_CHECK_INTERVAL, // Using a common interval like 1h for general volatility
                VOLATILITY_ATR_PERIOD
            );

            if (atrValue != null && atrValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal atrPercentage = atrValue.divide(currentPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                logger.debug("Market volatility check: {} ATR {}%, Threshold {}%", 
                    VOLATILITY_CHECK_SYMBOL, atrPercentage, HIGH_VOLATILITY_ATR_THRESHOLD_PERCENT);
                return atrPercentage.doubleValue() > HIGH_VOLATILITY_ATR_THRESHOLD_PERCENT;
            } else {
                logger.warn("Could not calculate ATR for {} to check market volatility. Assuming low volatility.", VOLATILITY_CHECK_SYMBOL);
            }
        } catch (Exception e) {
            logger.error("Error checking market volatility for {}: {}. Assuming low volatility.", VOLATILITY_CHECK_SYMBOL, e.getMessage());
        }
        return false; // Default to low volatility on error or no data
    }

    public void startSniperStrategy() {
        if (!isSniperStrategyActive) {
            isSniperStrategyActive = true;
            logger.info("SniperEntryStrategy has been ACTIVATED globally.");
            // Optionally, trigger an immediate run if desired, though scheduler will pick it up
            // scheduledSniperStrategyExecution(); 
        } else {
            logger.info("SniperEntryStrategy is already active.");
        }
    }

    public void stopSniperStrategy() {
        if (isSniperStrategyActive) {
            isSniperStrategyActive = false;
            logger.info("SniperEntryStrategy has been DEACTIVATED globally.");
            // Optionally, interrupt any ongoing sniper tasks if the executor allows
            // For now, relies on the flag check at the start of scheduledSniperStrategyExecution
        } else {
            logger.info("SniperEntryStrategy is already inactive.");
        }
    }

    // Getter for Sniper Strategy ID
    public String getSniperStrategyId() {
        return sniperStrategyId;
    }

    // Getter for Sniper Strategy Symbols
    public List<String> getSniperStrategySymbols() {
        return sniperStrategySymbols;
    }

    // Getter for Default Strategy ID
    public String getDefaultStrategyId() {
        return defaultStrategyId;
    }

    // Getter for Default Strategy Symbols
    public List<String> getDefaultStrategySymbols() {
        return defaultStrategySymbols;
    }

    @Scheduled(fixedDelay = 60000) // Every 1 minute
    public void evaluateHedging() {
        try {
            hedgingService.evaluateHedgingOpportunities();
        } catch (Exception e) {
            logger.error("Error in scheduled hedging evaluation: {}", e.getMessage());
        }
    }
}