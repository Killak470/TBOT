package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.model.ScanConfig;
import com.tradingbot.backend.model.ScanResult;
import com.tradingbot.backend.repository.BotSignalRepository;
import com.tradingbot.backend.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for providing enhanced AI context including market conditions,
 * historical performance, and comprehensive risk assessment
 */
@Service
public class EnhancedAIContextService {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedAIContextService.class);
    
    @Autowired
    private RiskManagementService riskManagementService;
    
    @Autowired
    private MarketRegimeService marketRegimeService;
    
    @Autowired
    private MultiTimeframeService multiTimeframeService;
    
    @Autowired
    private PerformanceLearningService performanceLearningService;
    
    @Autowired
    private BotSignalRepository botSignalRepository;
    
    @Autowired
    private PositionRepository positionRepository;
    
    public static class AIContext {
        private String symbol;
        private String exchange;
        private ScanConfig.MarketType marketType;
        
        // Market regime information
        private MarketRegimeService.RegimeAnalysis marketRegime;
        
        // Multi-timeframe confluence
        private MultiTimeframeService.ConfluenceResult confluence;
        
        // Risk metrics
        private RiskMetrics riskMetrics;
        
        // Historical performance
        private HistoricalPerformance historicalPerformance;
        
        // Portfolio context
        private PortfolioContext portfolioContext;
        
        // Macro market conditions
        private MacroConditions macroConditions;
        
        // AI recommendations
        private AIRecommendations recommendations;
        
        public AIContext(String symbol, String exchange, ScanConfig.MarketType marketType) {
            this.symbol = symbol;
            this.exchange = exchange;
            this.marketType = marketType;
        }
        
        // Getters and setters
        public String getSymbol() { return symbol; }
        public String getExchange() { return exchange; }
        public ScanConfig.MarketType getMarketType() { return marketType; }
        public MarketRegimeService.RegimeAnalysis getMarketRegime() { return marketRegime; }
        public void setMarketRegime(MarketRegimeService.RegimeAnalysis marketRegime) { this.marketRegime = marketRegime; }
        public MultiTimeframeService.ConfluenceResult getConfluence() { return confluence; }
        public void setConfluence(MultiTimeframeService.ConfluenceResult confluence) { this.confluence = confluence; }
        public RiskMetrics getRiskMetrics() { return riskMetrics; }
        public void setRiskMetrics(RiskMetrics riskMetrics) { this.riskMetrics = riskMetrics; }
        public HistoricalPerformance getHistoricalPerformance() { return historicalPerformance; }
        public void setHistoricalPerformance(HistoricalPerformance historicalPerformance) { this.historicalPerformance = historicalPerformance; }
        public PortfolioContext getPortfolioContext() { return portfolioContext; }
        public void setPortfolioContext(PortfolioContext portfolioContext) { this.portfolioContext = portfolioContext; }
        public MacroConditions getMacroConditions() { return macroConditions; }
        public void setMacroConditions(MacroConditions macroConditions) { this.macroConditions = macroConditions; }
        public AIRecommendations getRecommendations() { return recommendations; }
        public void setRecommendations(AIRecommendations recommendations) { this.recommendations = recommendations; }
    }
    
    public static class RiskMetrics {
        private BigDecimal currentVolatility;
        private BigDecimal maxDrawdown;
        private BigDecimal correlationRisk;
        private BigDecimal positionSizeRecommendation;
        private BigDecimal stopLossRecommendation;
        private BigDecimal takeProfitRecommendation;
        private String riskLevel; // LOW, MEDIUM, HIGH, EXTREME
        
        // Getters and setters
        public BigDecimal getCurrentVolatility() { return currentVolatility; }
        public void setCurrentVolatility(BigDecimal currentVolatility) { this.currentVolatility = currentVolatility; }
        public BigDecimal getMaxDrawdown() { return maxDrawdown; }
        public void setMaxDrawdown(BigDecimal maxDrawdown) { this.maxDrawdown = maxDrawdown; }
        public BigDecimal getCorrelationRisk() { return correlationRisk; }
        public void setCorrelationRisk(BigDecimal correlationRisk) { this.correlationRisk = correlationRisk; }
        public BigDecimal getPositionSizeRecommendation() { return positionSizeRecommendation; }
        public void setPositionSizeRecommendation(BigDecimal positionSizeRecommendation) { this.positionSizeRecommendation = positionSizeRecommendation; }
        public BigDecimal getStopLossRecommendation() { return stopLossRecommendation; }
        public void setStopLossRecommendation(BigDecimal stopLossRecommendation) { this.stopLossRecommendation = stopLossRecommendation; }
        public BigDecimal getTakeProfitRecommendation() { return takeProfitRecommendation; }
        public void setTakeProfitRecommendation(BigDecimal takeProfitRecommendation) { this.takeProfitRecommendation = takeProfitRecommendation; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    }
    
    public static class HistoricalPerformance {
        private double winRate;
        private BigDecimal avgWin;
        private BigDecimal avgLoss;
        private double sharpeRatio;
        private int totalTrades;
        private BigDecimal totalProfit;
        private List<String> bestPerformingStrategies;
        private List<String> worstPerformingStrategies;
        
        public HistoricalPerformance() {
            this.bestPerformingStrategies = new ArrayList<>();
            this.worstPerformingStrategies = new ArrayList<>();
        }
        
        // Getters and setters
        public double getWinRate() { return winRate; }
        public void setWinRate(double winRate) { this.winRate = winRate; }
        public BigDecimal getAvgWin() { return avgWin; }
        public void setAvgWin(BigDecimal avgWin) { this.avgWin = avgWin; }
        public BigDecimal getAvgLoss() { return avgLoss; }
        public void setAvgLoss(BigDecimal avgLoss) { this.avgLoss = avgLoss; }
        public double getSharpeRatio() { return sharpeRatio; }
        public void setSharpeRatio(double sharpeRatio) { this.sharpeRatio = sharpeRatio; }
        public int getTotalTrades() { return totalTrades; }
        public void setTotalTrades(int totalTrades) { this.totalTrades = totalTrades; }
        public BigDecimal getTotalProfit() { return totalProfit; }
        public void setTotalProfit(BigDecimal totalProfit) { this.totalProfit = totalProfit; }
        public List<String> getBestPerformingStrategies() { return bestPerformingStrategies; }
        public void setBestPerformingStrategies(List<String> bestPerformingStrategies) { this.bestPerformingStrategies = bestPerformingStrategies; }
        public List<String> getWorstPerformingStrategies() { return worstPerformingStrategies; }
        public void setWorstPerformingStrategies(List<String> worstPerformingStrategies) { this.worstPerformingStrategies = worstPerformingStrategies; }
    }
    
    public static class PortfolioContext {
        private int totalOpenPositions;
        private BigDecimal totalExposure;
        private BigDecimal availableCapital;
        private double portfolioCorrelation;
        private List<String> highlyCorrelatedAssets;
        private String riskDistribution; // Description of how risk is distributed
        
        public PortfolioContext() {
            this.highlyCorrelatedAssets = new ArrayList<>();
        }
        
        // Getters and setters
        public int getTotalOpenPositions() { return totalOpenPositions; }
        public void setTotalOpenPositions(int totalOpenPositions) { this.totalOpenPositions = totalOpenPositions; }
        public BigDecimal getTotalExposure() { return totalExposure; }
        public void setTotalExposure(BigDecimal totalExposure) { this.totalExposure = totalExposure; }
        public BigDecimal getAvailableCapital() { return availableCapital; }
        public void setAvailableCapital(BigDecimal availableCapital) { this.availableCapital = availableCapital; }
        public double getPortfolioCorrelation() { return portfolioCorrelation; }
        public void setPortfolioCorrelation(double portfolioCorrelation) { this.portfolioCorrelation = portfolioCorrelation; }
        public List<String> getHighlyCorrelatedAssets() { return highlyCorrelatedAssets; }
        public void setHighlyCorrelatedAssets(List<String> highlyCorrelatedAssets) { this.highlyCorrelatedAssets = highlyCorrelatedAssets; }
        public String getRiskDistribution() { return riskDistribution; }
        public void setRiskDistribution(String riskDistribution) { this.riskDistribution = riskDistribution; }
    }
    
    public static class MacroConditions {
        private String marketSentiment; // BULLISH, BEARISH, NEUTRAL
        private String volatilityRegime; // LOW, NORMAL, HIGH, EXTREME
        private String liquidityConditions; // TIGHT, NORMAL, ABUNDANT
        private List<String> marketDrivers; // Current market driving factors
        private String riskOnOff; // RISK_ON, RISK_OFF, MIXED
        
        public MacroConditions() {
            this.marketDrivers = new ArrayList<>();
        }
        
        // Getters and setters
        public String getMarketSentiment() { return marketSentiment; }
        public void setMarketSentiment(String marketSentiment) { this.marketSentiment = marketSentiment; }
        public String getVolatilityRegime() { return volatilityRegime; }
        public void setVolatilityRegime(String volatilityRegime) { this.volatilityRegime = volatilityRegime; }
        public String getLiquidityConditions() { return liquidityConditions; }
        public void setLiquidityConditions(String liquidityConditions) { this.liquidityConditions = liquidityConditions; }
        public List<String> getMarketDrivers() { return marketDrivers; }
        public void setMarketDrivers(List<String> marketDrivers) { this.marketDrivers = marketDrivers; }
        public String getRiskOnOff() { return riskOnOff; }
        public void setRiskOnOff(String riskOnOff) { this.riskOnOff = riskOnOff; }
    }
    
    public static class AIRecommendations {
        private String primaryRecommendation; // STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL
        private double confidenceLevel; // 0.0 to 1.0
        private List<String> supportingFactors;
        private List<String> riskFactors;
        private String positionSizingAdvice;
        private String timingAdvice;
        private String exitStrategyAdvice;
        
        public AIRecommendations() {
            this.supportingFactors = new ArrayList<>();
            this.riskFactors = new ArrayList<>();
        }
        
        // Getters and setters
        public String getPrimaryRecommendation() { return primaryRecommendation; }
        public void setPrimaryRecommendation(String primaryRecommendation) { this.primaryRecommendation = primaryRecommendation; }
        public double getConfidenceLevel() { return confidenceLevel; }
        public void setConfidenceLevel(double confidenceLevel) { this.confidenceLevel = confidenceLevel; }
        public List<String> getSupportingFactors() { return supportingFactors; }
        public void setSupportingFactors(List<String> supportingFactors) { this.supportingFactors = supportingFactors; }
        public List<String> getRiskFactors() { return riskFactors; }
        public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }
        public String getPositionSizingAdvice() { return positionSizingAdvice; }
        public void setPositionSizingAdvice(String positionSizingAdvice) { this.positionSizingAdvice = positionSizingAdvice; }
        public String getTimingAdvice() { return timingAdvice; }
        public void setTimingAdvice(String timingAdvice) { this.timingAdvice = timingAdvice; }
        public String getExitStrategyAdvice() { return exitStrategyAdvice; }
        public void setExitStrategyAdvice(String exitStrategyAdvice) { this.exitStrategyAdvice = exitStrategyAdvice; }
    }
    
    /**
     * Generate comprehensive AI context for a symbol
     */
    public AIContext generateEnhancedContext(String symbol, String exchange, ScanConfig.MarketType marketType) {
        logger.info("Generating enhanced AI context for {} on {}", symbol, exchange);
        
        AIContext context = new AIContext(symbol, exchange, marketType);
        
        try {
            // 1. Market regime analysis
            context.setMarketRegime(marketRegimeService.analyzeMarketRegime(symbol, exchange, marketType));
            
            // 2. Multi-timeframe confluence
            context.setConfluence(multiTimeframeService.analyzeMultiTimeframeConfluence(symbol, exchange, marketType));
            
            // 3. Risk metrics
            context.setRiskMetrics(generateRiskMetrics(symbol, exchange, marketType));
            
            // 4. Historical performance
            context.setHistoricalPerformance(generateHistoricalPerformance(symbol));
            
            // 5. Portfolio context
            context.setPortfolioContext(generatePortfolioContext(symbol));
            
            // 6. Macro conditions
            context.setMacroConditions(generateMacroConditions());
            
            // 7. AI recommendations
            context.setRecommendations(generateAIRecommendations(context));
            
            logger.info("Enhanced AI context generated successfully for {}", symbol);
            
        } catch (Exception e) {
            logger.error("Error generating enhanced AI context for {}: {}", symbol, e.getMessage());
        }
        
        return context;
    }
    
    /**
     * Generate risk metrics for the symbol
     */
    private RiskMetrics generateRiskMetrics(String symbol, String exchange, ScanConfig.MarketType marketType) {
        RiskMetrics metrics = new RiskMetrics();
        
        try {
            // Current volatility
            metrics.setCurrentVolatility(riskManagementService.calculateVolatility(symbol, "BYBIT"));
            
            // Max drawdown
            metrics.setMaxDrawdown(riskManagementService.calculateMaxDrawdown(symbol));
            
            // Correlation risk
            boolean withinCorrelationLimits = riskManagementService.isWithinCorrelationLimits(symbol);
            metrics.setCorrelationRisk(BigDecimal.valueOf(withinCorrelationLimits ? 0.3 : 0.8));
            
            // Position size recommendation
            BigDecimal mockPrice = BigDecimal.valueOf(100); // Mock price for calculation
            BigDecimal mockStopLoss = mockPrice.multiply(BigDecimal.valueOf(0.98)); // 2% stop loss
            BigDecimal positionSize = riskManagementService.calculatePositionSize(symbol, mockPrice, mockStopLoss, marketType.toString(), exchange);
            metrics.setPositionSizeRecommendation(positionSize);
            
            // Stop loss recommendation
            BigDecimal stopLoss = riskManagementService.calculateStopLossPrice(symbol, mockPrice, "LONG");
            metrics.setStopLossRecommendation(stopLoss);
            
            // Take profit recommendation
            BigDecimal takeProfit = riskManagementService.calculateTakeProfitPrice(mockPrice, stopLoss, "LONG", BigDecimal.valueOf(2.0));
            metrics.setTakeProfitRecommendation(takeProfit);
            
            // Risk level assessment
            metrics.setRiskLevel(assessRiskLevel(metrics.getCurrentVolatility(), metrics.getMaxDrawdown()));
            
        } catch (Exception e) {
            logger.error("Error generating risk metrics for {}: {}", symbol, e.getMessage());
            setDefaultRiskMetrics(metrics);
        }
        
        return metrics;
    }
    
    /**
     * Generate historical performance data
     */
    private HistoricalPerformance generateHistoricalPerformance(String symbol) {
        HistoricalPerformance performance = new HistoricalPerformance();
        
        try {
            // Get historical performance metrics from learning service
            Map<String, PerformanceLearningService.PerformanceMetrics> allMetrics = 
                performanceLearningService.getAllPerformanceMetrics();
            
            // Filter metrics for this symbol
            List<PerformanceLearningService.PerformanceMetrics> symbolMetrics = allMetrics.values().stream()
                .filter(m -> symbol.equals(m.getSymbol()))
                .collect(Collectors.toList());
            
            if (!symbolMetrics.isEmpty()) {
                // Aggregate metrics
                double totalWinRate = symbolMetrics.stream().mapToDouble(PerformanceLearningService.PerformanceMetrics::getWinRate).average().orElse(0.0);
                int totalTrades = symbolMetrics.stream().mapToInt(PerformanceLearningService.PerformanceMetrics::getTotalTrades).sum();
                
                performance.setWinRate(totalWinRate);
                performance.setTotalTrades(totalTrades);
                
                // Get best and worst strategies
                List<String> bestStrategies = symbolMetrics.stream()
                    .filter(m -> m.getWinRate() > 0.6)
                    .map(PerformanceLearningService.PerformanceMetrics::getStrategyType)
                    .collect(Collectors.toList());
                performance.setBestPerformingStrategies(bestStrategies);
                
                List<String> worstStrategies = symbolMetrics.stream()
                    .filter(m -> m.getWinRate() < 0.4)
                    .map(PerformanceLearningService.PerformanceMetrics::getStrategyType)
                    .collect(Collectors.toList());
                performance.setWorstPerformingStrategies(worstStrategies);
            } else {
                // Set defaults for new symbols
                performance.setWinRate(0.55); // Neutral win rate
                performance.setTotalTrades(0);
                performance.setAvgWin(BigDecimal.valueOf(0.025)); // 2.5%
                performance.setAvgLoss(BigDecimal.valueOf(0.015)); // 1.5%
                performance.setSharpeRatio(0.5);
            }
            
        } catch (Exception e) {
            logger.error("Error generating historical performance for {}: {}", symbol, e.getMessage());
            setDefaultHistoricalPerformance(performance);
        }
        
        return performance;
    }
    
    /**
     * Generate portfolio context
     */
    private PortfolioContext generatePortfolioContext(String symbol) {
        PortfolioContext context = new PortfolioContext();
        
        try {
            // Get open positions
            List<Position> openPositions = positionRepository.findByStatus("OPEN");
            context.setTotalOpenPositions(openPositions.size());
            
            // Calculate total exposure
            BigDecimal totalExposure = openPositions.stream()
                .map(p -> p.getSize().multiply(p.getCurrentPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            context.setTotalExposure(totalExposure);
            
            // Get enhanced account value as available capital
            BigDecimal availableCapital = riskManagementService.getEnhancedAccountValue();
            context.setAvailableCapital(availableCapital);
            
            // Calculate portfolio correlation
            Map<String, Map<String, Double>> correlations = riskManagementService.calculatePositionCorrelations();
            double avgCorrelation = correlations.values().stream()
                .flatMap(map -> map.values().stream())
                .filter(corr -> !corr.equals(1.0)) // Exclude self-correlation
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            context.setPortfolioCorrelation(avgCorrelation);
            
            // Find highly correlated assets
            List<String> correlatedAssets = correlations.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(symbol))
                .filter(entry -> entry.getValue().getOrDefault(symbol, 0.0) > 0.7)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            context.setHighlyCorrelatedAssets(correlatedAssets);
            
            // Risk distribution description
            context.setRiskDistribution(generateRiskDistributionDescription(openPositions, totalExposure));
            
        } catch (Exception e) {
            logger.error("Error generating portfolio context for {}: {}", symbol, e.getMessage());
            setDefaultPortfolioContext(context);
        }
        
        return context;
    }
    
    /**
     * Generate macro market conditions
     */
    private MacroConditions generateMacroConditions() {
        MacroConditions conditions = new MacroConditions();
        
        try {
            // This would ideally integrate with news/sentiment APIs
            // For now, provide reasonable defaults based on market analysis
            
            conditions.setMarketSentiment("NEUTRAL");
            conditions.setVolatilityRegime("NORMAL");
            conditions.setLiquidityConditions("NORMAL");
            conditions.setRiskOnOff("MIXED");
            
            // Sample market drivers
            List<String> drivers = Arrays.asList(
                "Technical momentum",
                "Market structure",
                "Volume patterns",
                "Risk management factors"
            );
            conditions.setMarketDrivers(drivers);
            
        } catch (Exception e) {
            logger.error("Error generating macro conditions: {}", e.getMessage());
            setDefaultMacroConditions(conditions);
        }
        
        return conditions;
    }
    
    /**
     * Generate AI recommendations based on all context
     */
    private AIRecommendations generateAIRecommendations(AIContext context) {
        AIRecommendations recommendations = new AIRecommendations();
        
        try {
            // Analyze confluence and regime to make recommendation
            MultiTimeframeService.ConfluenceResult confluence = context.getConfluence();
            MarketRegimeService.RegimeAnalysis regime = context.getMarketRegime();
            RiskMetrics riskMetrics = context.getRiskMetrics();
            
            // Primary recommendation based on confluence
            if (confluence != null && confluence.isHasConfluence()) {
                String signal = confluence.getFinalSignal();
                if (signal.contains("BUY")) {
                    recommendations.setPrimaryRecommendation("BUY");
                } else if (signal.contains("SELL")) {
                    recommendations.setPrimaryRecommendation("SELL");
                } else {
                    recommendations.setPrimaryRecommendation("HOLD");
                }
                recommendations.setConfidenceLevel(confluence.getConfluenceStrength());
            } else {
                recommendations.setPrimaryRecommendation("HOLD");
                recommendations.setConfidenceLevel(0.3);
            }
            
            // Supporting factors
            List<String> supportingFactors = new ArrayList<>();
            if (confluence != null && confluence.isHasConfluence()) {
                supportingFactors.add("Multi-timeframe confluence detected");
            }
            if (regime != null && regime.getConfidence() > 0.7) {
                supportingFactors.add("Clear market regime identified: " + regime.getRegime());
            }
            recommendations.setSupportingFactors(supportingFactors);
            
            // Risk factors
            List<String> riskFactors = new ArrayList<>();
            if (riskMetrics != null && "HIGH".equals(riskMetrics.getRiskLevel())) {
                riskFactors.add("High volatility environment");
            }
            if (context.getPortfolioContext() != null && context.getPortfolioContext().getPortfolioCorrelation() > 0.7) {
                riskFactors.add("High portfolio correlation risk");
            }
            recommendations.setRiskFactors(riskFactors);
            
            // Position sizing advice
            recommendations.setPositionSizingAdvice(generatePositionSizingAdvice(context));
            
            // Timing advice
            recommendations.setTimingAdvice(generateTimingAdvice(context));
            
            // Exit strategy advice
            recommendations.setExitStrategyAdvice(generateExitStrategyAdvice(context));
            
        } catch (Exception e) {
            logger.error("Error generating AI recommendations: {}", e.getMessage());
            setDefaultAIRecommendations(recommendations);
        }
        
        return recommendations;
    }
    
    /**
     * Helper methods for generating advice
     */
    private String generatePositionSizingAdvice(AIContext context) {
        RiskMetrics riskMetrics = context.getRiskMetrics();
        if (riskMetrics != null && "HIGH".equals(riskMetrics.getRiskLevel())) {
            return "Use reduced position size due to high volatility. Consider 50% of normal allocation.";
        } else if (context.getPortfolioContext() != null && context.getPortfolioContext().getPortfolioCorrelation() > 0.7) {
            return "Reduce position size due to high portfolio correlation. Diversification needed.";
        } else {
            return "Standard position sizing appropriate. Monitor risk metrics closely.";
        }
    }
    
    private String generateTimingAdvice(AIContext context) {
        if (context.getConfluence() != null && context.getConfluence().isHasConfluence()) {
            return "Strong timing signal detected. Consider entering position soon.";
        } else {
            return "Wait for clearer signals before entering. Market timing is uncertain.";
        }
    }
    
    private String generateExitStrategyAdvice(AIContext context) {
        MarketRegimeService.RegimeAnalysis regime = context.getMarketRegime();
        if (regime != null) {
                         switch (regime.getRegime()) {
                 case VOLATILE_MARKET:
                     return "Use tight stops and quick profit-taking in volatile conditions.";
                 case BULL_MARKET:
                 case BEAR_MARKET:
                     return "Use trailing stops to capture trend continuation while protecting profits.";
                 default:
                     return "Use standard risk-reward ratios with structured exit levels.";
             }
        }
        return "Implement systematic exit strategy with predefined stop-loss and take-profit levels.";
    }
    
    /**
     * Helper methods for defaults and risk assessment
     */
    private String assessRiskLevel(BigDecimal volatility, BigDecimal maxDrawdown) {
        double vol = volatility.doubleValue();
        double dd = maxDrawdown.doubleValue();
        
        if (vol > 0.05 || dd > 0.15) {
            return "HIGH";
        } else if (vol > 0.03 || dd > 0.10) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
    
    private void setDefaultRiskMetrics(RiskMetrics metrics) {
        metrics.setCurrentVolatility(BigDecimal.valueOf(0.03));
        metrics.setMaxDrawdown(BigDecimal.valueOf(0.05));
        metrics.setCorrelationRisk(BigDecimal.valueOf(0.5));
        metrics.setRiskLevel("MEDIUM");
    }
    
    private void setDefaultHistoricalPerformance(HistoricalPerformance performance) {
        performance.setWinRate(0.55);
        performance.setTotalTrades(0);
        performance.setAvgWin(BigDecimal.valueOf(0.025));
        performance.setAvgLoss(BigDecimal.valueOf(0.015));
        performance.setSharpeRatio(0.5);
    }
    
    private void setDefaultPortfolioContext(PortfolioContext context) {
        context.setTotalOpenPositions(0);
        context.setTotalExposure(BigDecimal.ZERO);
        context.setAvailableCapital(BigDecimal.valueOf(10000));
        context.setPortfolioCorrelation(0.0);
        context.setRiskDistribution("No open positions");
    }
    
    private void setDefaultMacroConditions(MacroConditions conditions) {
        conditions.setMarketSentiment("NEUTRAL");
        conditions.setVolatilityRegime("NORMAL");
        conditions.setLiquidityConditions("NORMAL");
        conditions.setRiskOnOff("MIXED");
        conditions.setMarketDrivers(Arrays.asList("Technical analysis", "Market structure"));
    }
    
    private void setDefaultAIRecommendations(AIRecommendations recommendations) {
        recommendations.setPrimaryRecommendation("HOLD");
        recommendations.setConfidenceLevel(0.5);
        recommendations.setSupportingFactors(Arrays.asList("Default analysis"));
        recommendations.setRiskFactors(Arrays.asList("Limited data available"));
        recommendations.setPositionSizingAdvice("Use conservative position sizing");
        recommendations.setTimingAdvice("Wait for clearer signals");
        recommendations.setExitStrategyAdvice("Use standard risk management");
    }
    
    private String generateRiskDistributionDescription(List<Position> positions, BigDecimal totalExposure) {
        if (positions.isEmpty()) {
            return "No open positions - low risk";
        }
        
        // Group by market type
        Map<String, Long> marketTypeCount = positions.stream()
            .collect(Collectors.groupingBy(
                p -> p.getMarketType() != null ? p.getMarketType() : "SPOT",
                Collectors.counting()
            ));
        
        return String.format("Risk distributed across %d positions: %s", 
            positions.size(), marketTypeCount.toString());
    }
    
    /**
     * Format context as text for AI analysis
     */
    public String formatContextForAI(AIContext context) {
        StringBuilder formatted = new StringBuilder();
        
        formatted.append("=== ENHANCED AI CONTEXT FOR ").append(context.getSymbol()).append(" ===\n\n");
        
        // Market regime
        if (context.getMarketRegime() != null) {
            MarketRegimeService.RegimeAnalysis regime = context.getMarketRegime();
            formatted.append("MARKET REGIME: ").append(regime.getRegime())
                     .append(" (").append(String.format("%.1f%%", regime.getConfidence() * 100)).append(" confidence)\n")
                     .append("Description: ").append(regime.getDescription()).append("\n\n");
        }
        
        // Confluence
        if (context.getConfluence() != null) {
            MultiTimeframeService.ConfluenceResult confluence = context.getConfluence();
            formatted.append("MULTI-TIMEFRAME CONFLUENCE: ")
                     .append(confluence.isHasConfluence() ? "YES" : "NO").append("\n")
                     .append("Signal: ").append(confluence.getFinalSignal())
                     .append(" (").append(String.format("%.1f%%", confluence.getConfluenceStrength() * 100)).append(" strength)\n")
                     .append("Reason: ").append(confluence.getConfluenceReason()).append("\n\n");
        }
        
        // Risk metrics
        if (context.getRiskMetrics() != null) {
            RiskMetrics risk = context.getRiskMetrics();
            formatted.append("RISK ASSESSMENT: ").append(risk.getRiskLevel()).append("\n")
                     .append("Volatility: ").append(String.format("%.2f%%", risk.getCurrentVolatility().doubleValue() * 100)).append("\n")
                     .append("Max Drawdown: ").append(String.format("%.2f%%", risk.getMaxDrawdown().doubleValue() * 100)).append("\n\n");
        }
        
        // Portfolio context
        if (context.getPortfolioContext() != null) {
            PortfolioContext portfolio = context.getPortfolioContext();
            formatted.append("PORTFOLIO CONTEXT:\n")
                     .append("Open Positions: ").append(portfolio.getTotalOpenPositions()).append("\n")
                     .append("Total Exposure: $").append(portfolio.getTotalExposure()).append("\n")
                     .append("Portfolio Correlation: ").append(String.format("%.2f", portfolio.getPortfolioCorrelation())).append("\n\n");
        }
        
        // AI recommendations
        if (context.getRecommendations() != null) {
            AIRecommendations rec = context.getRecommendations();
            formatted.append("AI RECOMMENDATIONS:\n")
                     .append("Primary: ").append(rec.getPrimaryRecommendation())
                     .append(" (").append(String.format("%.1f%%", rec.getConfidenceLevel() * 100)).append(" confidence)\n")
                     .append("Position Sizing: ").append(rec.getPositionSizingAdvice()).append("\n")
                     .append("Timing: ").append(rec.getTimingAdvice()).append("\n")
                     .append("Exit Strategy: ").append(rec.getExitStrategyAdvice()).append("\n");
        }
        
        return formatted.toString();
    }
} 