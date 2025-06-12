package com.tradingbot.backend.service;

import com.tradingbot.backend.model.OrderRequest;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.model.SentimentData;
import com.tradingbot.backend.model.TechnicalIndicator;
import com.tradingbot.backend.service.cache.PositionCacheService;
import com.tradingbot.backend.service.cache.PositionCacheService.PositionUpdateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class HedgingService {

    private static final Logger logger = LoggerFactory.getLogger(HedgingService.class);

    @Autowired
    private PositionCacheService positionCacheService;
    
    @Autowired
    private OrderManagementService orderManagementService;
    
    @Autowired
    private AIAnalysisService aiAnalysisService;
    
    @Autowired
    private MarketRegimeService marketRegimeService;
    
    @Autowired
    private MarketScannerService marketScannerService;
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private SignalWeightingService signalWeightingService;

    @Autowired
    private InstrumentInfoService instrumentInfoService;

    // Track hedge relationships
    private final Map<String, HedgePosition> activeHedges = new ConcurrentHashMap<>();
    
    // Track last hedge evaluation per symbol to avoid over-hedging
    private final Map<String, Instant> lastHedgeEvaluation = new ConcurrentHashMap<>();
    
    // Minimum time between hedge evaluations (5 minutes)
    private static final long HEDGE_COOLDOWN_MINUTES = 5;

    // Predefined strong positive correlations (Symbol -> List of correlated symbols)
    // TODO: Populate this map with relevant correlations for your trading strategy
    private static final Map<String, List<String>> PREDEFINED_CORRELATIONS = Map.of(
        // Example:
        // "BTCUSDT", List.of("ETHUSDT", "SOLUSDT"),
        // "ETHUSDT", List.of("BTCUSDT", "MATICUSDT", "OPUSDT"),
        // "SOLUSDT", List.of("BTCUSDT", "BONKUSDT", "JUPUSDT")
    );

    /**
     * Hedge Position Data Structure
     */
    public static class HedgePosition {
        private String originalSymbol;
        private String hedgeSymbol;
        private String originalSide;
        private String hedgeSide;
        private BigDecimal originalSize;
        private BigDecimal hedgeSize;
        private BigDecimal hedgeRatio; // What percentage of original position is hedged
        private HedgeType hedgeType;
        private HedgeReason hedgeReason;
        private Instant createdAt;
        private BigDecimal triggerPrice;
        private String exchange;
        private boolean isActive;
        
        // Constructors, getters, setters...
        public HedgePosition(String originalSymbol, String hedgeSymbol, String originalSide, 
                           String hedgeSide, BigDecimal originalSize, BigDecimal hedgeSize,
                           BigDecimal hedgeRatio, HedgeType hedgeType, HedgeReason hedgeReason,
                           BigDecimal triggerPrice, String exchange) {
            this.originalSymbol = originalSymbol;
            this.hedgeSymbol = hedgeSymbol;
            this.originalSide = originalSide;
            this.hedgeSide = hedgeSide;
            this.originalSize = originalSize;
            this.hedgeSize = hedgeSize;
            this.hedgeRatio = hedgeRatio;
            this.hedgeType = hedgeType;
            this.hedgeReason = hedgeReason;
            this.triggerPrice = triggerPrice;
            this.exchange = exchange;
            this.createdAt = Instant.now();
            this.isActive = true;
        }
        
        // Getters and setters...
        public String getOriginalSymbol() { return originalSymbol; }
        public String getHedgeSymbol() { return hedgeSymbol; }
        public String getOriginalSide() { return originalSide; }
        public String getHedgeSide() { return hedgeSide; }
        public BigDecimal getOriginalSize() { return originalSize; }
        public BigDecimal getHedgeSize() { return hedgeSize; }
        public BigDecimal getHedgeRatio() { return hedgeRatio; }
        public HedgeType getHedgeType() { return hedgeType; }
        public HedgeReason getHedgeReason() { return hedgeReason; }
        public Instant getCreatedAt() { return createdAt; }
        public BigDecimal getTriggerPrice() { return triggerPrice; }
        public String getExchange() { return exchange; }
        public boolean isActive() { return isActive; }
        
        public void setActive(boolean active) { this.isActive = active; }
        public void setHedgeSize(BigDecimal hedgeSize) { this.hedgeSize = hedgeSize; }
        public void setHedgeRatio(BigDecimal hedgeRatio) { this.hedgeRatio = hedgeRatio; }
    }

    public enum HedgeType {
        DIRECT_OPPOSITE,    // Same symbol, opposite side (uses positionIdx for Bybit)
        CORRELATION_HEDGE,  // Different correlated symbol
        PORTFOLIO_HEDGE,    // Hedge overall portfolio exposure
        DYNAMIC_HEDGE       // Adjustable hedge ratio based on conditions
    }

    public enum HedgeReason {
        MARKET_REGIME_CHANGE,  // Market regime shifted against position
        HIGH_UNREALIZED_LOSS,  // Position moving significantly against us
        AI_SIGNAL_REVERSAL,    // AI analysis suggests reversal (old general AI)
        AI_HEDGE_ADVISORY,     // AI explicitly advises hedging the active position (new specific AI)
        VOLATILITY_SPIKE,      // High volatility detected
        PORTFOLIO_RISK,        // Overall portfolio risk too high
        TIME_BASED,            // Position held too long in adverse conditions
        CORRELATION_RISK       // High correlation risk detected
    }

    /**
     * Main method to evaluate and execute hedging for all positions
     */
    public void evaluateHedgingOpportunities() {
        logger.info("Starting hedging evaluation for all positions");
        
        List<PositionUpdateData> allPositions = positionCacheService.getAllPositions();
        
        for (PositionUpdateData position : allPositions) {
            if (shouldEvaluatePosition(position)) {
                evaluatePositionForHedging(position);
            }
        }
        
        // Also evaluate existing hedges for adjustment/closure
        evaluateExistingHedges();
    }

    /**
     * Evaluate a specific position for hedging needs
     */
    public void evaluatePositionForHedging(PositionUpdateData position) {
        String symbol = position.getSymbol();
        logger.info("HEDGING_TRACE [{}]: Starting evaluation.", symbol);

        try {
            // Check cooldown
            if (isInCooldown(symbol)) {
                logger.info("HEDGING_TRACE [{}]: Evaluation in cooldown. Last eval: {}", symbol, lastHedgeEvaluation.get(symbol));
                return;
            }
            
            // Skip if position is too small or already closed
            if (position.getSize() == null || position.getSize().compareTo(BigDecimal.valueOf(0.01)) < 0) {
                logger.info("HEDGING_TRACE [{}]: Position size ({}) is too small or null. Skipping.", symbol, position.getSize());
                return;
            }
            
            logger.info("HEDGING_TRACE [{}]: Position size {} is valid. Proceeding with analysis.", symbol, position.getSize());
            
            // Get current market analysis
            HedgeAnalysis analysis = analyzeHedgingNeed(position);
            logger.info("HEDGING_TRACE [{}]: Hedging analysis complete. ShouldHedge: {}, PrimaryReason: {}, MaxConfidence: {}", 
                        symbol, analysis.shouldHedge(), analysis.getPrimaryReason(), analysis.getMaxConfidence());
            
            if (analysis.shouldHedge()) {
                logger.info("HEDGING_TRACE [{}]: Conditions met to hedge. PrimaryReason: {}", symbol, analysis.getPrimaryReason());
                executeHedge(position, analysis);
            } else {
                logger.info("HEDGING_TRACE [{}]: Conditions NOT met to hedge.", symbol);
            }
            
            // Update last evaluation time
            lastHedgeEvaluation.put(symbol, Instant.now());
            
        } catch (Exception e) {
            logger.error("HEDGING_TRACE [{}]: Error during hedging evaluation: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Analyze if a position needs hedging
     */
    private HedgeAnalysis analyzeHedgingNeed(PositionUpdateData position) {
        String symbol = position.getSymbol();
        String exchange = position.getExchange() != null ? position.getExchange() : "BYBIT"; // Default exchange if null
        logger.info("HEDGING_TRACE [{}]: Starting analyzeHedgingNeed. Side: {}, Entry: {}, Size: {}, InitialMargin: {}", 
                    symbol, position.getSide(), position.getEntryPrice(), position.getSize(), position.getInitialMargin());
        HedgeAnalysis analysis = new HedgeAnalysis();
        
        BigDecimal pnlPercentageAgainstMargin = BigDecimal.ZERO;
        BigDecimal pnlPercentageAgainstNotional = BigDecimal.ZERO;

        try {
            // 1. Check unrealized PnL risk
            BigDecimal unrealizedPnl = position.getUnrealisedPnl();
            BigDecimal initialMargin = position.getInitialMargin();
            BigDecimal positionValue = position.getPositionValue();

            logger.debug("HEDGING_TRACE [{}]: PnL Check: UnrealizedPnL={}, InitialMargin={}, PositionValue={}", 
                        symbol, unrealizedPnl, initialMargin, positionValue);
            
            // Calculate PnL % against Initial Margin
            if (unrealizedPnl != null && initialMargin != null && initialMargin.compareTo(BigDecimal.ZERO) > 0) {
                pnlPercentageAgainstMargin = unrealizedPnl.divide(initialMargin, 4, RoundingMode.HALF_UP);
                logger.info("HEDGING_TRACE [{}]: PnL Check: Calculated PnL Percentage against Initial Margin = {}%", symbol, pnlPercentageAgainstMargin.multiply(BigDecimal.valueOf(100)));
                
                double pnlThresholdFromConfig = riskManagementService.getHedgePnlThresholdPercent();
                BigDecimal pnlHedgeThreshold = BigDecimal.valueOf(pnlThresholdFromConfig / 100.0).negate(); 

                if (pnlPercentageAgainstMargin.compareTo(pnlHedgeThreshold) < 0) {
                    analysis.addReason(HedgeReason.HIGH_UNREALIZED_LOSS, 0.8);
                    logger.info("HEDGING_TRACE [{}]: PnL Check (vs Margin): ADDED High Unrealized Loss (Loss: {}% > {}%)", symbol, pnlPercentageAgainstMargin.multiply(BigDecimal.valueOf(100)), pnlHedgeThreshold.multiply(BigDecimal.valueOf(-100)));
                } else {
                    logger.info("HEDGING_TRACE [{}]: PnL Check (vs Margin): Loss ({}%) is NOT > {}%. No PnL hedge triggered.", symbol, pnlPercentageAgainstMargin.multiply(BigDecimal.valueOf(100)), pnlHedgeThreshold.multiply(BigDecimal.valueOf(-100)));
                }
            } else {
                logger.warn("HEDGING_TRACE [{}]: PnL Check (vs Margin): Skipped or N/A. UnrealizedPnL ({}) or InitialMargin ({}) is null, or InitialMargin is not positive.", 
                            symbol, unrealizedPnl, initialMargin);
            }

            // Calculate PnL % against Notional Value (for AI context)
            if (unrealizedPnl != null && positionValue != null && positionValue.compareTo(BigDecimal.ZERO) > 0) {
                pnlPercentageAgainstNotional = unrealizedPnl.divide(positionValue, 4, RoundingMode.HALF_UP);
                logger.info("HEDGING_TRACE [{}]: PnL Info (vs Notional): Calculated PnL Percentage against Notional Value = {}%", symbol, pnlPercentageAgainstNotional.multiply(BigDecimal.valueOf(100)));
            } else {
                logger.warn("HEDGING_TRACE [{}]: PnL Info (vs Notional): Skipped or N/A. UnrealizedPnL ({}) or PositionValue ({}) is null, or PositionValue is not positive.", 
                            symbol, unrealizedPnl, positionValue);
            }
            
            // 2. Get specific AI Hedge Advisory (New Logic)
            logger.info("HEDGING_TRACE [{}]: AI Hedge Advisory Check: Fetching data...", symbol);
            String currentMarketPriceStr = "N/A";
            BigDecimal currentMarketPrice = riskManagementService.getCurrentPrice(symbol, exchange);
            if (currentMarketPrice != null) {
                currentMarketPriceStr = currentMarketPrice.toPlainString();
            }

            ScanResult currentMarketScan = getCurrentMarketAnalysis(symbol, exchange); // Re-use to get indicators
            String technicalDataJson = "{}"; // Default empty JSON
            if (currentMarketScan != null && currentMarketScan.getIndicators() != null && !currentMarketScan.getIndicators().isEmpty()) {
                try {
                    // Ensure ObjectMapper is available. If not autowired, instantiate it.
                    // For simplicity, assuming it's available or can be instantiated.
                    technicalDataJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(currentMarketScan.getIndicators());
                } catch (Exception e) {
                    logger.warn("HEDGING_TRACE [{}]: Failed to serialize indicators to JSON: {}", symbol, e.getMessage());
                }
            } else {
                 logger.warn("HEDGING_TRACE [{}]: No indicators found in ScanResult for AI Hedge Advisory.", symbol);
            }
            
            SentimentData sentimentData = new SentimentData(); // Dummy object for now
            sentimentData.setSymbol(symbol); // It's good practice to set the symbol
            sentimentData.setSentimentScore(0.0); 
            sentimentData.setTotalMentions(0);

            logger.info("HEDGING_TRACE [{}]: AI Hedge Advisory Check: Calling AIAnalysisService.generateHedgingAnalysis...", symbol);
            AIAnalysisService.AiHedgeDecision hedgeDecision = aiAnalysisService.generateHedgingAnalysis(
                position, 
                symbol, 
                technicalDataJson, 
                sentimentData, 
                currentMarketPriceStr,
                pnlPercentageAgainstMargin, // Pass PnL % vs Margin
                pnlPercentageAgainstNotional // Pass PnL % vs Notional
            );

            if (hedgeDecision != null) {
                logger.info("HEDGING_TRACE [{}]: AI Hedge Advisory Received: ShouldHedge={}, Confidence={}, Reason Snippet={}", 
                            symbol, hedgeDecision.shouldHedge, hedgeDecision.hedgeConfidence, hedgeDecision.hedgeReason != null ? hedgeDecision.hedgeReason.substring(0, Math.min(hedgeDecision.hedgeReason.length(), 100)) : "N/A");
                if (hedgeDecision.shouldHedge) {
                    analysis.addReason(HedgeReason.AI_HEDGE_ADVISORY, hedgeDecision.hedgeConfidence);
                    logger.info("HEDGING_TRACE [{}]: AI Hedge Advisory Check: ADDED AI Hedge Advisory. Confidence: {}", symbol, hedgeDecision.hedgeConfidence);
                }
            } else {
                logger.warn("HEDGING_TRACE [{}]: AI Hedge Advisory Check: Received null decision from AI Analysis Service.", symbol);
            }

            // 3. Check AI signal reversal (Old general AI logic - can be kept as a fallback or for comparison, or removed)
            logger.debug("HEDGING_TRACE [{}]: General AI Reversal Check (Old Logic): Using previously fetched market scan...", symbol);
            if (currentMarketScan != null && currentMarketScan.getAiAnalysis() != null) {
                logger.info("HEDGING_TRACE [{}]: General AI Reversal Check: AI Analysis Text (Snippet): '{}'", symbol, currentMarketScan.getAiAnalysis().substring(0, Math.min(100, currentMarketScan.getAiAnalysis().length())));
                String aiSignal = extractAISignal(currentMarketScan.getAiAnalysis());
                String positionSide = position.getSide(); 
                logger.info("HEDGING_TRACE [{}]: General AI Reversal Check: Extracted AI Signal = {}, Position Side = {}", symbol, aiSignal, positionSide);
                
                if (isSignalContradicting(positionSide, aiSignal)) {
                    double aiScore = signalWeightingService.convertAiToScore(currentMarketScan.getAiAnalysis());
                    double confidence = Math.abs(aiScore - 0.5) * 2; 
                    analysis.addReason(HedgeReason.AI_SIGNAL_REVERSAL, confidence * 0.7); // Reduce confidence as it's general
                    logger.info("HEDGING_TRACE [{}]: General AI Reversal Check: ADDED AI Signal Reversal (AI: {} vs Position: {}, ReducedConfidence: {})", symbol, aiSignal, positionSide, confidence * 0.7);
                } else {
                    logger.info("HEDGING_TRACE [{}]: General AI Reversal Check: AI Signal ({}) does NOT contradict Position Side ({}). No general AI hedge.", symbol, aiSignal, positionSide);
                }
            } else {
                logger.warn("HEDGING_TRACE [{}]: General AI Reversal Check: Skipped. No current market scan or AI analysis available (from earlier fetch attempt).", symbol);
            }
            
            // 4. Check market regime change
            logger.debug("HEDGING_TRACE [{}]: Regime Change Check: Analyzing market regime...", symbol);
            MarketRegimeService.RegimeAnalysis regimeAnalysis = marketRegimeService
                .analyzeMarketRegime(symbol, position.getExchange(), 
                                   ScanConfig.MarketType.LINEAR); // Assume linear for futures
            
            if (regimeAnalysis != null) {
                logger.info("HEDGING_TRACE [{}]: Regime Change Check: Current Regime = {}, Confidence = {}", symbol, regimeAnalysis.getRegime(), regimeAnalysis.getConfidence());
                if (isRegimeAdverse(position.getSide(), regimeAnalysis)) {
                    analysis.addReason(HedgeReason.MARKET_REGIME_CHANGE, regimeAnalysis.getConfidence());
                    logger.info("HEDGING_TRACE [{}]: Regime Change Check: ADDED Market Regime Change (Regime: {} is adverse for Side: {}, Confidence: {})", 
                                symbol, regimeAnalysis.getRegime(), position.getSide(), regimeAnalysis.getConfidence());
                } else {
                    logger.info("HEDGING_TRACE [{}]: Regime Change Check: Regime ({}) is NOT adverse for Position Side ({}). No regime hedge.", symbol, regimeAnalysis.getRegime(), position.getSide());
                }
            } else {
                 logger.warn("HEDGING_TRACE [{}]: Regime Change Check: Skipped. Regime analysis is null.", symbol);
            }
            
            // 5. Check volatility spike
            logger.debug("HEDGING_TRACE [{}]: Volatility Check: Calculating volatility risk...", symbol);
            if (currentMarketScan != null && currentMarketScan.getIndicators() != null) {
                double volatilityScore = calculateVolatilityRisk(currentMarketScan.getIndicators());
                logger.info("HEDGING_TRACE [{}]: Volatility Check: Calculated Volatility Score = {}", symbol, volatilityScore);
                if (volatilityScore > 0.7) { // Threshold for high volatility
                    analysis.addReason(HedgeReason.VOLATILITY_SPIKE, volatilityScore);
                    logger.info("HEDGING_TRACE [{}]: Volatility Check: ADDED Volatility Spike (Score: {} > 0.7)", symbol, volatilityScore);
                } else {
                    logger.info("HEDGING_TRACE [{}]: Volatility Check: Score ({}) is NOT > 0.7. No volatility hedge.", symbol, volatilityScore);
                }
            } else {
                 logger.warn("HEDGING_TRACE [{}]: Volatility Check: Skipped. No current market scan or indicators available.", symbol);
            }
            
            // 6. Check portfolio correlation risk
            // This requires a more complex implementation to fetch other open positions and their correlations.
            // For now, we'll assume it's a placeholder.
            // double correlationRisk = calculatePortfolioCorrelationRisk(symbol);
            // if (correlationRisk > 0.6) {
            //     analysis.addReason(HedgeReason.CORRELATION_RISK, correlationRisk);
            // }
            logger.debug("HEDGING_TRACE [{}]: Correlation Risk Check: Calculating portfolio correlation risk...", symbol);
            double correlationRiskScore = calculatePortfolioCorrelationRisk(symbol, position); // Pass current position
            logger.info("HEDGING_TRACE [{}]: Correlation Risk Check: Calculated Score = {}", symbol, correlationRiskScore);
            if (correlationRiskScore > 0.6) { // Example threshold for correlation risk
                analysis.addReason(HedgeReason.CORRELATION_RISK, correlationRiskScore);
                logger.info("HEDGING_TRACE [{}]: Correlation Risk Check: ADDED Correlation Risk (Score: {} > 0.6)", symbol, correlationRiskScore);
            } else {
                logger.info("HEDGING_TRACE [{}]: Correlation Risk Check: Score ({}) is NOT > 0.6. No correlation hedge.", symbol, correlationRiskScore);
            }

        } catch (Exception e) {
            logger.error("HEDGING_TRACE [{}]: Error during analyzeHedgingNeed: {}", symbol, e.getMessage(), e);
        }
        logger.info("HEDGING_TRACE [{}]: Finished analyzeHedgingNeed. Total reasons: {}. Max Confidence: {}. ShouldHedge: {}", 
                    symbol, analysis.getReasonsCount(), analysis.getMaxConfidence(), analysis.shouldHedge());
        return analysis;
    }

    /**
     * Execute hedging strategy
     */
    private void executeHedge(PositionUpdateData position, HedgeAnalysis analysis) {
        String symbol = position.getSymbol();
        logger.info("HEDGING_TRACE [{}]: Attempting to execute hedge. PrimaryReason: {}", symbol, analysis.getPrimaryReason());
        
        try {
            HedgeStrategy strategy = determineHedgeStrategy(position, analysis);
            
            if (strategy == null) {
                logger.warn("HEDGING_TRACE [{}]: Could not determine hedge strategy. Aborting hedge.", symbol);
                return;
            }
            logger.info("HEDGING_TRACE [{}]: Determined Hedge Strategy: Type={}, HedgeSymbol={}, HedgeSide={}, HedgeRatio={}", 
                        symbol, strategy.hedgeType, strategy.hedgeSymbol, strategy.hedgeSide, strategy.hedgeRatio);
            
            boolean hedgeExecuted = placeHedgeOrder(position, strategy, analysis);
            
            if (hedgeExecuted) {
                HedgePosition hedgePositionInfo = new HedgePosition(
                    strategy.originalSymbol, strategy.hedgeSymbol, strategy.originalSide, strategy.hedgeSide,
                    strategy.originalSize, strategy.hedgeSize, strategy.hedgeRatio, strategy.hedgeType,
                    analysis.getPrimaryReason(), position.getMarkPrice(), position.getExchange()
                );
                activeHedges.put(generateHedgeKey(symbol, strategy.hedgeType), hedgePositionInfo);
                logger.info("HEDGING_TRACE [{}]: Hedge successfully EXECUTED and tracked. HedgeKey: {}", 
                            symbol, generateHedgeKey(symbol, strategy.hedgeType));
            } else {
                 logger.warn("HEDGING_TRACE [{}]: Hedge order placement FAILED.", symbol);
            }
            
        } catch (Exception e) {
            logger.error("HEDGING_TRACE [{}]: Error during executeHedge: {}", symbol, e.getMessage(), e);
        }
    }

    /**
     * Determine the best hedging strategy for a position
     */
    private HedgeStrategy determineHedgeStrategy(PositionUpdateData position, HedgeAnalysis analysis) {
        String symbol = position.getSymbol();
        String exchange = position.getExchange();
        
        // For Bybit with hedge mode support, prefer direct opposite hedging
        if ("BYBIT".equalsIgnoreCase(exchange) && supportsHedgeMode(symbol)) {
            return createDirectOppositeHedge(position, analysis);
        }
        
        // For other cases, consider correlation hedging
        return createCorrelationHedge(position, analysis);
    }

    /**
     * Create direct opposite hedge (same symbol, opposite side)
     */
    private HedgeStrategy createDirectOppositeHedge(PositionUpdateData position, HedgeAnalysis analysis) {
        HedgeStrategy strategy = new HedgeStrategy();
        strategy.originalSymbol = position.getSymbol();
        strategy.hedgeSymbol = position.getSymbol(); // Same symbol
        strategy.originalSide = position.getSide();
        strategy.hedgeSide = getOppositeSide(position.getSide());
        strategy.originalSize = position.getSize();
        strategy.hedgeType = HedgeType.DIRECT_OPPOSITE;
        
        // Calculate hedge ratio based on analysis confidence
        double confidence = analysis.getMaxConfidence();
        BigDecimal baseRatio = BigDecimal.valueOf(0.5); // Default 50% hedge
        
        if (confidence > 0.8) {
            strategy.hedgeRatio = BigDecimal.valueOf(0.75); // 75% hedge for high confidence
        } else if (confidence > 0.6) {
            strategy.hedgeRatio = BigDecimal.valueOf(0.60); // 60% hedge for medium confidence
        } else {
            strategy.hedgeRatio = baseRatio; // 50% hedge for low confidence
        }
        
        strategy.hedgeSize = strategy.originalSize.multiply(strategy.hedgeRatio);
        
        logger.info("Direct opposite hedge strategy for {}: {}% hedge ({} {})", 
                   position.getSymbol(), strategy.hedgeRatio.multiply(BigDecimal.valueOf(100)), 
                   strategy.hedgeSize, strategy.hedgeSide);
        
        return strategy;
    }

    /**
     * Place the actual hedge order
     */
    private boolean placeHedgeOrder(PositionUpdateData position, HedgeStrategy strategy, HedgeAnalysis analysis) {
        String hedgeSymbol = strategy.getHedgeSymbol();
        String hedgeSide = strategy.getHedgeSide();
        BigDecimal originalHedgeQuantity = strategy.getHedgeSize(); // This is the initially calculated ideal quantity
        String exchange = strategy.getExchange() != null ? strategy.getExchange() : "BYBIT"; // Default if not set in strategy
        String marketType = determineMarketType(hedgeSymbol, exchange); // e.g., "LINEAR", "SPOT"
        String category = "linear"; // Default for Bybit futures
        if ("SPOT".equalsIgnoreCase(marketType)) {
            category = "spot";
        }

        logger.info("Attempting to place hedge order for Original Symbol: {}, Hedge Symbol: {}, Side: {}, Calculated Qty: {}, Exchange: {}, MarketType: {}",
            position.getSymbol(), hedgeSymbol, hedgeSide, originalHedgeQuantity, exchange, marketType);

        InstrumentInfoService.InstrumentQuantityRules qtyRules = instrumentInfoService.getQuantityRules(hedgeSymbol, category);
        BigDecimal minOrderQty = qtyRules.getMinOrderQty();
        BigDecimal qtyStep = qtyRules.getQtyStep();

        if (qtyStep == null || qtyStep.compareTo(BigDecimal.ZERO) <= 0) {
            logger.error("HEDGING_QTY_ADJUST [{}]: Invalid qtyStep ({}) from InstrumentInfoService. Cannot adjust quantity. Aborting hedge.", hedgeSymbol, qtyStep);
            return false;
        }

        // Adjust quantity to be a multiple of qtyStep
        // Ensure originalHedgeQuantity has a scale that doesn't cause issues with qtyStep.scale() if qtyStep is an integer.
        int scaleForAdjustment = Math.max(originalHedgeQuantity.scale(), qtyStep.scale());
        BigDecimal scaledOriginalQty = originalHedgeQuantity.setScale(scaleForAdjustment, RoundingMode.FLOOR);
        BigDecimal adjustedHedgeQuantity = scaledOriginalQty.divide(qtyStep, 0, RoundingMode.FLOOR).multiply(qtyStep);

        logger.info("HEDGING_QTY_ADJUST [{}]: Original Qty: {}, MinQty: {}, QtyStep: {}, Adjusted Qty (after step): {}",
            hedgeSymbol, originalHedgeQuantity, minOrderQty, qtyStep, adjustedHedgeQuantity);

        // Check if adjusted quantity is less than minOrderQty
        if (adjustedHedgeQuantity.compareTo(minOrderQty) < 0) {
            logger.warn("HEDGING_QTY_ADJUST [{}]: Adjusted hedge quantity {} is less than minOrderQty {}. Skipping hedge placement.",
                hedgeSymbol, adjustedHedgeQuantity, minOrderQty);
            return false;
        }

        // Check if adjusted quantity is zero or negative
        if (adjustedHedgeQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("HEDGING_QTY_ADJUST [{}]: Adjusted hedge quantity {} is zero or negative. Skipping hedge placement.",
                hedgeSymbol, adjustedHedgeQuantity);
            return false;
        }

        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setSymbol(hedgeSymbol);
        orderRequest.setSide(hedgeSide);
        orderRequest.setType("MARKET"); // Hedging typically uses market orders for speed
        orderRequest.setQuantity(adjustedHedgeQuantity); // Use the adjusted quantity
        
        // Ensure MarketType enum conversion is safe
        try {
            orderRequest.setMarketType(marketType); // Use the existing String setter in OrderRequest
        } catch (IllegalArgumentException e) {
            logger.error("HEDGING_ORDER_REQ [{}]: Invalid marketType string '{}'. Cannot set on OrderRequest. Aborting.", hedgeSymbol, marketType, e);
            return false;
        }
        
        // positionIdx handling for Bybit - not setting for new hedge order
        logger.debug("HEDGING_ORDER_REQ [{}]: Bybit direct opposite hedge. positionIdx will not be set on order request for new hedge creation.", hedgeSymbol);

        try {
            logger.info("HEDGING_ORDER_REQ [{}]: Placing adjusted hedge order: Side={}, Type={}, Qty={}, MarketType={}",
                hedgeSymbol, orderRequest.getSide(), orderRequest.getType(), orderRequest.getQuantity(), orderRequest.getMarketType());

            orderManagementService.placeOrder(orderRequest, exchange); // This call might throw an exception on failure
            
            logger.info("HEDGING_TRACE [{}]: Hedge order placed successfully for {} {} of {}. Exchange: {}, MarketType: {}",
                position.getSymbol(), hedgeSide, adjustedHedgeQuantity, hedgeSymbol, exchange, marketType);
            
            HedgePosition hedgeRecord = new HedgePosition(
                position.getSymbol(),
                hedgeSymbol,
                position.getSide(),
                hedgeSide,
                position.getSize(),
                adjustedHedgeQuantity,
                strategy.getHedgeRatio(),
                strategy.getHedgeType(),
                analysis.getPrimaryReason(),
                position.getMarkPrice(), // Price at which hedge was triggered
                exchange
            );
            activeHedges.put(generateHedgeKey(position.getSymbol(), strategy.getHedgeType()), hedgeRecord);

            return true;
        } catch (Exception e) {
            logger.error("HEDGING_TRACE [{}]: Error placing hedge order for {}: {}", position.getSymbol(), hedgeSymbol, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Evaluate existing hedges for adjustment or closure
     */
    private void evaluateExistingHedges() {
        logger.debug("Evaluating {} existing hedges", activeHedges.size());
        
        Iterator<Map.Entry<String, HedgePosition>> iterator = activeHedges.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, HedgePosition> entry = iterator.next();
            HedgePosition hedge = entry.getValue();
            
            try {
                if (shouldCloseHedge(hedge)) {
                    closeHedge(hedge);
                    iterator.remove();
                } else if (shouldAdjustHedge(hedge)) {
                    adjustHedge(hedge);
                }
            } catch (Exception e) {
                logger.error("Error evaluating hedge for {}: {}", hedge.getOriginalSymbol(), e.getMessage());
            }
        }
    }

    /**
     * Check if hedge should be closed
     */
    private boolean shouldCloseHedge(HedgePosition hedge) {
        // Get current position data
        PositionUpdateData originalPosition = positionCacheService.getPosition(hedge.getOriginalSymbol());
        
        // Close hedge if original position is closed
        if (originalPosition == null || originalPosition.getSize().compareTo(BigDecimal.valueOf(0.01)) < 0) {
            logger.info("Closing hedge for {} - original position closed", hedge.getOriginalSymbol());
            return true;
        }
        
        // Close hedge if conditions have improved
        HedgeAnalysis currentAnalysis = analyzeHedgingNeed(originalPosition);
        if (!currentAnalysis.shouldHedge()) {
            logger.info("Closing hedge for {} - conditions improved", hedge.getOriginalSymbol());
            return true;
        }
        
        // Close hedge if it's been active too long (e.g., 24 hours)
        if (hedge.getCreatedAt().isBefore(Instant.now().minus(24, ChronoUnit.HOURS))) {
            logger.info("Closing hedge for {} - hedge too old", hedge.getOriginalSymbol());
            return true;
        }
        
        return false;
    }

    /**
     * Close an existing hedge
     */
    private void closeHedge(HedgePosition hedge) {
        try {
            // Get current hedge position size
            PositionUpdateData hedgePosition = positionCacheService.getPosition(hedge.getHedgeSymbol());
            
            if (hedgePosition != null && hedgePosition.getSize().compareTo(BigDecimal.ZERO) > 0) {
                OrderRequest closeOrder = new OrderRequest();
                closeOrder.setSymbol(hedge.getHedgeSymbol());
                closeOrder.setSide(getOppositeSide(hedge.getHedgeSide())); // Opposite of hedge side
                closeOrder.setType("MARKET");
                closeOrder.setQuantity(hedgePosition.getSize());
                closeOrder.setBotId("HedgingService");
                closeOrder.setStrategyName("CloseHedge-" + hedge.getHedgeReason());
                closeOrder.setExchange(hedge.getExchange());
                
                logger.info("Closing hedge position: {} {} {}", 
                           closeOrder.getSide(), closeOrder.getQuantity(), hedge.getHedgeSymbol());
                
                orderManagementService.placeOrder(closeOrder, hedge.getExchange());
            }
            
            hedge.setActive(false);
            logger.info("Hedge closed for {}", hedge.getOriginalSymbol());
            
        } catch (Exception e) {
            logger.error("Error closing hedge for {}: {}", hedge.getOriginalSymbol(), e.getMessage(), e);
        }
    }

    // Helper classes and methods...
    
    private static class HedgeAnalysis {
        private Map<HedgeReason, Double> reasons = new HashMap<>();
        
        public void addReason(HedgeReason reason, double confidence) {
            logger.debug("HEDGING_ANALYSIS: Adding reason {} with confidence {}", reason, confidence);
            reasons.put(reason, confidence);
        }
        
        public boolean shouldHedge() {
            // Hedge if any reason has confidence > 0.5 (or adjust threshold as needed)
            return reasons.values().stream().anyMatch(conf -> conf > 0.5);
        }
        
        public double getMaxConfidence() {
            return reasons.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
        
        public HedgeReason getPrimaryReason() {
            return reasons.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null); // Return null if no reasons
        }
        public int getReasonsCount() {
            return reasons.size();
        }
    }
    
    private static class HedgeStrategy {
        String originalSymbol;
        String hedgeSymbol;
        String originalSide;
        String hedgeSide;
        BigDecimal originalSize;
        BigDecimal hedgeSize;
        BigDecimal hedgeRatio;
        HedgeType hedgeType;
        String exchange;

        // Getters
        public String getOriginalSymbol() { return originalSymbol; }
        public String getHedgeSymbol() { return hedgeSymbol; }
        public String getOriginalSide() { return originalSide; }
        public String getHedgeSide() { return hedgeSide; }
        public BigDecimal getOriginalSize() { return originalSize; }
        public BigDecimal getHedgeSize() { return hedgeSize; }
        public BigDecimal getHedgeRatio() { return hedgeRatio; }
        public HedgeType getHedgeType() { return hedgeType; }
        public String getExchange() { return exchange; }
    }

    // Helper methods...
    private boolean shouldEvaluatePosition(PositionUpdateData position) {
        return position != null && 
               position.getSize() != null && 
               position.getSize().compareTo(BigDecimal.valueOf(0.01)) > 0 &&
               "Buy".equalsIgnoreCase(position.getSide()) || "Sell".equalsIgnoreCase(position.getSide());
    }

    private boolean isInCooldown(String symbol) {
        Instant lastEval = lastHedgeEvaluation.get(symbol);
        return lastEval != null && 
               lastEval.isAfter(Instant.now().minus(HEDGE_COOLDOWN_MINUTES, ChronoUnit.MINUTES));
    }

    private ScanResult getCurrentMarketAnalysis(String symbol, String exchange) {
        try {
            ScanConfig config = new ScanConfig();
            config.setTradingPairs(Collections.singletonList(symbol));
            config.setInterval("1h");
            config.setExchange(exchange);
            config.setMarketType(ScanConfig.MarketType.LINEAR);
            config.setIncludeAiAnalysis(true);
            
            List<ScanResult> results = marketScannerService.scanMarket(config);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            logger.error("Error getting market analysis for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private String extractAISignal(String aiAnalysis) {
        if (aiAnalysis == null) return "NEUTRAL";
        
        String analysis = aiAnalysis.toLowerCase();
        if (analysis.contains("strong buy")) return "STRONG_BUY";
        if (analysis.contains("buy")) return "BUY";
        if (analysis.contains("strong sell")) return "STRONG_SELL";
        if (analysis.contains("sell")) return "SELL";
        return "NEUTRAL";
    }

    private boolean isSignalContradicting(String positionSide, String aiSignal) {
        if ("Buy".equalsIgnoreCase(positionSide)) {
            return "SELL".equals(aiSignal) || "STRONG_SELL".equals(aiSignal);
        } else if ("Sell".equalsIgnoreCase(positionSide)) {
            return "BUY".equals(aiSignal) || "STRONG_BUY".equals(aiSignal);
        }
        return false;
    }

    private boolean isRegimeAdverse(String positionSide, MarketRegimeService.RegimeAnalysis regime) {
        if ("Buy".equalsIgnoreCase(positionSide)) {
            return regime.getRegime() == MarketRegimeService.MarketRegime.BEAR_MARKET;
        } else if ("Sell".equalsIgnoreCase(positionSide)) {
            return regime.getRegime() == MarketRegimeService.MarketRegime.BULL_MARKET;
        }
        return false;
    }

    private double calculateVolatilityRisk(Map<String, TechnicalIndicator> indicators) {
        if (indicators == null || indicators.isEmpty()) {
            return 0.0; // No data, no perceived risk from volatility
        }

        double volatilityScore = 0.0;

        TechnicalIndicator atrIndicator = indicators.get("ATR_14"); // Common key for ATR
        if (atrIndicator != null && !"INSUFFICIENT_DATA".equalsIgnoreCase(atrIndicator.getSignal())) {
            double atrValue = atrIndicator.getValue(); // getValue() returns primitive double
            // Example: Higher ATR relative to current price might indicate higher volatility.
            // This needs a proper scaling logic to a 0-1 score and access to current price.
            // For now, a simple check based on ATR value itself (this threshold is arbitrary without price context)
            if (atrValue > 0.05) { 
                volatilityScore += 0.3;
            } else if (atrValue > 0.02) {
                volatilityScore += 0.1;
            }
        }

        TechnicalIndicator bbandsIndicator = indicators.get("BBANDS"); // Common key for Bollinger Bands
        if (bbandsIndicator != null && !"INSUFFICIENT_DATA".equalsIgnoreCase(bbandsIndicator.getSignal())) {
            Map<String, Object> additionalData = bbandsIndicator.getAdditionalData();
            if (additionalData != null && additionalData.containsKey("bandwidth")) {
                try {
                    // Assuming bandwidth is stored as a String or Number that can be parsed to Double
                    Object bandwidthObj = additionalData.get("bandwidth");
                    if (bandwidthObj != null) {
                        Double bandwidth = Double.parseDouble(bandwidthObj.toString());
                        // Higher bandwidth might indicate higher volatility
                        if (bandwidth > 0.1) { // Example threshold for bandwidth
                            volatilityScore += 0.3;
                        }
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Could not parse Bollinger Bandwidth from additionalData: {}", additionalData.get("bandwidth"));
                }
            }
        }
        
        // Normalize to ensure score is between 0 and 1
        return Math.min(1.0, Math.max(0.0, volatilityScore));
    }

    private double calculatePortfolioCorrelationRisk(String symbol, PositionUpdateData currentPosition) {
        if (currentPosition == null || currentPosition.getUnrealisedPnl() == null || currentPosition.getUnrealisedPnl().compareTo(BigDecimal.ZERO) >= 0) {
            logger.debug("HEDGING_CORRELATION [{}]: Current position is not in loss or PnL is null. No correlation risk calculated from its perspective.", symbol);
            return 0.0; // Current position not losing, so no amplified risk from its correlations yet.
        }

        List<String> correlatedSymbols = PREDEFINED_CORRELATIONS.get(symbol);
        if (correlatedSymbols == null || correlatedSymbols.isEmpty()) {
            logger.debug("HEDGING_CORRELATION [{}]: No predefined correlated symbols found.", symbol);
            return 0.0; // No predefined correlations for this symbol
        }

        logger.debug("HEDGING_CORRELATION [{}]: Found {} predefined correlated symbols: {}", symbol, correlatedSymbols.size(), correlatedSymbols);
        int correlatedLossCount = 0;
        String currentPositionSide = currentPosition.getSide();

        for (String correlatedSymbol : correlatedSymbols) {
            if (correlatedSymbol.equals(symbol)) continue; // Skip self

            PositionUpdateData correlatedPos = positionCacheService.getPosition(correlatedSymbol);
            if (correlatedPos != null && 
                correlatedPos.getSize() != null && 
                correlatedPos.getSize().compareTo(BigDecimal.ZERO) > 0 &&
                correlatedPos.getUnrealisedPnl() != null && 
                correlatedPos.getUnrealisedPnl().compareTo(BigDecimal.ZERO) < 0) { // Correlated position is open and losing

                if (currentPositionSide != null && currentPositionSide.equalsIgnoreCase(correlatedPos.getSide())) { // And on the same side
                    logger.info("HEDGING_CORRELATION [{}]: Correlated asset {} is also open on the same side ({}) and losing (PnL: {}). Incrementing risk.", 
                                symbol, correlatedSymbol, currentPositionSide, correlatedPos.getUnrealisedPnl());
                    correlatedLossCount++;
                } else {
                    logger.debug("HEDGING_CORRELATION [{}]: Correlated asset {} is open and losing, but on a different side ({} vs {}). Not counted towards same-direction risk.",
                                 symbol, correlatedSymbol, correlatedPos.getSide(), currentPositionSide);
                }
            } else if (correlatedPos != null) {
                 logger.debug("HEDGING_CORRELATION [{}]: Correlated asset {} is open but not losing (PnL: {}) or size is zero.", 
                                 symbol, correlatedSymbol, correlatedPos.getUnrealisedPnl());
            }
        }

        if (correlatedLossCount == 0) {
            logger.debug("HEDGING_CORRELATION [{}]: No other predefined correlated assets are currently open, on the same side, and losing.", symbol);
            return 0.0;
        }

        // Simple risk score: 0.3 per correlated losing position on the same side
        // Max score of 0.9 from this factor.
        double riskScore = Math.min(0.9, 0.3 * correlatedLossCount);
        logger.info("HEDGING_CORRELATION [{}]: Calculated portfolio correlation risk score: {}. {} correlated assets losing on same side.", symbol, riskScore, correlatedLossCount);
        return riskScore;
    }

    private String getOppositeSide(String side) {
        return "Buy".equalsIgnoreCase(side) ? "Sell" : "Buy";
    }

    private boolean supportsHedgeMode(String symbol) {
        // Check if symbol supports hedge mode (most USDT pairs on Bybit do)
        return symbol.endsWith("USDT");
    }

    private String determineMarketType(String symbol, String exchange) {
        if ("BYBIT".equalsIgnoreCase(exchange) && symbol.endsWith("USDT")) {
            return "linear";
        }
        return "spot";
    }

    private String generateHedgeKey(String symbol, HedgeType hedgeType) {
        return symbol + "_" + hedgeType.name();
    }

    // Additional methods for correlation hedging, dynamic adjustments, etc...
    private HedgeStrategy createCorrelationHedge(PositionUpdateData position, HedgeAnalysis analysis) {
        // Implement correlation-based hedging
        // For example, hedge BTC position with ETH
        return null; // Placeholder
    }

    private boolean shouldAdjustHedge(HedgePosition hedge) {
        // Implement logic to adjust hedge ratios
        return false; // Placeholder
    }

    private void adjustHedge(HedgePosition hedge) {
        // Implement hedge adjustment logic
    }

    /**
     * Public API methods for manual hedging control
     */
    
    public void forceHedgePosition(String symbol, BigDecimal hedgeRatio, HedgeReason reason) {
        PositionUpdateData position = positionCacheService.getPosition(symbol);
        if (position != null) {
            HedgeAnalysis analysis = new HedgeAnalysis();
            analysis.addReason(reason, 1.0);
            executeHedge(position, analysis);
        }
    }

    public void closeAllHedges() {
        logger.info("Closing all active hedges");
        activeHedges.values().forEach(this::closeHedge);
        activeHedges.clear();
    }

    public List<HedgePosition> getActiveHedges() {
        return new ArrayList<>(activeHedges.values());
    }

    public HedgePosition getHedgeForSymbol(String symbol) {
        return activeHedges.values().stream()
            .filter(h -> h.getOriginalSymbol().equals(symbol) && h.isActive())
            .findFirst()
            .orElse(null);
    }

    public boolean closeHedgeForSymbol(String symbol) {
        HedgePosition hedge = getHedgeForSymbol(symbol);
        if (hedge != null && hedge.isActive()) {
            closeHedge(hedge);
            activeHedges.entrySet().removeIf(entry -> 
                entry.getValue().getOriginalSymbol().equals(symbol));
            return true;
        }
        return false;
    }
}
 