package com.tradingbot.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.Order;
import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.model.PotentialTrade;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.repository.BotSignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BotSignalService {
    
    private static final Logger logger = LoggerFactory.getLogger(BotSignalService.class);
    
    private final BotSignalRepository botSignalRepository;
    private final OrderManagementService orderManagementService;
    private final RiskManagementService riskManagementService;
    private final ObjectMapper objectMapper;
    
    public BotSignalService(BotSignalRepository botSignalRepository,
                           OrderManagementService orderManagementService,
                           RiskManagementService riskManagementService,
                           ObjectMapper objectMapper) {
        this.botSignalRepository = botSignalRepository;
        this.orderManagementService = orderManagementService;
        this.riskManagementService = riskManagementService;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Generate a new bot signal from strategy analysis
     */
    public BotSignal generateSignal(String symbol, BotSignal.SignalType signalType, String strategyName,
                                  BigDecimal entryPrice, BigDecimal quantity, BigDecimal confidence,
                                  String rationale) {
        try {
            // Extract exchange from strategy name
            String exchange = null;
            String signalMarketType = "spot"; // Default to spot
            
            if (strategyName != null) {
                if (strategyName.toUpperCase().contains("BYBIT")) {
                    exchange = "BYBIT";
                    if (strategyName.toUpperCase().contains("LINEAR")) {
                        signalMarketType = "linear";
                    }
                } else if (strategyName.toUpperCase().contains("MEXC")) {
                    exchange = "MEXC";
                    if (strategyName.toUpperCase().contains("FUTURES")) {
                        signalMarketType = "futures";
                    }
                }
            }
            
            if (exchange == null) {
                logger.warn("Could not determine exchange from strategy name: {}", strategyName);
                exchange = "BYBIT"; // Default to Bybit
            }
            
            // Get account balance and max leverage
            BigDecimal accountBalance = BigDecimal.ZERO;
            BigDecimal maxLeverage = BigDecimal.ONE;
            
            if ("BYBIT".equals(exchange)) {
                if ("linear".equals(signalMarketType)) {
                    accountBalance = orderManagementService.getBybitFuturesApiService().getAvailableBalance();
                    maxLeverage = BigDecimal.valueOf(orderManagementService.getBybitFuturesApiService().getMaxLeverage(symbol));
                } else {
                    accountBalance = orderManagementService.getBybitSpotApiService().getAvailableBalance();
                }
            } else if ("MEXC".equals(exchange)) {
                if ("futures".equals(signalMarketType)) {
                    accountBalance = orderManagementService.getMexcFuturesApiService().getAvailableBalance();
                    maxLeverage = orderManagementService.getMexcFuturesApiService().getMaxLeverage(symbol);
                } else {
                    accountBalance = orderManagementService.getMexcApiClientService().getAvailableBalance();
                }
            }
            
            // Create and save the signal
            BotSignal signal = new BotSignal(symbol, signalType, strategyName, entryPrice, quantity, confidence, rationale);
            signal.setMarketType(signalMarketType);
            signal.setExchange(exchange);
            signal.setAccountBalance(accountBalance);
            signal.setMaxLeverage(maxLeverage);
            signal.setGeneratedAt(LocalDateTime.now());
            signal.setStatus(BotSignal.SignalStatus.PENDING);
            
            return botSignalRepository.save(signal);
            
        } catch (Exception e) {
            logger.error("Error generating signal: {}", e.getMessage());
            throw new RuntimeException("Failed to generate signal", e);
        }
    }
    
    /**
     * Get all pending signals waiting for approval
     */
    public List<BotSignal> getPendingSignals() {
        return botSignalRepository.findByStatusOrderByGeneratedAtDesc(BotSignal.SignalStatus.PENDING);
    }
    
    /**
     * Get signals by status
     */
    public List<BotSignal> getSignalsByStatus(BotSignal.SignalStatus status) {
        return botSignalRepository.findByStatusOrderByGeneratedAtDesc(status);
    }
    
    /**
     * Get recent signals (last 24 hours)
     */
    public List<BotSignal> getRecentSignals() {
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        return botSignalRepository.findSignalsSince(since);
    }
    
    /**
     * Approve a signal for execution
     */
    @Transactional
    public boolean approveSignal(Long signalId, String approvedBy) {
        Optional<BotSignal> signalOpt = botSignalRepository.findById(signalId);
        
        if (signalOpt.isEmpty()) {
            logger.warn("Signal not found: {}", signalId);
            return false;
        }
        
        BotSignal signal = signalOpt.get();
        
        if (signal.getStatus() != BotSignal.SignalStatus.PENDING) {
            logger.warn("Signal {} is not in PENDING status: {}", signalId, signal.getStatus());
            return false;
        }
        
        signal.setStatus(BotSignal.SignalStatus.APPROVED);
        signal.setProcessedAt(LocalDateTime.now());
        signal.setProcessedBy(approvedBy);
        
        botSignalRepository.save(signal);
        
        // Attempt to execute the signal immediately
        executeApprovedSignal(signal);
        
        logger.info("Signal {} approved by {} and queued for execution", signalId, approvedBy);
        return true;
    }
    
    /**
     * Reject a signal
     */
    @Transactional
    public boolean rejectSignal(Long signalId, String rejectedBy, String reason) {
        Optional<BotSignal> signalOpt = botSignalRepository.findById(signalId);
        
        if (signalOpt.isEmpty()) {
            logger.warn("Signal not found: {}", signalId);
            return false;
        }
        
        BotSignal signal = signalOpt.get();
        
        if (signal.getStatus() != BotSignal.SignalStatus.PENDING) {
            logger.warn("Signal {} is not in PENDING status: {}", signalId, signal.getStatus());
            return false;
        }
        
        signal.setStatus(BotSignal.SignalStatus.REJECTED);
        signal.setProcessedAt(LocalDateTime.now());
        signal.setProcessedBy(rejectedBy);
        signal.setRejectionReason(reason);
        
        botSignalRepository.save(signal);
        
        logger.info("Signal {} rejected by {}: {}", signalId, rejectedBy, reason);
        return true;
    }
    
    /**
     * Execute an approved signal by placing an order
     */
    @Transactional
    public void executeApprovedSignal(BotSignal signal) {
        try {
            // Set leverage for futures before placing order
            if (signal.getMarketType() == ScanConfig.MarketType.LINEAR || signal.getMarketType() == ScanConfig.MarketType.FUTURES) {
                if ("BYBIT".equals(signal.getExchange())) {
                    orderManagementService.getBybitFuturesApiService().setLeverage(
                        signal.getSymbol(), signal.getMaxLeverage().intValue());
                } else if ("MEXC".equals(signal.getExchange())) {
                    orderManagementService.getMexcFuturesApiService().setLeverage(
                        signal.getSymbol(), signal.getMaxLeverage());
                }
            }
            
            // Place the order
            String orderId = null;
            if ("BYBIT".equals(signal.getExchange())) {
                if (signal.getMarketType() == ScanConfig.MarketType.LINEAR || signal.getMarketType() == ScanConfig.MarketType.FUTURES) {
                    // Place futures order with stop loss and take profit
                    orderId = orderManagementService.getBybitFuturesApiService().placeFuturesOrder(
                        signal.getSymbol(),
                        signal.getSignalType() == BotSignal.SignalType.BUY ? "Buy" : "Sell",
                        "LIMIT",
                        signal.getQuantity().toString(),
                        signal.getEntryPrice().toString(),
                        signal.getStopLoss() != null ? signal.getStopLoss().toString() : null,
                        signal.getTakeProfit() != null ? signal.getTakeProfit().toString() : null,
                        "GTC", // Default to GTC for timeInForce
                        "bot_signal_" + signal.getId() + "_" + System.currentTimeMillis(), // Use signal ID + timestamp as orderLinkId
                        null // No trigger price for regular orders
                    );
                    if (orderId != null) {
                        logger.info("Placed Bybit futures order with ID: {}", orderId);
                    }
                } else {
                    // Place spot order
                    orderId = orderManagementService.getBybitSpotApiService().placeOrder(
                        signal.getSymbol(),
                        signal.getSignalType() == BotSignal.SignalType.BUY ? "Buy" : "Sell",
                        "LIMIT",
                        signal.getQuantity(),
                        signal.getEntryPrice()
                    );
                }
            } else {
                if (signal.getMarketType() == ScanConfig.MarketType.FUTURES || signal.getMarketType() == ScanConfig.MarketType.LINEAR) {
                    // Place MEXC futures order with stop loss and take profit
                    orderId = orderManagementService.getMexcFuturesApiService().placeOrder(
                        signal.getSymbol(),
                        signal.getSignalType() == BotSignal.SignalType.BUY ? "BUY" : "SELL",
                        "LIMIT",
                        signal.getQuantity(),
                        signal.getEntryPrice(),
                        signal.getStopLoss(),
                        signal.getTakeProfit()
                    );
                } else {
                    // Place MEXC spot order
                    orderId = orderManagementService.getMexcApiClientService().placeOrder(
                        signal.getSymbol(),
                        signal.getSignalType() == BotSignal.SignalType.BUY ? "BUY" : "SELL",
                        "LIMIT",
                        signal.getQuantity(),
                        signal.getEntryPrice()
                    );
                }
            }
            
            if (orderId != null) {
                signal.setOrderId(orderId);
                signal.setStatus(BotSignal.SignalStatus.EXECUTED);
                signal.setExecutedAt(LocalDateTime.now());
                botSignalRepository.save(signal);
                logger.info("Signal {} executed with order ID: {}", signal.getId(), orderId);
            } else {
                signal.setStatus(BotSignal.SignalStatus.FAILED);
                signal.setFailureReason("Failed to get order ID from exchange");
                botSignalRepository.save(signal);
                logger.error("Failed to execute signal {}: No order ID received", signal.getId());
            }
            
        } catch (Exception e) {
            logger.error("Error executing signal {}: {}", signal.getId(), e.getMessage());
            signal.setStatus(BotSignal.SignalStatus.FAILED);
            signal.setFailureReason(e.getMessage());
            botSignalRepository.save(signal);
        }
    }
    
    /**
     * Calculate risk/reward metrics for a signal
     */
    private void calculateRiskRewardMetrics(BotSignal signal) {
        if (signal.getStopLoss() != null && signal.getTakeProfit() != null) {
            BigDecimal entryPrice = signal.getEntryPrice();
            BigDecimal stopLoss = signal.getStopLoss();
            BigDecimal takeProfit = signal.getTakeProfit();
            
            BigDecimal potentialLoss = entryPrice.subtract(stopLoss).abs();
            BigDecimal potentialProfit = takeProfit.subtract(entryPrice).abs();
            
            if (potentialLoss.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal riskRewardRatio = potentialProfit.divide(potentialLoss, 2, RoundingMode.HALF_UP);
                signal.setRiskRewardRatio(riskRewardRatio);
            }
            
            if (signal.getQuantity() != null) {
                signal.setPotentialLoss(potentialLoss.multiply(signal.getQuantity()));
                signal.setPotentialProfit(potentialProfit.multiply(signal.getQuantity()));
            }
        }
    }
    
    /**
     * Cleanup expired signals (scheduled task)
     */
    @Scheduled(fixedRate = 3600000) // Run every hour
    @Transactional
    public void cleanupExpiredSignals() {
        try {
            LocalDateTime expiredBefore = LocalDateTime.now().minusHours(24); // Signals expire after 24 hours
            List<BotSignal> expiredSignals = botSignalRepository.findExpiredSignals(
                expiredBefore, BotSignal.SignalStatus.PENDING);
            
            for (BotSignal signal : expiredSignals) {
                signal.setStatus(BotSignal.SignalStatus.EXPIRED);
                signal.setProcessedAt(LocalDateTime.now());
                signal.setProcessedBy("SYSTEM");
                signal.setRejectionReason("Signal expired after 24 hours");
            }
            
            if (!expiredSignals.isEmpty()) {
                botSignalRepository.saveAll(expiredSignals);
                logger.info("Marked {} signals as expired", expiredSignals.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cleaning up expired signals", e);
        }
    }
    
    /**
     * Get signal statistics
     */
    public SignalStats getSignalStats() {
        return new SignalStats(
            botSignalRepository.countByStatus(BotSignal.SignalStatus.PENDING),
            botSignalRepository.countByStatus(BotSignal.SignalStatus.APPROVED),
            botSignalRepository.countByStatus(BotSignal.SignalStatus.EXECUTED),
            botSignalRepository.countByStatus(BotSignal.SignalStatus.REJECTED),
            botSignalRepository.countByStatus(BotSignal.SignalStatus.EXPIRED)
        );
    }
    
    /**
     * Save or update a bot signal
     */
    public BotSignal save(BotSignal signal) {
        return botSignalRepository.save(signal);
    }
    
    /**
     * Creates a new signal, enriches it with execution data, saves it as APPROVED, 
     * and immediately attempts execution.
     */
    @Transactional
    public BotSignal createAndExecuteSignal(BotSignal signal) {
        try {
            // 1. Enrich the signal with required data for execution
            String exchange = signal.getExchange();
            String symbol = signal.getSymbol();
            String marketType = signal.getMarketType().toString();

            BigDecimal accountBalance = BigDecimal.ZERO;
            BigDecimal maxLeverage = BigDecimal.ONE; // Default for spot

            if ("BYBIT".equalsIgnoreCase(exchange)) {
                if ("LINEAR".equalsIgnoreCase(marketType)) {
                    accountBalance = orderManagementService.getBybitFuturesApiService().getAvailableBalance();
                    maxLeverage = BigDecimal.valueOf(orderManagementService.getBybitFuturesApiService().getMaxLeverage(symbol));
                } else {
                    accountBalance = orderManagementService.getBybitSpotApiService().getAvailableBalance();
                }
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                if ("FUTURES".equalsIgnoreCase(marketType) || "LINEAR".equalsIgnoreCase(marketType)) {
                    accountBalance = orderManagementService.getMexcFuturesApiService().getAvailableBalance();
                    maxLeverage = orderManagementService.getMexcFuturesApiService().getMaxLeverage(symbol);
                } else {
                    accountBalance = orderManagementService.getMexcApiClientService().getAvailableBalance();
                }
            }

            signal.setAccountBalance(accountBalance);
            signal.setMaxLeverage(maxLeverage);
            logger.info("Enriched signal for {}: Account Balance={}, Max Leverage={}", symbol, accountBalance, maxLeverage);

            // 2. Calculate position size (quantity)
            BigDecimal positionSize = riskManagementService.calculatePositionSize(
                symbol,
                signal.getEntryPrice(),
                signal.getStopLoss(),
                marketType,
                exchange
            );

            if (positionSize == null || positionSize.compareTo(BigDecimal.ZERO) <= 0) {
                String warning = String.format("Calculated position size for %s is zero or null. Aborting execution.", symbol);
                logger.warn(warning);
                signal.setStatus(BotSignal.SignalStatus.FAILED);
                signal.setFailureReason(warning);
                return botSignalRepository.save(signal);
            }
            signal.setQuantity(positionSize);
            logger.info("Calculated Position Size for {}: {}", symbol, positionSize);

            // 3. Set final status and save before execution attempt
            signal.setStatus(BotSignal.SignalStatus.APPROVED);
            signal.setProcessedAt(LocalDateTime.now());
            signal.setProcessedBy("USER_SCANNER");

            BotSignal savedSignal = botSignalRepository.save(signal);
            logger.info("Signal {} created from user selection and marked as APPROVED.", savedSignal.getId());
            
            // 4. Attempt to execute it.
            executeApprovedSignal(savedSignal);
            
            // Return the signal in its final state after the execution attempt
            return savedSignal;

        } catch (Exception e) {
            logger.error("Error during createAndExecuteSignal for signal {}: {}", signal.getSymbol(), e.getMessage(), e);
            signal.setStatus(BotSignal.SignalStatus.FAILED);
            signal.setFailureReason(e.getMessage());
            // We still want to save the signal to record the failure
            return botSignalRepository.save(signal);
        }
    }
    
    @Transactional
    public BotSignal createSignalFromPotentialTrade(PotentialTrade trade) {
        BotSignal.SignalType signalType = "BUY".equalsIgnoreCase(trade.getSide()) ? BotSignal.SignalType.BUY : BotSignal.SignalType.SELL;

        BotSignal signal = new BotSignal();
        signal.setSymbol(trade.getSymbol());
        signal.setSignalType(signalType);
        signal.setStrategyName(trade.getTitle());
        signal.setEntryPrice(trade.getEntryPrice());
        signal.setStopLoss(trade.getStopLoss());
        signal.setTakeProfit(trade.getTakeProfit());
        signal.setConfidence(BigDecimal.valueOf(trade.getConfidence()));
        signal.setRationale(trade.getRationale());
        signal.setExchange(trade.getExchange());
        signal.setMarketType(trade.getMarketType().toUpperCase());
        signal.setGeneratedAt(LocalDateTime.now());
        signal.setStatus(BotSignal.SignalStatus.PENDING);
        
        // Quantity will be calculated later by risk management when the order is placed
        signal.setQuantity(BigDecimal.ZERO);

        return botSignalRepository.save(signal);
    }
    
    public static class SignalStats {
        public final long pending;
        public final long approved;
        public final long executed;
        public final long rejected;
        public final long expired;
        
        public SignalStats(long pending, long approved, long executed, long rejected, long expired) {
            this.pending = pending;
            this.approved = approved;
            this.executed = executed;
            this.rejected = rejected;
            this.expired = expired;
        }
    }
} 