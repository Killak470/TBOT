package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.service.cache.PositionCacheService;
import com.tradingbot.backend.service.cache.PositionCacheService.PositionUpdateData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.repository.PositionRepository;
import com.tradingbot.backend.service.util.TechnicalAnalysisUtil;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Service for managing position risk, calculating metrics, and providing
 * risk management recommendations
 */
@Service
public class RiskManagementService {
    private static final Logger logger = LoggerFactory.getLogger(RiskManagementService.class);
    
    private final MexcApiClientService mexcApiClientService;
    private final PositionRepository positionRepository;
    private final ObjectMapper objectMapper;
    private final BybitApiClientService bybitApiClientService;
    private final PositionCacheService positionCacheService;
    private final BybitFuturesApiService bybitFuturesApiService;
    private final MexcFuturesApiService mexcFuturesApiService;
    
    // Risk settings (can be moved to application.properties)
    @Value("${risk.max.account.percentage:2.0}")
    private double maxAccountRiskPercentage; // Max % of account to risk per trade
    
    @Value("${risk.default.stop.loss.percentage:2.0}")
    private double defaultStopLossPercentage; // Default stop loss % from entry
    
    @Value("${risk.default.take.profit.percentage:4.0}")
    private double defaultTakeProfitPercentage; // Default take profit % from entry
    
    @Value("${risk.max.correlated.positions:3}")
    private int maxCorrelatedPositions; // Maximum number of highly correlated positions
    
    @Value("${risk.correlation.threshold:0.7}")
    private double correlationThreshold; // Correlation threshold above which positions are considered correlated
    
    @Value("${risk.volatility.lookback.days:30}")
    private int volatilityLookbackDays; // Days to look back for volatility calculation
    
    @Value("${risk.drawdown.max.percentage:20.0}")
    private double maxDrawdownPercentage; // Maximum acceptable drawdown percentage
    
    @Value("${risk.portfolio.max.deployed.percentage:30.0}")
    private double maxPortfolioDeployedPercentage; // Max % of account value to be deployed across all positions

    @Value("${risk.single.coin.max.percentage:5.0}")
    private double maxSingleCoinPercentage; // Max % of account value for a single position
    
    @Value("${risk.hedge.pnl.threshold.percent:15.0}") // Added for hedging PnL threshold
    private double hedgePnlThresholdPercent; // PnL loss % on a position to consider hedging

    private Map<String, List<BigDecimal>> priceHistoryCache = new HashMap<>();

    // Public getter for maxAccountRiskPercentage
    public double getMaxAccountRiskPercentage() {
        return maxAccountRiskPercentage;
    }

    // Fields for drawdown tracking
    private BigDecimal startOfDayPortfolioValue = BigDecimal.ZERO;
    private BigDecimal peakPortfolioValueToday = BigDecimal.ZERO;
    private BigDecimal startOfWeekPortfolioValue = BigDecimal.ZERO;
    private BigDecimal peakPortfolioValueThisWeek = BigDecimal.ZERO;
    private static final double DAILY_LOSS_LIMIT_PERCENT = 10.0;
    private static final double WEEKLY_DRAWDOWN_LIMIT_PERCENT = 20.0;
    
    // Getter for hedgePnlThresholdPercent
    public double getHedgePnlThresholdPercent() {
        return hedgePnlThresholdPercent;
    }

    public RiskManagementService(
            MexcApiClientService mexcApiClientService,
            PositionRepository positionRepository,
            BybitApiClientService bybitApiClientService,
            PositionCacheService positionCacheService,
            BybitFuturesApiService bybitFuturesApiService,
            MexcFuturesApiService mexcFuturesApiService) {
        this.mexcApiClientService = mexcApiClientService;
        this.positionRepository = positionRepository;
        this.objectMapper = new ObjectMapper();
        this.bybitApiClientService = bybitApiClientService;
        this.positionCacheService = positionCacheService;
        this.bybitFuturesApiService = bybitFuturesApiService;
        this.mexcFuturesApiService = mexcFuturesApiService;
        updatePortfolioSnapshot(); // Initial population of portfolio values
    }
    
    /**
     * Calculate the recommended position size based on account value,
     * risk tolerance, and market conditions.
     *
     * @param symbol Trading pair symbol
     * @param entryPrice Intended entry price
     * @param stopLossPrice Intended stop loss price
     * @param marketType The market type (e.g., "linear", "spot"), currently for future use.
     * @param exchange The exchange (e.g., "BYBIT"), currently for future use.
     * @return Recommended position size, or null if it cannot be calculated or is below minimum.
     */
    public BigDecimal calculatePositionSize(String symbol, BigDecimal entryPrice, BigDecimal stopLossPrice, String marketType, String exchange) {
        try {
            BigDecimal accountValue;
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                accountValue = "LINEAR".equalsIgnoreCase(marketType) ? bybitFuturesApiService.getAvailableBalance() : bybitApiClientService.getAvailableBalance();
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                accountValue = "FUTURES".equalsIgnoreCase(marketType) ? mexcFuturesApiService.getAvailableBalance() : mexcApiClientService.getAvailableBalance();
            } else {
                logger.error("Unsupported exchange '{}' for position size calculation.", exchange);
                return null;
            }

            if (accountValue == null || accountValue.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("Account value is zero or null for exchange {}. Cannot calculate position size.", exchange);
                return null;
            }

            BigDecimal riskAmount = accountValue.multiply(BigDecimal.valueOf(maxAccountRiskPercentage / 100.0));
            BigDecimal priceDifference = entryPrice.subtract(stopLossPrice).abs();

            if (priceDifference.compareTo(BigDecimal.ZERO) == 0) {
                logger.warn("Entry price and stop loss price are identical. Cannot calculate position size for {}.", symbol);
                return null;
            }

            BigDecimal positionSize = riskAmount.divide(priceDifference, 8, RoundingMode.DOWN);

            // Fetch instrument rules
            JsonNode lotSizeFilter = null;
            if ("BYBIT".equalsIgnoreCase(exchange) && "LINEAR".equalsIgnoreCase(marketType)) {
                JsonNode instrumentInfo = bybitFuturesApiService.getInstrumentInfo(symbol);
                lotSizeFilter = instrumentInfo.path("lotSizeFilter");
            } else if ("MEXC".equalsIgnoreCase(exchange) && "FUTURES".equalsIgnoreCase(marketType)) {
                 JsonNode instrumentInfo = mexcFuturesApiService.getContractDetails(symbol);
                 lotSizeFilter = instrumentInfo; // Assuming the root is the filter details for MEXC
            } else {
                logger.warn("Position size check for market type '{}' on exchange '{}' is not fully implemented. Bypassing min/step validation.", marketType, exchange);
                return positionSize.setScale(4, RoundingMode.DOWN); // Return with a reasonable precision
            }

            if (lotSizeFilter == null || lotSizeFilter.isMissingNode() || lotSizeFilter.isEmpty()) {
                logger.error("Could not fetch lot size filter for {} on {}. Cannot validate position size.", symbol, exchange);
                return null;
            }

            BigDecimal minOrderQty = new BigDecimal(lotSizeFilter.path("minOrderQty").asText("0"));
            BigDecimal qtyStep = new BigDecimal(lotSizeFilter.path("qtyStep").asText("0.00000001"));

            if (positionSize.compareTo(minOrderQty) < 0) {
                logger.warn("Calculated position size {} for {} is below the minimum order quantity of {}. No order will be placed.", positionSize, symbol, minOrderQty);
                return null; // Return null to indicate that no valid trade can be made
            }

            // Adjust size to match the quantity step
            BigDecimal remainder = positionSize.remainder(qtyStep);
            BigDecimal adjustedPositionSize = positionSize.subtract(remainder);

            logger.info("Calculated position size for {}: ideal={}, min={}, step={}, final={}", symbol, positionSize, minOrderQty, qtyStep, adjustedPositionSize);
            
            if (adjustedPositionSize.compareTo(minOrderQty) < 0) {
                 logger.warn("Adjusted position size {} for {} is now below the minimum order quantity of {}. No order will be placed.", adjustedPositionSize, symbol, minOrderQty);
                return null;
            }

            return adjustedPositionSize;

        } catch (Exception e) {
            logger.error("Error calculating position size for {} on {}: {}", symbol, exchange, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Calculate the stop loss price based on entry price and market volatility
     *
     * @param symbol Trading pair symbol
     * @param entryPrice Entry price
     * @param side Position side (LONG or SHORT)
     * @return Recommended stop loss price
     */
    public BigDecimal calculateStopLossPrice(String symbol, BigDecimal entryPrice, String side) 
            throws JsonProcessingException {
        // Get volatility for the symbol
        BigDecimal volatility = calculateVolatility(symbol, "BYBIT");
        
        // Higher volatility means wider stop loss
        // Lower volatility means tighter stop loss
        BigDecimal stopLossPercentage;
        if (volatility.compareTo(BigDecimal.valueOf(0.02)) > 0) {
            // High volatility
            stopLossPercentage = BigDecimal.valueOf(defaultStopLossPercentage * 1.5)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        } else if (volatility.compareTo(BigDecimal.valueOf(0.01)) > 0) {
            // Medium volatility
            stopLossPercentage = BigDecimal.valueOf(defaultStopLossPercentage)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        } else {
            // Low volatility
            stopLossPercentage = BigDecimal.valueOf(defaultStopLossPercentage * 0.75)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        }
        
        // Calculate stop loss price based on side
        if ("LONG".equals(side)) {
            return entryPrice.multiply(BigDecimal.ONE.subtract(stopLossPercentage));
        } else {
            return entryPrice.multiply(BigDecimal.ONE.add(stopLossPercentage));
        }
    }
    
    /**
     * Calculate the take profit price based on entry price, stop loss price,
     * and desired risk-reward ratio
     *
     * @param entryPrice Entry price
     * @param stopLossPrice Stop loss price
     * @param side Position side (LONG or SHORT)
     * @param riskRewardRatio Desired risk-reward ratio (default: 2.0)
     * @return Recommended take profit price
     */
    public BigDecimal calculateTakeProfitPrice(
            BigDecimal entryPrice, 
            BigDecimal stopLossPrice, 
            String side,
            BigDecimal riskRewardRatio) {
        
        if (riskRewardRatio == null) {
            riskRewardRatio = BigDecimal.valueOf(2.0); // Default risk-reward ratio
        }
        
        // Calculate the price distance from entry to stop loss
        BigDecimal riskDistance = entryPrice.subtract(stopLossPrice).abs();
        
        // Calculate reward distance (risk distance * risk-reward ratio)
        BigDecimal rewardDistance = riskDistance.multiply(riskRewardRatio);
        
        // Calculate take profit price based on side
        if ("LONG".equals(side)) {
            return entryPrice.add(rewardDistance);
        } else {
            return entryPrice.subtract(rewardDistance);
        }
    }
    
    /**
     * Calculate the correlation between positions
     * 
     * @return Map of correlation coefficients between symbols
     */
    public Map<String, Map<String, Double>> calculatePositionCorrelations() throws JsonProcessingException {
        // Get all open positions
        List<Position> openPositions = positionRepository.findByStatus("OPEN");
        
        // Get distinct symbols
        List<String> symbols = openPositions.stream()
            .map(Position::getSymbol)
            .distinct()
            .collect(Collectors.toList());
        
        // If we have fewer than 2 symbols, no correlation to calculate
        if (symbols.size() < 2) {
            return Collections.emptyMap();
        }
        
        // Get price history for each symbol
        Map<String, double[]> priceData = new HashMap<>();
        for (String symbol : symbols) {
            List<BigDecimal> prices = getPriceHistory(symbol, 30, "BYBIT"); // 30 days of daily prices
            
            // Convert to primitive double array
            double[] priceArray = new double[prices.size()];
            for (int i = 0; i < prices.size(); i++) {
                priceArray[i] = prices.get(i).doubleValue();
            }
            
            priceData.put(symbol, priceArray);
        }
        
        // Calculate correlation matrix
        Map<String, Map<String, Double>> correlationMatrix = new HashMap<>();
        PearsonsCorrelation correlation = new PearsonsCorrelation();
        
        for (String symbol1 : symbols) {
            Map<String, Double> symbolCorrelations = new HashMap<>();
            correlationMatrix.put(symbol1, symbolCorrelations);
            
            for (String symbol2 : symbols) {
                // Skip self-correlation
                if (symbol1.equals(symbol2)) {
                    symbolCorrelations.put(symbol2, 1.0);
                    continue;
                }
                
                double[] prices1 = priceData.get(symbol1);
                double[] prices2 = priceData.get(symbol2);
                
                // Ensure both arrays have the same length
                int minLength = Math.min(prices1.length, prices2.length);
                if (minLength < 5) {
                    // Not enough data points for reliable correlation
                    symbolCorrelations.put(symbol2, 0.0);
                    continue;
                }
                
                double[] adjusted1 = Arrays.copyOfRange(prices1, 0, minLength);
                double[] adjusted2 = Arrays.copyOfRange(prices2, 0, minLength);
                
                // Calculate correlation
                double corr = correlation.correlation(adjusted1, adjusted2);
                symbolCorrelations.put(symbol2, corr);
            }
        }
        
        return correlationMatrix;
    }
    
    /**
     * Check if adding a position for a symbol would exceed
     * the maximum number of correlated positions
     *
     * @param symbol Symbol to check
     * @return true if position can be added, false if it would exceed correlation limits
     */
    public boolean isWithinCorrelationLimits(String symbol) throws JsonProcessingException {
        // Get all open positions
        List<Position> openPositions = positionRepository.findByStatus("OPEN");
        
        // If we have no open positions, we're within limits
        if (openPositions.isEmpty()) {
            return true;
        }
        
        // Get price history for new symbol
        List<BigDecimal> newSymbolPrices = getPriceHistory(symbol, 30, "BYBIT");
        if (newSymbolPrices.size() < 5) {
            // Not enough data, assume within limits
            return true;
        }
        
        // Count how many existing positions are highly correlated with the new symbol
        int correlatedPositions = 0;
        PearsonsCorrelation correlation = new PearsonsCorrelation();
        
        for (Position position : openPositions) {
            String existingSymbol = position.getSymbol();
            List<BigDecimal> existingPrices = getPriceHistory(existingSymbol, 30, "BYBIT");
            
            if (existingPrices.size() < 5) {
                continue; // Not enough data
            }
            
            // Ensure both arrays have the same length
            int minLength = Math.min(newSymbolPrices.size(), existingPrices.size());
            
            double[] newPrices = new double[minLength];
            double[] exPrices = new double[minLength];
            
            for (int i = 0; i < minLength; i++) {
                newPrices[i] = newSymbolPrices.get(i).doubleValue();
                exPrices[i] = existingPrices.get(i).doubleValue();
            }
            
            // Calculate correlation
            double corr = correlation.correlation(newPrices, exPrices);
            
            // Check if correlation exceeds threshold
            if (Math.abs(corr) > correlationThreshold) {
                correlatedPositions++;
            }
        }
        
        return correlatedPositions < maxCorrelatedPositions;
    }
    
    /**
     * Calculate volatility for a symbol over a specified lookback period
     *
     * @param symbol Trading pair symbol
     * @param exchange The exchange the symbol belongs to
     * @return Volatility as a decimal (e.g., 0.05 = 5% daily volatility)
     */
    public BigDecimal calculateVolatility(String symbol, String exchange) throws JsonProcessingException {
        List<BigDecimal> prices = getPriceHistory(symbol, volatilityLookbackDays, exchange);
        
        if (prices.size() < 2) {
            // Not enough data, return default volatility
            return BigDecimal.valueOf(0.02); // 2% daily volatility
        }
        
        // Calculate daily returns
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal previousPrice = prices.get(i - 1);
            BigDecimal currentPrice = prices.get(i);
            
            if (previousPrice.compareTo(BigDecimal.ZERO) > 0) {
                double dailyReturn = currentPrice.subtract(previousPrice)
                    .divide(previousPrice, 8, RoundingMode.HALF_UP)
                    .doubleValue();
                returns.add(dailyReturn);
            }
        }
        
        // Calculate standard deviation of returns (volatility)
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (Double ret : returns) {
            stats.addValue(ret);
        }
        
        return BigDecimal.valueOf(stats.getStandardDeviation());
    }
    
    /**
     * Calculate maximum drawdown for a symbol over a specified lookback period
     *
     * @param symbol Trading pair symbol
     * @return Maximum drawdown as a decimal (e.g., 0.15 = 15% drawdown)
     */
    public BigDecimal calculateMaxDrawdown(String symbol) throws JsonProcessingException {
        List<BigDecimal> prices = getPriceHistory(symbol, volatilityLookbackDays, "BYBIT");
        
        if (prices.size() < 2) {
            // Not enough data
            return BigDecimal.ZERO;
        }
        
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal peak = prices.get(0);
        
        for (BigDecimal price : prices) {
            // Update peak if current price is higher
            if (price.compareTo(peak) > 0) {
                peak = price;
            }
            
            // Calculate drawdown from peak
            if (peak.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal drawdown = peak.subtract(price)
                    .divide(peak, 8, RoundingMode.HALF_UP);
                
                // Update max drawdown if current drawdown is larger
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }
        
        return maxDrawdown;
    }
    
    /**
     * Calculate Sharpe ratio for a position
     *
     * @param position The position to analyze
     * @return Sharpe ratio (risk-adjusted return)
     */
    public BigDecimal calculateSharpeRatio(Position position) throws JsonProcessingException {
        // Get historical prices for the position's symbol
        List<BigDecimal> prices = getPriceHistory(position.getSymbol(), 30, position.getExchange());
        
        if (prices.size() < 5 || position.getEntryPrice() == null || position.getCurrentPrice() == null) {
            // Not enough data
            return BigDecimal.ZERO;
        }
        
        // Calculate returns
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal previousPrice = prices.get(i - 1);
            BigDecimal currentPrice = prices.get(i);
            
            if (previousPrice.compareTo(BigDecimal.ZERO) > 0) {
                double dailyReturn = currentPrice.subtract(previousPrice)
                    .divide(previousPrice, 8, RoundingMode.HALF_UP)
                    .doubleValue();
                returns.add(dailyReturn);
            }
        }
        
        // Calculate statistics
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (Double ret : returns) {
            stats.addValue(ret);
        }
        
        double meanReturn = stats.getMean();
        double stdDev = stats.getStandardDeviation();
        
        // Risk-free rate (could be parameterized)
        double riskFreeRate = 0.02 / 365.0; // Assuming 2% annual risk-free rate
        
        // Calculate Sharpe ratio
        if (stdDev > 0) {
            double sharpeRatio = (meanReturn - riskFreeRate) / stdDev;
            // Annualize (multiply by sqrt(252) for daily returns)
            sharpeRatio *= Math.sqrt(252);
            return BigDecimal.valueOf(sharpeRatio);
        }
        
        return BigDecimal.ZERO;
    }
    
    /**
     * Scheduled task to update risk metrics for all open positions
     */
    @Scheduled(fixedDelay = 3600000) // Run every hour
    @Transactional
    public void updateRiskMetrics() {
        logger.info("Updating risk metrics for open positions");
        
        // Get all open positions
        List<Position> openPositions = positionRepository.findByStatus("OPEN");
        
        for (Position position : openPositions) {
            try {
                // Calculate and update volatility
                BigDecimal volatility = calculateVolatility(position.getSymbol(), position.getExchange());
                position.setVolatility(volatility);
                
                // Calculate and update Sharpe ratio
                BigDecimal sharpeRatio = calculateSharpeRatio(position);
                position.setSharpeRatio(sharpeRatio);
                
                // Calculate and update max drawdown
                BigDecimal maxDrawdown = calculateMaxDrawdown(position.getSymbol());
                position.setMaxDrawdown(maxDrawdown);
                
                // Update highest and lowest prices
                BigDecimal currentPrice = position.getCurrentPrice();
                if (currentPrice != null) {
                    if (position.getHighestPrice() == null || currentPrice.compareTo(position.getHighestPrice()) > 0) {
                        position.setHighestPrice(currentPrice);
                    }
                    
                    if (position.getLowestPrice() == null || currentPrice.compareTo(position.getLowestPrice()) < 0) {
                        position.setLowestPrice(currentPrice);
                    }
                }
                
                // Save updated position
                positionRepository.save(position);
                
            } catch (Exception e) {
                logger.error("Error updating risk metrics for position {}: {}", 
                    position.getId(), e.getMessage());
            }
        }
    }
    
    // Helper methods
    
    /**
     * Get account information including balances
     */
    private JsonNode getAccountInfo() throws JsonProcessingException {
        ObjectNode jsonResult = objectMapper.createObjectNode(); // Initialize jsonResult
        logger.info("RISK_MGMT: Fetching account info from Bybit (getWalletBalance UNIFIED)");
        ResponseEntity<String> response = bybitApiClientService.getWalletBalance("UNIFIED", null); 
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            logger.debug("RISK_MGMT: Raw Bybit getWalletBalance UNIFIED response: {}", response.getBody());

            // Bybit UNIFIED getWalletBalance structure: 
            // {"retCode":0,"retMsg":"OK","result":{"list":[{"accountType":"UNIFIED","memberId":"...","accountLtv":"...",
            // "coin":[{"coin":"USDT","equity":"...","usdValue":"...","walletBalance":"...", ... "availableToWithdraw":"..."},
            //          {"coin":"SUI","equity":"...","usdValue":"...","walletBalance":"...", ... "availableToWithdraw":"..."}]}]}
            // We need to transform this to what getAccountValue expects: {"balances": [{"asset": ..., "free": ..., "locked": ...}]}

            if (rootNode.has("result") && rootNode.get("result").has("list")) {
                ArrayNode bybitAccountList = (ArrayNode) rootNode.get("result").get("list");
                ArrayNode adaptedBalances = objectMapper.createArrayNode();

                if (bybitAccountList.size() > 0 && bybitAccountList.get(0).has("coin") && bybitAccountList.get(0).get("coin").isArray()) {
                    ArrayNode coinsArray = (ArrayNode) bybitAccountList.get(0).get("coin");
                    for (JsonNode bybitCoinBalance : coinsArray) {
                        ObjectNode adaptedBalance = objectMapper.createObjectNode();
                        String assetName = bybitCoinBalance.path("coin").asText();
                        
                        String balanceToUseStr; 
                        String availableToWithdrawStr = "0"; 

                        if ("USDT".equals(assetName) && bybitCoinBalance.hasNonNull("equity")) {
                            balanceToUseStr = bybitCoinBalance.path("equity").asText();
                            logger.debug("RISK_MGMT: Using 'equity' ({}) for USDT as total balance.", balanceToUseStr);
                        } else {
                            balanceToUseStr = bybitCoinBalance.path("walletBalance").asText();
                            logger.debug("RISK_MGMT: Using 'walletBalance' ({}) for {} as total balance.", balanceToUseStr, assetName);
                        }

                        if (balanceToUseStr == null || balanceToUseStr.isEmpty()) {
                            balanceToUseStr = "0"; 
                        }

                        String rawAvailableToWithdraw = bybitCoinBalance.path("availableToWithdraw").asText();
                        if (rawAvailableToWithdraw != null && !rawAvailableToWithdraw.isEmpty()) {
                            availableToWithdrawStr = rawAvailableToWithdraw;
                        } else {
                            availableToWithdrawStr = "0"; 
                        }
                        
                        logger.debug("RISK_MGMT: Processing coin: {}, ChosenBalanceStrToUse: '{}', RawAvailableToWithdraw: '{}' (becomes '{}')", 
                                     assetName, balanceToUseStr, rawAvailableToWithdraw, availableToWithdrawStr);

                        adaptedBalance.put("asset", assetName);
                        adaptedBalance.put("free", availableToWithdrawStr);
                        
                        BigDecimal totalBalanceDecimal = new BigDecimal(balanceToUseStr);
                        BigDecimal availableBalanceDecimal = new BigDecimal(availableToWithdrawStr);
                        
                        BigDecimal lockedDecimal = totalBalanceDecimal.subtract(availableBalanceDecimal);
                        if (lockedDecimal.compareTo(BigDecimal.ZERO) < 0) {
                            logger.warn("RISK_MGMT: Calculated locked amount for {} is negative ({}). Clamping to 0. Total: {}, Free: {}", 
                                        assetName, lockedDecimal, totalBalanceDecimal, availableBalanceDecimal);
                            lockedDecimal = BigDecimal.ZERO;
                            if (availableBalanceDecimal.compareTo(totalBalanceDecimal) > 0) {
                                adaptedBalance.put("free", totalBalanceDecimal.toPlainString()); 
                            }
                        }
                        adaptedBalance.put("locked", lockedDecimal.toPlainString());
                        adaptedBalances.add(adaptedBalance);

                        logger.debug("RISK_MGMT: Adapted coin: {}, free: {}, locked: {}", assetName, adaptedBalance.get("free").asText(), lockedDecimal.toPlainString());
                    } // End of for loop
                } // End of if (bybitAccountList.size() > 0 && bybitAccountList.get(0).has("coin") ...)
                // NOTE: The incorrect 'else { logger.error("Failed to get klines from Bybit for {}", ...); }'
                // NOTE: and 'catch (Exception e) { logger.error("Error fetching or parsing klines from Bybit for {}", ...); }'
                // NOTE: that were here have been removed.
                jsonResult.set("balances", adaptedBalances);
            } else { // This 'else' is for if (rootNode.has("result") && rootNode.get("result").has("list"))
                logger.warn("RISK_MGMT: getAccountInfo UNIFIED response does not contain expected 'result.list' structure. Body: {}", 
                    (response != null && response.getBody() != null ? response.getBody().substring(0, Math.min(response.getBody().length(), 1000)) : "null body"));
                // Return empty structure on error
                // ObjectNode errorResult = objectMapper.createObjectNode(); // jsonResult is already an ObjectNode
                jsonResult.set("balances", objectMapper.createArrayNode()); 
                return jsonResult;  // Return jsonResult which now contains an empty balances array
            }
        } else { // This 'else' is for if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null)
            logger.warn("RISK_MGMT: getAccountInfo UNIFIED response status: {}, body: {}", 
                response.getStatusCode(), response.getBody());
            // Return empty structure on error
            // ObjectNode errorResult = objectMapper.createObjectNode(); // jsonResult is already an ObjectNode
            jsonResult.set("balances", objectMapper.createArrayNode()); 
            return jsonResult; // Return jsonResult which now contains an empty balances array
        }
        // This was missing, ensure jsonResult is returned if the first if was successful.
        return jsonResult; 
    }
    
    /**
     * Get minimum order quantity for a symbol
     */
    private BigDecimal getMinimumOrderQuantity(String symbol) {
        // This method is now deprecated in favor of fetching live rules in calculatePositionSize
        logger.warn("Call to deprecated getMinimumOrderQuantity. Logic should fetch rules directly.");
        return BigDecimal.valueOf(0.001);
    }
    
    /**
     * Get maximum order quantity for a symbol based on available balance
     */
    private BigDecimal getMaximumOrderQuantity(String symbol, BigDecimal accountValue) 
            throws JsonProcessingException {
        // This method is now deprecated in favor of fetching live rules in calculatePositionSize
        logger.warn("Call to deprecated getMaximumOrderQuantity. Logic should fetch rules directly.");
        BigDecimal price = getAssetPrice(symbol);
        
        // Limit to 50% of account value as a safety measure
        return accountValue.multiply(BigDecimal.valueOf(0.5))
            .divide(price, 8, RoundingMode.DOWN);
    }
    
    /**
     * Get quantity precision for a symbol
     */
    private int getQuantityPrecision(String symbol) {
         // This method is now deprecated in favor of fetching live rules in calculatePositionSize
        logger.warn("Call to deprecated getQuantityPrecision. Logic should fetch rules directly.");
        return 4;
    }
    
    /**
     * Calculate optimal position size using Kelly Criterion
     */
    public BigDecimal calculateOptimalPositionSize(
            String symbol, 
            BigDecimal entryPrice, 
            BigDecimal stopLoss, 
            double winRate, 
            double avgWin, 
            double avgLoss,
            BigDecimal accountBalance) {
        
        try {
            // Kelly Criterion: f = (bp - q) / b
            // where b = avg win / avg loss, p = win rate, q = loss rate
            double b = avgWin / avgLoss;
            double p = winRate;
            double q = 1 - winRate;
            
            double kellyFraction = (b * p - q) / b;
            
            // Cap Kelly at 25% for safety and ensure minimum 1%
            kellyFraction = Math.min(kellyFraction, 0.25);
            kellyFraction = Math.max(kellyFraction, 0.01); // Min 1%
            
            // Calculate risk amount
            BigDecimal riskPercentage = entryPrice.subtract(stopLoss).abs()
                .divide(entryPrice, 8, RoundingMode.HALF_UP);
            
            BigDecimal optimalRisk = accountBalance.multiply(BigDecimal.valueOf(kellyFraction));
            
            // Position size = optimal risk / (entry price * risk percentage)
            BigDecimal positionSize = optimalRisk.divide(
                entryPrice.multiply(riskPercentage), 
                8, 
                RoundingMode.HALF_UP
            );
            
            logger.debug("Kelly position sizing for {}: Kelly fraction={}, position size={}", 
                        symbol, kellyFraction, positionSize);
            
            return positionSize;
            
        } catch (Exception e) {
            logger.error("Error calculating Kelly position size for {}: {}", symbol, e.getMessage());
            // Fallback to conservative approach
            return calculatePositionSize(symbol, entryPrice, stopLoss, "linear", "BYBIT");
        }
    }

    /**
     * Dynamic risk management implementation - Enhanced with actual trailing stops
     */
    public void implementDynamicRiskManagement(BotSignal signal) {
        try {
            logger.info("Implementing enhanced dynamic risk management for signal {}", signal.getId());
            
            // Get current market data
            BigDecimal currentPrice = getCurrentPrice(signal.getSymbol());
            BigDecimal currentATR = getCurrentATR(signal.getSymbol());
            BigDecimal currentVolatility = calculateVolatility(signal.getSymbol(), "BYBIT");
            
            // 1. Update trailing stops
            updateTrailingStops(signal, currentPrice, currentATR);
            
            // 2. Check for partial profit taking
            checkAndExecutePartialProfits(signal, currentPrice);
            
            // 3. Adjust position size for volatility changes
            adjustPositionForVolatilityChange(signal, currentVolatility);
            
            // 4. Update stop loss based on market structure
            updateStopLossBasedOnMarketStructure(signal, currentPrice);
            
            // 5. Monitor correlation risk
            monitorCorrelationRisk(signal);
            
            logger.debug("Enhanced dynamic risk management applied for signal {}", signal.getId());
            
        } catch (Exception e) {
            logger.error("Error implementing dynamic risk management for signal {}: {}", 
                        signal.getId(), e.getMessage());
        }
    }
    
    /**
     * Update trailing stops based on current price and ATR
     */
    private void updateTrailingStops(BotSignal signal, BigDecimal currentPrice, BigDecimal atr) {
        try {
            if (signal.getTrailingStopPrice() == null) {
                // Initialize trailing stop
                BigDecimal trailingDistance = atr.multiply(BigDecimal.valueOf(2.0));
                
                if ("LONG".equals(signal.getSide())) {
                    signal.setTrailingStopPrice(currentPrice.subtract(trailingDistance));
                } else {
                    signal.setTrailingStopPrice(currentPrice.add(trailingDistance));
                }
                
                logger.debug("Initialized trailing stop for {} at {}", signal.getSymbol(), signal.getTrailingStopPrice());
                return;
            }
            
            // Update trailing stop if price moved favorably
            if ("LONG".equals(signal.getSide())) {
                BigDecimal newTrailingStop = currentPrice.subtract(atr.multiply(BigDecimal.valueOf(2.0)));
                if (newTrailingStop.compareTo(signal.getTrailingStopPrice()) > 0) {
                    signal.setTrailingStopPrice(newTrailingStop);
                    logger.info("Updated trailing stop for {} LONG position to {}", 
                               signal.getSymbol(), newTrailingStop);
                }
            } else {
                BigDecimal newTrailingStop = currentPrice.add(atr.multiply(BigDecimal.valueOf(2.0)));
                if (newTrailingStop.compareTo(signal.getTrailingStopPrice()) < 0) {
                    signal.setTrailingStopPrice(newTrailingStop);
                    logger.info("Updated trailing stop for {} SHORT position to {}", 
                               signal.getSymbol(), newTrailingStop);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error updating trailing stops for signal {}: {}", signal.getId(), e.getMessage());
        }
    }
    
    /**
     * Check and execute partial profit taking
     */
    private void checkAndExecutePartialProfits(BotSignal signal, BigDecimal currentPrice) {
        try {
            if (signal.getEntryPrice() == null || signal.getProfitLevelsTaken() == null) {
                signal.setProfitLevelsTaken(new ArrayList<>());
                return;
            }
            
            List<BigDecimal> profitLevels = calculateProfitLevels(signal);
            List<Integer> takenLevels = signal.getProfitLevelsTaken();
            
            for (int i = 0; i < profitLevels.size(); i++) {
                if (takenLevels.contains(i)) {
                    continue; // Already taken this level
                }
                
                BigDecimal targetPrice = profitLevels.get(i);
                boolean shouldTakeProfit = false;
                
                if ("LONG".equals(signal.getSide()) && currentPrice.compareTo(targetPrice) >= 0) {
                    shouldTakeProfit = true;
                } else if ("SHORT".equals(signal.getSide()) && currentPrice.compareTo(targetPrice) <= 0) {
                    shouldTakeProfit = true;
                }
                
                if (shouldTakeProfit) {
                    // Reduce position size by 25% at each level
                    BigDecimal reductionPercentage = BigDecimal.valueOf(0.25);
                    BigDecimal newSize = signal.getPositionSize().multiply(BigDecimal.ONE.subtract(reductionPercentage));
                    signal.setPositionSize(newSize);
                    
                    takenLevels.add(i);
                    
                    logger.info("Partial profit taken for {} at level {} ({}), new position size: {}", 
                               signal.getSymbol(), i + 1, targetPrice, newSize);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error checking partial profits for signal {}: {}", signal.getId(), e.getMessage());
        }
    }
    
    /**
     * Update stop loss based on market structure
     */
    private void updateStopLossBasedOnMarketStructure(BotSignal signal, BigDecimal currentPrice) {
        try {
            // Get recent support/resistance levels
            List<BigDecimal> supportLevels = calculateSupportLevels(signal.getSymbol());
            List<BigDecimal> resistanceLevels = calculateResistanceLevels(signal.getSymbol());
            
            if ("LONG".equals(signal.getSide()) && !supportLevels.isEmpty()) {
                // For long positions, place stop below nearest support
                BigDecimal nearestSupport = supportLevels.stream()
                    .filter(level -> level.compareTo(currentPrice) < 0)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
                
                if (nearestSupport != null) {
                    BigDecimal structuralStop = nearestSupport.multiply(BigDecimal.valueOf(0.995)); // 0.5% below support
                    if (signal.getStopLossPrice() == null || structuralStop.compareTo(signal.getStopLossPrice()) > 0) {
                        signal.setStopLossPrice(structuralStop);
                        logger.debug("Updated stop loss for {} LONG to structural level: {}", 
                                   signal.getSymbol(), structuralStop);
                    }
                }
            } else if ("SHORT".equals(signal.getSide()) && !resistanceLevels.isEmpty()) {
                // For short positions, place stop above nearest resistance
                BigDecimal nearestResistance = resistanceLevels.stream()
                    .filter(level -> level.compareTo(currentPrice) > 0)
                    .min(BigDecimal::compareTo)
                    .orElse(null);
                
                if (nearestResistance != null) {
                    BigDecimal structuralStop = nearestResistance.multiply(BigDecimal.valueOf(1.005)); // 0.5% above resistance
                    if (signal.getStopLossPrice() == null || structuralStop.compareTo(signal.getStopLossPrice()) < 0) {
                        signal.setStopLossPrice(structuralStop);
                        logger.debug("Updated stop loss for {} SHORT to structural level: {}", 
                                   signal.getSymbol(), structuralStop);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Error updating structural stop loss for signal {}: {}", signal.getId(), e.getMessage());
        }
    }
    
    /**
     * Monitor correlation risk across portfolio
     */
    private void monitorCorrelationRisk(BotSignal signal) {
        try {
            List<Position> openPositions = getOpenPositions();
            if (openPositions.size() < 2) return;
            
            double totalCorrelation = calculatePortfolioCorrelation(openPositions);
            
            // If correlation is too high, consider reducing position sizes
            if (totalCorrelation > 0.8) {
                logger.warn("High correlation detected ({}), consider reducing position sizes", totalCorrelation);
                
                // Reduce position size by 10% if correlation is very high
                BigDecimal reductionFactor = BigDecimal.valueOf(0.9);
                signal.setPositionSize(signal.getPositionSize().multiply(reductionFactor));
                
                logger.info("Reduced position size for {} due to high correlation", signal.getSymbol());
            }
            
        } catch (Exception e) {
            logger.error("Error monitoring correlation risk for signal {}: {}", signal.getId(), e.getMessage());
        }
    }
    
    /**
     * Calculate support levels for a symbol
     */
    public List<BigDecimal> calculateSupportLevels(String symbol) {
        List<BigDecimal> supportLevels = new ArrayList<>();
        
        try {
            // Get recent price history
            List<BigDecimal> prices = getPriceHistory(symbol, 30, "BYBIT");
            if (prices.size() < 10) return supportLevels;
            
            // Simple support detection: find local lows
            for (int i = 2; i < prices.size() - 2; i++) {
                BigDecimal current = prices.get(i);
                BigDecimal prev1 = prices.get(i - 1);
                BigDecimal prev2 = prices.get(i - 2);
                BigDecimal next1 = prices.get(i + 1);
                BigDecimal next2 = prices.get(i + 2);
                
                // Check if current price is a local low
                if (current.compareTo(prev1) < 0 && current.compareTo(prev2) < 0 &&
                    current.compareTo(next1) < 0 && current.compareTo(next2) < 0) {
                    supportLevels.add(current);
                }
            }
            
            // Sort in descending order (nearest first)
            supportLevels.sort(Collections.reverseOrder());
            
        } catch (Exception e) {
            logger.error("Error calculating support levels for {}: {}", symbol, e.getMessage());
        }
        
        return supportLevels;
    }
    
    /**
     * Calculate resistance levels for a symbol
     */
    public List<BigDecimal> calculateResistanceLevels(String symbol) {
        List<BigDecimal> resistanceLevels = new ArrayList<>();
        
        try {
            // Get recent price history
            List<BigDecimal> prices = getPriceHistory(symbol, 30, "BYBIT");
            if (prices.size() < 10) return resistanceLevels;
            
            // Simple resistance detection: find local highs
            for (int i = 2; i < prices.size() - 2; i++) {
                BigDecimal current = prices.get(i);
                BigDecimal prev1 = prices.get(i - 1);
                BigDecimal prev2 = prices.get(i - 2);
                BigDecimal next1 = prices.get(i + 1);
                BigDecimal next2 = prices.get(i + 2);
                
                // Check if current price is a local high
                if (current.compareTo(prev1) > 0 && current.compareTo(prev2) > 0 &&
                    current.compareTo(next1) > 0 && current.compareTo(next2) > 0) {
                    resistanceLevels.add(current);
                }
            }
            
            // Sort in ascending order (nearest first)
            resistanceLevels.sort(BigDecimal::compareTo);
            
        } catch (Exception e) {
            logger.error("Error calculating resistance levels for {}: {}", symbol, e.getMessage());
        }
        
        return resistanceLevels;
    }
    
    /**
     * Get current price for a symbol
     */
    private BigDecimal getCurrentPrice(String symbol) {
        // Default to Bybit or make configurable
        try {
            return getCurrentPrice(symbol, "BYBIT");
        } catch (JsonProcessingException e) {
            logger.error("Error getting current price for {} on BYBIT: {}", symbol, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Calculate profit taking levels
     */
    private List<BigDecimal> calculateProfitLevels(BotSignal signal) {
        List<BigDecimal> levels = new ArrayList<>();
        
        if (signal.getEntryPrice() != null) {
            // Multiple profit levels: 1.5%, 3%, 5%
            if ("LONG".equals(signal.getSide())) {
                levels.add(signal.getEntryPrice().multiply(BigDecimal.valueOf(1.015))); // 1.5%
                levels.add(signal.getEntryPrice().multiply(BigDecimal.valueOf(1.03)));  // 3%
                levels.add(signal.getEntryPrice().multiply(BigDecimal.valueOf(1.05)));  // 5%
            } else {
                levels.add(signal.getEntryPrice().multiply(BigDecimal.valueOf(0.985))); // 1.5% down
                levels.add(signal.getEntryPrice().multiply(BigDecimal.valueOf(0.97)));  // 3% down
                levels.add(signal.getEntryPrice().multiply(BigDecimal.valueOf(0.95)));  // 5% down
            }
        }
        
        return levels;
    }
    
    /**
     * Enhanced account value calculation with risk adjustments
     */
    public BigDecimal getEnhancedAccountValue() throws JsonProcessingException {
        JsonNode accountInfo = getAccountInfo();
        BigDecimal baseValue = getAccountValue(accountInfo);
        
        // Apply risk adjustments based on current positions
        List<Position> openPositions = getOpenPositions();
        BigDecimal riskAdjustment = calculateRiskAdjustment(openPositions);
        
        return baseValue.multiply(riskAdjustment);
    }
    
    /**
     * Calculate risk adjustment factor based on open positions
     */
    private BigDecimal calculateRiskAdjustment(List<Position> positions) {
        if (positions.isEmpty()) {
            return BigDecimal.ONE; // No adjustment needed
        }
        
        // Calculate total exposure
        BigDecimal totalExposure = positions.stream()
            .map(p -> p.getSize().multiply(p.getCurrentPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Calculate correlation-based adjustment
        double correlationRisk = calculatePortfolioCorrelation(positions);
        
        // Higher correlation = higher risk = lower available capital
        BigDecimal correlationAdjustment = BigDecimal.valueOf(1.0 - (correlationRisk * 0.3));
        
        return correlationAdjustment.max(BigDecimal.valueOf(0.5)); // Minimum 50% of capital available
    }
    
    /**
     * Get current ATR for a symbol
     */
    private BigDecimal getCurrentATR(String symbol) {
        try {
            // This would typically fetch recent price data and calculate ATR
            // For now, return a reasonable default based on symbol type
            if (symbol.startsWith("BTC")) {
                return BigDecimal.valueOf(0.03); // 3% ATR for BTC
            } else if (symbol.startsWith("ETH")) {
                return BigDecimal.valueOf(0.035); // 3.5% ATR for ETH
            } else {
                return BigDecimal.valueOf(0.04); // 4% ATR for altcoins
            }
        } catch (Exception e) {
            logger.error("Error getting ATR for {}: {}", symbol, e.getMessage());
            return BigDecimal.valueOf(0.02); // Conservative fallback
        }
    }
    
    /**
     * Adjust position size for volatility changes
     */
    private void adjustPositionForVolatilityChange(BotSignal signal, BigDecimal currentVolatility) {
        try {
            BigDecimal originalVolatility = signal.getVolatilityAtEntry();
            
            if (originalVolatility != null && currentVolatility != null) {
                double volatilityChange = currentVolatility.subtract(originalVolatility)
                    .divide(originalVolatility, 4, RoundingMode.HALF_UP).doubleValue();
                
                // If volatility increased significantly, consider reducing position
                if (volatilityChange > 0.5) { // 50% increase in volatility
                    logger.info("High volatility increase detected for {}, consider position reduction", 
                               signal.getSymbol());
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error adjusting position for volatility change: {}", e.getMessage());
        }
    }
    
    /**
     * Get open positions
     */
    private List<Position> getOpenPositions() {
        // This needs to fetch from all relevant exchanges or a consolidated source
        // For now, assuming it gets all open positions the bot is aware of
        // This could be from PositionCacheService or directly from exchanges
        // If using PositionRepository, it depends on how positions are persisted and updated.
        // logger.warn("RISK_MGMT: getOpenPositions() is using positionRepository.findByStatus(\\"OPEN\\"). Ensure this reflects all current open positions across relevant exchanges for accurate portfolio calculations.");
        // return positionRepository.findByStatus("OPEN"); // Example: from a DB

        logger.debug("RISK_MGMT: Fetching open positions from PositionCacheService.");
        if (positionCacheService == null) {
            logger.error("RISK_MGMT: PositionCacheService is null in getOpenPositions. Cannot fetch positions.");
            return Collections.emptyList();
        }

        // Convert PositionUpdateData from cache to Position objects if needed by downstream methods
        // For calculating deployed value, we mainly need symbol, size, and exchange to get current price.
        // The PositionCacheService.PositionUpdateData itself has size, symbol, exchange.
        // Let's adapt by creating simplified Position objects or directly using PositionUpdateData if possible.
        // For now, creating simplified Position objects.
        List<PositionUpdateData> cachedPositions = positionCacheService.getAllPositions(); // Assuming getAllPositions() exists
        List<Position> openPositions = new ArrayList<>();
        for (PositionUpdateData pud : cachedPositions) {
            if (pud.getSize() != null && pud.getSize().compareTo(BigDecimal.ZERO) > 0) {
                Position pos = new Position();
                pos.setSymbol(pud.getSymbol());
                pos.setQuantity(pud.getSize()); // Changed from setSize to setQuantity
                pos.setExchange(pud.getExchange());
                // Set other fields if they are essential for the caller of getOpenPositions()
                // For portfolio heat, symbol, size, exchange are primary.
                openPositions.add(pos);
            }
        }
        return openPositions;
    }
    
    /**
     * Calculate portfolio correlation
     */
    private double calculatePortfolioCorrelation(List<Position> positions) {
        // Simplified correlation calculation
        // In a real implementation, this would calculate correlation between assets
        if (positions.size() <= 1) {
            return 0.0;
        }
        
        // Assume moderate correlation for same market type positions
        long spotCount = positions.stream()
            .filter(p -> "SPOT".equals(p.getMarketType()))
            .count();
        long futuresCount = positions.size() - spotCount;
        
        if (spotCount > 0 && futuresCount > 0) {
            return 0.5; // Mixed portfolio has medium correlation
        } else {
            return 0.7; // Same market type has higher correlation
        }
    }

    /**
     * Get win rate for a symbol based on historical performance from PositionRepository
     */
    public double getHistoricalWinRate(String symbol) {
        try {
            List<Position> closedPositions = positionRepository.findBySymbolAndStatus(symbol, "CLOSED");
            
            if (closedPositions == null || closedPositions.isEmpty()) {
                logger.info("RISK_MGMT: No closed positions found for symbol {} to calculate historical win rate. Returning default 0.55.", symbol);
                return 0.55; // Default if no history
            }
            
            long totalTrades = closedPositions.size();
            long winningTrades = closedPositions.stream()
                                    .filter(p -> p.getRealizedPnl() != null && p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                                    .count();
            
            if (totalTrades == 0) {
                return 0.55; // Avoid division by zero, default win rate
            }
            
            double winRate = (double) winningTrades / totalTrades;
            logger.info("RISK_MGMT: Calculated historical win rate for {}: {} ({} wins / {} total trades)", 
                        symbol, String.format("%.2f", winRate), winningTrades, totalTrades);
            return winRate;
            
        } catch (Exception e) {
            logger.error("RISK_MGMT: Error getting historical win rate for {}: {}. Returning default 0.55.", symbol, e.getMessage(), e);
            return 0.55; // Conservative default on error
        }
    }
    
    /**
     * Get average win/loss P&L amounts for a symbol based on historical performance.
     */
    public Map<String, Double> getHistoricalWinLossRatios(String symbol) {
        Map<String, Double> results = new HashMap<>();
        results.put("avgWinPnl", 0.0); // Default to 0 if no data or error
        results.put("avgLossPnl", 0.0);

        try {
            List<Position> closedPositions = positionRepository.findBySymbolAndStatus(symbol, "CLOSED");

            if (closedPositions == null || closedPositions.isEmpty()) {
                logger.info("RISK_MGMT: No closed positions found for symbol {} to calculate historical win/loss P&L. Returning defaults.", symbol);
                return results; // Return defaults (0.0)
            }

            BigDecimal totalWinningPnl = BigDecimal.ZERO;
            long winningTradesCount = 0;
            BigDecimal totalLosingPnl = BigDecimal.ZERO;
            long losingTradesCount = 0;

            for (Position p : closedPositions) {
                if (p.getRealizedPnl() != null) {
                    if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) {
                        totalWinningPnl = totalWinningPnl.add(p.getRealizedPnl());
                        winningTradesCount++;
                    } else if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                        totalLosingPnl = totalLosingPnl.add(p.getRealizedPnl()); // Still negative here
                        losingTradesCount++;
                    }
                }
            }

            if (winningTradesCount > 0) {
                results.put("avgWinPnl", totalWinningPnl.divide(BigDecimal.valueOf(winningTradesCount), 2, RoundingMode.HALF_UP).doubleValue());
            }
            if (losingTradesCount > 0) {
                // Average loss is typically represented as a positive number
                results.put("avgLossPnl", totalLosingPnl.abs().divide(BigDecimal.valueOf(losingTradesCount), 2, RoundingMode.HALF_UP).doubleValue());
            }

            logger.info("RISK_MGMT: Calculated historical win/loss P&L for {}: AvgWinPnl={}, AvgLossPnl={}", 
                        symbol, results.get("avgWinPnl"), results.get("avgLossPnl"));
            
        } catch (Exception e) {
            logger.error("RISK_MGMT: Error getting historical win/loss P&L for {}: {}. Returning defaults.", symbol, e.getMessage(), e);
            // Ensure defaults are set in case of partial calculation before error
            results.put("avgWinPnl", 0.0);
            results.put("avgLossPnl", 0.0);
        }
        
        return results;
    }

    /**
     * Validate if a trade meets risk management criteria
     * 
     * @param symbol Trading pair symbol
     * @param positionSize Proposed position size
     * @param exchange Exchange name
     * @param intendedSide Intended position side
     * @param accountBalance Account balance
     * @return true if trade is valid, false otherwise
     */
    public boolean validateTrade(String symbol, BigDecimal positionSize, String exchange, String intendedSide, BigDecimal accountBalance) {
        if (positionSize == null || positionSize.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("RISK_MGMT: Invalid position size for {}: {}. Must be positive.", symbol, positionSize);
            return false;
        }

        if (accountBalance == null || accountBalance.compareTo(BigDecimal.ZERO) <= 0) {
            logger.warn("RISK_MGMT: Account balance is zero or null. Cannot validate trade for {}.");
            return false; // Cannot perform risk checks without account balance
        }

        // 1. Max single position size relative to account value
        try {
            BigDecimal currentPrice = getCurrentPrice(symbol, exchange); // Helper to get current price
            if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("RISK_MGMT: Could not fetch current price for {} on {}. Cannot validate single position size.", symbol, exchange);
                return false; // Or handle as a pass with warning, depending on strictness
            }
            BigDecimal positionValue = positionSize.multiply(currentPrice);
            BigDecimal maxAllowedSinglePositionValue = accountBalance.multiply(BigDecimal.valueOf(maxSingleCoinPercentage / 100.0));

            if (positionValue.compareTo(maxAllowedSinglePositionValue) > 0) {
                logger.warn("RISK_MGMT: Proposed position value {} for {} exceeds max single coin percentage ({}% of account value {}). Limit: {}", 
                    positionValue, symbol, maxSingleCoinPercentage, accountBalance, maxAllowedSinglePositionValue);
                return false;
            }
        } catch (Exception e) {
            logger.error("RISK_MGMT: Error validating single position size for {}: {}", symbol, e.getMessage(), e);
            return false; // Fail validation on error
        }

        // 2. Max portfolio deployed capital
        try {
            BigDecimal currentTotalDeployedValue = BigDecimal.ZERO;
            // Assuming getOpenPositions() provides all open positions for the account across exchanges.
            // We then filter by the exchange of the current trade being validated.
            List<Position> openPositionsOnExchange = getOpenPositions().stream()
                .filter(p -> exchange.equalsIgnoreCase(p.getExchange()))
                .collect(Collectors.toList());

            for (Position pos : openPositionsOnExchange) {
                if (pos.getSize() != null && pos.getSize().compareTo(BigDecimal.ZERO) > 0) {
                    try {
                        BigDecimal price = getCurrentPrice(pos.getSymbol(), pos.getExchange()); // Use position's exchange
                        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                            currentTotalDeployedValue = currentTotalDeployedValue.add(pos.getSize().multiply(price));
                        }
                    } catch (Exception e) {
                        logger.warn("RISK_MGMT: Could not get price for existing position {} on exchange {} to calculate portfolio heat: {}", 
                                    pos.getSymbol(), pos.getExchange(), e.getMessage());
                        // Decide if this should be a hard fail or if we proceed with available data
                    }
                }
            }

            // Calculate the value of the proposed new position
            BigDecimal currentPriceOfNewTrade = getCurrentPrice(symbol, exchange);
            if (currentPriceOfNewTrade == null || currentPriceOfNewTrade.compareTo(BigDecimal.ZERO) <= 0) {
                 logger.warn("RISK_MGMT: Could not fetch current price for new trade symbol {} on {}. Cannot accurately check portfolio heat.", symbol, exchange);
                 return false; // Cannot assess portfolio heat without current price of new trade
            }
            BigDecimal newTradeValue = positionSize.multiply(currentPriceOfNewTrade);
            BigDecimal proposedPortfolioValue = currentTotalDeployedValue.add(newTradeValue);
            BigDecimal maxAllowedPortfolioValue = accountBalance.multiply(BigDecimal.valueOf(maxPortfolioDeployedPercentage / 100.0));

            if (proposedPortfolioValue.compareTo(maxAllowedPortfolioValue) > 0) {
                logger.warn("RISK_MGMT: Proposed portfolio value {} (current deployed {} + new trade {}) for exchange {} exceeds max portfolio deployed capital ({}% of account {}). Limit: {}",
                    proposedPortfolioValue, currentTotalDeployedValue, newTradeValue, exchange, maxPortfolioDeployedPercentage, accountBalance, maxAllowedPortfolioValue);
                return false;
            }
        } catch (Exception e) {
            logger.error("RISK_MGMT: Error calculating portfolio deployed capital for exchange {}: {}", exchange, e.getMessage(), e);
            return false; // Fail validation on error
        }

        // TODO: Add checks for:
        // - Correlation checks (if this new position increases correlation risk beyond limits)
        // - Circuit breaker checks (daily loss limit, consecutive loss limit)

        logger.info("RISK_MGMT: Trade validation for symbol {} (side {}), size {}, on exchange {} with account balance {} passed checks.", 
            symbol, intendedSide, positionSize, exchange, accountBalance);
        return true;
    }

    // Hel6per method to get current price (needs to be implemented robustly)
    // This is a simplified placeholder. In a real system, this would call the appropriate API client.
    public BigDecimal getCurrentPrice(String symbol, String exchange) throws JsonProcessingException {
        ResponseEntity<String> response;
        if ("BYBIT".equalsIgnoreCase(exchange)) {
            response = bybitApiClientService.getLatestPrice(symbol, exchange);
        } else if ("MEXC".equalsIgnoreCase(exchange)) {
            response = mexcApiClientService.getLatestPrice(symbol, exchange); // Added exchange
        } else {
            logger.warn("Unsupported exchange: {}", exchange);
            return null; // Or throw exception
        }

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            if (root.hasNonNull("result") && root.get("result").hasNonNull("list") && 
                root.get("result").get("list").isArray() && !root.get("result").get("list").isEmpty()) {
                JsonNode tickerInfo = root.get("result").get("list").get(0);
                if (tickerInfo.hasNonNull("lastPrice")) {
                    return new BigDecimal(tickerInfo.get("lastPrice").asText());
                }
            }
        }
        logger.warn("RISK_MGMT: Could not get price for {} from {}", symbol, exchange);
        return null;
    }

    /**
     * Calculates the Average True Range (ATR) for a given symbol and interval.
     *
     * @param symbol The trading pair symbol (e.g., BTCUSDT).
     * @param exchange The exchange name (e.g., "BYBIT", "MEXC").
     * @param interval The kline interval (e.g., "1h", "4h", "1D").
     * @param period The period for ATR calculation (typically 14).
     * @return The calculated ATR as a BigDecimal, or null if calculation fails.
     * @throws JsonProcessingException If there's an error fetching or parsing kline data.
     */
    public BigDecimal calculateATRForSymbol(String symbol, String exchange, String interval, int period) throws JsonProcessingException {
        logger.debug("RISK_MGMT: Calculating ATR for symbol={}, exchange={}, interval={}, period={}", symbol, exchange, interval, period);

        // Determine the number of klines to fetch. Need at least 'period + 1' for TR calculation,
        // plus some buffer for stability and to ensure enough data for smoothing if underlying TA util needs more history.
        // A common practice is to fetch more data, e.g., period + 100 or 2 * period.
        int klinesToFetch = period + 100; // Fetch a decent amount of historical data.

        List<BigDecimal> highPrices = new ArrayList<>();
        List<BigDecimal> lowPrices = new ArrayList<>();
        List<BigDecimal> closePrices = new ArrayList<>();

        String effectiveSymbol = symbol;
        // Symbol normalization logic might be needed here based on exchange requirements, similar to getPriceHistory.
        // For Bybit, V5 kline API expects symbols like BTCUSDT. For MEXC spot, 1000PEPEUSDT might need to be PEPEUSDT.
        if ("MEXC".equalsIgnoreCase(exchange) && !symbol.contains("_")) { // Assuming non-futures
             if (symbol.startsWith("1000") && symbol.endsWith("USDT") && symbol.length() > 7) {
                effectiveSymbol = symbol.substring(4);
                logger.debug("RISK_MGMT_ATR: Normalized MEXC spot symbol from {} to {}", symbol, effectiveSymbol);
            }
        }
        // Bybit V5 usually handles common formats like BTCUSDT directly for spot/linear.

        try {
            ResponseEntity<String> response;
            if ("BYBIT".equalsIgnoreCase(exchange)) {
                // Bybit V5 klines: category can be spot, linear, inverse. Assume linear for futures, spot otherwise.
                // Interval needs to be in Bybit's format (e.g., "D" for daily, "60" for 1 hour).
                String bybitInterval = convertIntervalToBybitFormat(interval);
                String category = effectiveSymbol.endsWith("USDT") ? "linear" : "spot"; // Basic inference, might need refinement
                 if (effectiveSymbol.contains("_")) { // if it explicitly contains _, it's likely a MEXC futures symbol, but this is Bybit path
                    category = "linear"; 
                    effectiveSymbol = effectiveSymbol.split("_")[0] + "USDT"; // e.g. BTC_PERP to BTCUSDT for bybit
                }
                logger.debug("RISK_MGMT_ATR: Fetching klines from Bybit for ATR. Symbol: {}, Category: {}, Interval: {}, Limit: {}", effectiveSymbol, category, bybitInterval, klinesToFetch);
                response = bybitApiClientService.getKlineData(effectiveSymbol, bybitInterval, klinesToFetch, null, null, category);
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                String mexcInterval = convertIntervalToMexcFormat(interval); // Ensure this helper exists or implement it
                // MEXC klines: spot might use getKlineData, futures would need a different method from MexcFuturesApiService if available
                if (effectiveSymbol.contains("_")) { // Indicates futures symbol for MEXC
                    logger.warn("RISK_MGMT_ATR: MEXC Futures kline fetching for ATR not yet fully implemented for symbol {}. Using spot endpoint as placeholder.", effectiveSymbol);
                    // response = mexcFuturesApiService.getKlineData(effectiveSymbol, mexcInterval, klinesToFetch, null); // Hypothetical
                    // Fallback to spot or throw error for now if MEXC futures klines are needed for ATR
                     return null; // Or throw specific exception
                } else {
                    logger.debug("RISK_MGMT_ATR: Fetching klines from MEXC Spot for ATR. Symbol: {}, Interval: {}, Limit: {}", effectiveSymbol, mexcInterval, klinesToFetch);
                    response = mexcApiClientService.getKlineData(effectiveSymbol, mexcInterval, klinesToFetch, null);
                }
            } else {
                logger.error("RISK_MGMT_ATR: Unsupported exchange '{}' for ATR calculation.", exchange);
                return null;
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                JsonNode klineArrayNode = null;

                if ("BYBIT".equalsIgnoreCase(exchange)) {
                    klineArrayNode = rootNode.path("result").path("list");
                } else if ("MEXC".equalsIgnoreCase(exchange)) {
                    // MEXC Spot kline response is typically a direct array.
                    klineArrayNode = rootNode; 
                }

                if (klineArrayNode == null || klineArrayNode.isMissingNode() || !klineArrayNode.isArray() || klineArrayNode.isEmpty()) {
                    logger.warn("RISK_MGMT_ATR: Kline data array not found, not an array, or empty for {}-{}. Response: {}", effectiveSymbol, interval, response.getBody());
                    return null;
                }

                for (JsonNode klineData : klineArrayNode) {
                    if ("BYBIT".equalsIgnoreCase(exchange)) {
                        // Bybit: [timestamp, open, high, low, close, volume, turnover]
                        if (klineData.isArray() && klineData.size() >= 5) {
                            highPrices.add(new BigDecimal(klineData.get(2).asText()));
                            lowPrices.add(new BigDecimal(klineData.get(3).asText()));
                            closePrices.add(new BigDecimal(klineData.get(4).asText()));
                        }
                    } else if ("MEXC".equalsIgnoreCase(exchange)) {
                        // MEXC Spot: [openTime, open, high, low, close, volume, closeTime, quoteAssetVolume, ...]
                        if (klineData.isArray() && klineData.size() >= 5) {
                            highPrices.add(new BigDecimal(klineData.get(2).asText()));
                            lowPrices.add(new BigDecimal(klineData.get(3).asText()));
                            closePrices.add(new BigDecimal(klineData.get(4).asText()));
                        }
                    }
                }
                // Bybit returns newest first, reverse to have oldest first for TA utils if that's the convention of TechnicalAnalysisUtil.calculateATR
                // The TA util expects oldest first. Let's ensure data is chronological.
                if ("BYBIT".equalsIgnoreCase(exchange)) {
                    Collections.reverse(highPrices);
                    Collections.reverse(lowPrices);
                    Collections.reverse(closePrices);
                }
                // MEXC klines are usually oldest first.

                if (highPrices.size() < period + 1) {
                    logger.warn("RISK_MGMT_ATR: Not enough kline data points ({}) after parsing for symbol {} to calculate ATR with period {}. Need at least {}.", 
                                highPrices.size(), effectiveSymbol, period, period + 1);
                    return null;
                }

                // Convert List<BigDecimal> to List<Double> for TechnicalAnalysisUtil.calculateATR
                List<Double> highPricesDouble = highPrices.stream().map(BigDecimal::doubleValue).collect(Collectors.toList());
                List<Double> lowPricesDouble = lowPrices.stream().map(BigDecimal::doubleValue).collect(Collectors.toList());
                List<Double> closePricesDouble = closePrices.stream().map(BigDecimal::doubleValue).collect(Collectors.toList());

                double atrValue = TechnicalAnalysisUtil.calculateATR(highPricesDouble, lowPricesDouble, closePricesDouble, period);
                logger.info("RISK_MGMT_ATR: Calculated ATR for symbol {} = {}", effectiveSymbol, atrValue);
                return BigDecimal.valueOf(atrValue);

            } else {
                logger.error("RISK_MGMT_ATR: Failed to fetch klines for {} from {}. Status: {}, Body: {}", effectiveSymbol, exchange, response.getStatusCode(), response.getBody());
                return null;
            }
        } catch (IllegalArgumentException e) {
            logger.error("RISK_MGMT_ATR: Invalid argument for ATR calculation for symbol {}: {}", effectiveSymbol, e.getMessage(), e);
            return null;
        } catch (Exception e) { // Catch any other exception during the process
            logger.error("RISK_MGMT_ATR: Exception calculating ATR for symbol {}: {}", effectiveSymbol, e.getMessage(), e);
            // Re-throw JsonProcessingException if it's the cause, otherwise wrap or return null
            if (e instanceof JsonProcessingException) {
                throw (JsonProcessingException) e;
            }
            return null;
        }
    }

    // Helper to convert common interval strings to Bybit's format
    // (e.g., "1m", "1h", "1d" to "1", "60", "D")
    private String convertIntervalToBybitFormat(String interval) {
        if (interval == null) return "60"; // Default to 1 hour
        switch (interval.toLowerCase()) {
            case "1m": return "1";
            case "3m": return "3";
            case "5m": return "5";
            case "15m": return "15";
            case "30m": return "30";
            case "1h": return "60";
            case "2h": return "120";
            case "4h": return "240";
            case "6h": return "360"; // Bybit might not support 6h directly, 240 (4h) or 720 (12h) might be alternatives
            case "12h": return "720";
            case "1d": return "D";
            case "1w": return "W";
            case "1M": return "M"; // Check if Bybit uses "M" or "MON"
            default:
                logger.warn("RISK_MGMT_ATR: Unknown interval format for Bybit: '{}', using default '60' (1 hour).", interval);
                return "60"; // Default or throw error
        }
    }

    // Helper to convert common interval strings to MEXC's format
    // (e.g., "1m", "1h", "1d" to "1m", "1h", "1d" - MEXC often uses common strings)
    // This is a placeholder, actual MEXC formats should be verified from their API docs.
    private String convertIntervalToMexcFormat(String interval) {
        if (interval == null) return "1h"; // Default
        // MEXC spot API intervals: 1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w, 1M
        // MEXC futures API intervals: Min1, Min5, Min15, Min30, Min60, Hour4, Day1, Week1, Month1
        // This function might need to differentiate based on spot/futures if called for both.
        // Assuming spot usage for now based on mexcApiClientService.getKlineData.
        String lowerInterval = interval.toLowerCase();
        switch (lowerInterval) {
            case "1m": case "5m": case "15m": case "30m":
            case "1h": case "4h": case "1d": case "1w": case "1M":
                return lowerInterval; // MEXC spot seems to use these directly
            // Mapping some common alternatives if needed
            case "d": return "1d";
            case "w": return "1w";
            case "m": return "1M";
            default:
                logger.warn("RISK_MGMT_ATR: Unknown interval format for MEXC: '{}', using default '1h'.", interval);
                return "1h";
        }
    }

    // Scheduled tasks for updating drawdown tracking values
    @Scheduled(cron = "0 0 0 * * ?", zone = "UTC") // Daily at midnight UTC
    public void dailyPortfolioReset() {
        logger.info("RISK_MGMT: Performing daily portfolio reset for drawdown tracking.");
        // updatePortfolioSnapshot(); // getCurrentPortfolioValueAndUpdatePeaks called by getCurrentPortfolioValue below will handle peak updates implicitly
        this.startOfDayPortfolioValue = getCurrentPortfolioValueAndUpdatePeaks(); // Use method that also updates peaks
        // peakPortfolioValueToday is reset by setting it to startOfDayPortfolioValue, then it grows with current value
        this.peakPortfolioValueToday = this.startOfDayPortfolioValue; 
        logger.info("RISK_MGMT: Start of Day Portfolio Value: {}, Peak Today Reset to: {}", this.startOfDayPortfolioValue, this.peakPortfolioValueToday);

    }

    @Scheduled(cron = "0 0 0 ? * MON", zone = "UTC") // Weekly on Monday at midnight UTC
    public void weeklyPortfolioReset() {
        logger.info("RISK_MGMT: Performing weekly portfolio reset for drawdown tracking.");
        this.startOfWeekPortfolioValue = getCurrentPortfolioValueAndUpdatePeaks(); // Use method that also updates peaks
        // peakPortfolioValueThisWeek is reset by setting it to startOfWeekPortfolioValue
        this.peakPortfolioValueThisWeek = this.startOfWeekPortfolioValue;
        logger.info("RISK_MGMT: Start of Week Portfolio Value: {}, Peak This Week Reset to: {}", this.startOfWeekPortfolioValue, this.peakPortfolioValueThisWeek);
    }

    // Helper to get current portfolio value and update peaks
    private BigDecimal getCurrentPortfolioValueAndUpdatePeaks() {
        BigDecimal currentValue = getCurrentPortfolioValueInternal(); // Changed to avoid recursion with public getCurrentPortfolioValue
        if (this.peakPortfolioValueToday == null || this.peakPortfolioValueToday.compareTo(BigDecimal.ZERO) == 0 || currentValue.compareTo(this.peakPortfolioValueToday) > 0) {
            this.peakPortfolioValueToday = currentValue;
             if(currentValue.compareTo(BigDecimal.ZERO) > 0) logger.debug("RISK_MGMT: New peak portfolio value today: {}", this.peakPortfolioValueToday);
        }
        if (this.peakPortfolioValueThisWeek == null || this.peakPortfolioValueThisWeek.compareTo(BigDecimal.ZERO) == 0 || currentValue.compareTo(this.peakPortfolioValueThisWeek) > 0) {
            this.peakPortfolioValueThisWeek = currentValue;
            if(currentValue.compareTo(BigDecimal.ZERO) > 0) logger.debug("RISK_MGMT: New peak portfolio value this week: {}", this.peakPortfolioValueThisWeek);
        }
        return currentValue;
    }
    
    // Renamed from getAccountValue for clarity as it's used for portfolio value now
    // This is the public accessor, it will also ensure peaks are updated.
    public BigDecimal getCurrentPortfolioValue() {
        return getCurrentPortfolioValueAndUpdatePeaks();
    }
    
    // Internal method to fetch value without triggering recursive peak updates
    private BigDecimal getCurrentPortfolioValueInternal() {
        try {
            JsonNode accountInfo = getAccountInfo(); // This fetches from the primary exchange configured
            return getAccountValue(accountInfo); // This parses the specific structure
        } catch (Exception e) {
            logger.error("RISK_MGMT: Error fetching current portfolio value: {}. Returning zero.", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // Initialize snapshot (e.g. on startup or PostConstruct)
    // @PostConstruct // Alternatively, use PostConstruct if dependencies are ready
    private void updatePortfolioSnapshot() {
        BigDecimal currentValue = getCurrentPortfolioValueInternal(); // Use internal getter
        if (this.startOfDayPortfolioValue.compareTo(BigDecimal.ZERO) == 0) this.startOfDayPortfolioValue = currentValue;
        if (this.peakPortfolioValueToday.compareTo(BigDecimal.ZERO) == 0) this.peakPortfolioValueToday = currentValue;
        if (this.startOfWeekPortfolioValue.compareTo(BigDecimal.ZERO) == 0) this.startOfWeekPortfolioValue = currentValue;
        if (this.peakPortfolioValueThisWeek.compareTo(BigDecimal.ZERO) == 0) this.peakPortfolioValueThisWeek = currentValue;
        logger.info("RISK_MGMT: Portfolio snapshot updated. StartOfDay: {}, PeakToday: {}, StartOfWeek: {}, PeakThisWeek: {}", 
            this.startOfDayPortfolioValue, this.peakPortfolioValueToday, this.startOfWeekPortfolioValue, this.peakPortfolioValueThisWeek);
    }

    public boolean isDailyLossLimitExceeded() {
        BigDecimal currentValue = getCurrentPortfolioValueAndUpdatePeaks(); // Ensures peaks are up-to-date
        if (this.peakPortfolioValueToday != null && this.peakPortfolioValueToday.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dailyDrawdown = this.peakPortfolioValueToday.subtract(currentValue)
                                     .divide(this.peakPortfolioValueToday, 4, RoundingMode.HALF_UP)
                                     .multiply(BigDecimal.valueOf(100));
            logger.debug("RISK_MGMT: Daily Drawdown Check: CurrentValue={}, PeakToday={}, Drawdown={}%, Limit={}%", 
                currentValue, this.peakPortfolioValueToday, dailyDrawdown, DAILY_LOSS_LIMIT_PERCENT);
            if (dailyDrawdown.doubleValue() >= DAILY_LOSS_LIMIT_PERCENT) { // Use >= for the limit
                logger.warn("RISK_MGMT: DAILY LOSS LIMIT EXCEEDED! Drawdown: {}%", dailyDrawdown);
                return true;
            }
        }
        return false;
    }

    public boolean isWeeklyDrawdownLimitExceeded() {
        BigDecimal currentValue = getCurrentPortfolioValueAndUpdatePeaks(); // Ensures peaks are up-to-date
        if (this.peakPortfolioValueThisWeek != null && this.peakPortfolioValueThisWeek.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal weeklyDrawdown = this.peakPortfolioValueThisWeek.subtract(currentValue)
                                      .divide(this.peakPortfolioValueThisWeek, 4, RoundingMode.HALF_UP)
                                      .multiply(BigDecimal.valueOf(100));
            logger.debug("RISK_MGMT: Weekly Drawdown Check: CurrentValue={}, PeakThisWeek={}, Drawdown={}%, Limit={}%", 
                currentValue, this.peakPortfolioValueThisWeek, weeklyDrawdown, WEEKLY_DRAWDOWN_LIMIT_PERCENT);
            if (weeklyDrawdown.doubleValue() >= WEEKLY_DRAWDOWN_LIMIT_PERCENT) { // Use >= for the limit
                logger.warn("RISK_MGMT: WEEKLY DRAWDOWN LIMIT EXCEEDED! Drawdown: {}%", weeklyDrawdown);
                return true;
            }
        }
        return false;
    }

    // STUB METHOD -> Restoring actual logic
    private BigDecimal getAccountValue(JsonNode accountInfo) {
        BigDecimal totalValue = BigDecimal.ZERO;
        if (accountInfo != null && accountInfo.has("balances") && accountInfo.get("balances").isArray()) {
            for (JsonNode balanceNode : accountInfo.get("balances")) {
                try {
                    String asset = balanceNode.path("asset").asText();
                    BigDecimal free = new BigDecimal(balanceNode.path("free").asText("0"));
                    BigDecimal locked = new BigDecimal(balanceNode.path("locked").asText("0"));
                    BigDecimal assetTotal = free.add(locked);

                    if (assetTotal.compareTo(BigDecimal.ZERO) > 0) {
                        if ("USDT".equalsIgnoreCase(asset)) {
                            totalValue = totalValue.add(assetTotal);
                        } else {
                            // Fetch price of non-USDT asset and convert to USDT value
                            BigDecimal priceInUsdt = getAssetPrice(asset + "USDT"); // Assumes asset pair is like BTCUSDT
                            if (priceInUsdt != null && priceInUsdt.compareTo(BigDecimal.ZERO) > 0) {
                                totalValue = totalValue.add(assetTotal.multiply(priceInUsdt));
                                logger.debug("RISK_MGMT: Converted {} {} to USDT: {} (Price: {})", assetTotal, asset, assetTotal.multiply(priceInUsdt), priceInUsdt);
                            } else {
                                logger.warn("RISK_MGMT: Could not get USDT price for asset: {}. It will not be included in total account value.", asset);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("RISK_MGMT: Error processing balance node in getAccountValue: {} - {}", balanceNode, e.getMessage(), e);
                }
            }
        }
        logger.info("RISK_MGMT: Calculated total account value (getAccountValue): {}", totalValue);
        return totalValue;
    }

    // STUB METHOD -> Restoring actual logic
    private List<BigDecimal> getPriceHistory(String symbol, int days, String exchange) throws JsonProcessingException {
        logger.debug("RISK_MGMT: getPriceHistory called for symbol: {}, days: {}, exchange: {}", symbol, days, exchange);
        List<BigDecimal> closePrices = new ArrayList<>();

        if (symbol == null || symbol.isEmpty() || days <= 0 || exchange == null || exchange.isEmpty()) {
            logger.warn("RISK_MGMT: Invalid parameters for getPriceHistory. Symbol: {}, Days: {}, Exchange: {}", symbol, days, exchange);
            return closePrices; // Return empty list for invalid input
        }

        String effectiveSymbol = symbol; // Symbol might need formatting based on exchange

        try {
            ResponseEntity<String> responseEntity = null;
            String interval;

            if ("BYBIT".equalsIgnoreCase(exchange)) {
                interval = "D"; // Daily interval for Bybit
                String category = effectiveSymbol.endsWith("USDT") ? "linear" : "spot";
                if (effectiveSymbol.contains("_")) {
                    category = "linear";
                    effectiveSymbol = effectiveSymbol.replace("_", ""); 
                }
                logger.debug("RISK_MGMT: Fetching {} klines from Bybit for getPriceHistory. Symbol: {}, Category: {}, Interval: {}, Limit: {}", days, effectiveSymbol, category, interval, days);
                responseEntity = bybitApiClientService.getKlineData(effectiveSymbol, interval, days, null, null, category);
            
            } else if ("MEXC".equalsIgnoreCase(exchange)) {
                interval = "1D"; // MEXC usually uses "1D" for daily for spot. verify this.
                if (effectiveSymbol.startsWith("1000") && effectiveSymbol.endsWith("USDT") && effectiveSymbol.length() > 7) {
                     effectiveSymbol = effectiveSymbol.substring(4);
                     logger.debug("RISK_MGMT: Normalized MEXC spot symbol from {} to {} for getPriceHistory", symbol, effectiveSymbol);
                }
                logger.debug("RISK_MGMT: Fetching {} klines from MEXC Spot for getPriceHistory. Symbol: {}, Interval: {}, Limit: {}", days, effectiveSymbol, interval, days);
                responseEntity = mexcApiClientService.getSpotKlines(effectiveSymbol, interval, null, null, days);

            } else {
                logger.warn("RISK_MGMT: Unsupported exchange '{}' for getPriceHistory.", exchange);
                return closePrices;
            }

            if (responseEntity != null && responseEntity.getStatusCode().is2xxSuccessful() && responseEntity.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(responseEntity.getBody());
                JsonNode klineArrayNode = null;

                if ("BYBIT".equalsIgnoreCase(exchange)) {
                    klineArrayNode = rootNode.path("result").path("list");
                    if (klineArrayNode.isMissingNode() || !klineArrayNode.isArray()) {
                         logger.warn("RISK_MGMT: Bybit kline data array not found or not an array for {}. Response: {}", effectiveSymbol, responseEntity.getBody().substring(0, Math.min(500, responseEntity.getBody().length())));
                         return closePrices;
                    }
                    for (JsonNode klineData : klineArrayNode) {
                        if (klineData.isArray() && klineData.size() >= 5) { 
                            try {
                                closePrices.add(new BigDecimal(klineData.get(4).asText())); 
                            } catch (NumberFormatException e) {
                                logger.warn("RISK_MGMT: Could not parse close price from Bybit kline: {} for symbol {}", klineData.get(4).asText(), effectiveSymbol, e);
                            }
                        }
                    }
                    Collections.reverse(closePrices); 
                
                } else if ("MEXC".equalsIgnoreCase(exchange)) {
                    if (rootNode.isArray()) {
                        klineArrayNode = rootNode;
                    } else if (rootNode.has("data") && rootNode.get("data").isArray()){ 
                        klineArrayNode = rootNode.get("data");
                    } else if (rootNode.has("code") && rootNode.get("code").asInt() != 200 && rootNode.has("msg")){
                        logger.warn("RISK_MGMT: MEXC API error for {}: {} - {}", effectiveSymbol, rootNode.get("code").asInt(), rootNode.get("msg").asText());
                        return closePrices;
                    }

                    if (klineArrayNode == null || klineArrayNode.isMissingNode() || !klineArrayNode.isArray()) {
                        logger.warn("RISK_MGMT: MEXC kline data array not found or not an array for {}. Response: {}", effectiveSymbol, responseEntity.getBody().substring(0, Math.min(500, responseEntity.getBody().length())));
                        return closePrices;
                    }
                    for (JsonNode klineData : klineArrayNode) {
                        if (klineData.isArray() && klineData.size() >= 5) { 
                            try {
                                closePrices.add(new BigDecimal(klineData.get(4).asText())); 
                            } catch (NumberFormatException e) {
                                logger.warn("RISK_MGMT: Could not parse close price from MEXC kline: {} for symbol {}", klineData.get(4).asText(), effectiveSymbol, e);
                            }
                        }
                    }
                }
                logger.info("RISK_MGMT: Successfully fetched {} price points for {} on {} for {} days.", closePrices.size(), effectiveSymbol, exchange, days);
            } else {
                logger.error("RISK_MGMT: Failed to fetch klines for {} from {}. Status: {}, Body: {}", 
                    effectiveSymbol, exchange, 
                    responseEntity != null ? responseEntity.getStatusCode() : "N/A", 
                    responseEntity != null && responseEntity.getBody() != null ? responseEntity.getBody().substring(0, Math.min(500, responseEntity.getBody().length())) : "N/A");
            }

        } catch (JsonProcessingException e) {
            logger.error("RISK_MGMT: JsonProcessingException in getPriceHistory for symbol {}: {}", effectiveSymbol, e.getMessage(), e);
            throw e; 
        } catch (Exception e) {
            logger.error("RISK_MGMT: Unexpected exception in getPriceHistory for symbol {}: {}", effectiveSymbol, e.getMessage(), e);
            return new ArrayList<>(); 
        }
        return closePrices;
    }

    // STUB METHOD -> Restoring actual logic
    private BigDecimal getAssetPrice(String symbol) throws JsonProcessingException {
        // This method should fetch the current price of an asset (e.g., BTC from BTCUSDT)
        // It might need to determine the exchange or assume a primary one.
        // For simplicity, defaulting to BYBIT. A more robust solution might try multiple exchanges or take exchange as param.
        String exchange = "BYBIT"; // Or determine dynamically if needed
        logger.debug("RISK_MGMT: getAssetPrice called for symbol: {} on exchange: {}", symbol, exchange);
        
        try {
            // Attempt to use the existing getCurrentPrice method that handles API calls
            BigDecimal price = getCurrentPrice(symbol, exchange);
            if (price == null) {
                 logger.warn("RISK_MGMT: getAssetPrice received null from getCurrentPrice for {} on {}.", symbol, exchange);
                 // Fallback or alternative logic could be added here if necessary
            }
            return price; // This can be null if getCurrentPrice fails
        } catch (JsonProcessingException e) {
            logger.error("RISK_MGMT: JsonProcessingException in getAssetPrice for symbol {}: {}", symbol, e.getMessage());
            throw e; // Re-throw as the method signature includes it
        } catch (Exception e) {
            logger.error("RISK_MGMT: Unexpected exception in getAssetPrice for symbol {}: {}", symbol, e.getMessage(), e);
            // Depending on policy, could return null or throw a runtime exception
            return null; 
        }
    }
} 