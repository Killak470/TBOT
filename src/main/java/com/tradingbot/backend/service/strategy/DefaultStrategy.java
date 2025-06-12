package com.tradingbot.backend.service.strategy;

import com.tradingbot.backend.model.Candlestick;
import com.tradingbot.backend.service.MarketDataService;
import com.tradingbot.backend.service.OrderManagementService;
import com.tradingbot.backend.service.RiskManagementService;
import com.tradingbot.backend.service.cache.PositionCacheService;
import com.tradingbot.backend.service.cache.PositionCacheService.PositionUpdateData;
import com.tradingbot.backend.service.util.TechnicalAnalysisUtil; // For potential future use

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DefaultStrategy implements TradingStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DefaultStrategy.class);
    private static final double SECURE_PROFIT_PERCENTAGE = 0.30; // 30%
    private static final double DEFAULT_STOP_LOSS_PERCENT = 0.05; // Default 5% initial SL

    private final MarketDataService marketDataService;
    private final PositionCacheService positionCacheService;
    private final RiskManagementService riskManagementService; // Kept for potential future use (e.g., ATR)
    private final OrderManagementService orderManagementService; // Kept for potential future use

    private Map<String, Object> configuration;

    @Autowired
    public DefaultStrategy(MarketDataService marketDataService,
                           PositionCacheService positionCacheService,
                           RiskManagementService riskManagementService,
                           OrderManagementService orderManagementService) {
        this.marketDataService = marketDataService;
        this.positionCacheService = positionCacheService;
        this.riskManagementService = riskManagementService;
        this.orderManagementService = orderManagementService;
        this.configuration = new HashMap<>();
        loadDefaultConfiguration();
    }

    private void loadDefaultConfiguration() {
        // Add any default strategy-specific configurations here if needed in the future
        configuration.put("defaultStopLossPercent", DEFAULT_STOP_LOSS_PERCENT);
        logger.info("DefaultStrategy: Loaded default configuration.");
    }
    @Override
    public String getId() {
        return "DefaultStrategy";
    }
    
    @Override
    public String evaluateEntry(String symbol, String interval) {
        logger.info("DefaultStrategy: Evaluating ENTRY for {} on interval {}. No active entry logic by default.",
                    symbol, interval);
        // Default strategy might rely on external signals or manual entry,
        // or a very simple AI confirmation if AIAnalysisService was injected.
        return "HOLD"; // Does not actively generate entry signals by itself.
    }

    @Override
    public boolean evaluateExit(String symbol, String interval) {
        logger.debug("DefaultStrategy: Evaluating EXIT for {} on interval {}", symbol, interval);

        PositionUpdateData positionInfo = positionCacheService.getPosition(symbol);
        if (positionInfo == null || positionInfo.getSize() == null || positionInfo.getSize().compareTo(BigDecimal.ZERO) == 0) {
            logger.debug("DefaultStrategy: No active position for {} to evaluate exit.", symbol);
            return false;
        }

        String exchange = positionInfo.getExchange() != null ? positionInfo.getExchange() : "BYBIT"; // Default if not set
        List<Candlestick> klines = fetchCandlestickData(symbol, interval, 2, exchange);
        if (klines.isEmpty()) {
            logger.warn("DefaultStrategy: Could not fetch current price data for {} on exchange {}. Cannot evaluate exit.", symbol, exchange);
            return false;
        }
        BigDecimal currentPrice = BigDecimal.valueOf(klines.get(klines.size() - 1).getClose());

        BigDecimal activeStopLoss = positionInfo.getStrategyStopLossPrice();
        boolean isPt1Taken = positionInfo.isFirstProfitTargetTaken(); // Though DefaultStrategy doesn't use PT1 currently
        boolean isSecureSlApplied = positionInfo.isSecureProfitSlApplied();

        // --- Secure 30% Profit Stop Loss Adjustment ---
        if (!isSecureSlApplied && positionInfo.getEntryPrice() != null && positionInfo.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal entryPrice = positionInfo.getEntryPrice();
            BigDecimal secureProfitSlCandidate = null;
            boolean targetReached = false;

            if ("Buy".equalsIgnoreCase(positionInfo.getSide())) { // LONG position
                BigDecimal profitTargetPrice = entryPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(SECURE_PROFIT_PERCENTAGE)));
                if (currentPrice.compareTo(profitTargetPrice) >= 0) {
                    secureProfitSlCandidate = profitTargetPrice;
                    targetReached = true;
                    logger.info("DefaultStrategy ({}): Secure Profit (30%) target for LONG reached. Current: {}, Entry: {}, Target SL: {}",
                                symbol, currentPrice, entryPrice, secureProfitSlCandidate);
                }
            } else if ("Sell".equalsIgnoreCase(positionInfo.getSide())) { // SHORT position
                BigDecimal profitTargetPrice = entryPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(SECURE_PROFIT_PERCENTAGE)));
                if (currentPrice.compareTo(profitTargetPrice) <= 0) {
                    secureProfitSlCandidate = profitTargetPrice;
                    targetReached = true;
                    logger.info("DefaultStrategy ({}): Secure Profit (30%) target for SHORT reached. Current: {}, Entry: {}, Target SL: {}",
                                symbol, currentPrice, entryPrice, secureProfitSlCandidate);
                }
            }

            if (targetReached && secureProfitSlCandidate != null) {
                boolean applyThisSl = false;
                if (activeStopLoss == null || activeStopLoss.compareTo(BigDecimal.ZERO) == 0) {
                    applyThisSl = true;
                } else {
                    if ("Buy".equalsIgnoreCase(positionInfo.getSide()) && secureProfitSlCandidate.compareTo(activeStopLoss) > 0) {
                        applyThisSl = true;
                    } else if ("Sell".equalsIgnoreCase(positionInfo.getSide()) && secureProfitSlCandidate.compareTo(activeStopLoss) < 0) {
                        applyThisSl = true;
                    }
                }

                if (applyThisSl) {
                    logger.info("DefaultStrategy ({}): Applying Secure Profit SL at {}. Previous SL was: {}",
                                symbol, secureProfitSlCandidate, activeStopLoss);
                    activeStopLoss = secureProfitSlCandidate; 
                    isSecureSlApplied = true; 
                    positionCacheService.updateStrategyPositionInfo(symbol, activeStopLoss, isPt1Taken, isSecureSlApplied);
                } else {
                     logger.info("DefaultStrategy ({}): Secure Profit SL candidate {} not applied as it's not better than current SL {}.",
                                symbol, secureProfitSlCandidate, activeStopLoss);
                }
            }
        }
        
        // --- Basic Stop Loss Hit Check ---
        if (activeStopLoss != null && activeStopLoss.compareTo(BigDecimal.ZERO) != 0) {
            if ("Buy".equalsIgnoreCase(positionInfo.getSide())) {
                if (currentPrice.compareTo(activeStopLoss) <= 0) {
                    logger.info("DefaultStrategy: Stop loss hit for LONG {}. Current: {}, SL: {}",
                                symbol, currentPrice, activeStopLoss);
                    return true; // Signal exit
                }
            } else if ("Sell".equalsIgnoreCase(positionInfo.getSide())) {
                if (currentPrice.compareTo(activeStopLoss) >= 0) {
                    logger.info("DefaultStrategy: Stop loss hit for SHORT {}. Current: {}, SL: {}",
                                symbol, currentPrice, activeStopLoss);
                    return true; // Signal exit
                }
            }
        }
        return false; // No exit conditions met
    }

    @Override
    public String getName() {
        return "Default Strategy";
    }

    @Override
    public String getDescription() {
        return "A default strategy with basic stop-loss management including a 30% secure profit SL adjustment.";
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return new HashMap<>(this.configuration);
    }

    @Override
    public void updateConfiguration(Map<String, Object> newConfig) {
        if (newConfig != null) {
            newConfig.forEach((key, value) -> {
                if (this.configuration.containsKey(key)) {
                    this.configuration.put(key, value);
                    logger.info("DefaultStrategy: Updated configuration for {}: {} = {}", getName(), key, value);
                } else {
                    logger.warn("DefaultStrategy: Attempted to update unknown configuration key '{}' for {}", key, getName());
                }
            });
        }
    }

    @Override
    public BigDecimal calculatePositionSize(String symbol, BigDecimal accountBalance) {
        logger.warn("DefaultStrategy: calculatePositionSize not fully implemented. Returning zero size. This should be configured or implemented based on risk parameters.");
        // For a real scenario, this would involve risk % per trade, leverage, SL distance, etc.
        return BigDecimal.ZERO; 
    }

    @Override
    public BigDecimal getInitialStopLossPrice(String symbol, String side, BigDecimal entryPrice, String exchange, String interval) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("DefaultStrategy: Cannot calculate initial SL for {} due to invalid entry price: {}", symbol, entryPrice);
            return null;
        }
        double stopLossPercent = (double) configuration.getOrDefault("defaultStopLossPercent", DEFAULT_STOP_LOSS_PERCENT);
        BigDecimal slOffset = entryPrice.multiply(BigDecimal.valueOf(stopLossPercent));

        if ("BUY".equalsIgnoreCase(side)) {
            return entryPrice.subtract(slOffset).setScale(entryPrice.scale(), RoundingMode.HALF_DOWN);
        } else if ("SELL".equalsIgnoreCase(side)) {
            return entryPrice.add(slOffset).setScale(entryPrice.scale(), RoundingMode.HALF_UP);
        } else {
            logger.warn("DefaultStrategy: Unknown side '{}' for initial SL calculation for {}.", side, symbol);
            return null;
        }
    }
    
    private List<Candlestick> fetchCandlestickData(String symbol, String interval, int limit, String exchange) {
        List<Candlestick> klines = marketDataService.getKlines(symbol, interval, limit, exchange);
        if (klines == null || klines.isEmpty()) {
            logger.warn("DefaultStrategy (fetchCandlestickData): Kline data from MarketDataService is null or empty for {}-{} on {}, limit {}", 
                symbol, interval, exchange, limit);
            return Collections.emptyList(); // Return empty list to avoid NullPointerExceptions further down
        }
        return klines;
    }
} 