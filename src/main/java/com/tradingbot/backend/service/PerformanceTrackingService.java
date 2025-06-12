package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BalanceSnapshot;import com.tradingbot.backend.model.StrategyPerformance;import com.tradingbot.backend.model.Position;import com.tradingbot.backend.repository.BalanceSnapshotRepository;import com.tradingbot.backend.repository.StrategyPerformanceRepository;import com.tradingbot.backend.repository.PositionRepository;import com.tradingbot.backend.service.ExchangeContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PerformanceTrackingService {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTrackingService.class);
    
    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final StrategyPerformanceRepository strategyPerformanceRepository;
    private final PositionRepository positionRepository;
    private final AccountService accountService;
    
    public PerformanceTrackingService(
            BalanceSnapshotRepository balanceSnapshotRepository,
            StrategyPerformanceRepository strategyPerformanceRepository,
            PositionRepository positionRepository,
            AccountService accountService) {
        this.balanceSnapshotRepository = balanceSnapshotRepository;
        this.strategyPerformanceRepository = strategyPerformanceRepository;
        this.positionRepository = positionRepository;
        this.accountService = accountService;
    }
    
    // Take a balance snapshot every 15 minutes
    @Scheduled(fixedRate = 900000) // 15 minutes
    @Transactional
    public void captureBalanceSnapshot() {
        try {
            logger.debug("Capturing balance snapshot");
            Map<String, Object> balanceSummary = accountService.getBalanceSummary();
            
            if (balanceSummary != null && balanceSummary.get("totalEstimatedValueUSDT") != null) {
                BalanceSnapshot snapshot = new BalanceSnapshot();
                snapshot.setTimestamp(LocalDateTime.now());
                snapshot.setTotalBalanceUsdt(new BigDecimal(balanceSummary.get("totalEstimatedValueUSDT").toString()));
                
                // Calculate available and locked balances
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> balances = (List<Map<String, Object>>) balanceSummary.get("balances");
                
                BigDecimal availableBalance = BigDecimal.ZERO;
                BigDecimal lockedBalance = BigDecimal.ZERO;
                
                if (balances != null) {
                    for (Map<String, Object> balance : balances) {
                        BigDecimal free = new BigDecimal(balance.get("free").toString());
                        BigDecimal locked = new BigDecimal(balance.get("locked").toString());
                        BigDecimal estimatedValue = new BigDecimal(balance.get("estimatedValueUSDT").toString());
                        BigDecimal total = free.add(locked);
                        
                        if (total.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal freeRatio = free.divide(total, 8, RoundingMode.HALF_UP);
                            BigDecimal lockedRatio = locked.divide(total, 8, RoundingMode.HALF_UP);
                            
                            availableBalance = availableBalance.add(estimatedValue.multiply(freeRatio));
                            lockedBalance = lockedBalance.add(estimatedValue.multiply(lockedRatio));
                        }
                    }
                }
                
                snapshot.setAvailableBalanceUsdt(availableBalance);
                snapshot.setLockedBalanceUsdt(lockedBalance);
                
                // Calculate profit/loss if we have a previous snapshot
                var previousSnapshot = balanceSnapshotRepository.findTopByOrderByTimestampDesc();
                if (previousSnapshot.isPresent()) {
                    BigDecimal previousBalance = previousSnapshot.get().getTotalBalanceUsdt();
                    BigDecimal currentBalance = snapshot.getTotalBalanceUsdt();
                    BigDecimal profitLoss = currentBalance.subtract(previousBalance);
                    BigDecimal profitLossPercentage = previousBalance.compareTo(BigDecimal.ZERO) > 0 
                        ? profitLoss.divide(previousBalance, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        : BigDecimal.ZERO;
                    
                    snapshot.setProfitLoss(profitLoss);
                    snapshot.setProfitLossPercentage(profitLossPercentage);
                }
                
                balanceSnapshotRepository.save(snapshot);
                logger.debug("Balance snapshot captured: {} USDT", snapshot.getTotalBalanceUsdt());
            }
        } catch (Exception e) {
            logger.error("Error capturing balance snapshot", e);
        }
    }
    
    // Update strategy performance every hour
    @Scheduled(fixedRate = 3600000) // 1 hour
    @Transactional
    public void updateStrategyPerformance() {
        try {
            logger.debug("Updating strategy performance");
            
            // Get all positions grouped by strategy
            List<Position> allPositions = positionRepository.findAll();
            Map<String, List<Position>> positionsByStrategy = allPositions.stream()
                .filter(position -> position.getStrategyName() != null)
                .collect(Collectors.groupingBy(Position::getStrategyName));
            
            LocalDateTime now = LocalDateTime.now();
            
            for (Map.Entry<String, List<Position>> entry : positionsByStrategy.entrySet()) {
                String strategyName = entry.getKey();
                List<Position> positions = entry.getValue();
                
                StrategyPerformance performance = calculateStrategyPerformance(strategyName, positions, now);
                strategyPerformanceRepository.save(performance);
            }
            
            logger.debug("Strategy performance updated for {} strategies", positionsByStrategy.size());
        } catch (Exception e) {
            logger.error("Error updating strategy performance", e);
        }
    }
    
    private StrategyPerformance calculateStrategyPerformance(String strategyName, List<Position> positions, LocalDateTime timestamp) {
        StrategyPerformance performance = new StrategyPerformance(strategyName, timestamp);
        
        // Filter closed positions for accurate metrics
        List<Position> closedPositions = positions.stream()
            .filter(Position::isClosed)
            .collect(Collectors.toList());
        
        if (closedPositions.isEmpty()) {
            return performance;
        }
        
        // Basic statistics
        performance.setTotalTrades(closedPositions.size());
        
        List<Position> winningPositions = closedPositions.stream()
            .filter(pos -> pos.getRealizedPnl() != null && pos.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
            .collect(Collectors.toList());
        
        List<Position> losingPositions = closedPositions.stream()
            .filter(pos -> pos.getRealizedPnl() != null && pos.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0)
            .collect(Collectors.toList());
        
        performance.setWinningTrades(winningPositions.size());
        performance.setLosingTrades(losingPositions.size());
        
        // Win rate
        if (performance.getTotalTrades() > 0) {
            BigDecimal winRate = BigDecimal.valueOf(performance.getWinningTrades())
                .divide(BigDecimal.valueOf(performance.getTotalTrades()), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            performance.setWinRate(winRate);
        }
        
        // Profit/Loss calculations
        BigDecimal totalPnL = closedPositions.stream()
            .map(pos -> pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        performance.setTotalProfitLoss(totalPnL);
        
        // Average win/loss
        if (!winningPositions.isEmpty()) {
            BigDecimal totalWins = winningPositions.stream()
                .map(pos -> pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            performance.setAverageWin(totalWins.divide(BigDecimal.valueOf(winningPositions.size()), 8, RoundingMode.HALF_UP));
        }
        
        if (!losingPositions.isEmpty()) {
            BigDecimal totalLosses = losingPositions.stream()
                .map(pos -> pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            performance.setAverageLoss(totalLosses.divide(BigDecimal.valueOf(losingPositions.size()), 8, RoundingMode.HALF_UP));
        }
        
        // Largest win/loss
        if (!winningPositions.isEmpty()) {
            BigDecimal largestWin = winningPositions.stream()
                .map(pos -> pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            performance.setLargestWin(largestWin);
        }
        
        if (!losingPositions.isEmpty()) {
            BigDecimal largestLoss = losingPositions.stream()
                .map(pos -> pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
            performance.setLargestLoss(largestLoss);
        }
        
        // Total volume
        BigDecimal totalVolume = closedPositions.stream()
            .map(pos -> pos.getQuantity() != null && pos.getCurrentPrice() != null 
                ? pos.getQuantity().multiply(pos.getCurrentPrice()) 
                : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        performance.setTotalVolume(totalVolume);
        
        return performance;
    }
    
    // Clean up old data every day at midnight
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupOldData() {
        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(90); // Keep 90 days of data
            
            balanceSnapshotRepository.deleteByTimestampBefore(cutoffDate);
            strategyPerformanceRepository.deleteByTimestampBefore(cutoffDate);
            
            logger.info("Cleaned up performance data older than {}", cutoffDate);
        } catch (Exception e) {
            logger.error("Error cleaning up old performance data", e);
        }
    }
    
    // Public methods for getting performance data
    public List<BalanceSnapshot> getBalanceHistory(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        return balanceSnapshotRepository.findSnapshotsFromDate(startDate);
    }
    
    public List<StrategyPerformance> getLatestStrategyPerformances() {
        return strategyPerformanceRepository.findLatestPerformanceForAllStrategies();
    }
    
    public List<StrategyPerformance> getStrategyPerformanceHistory(String strategyName, int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        LocalDateTime endDate = LocalDateTime.now();
        return strategyPerformanceRepository.findByStrategyNameAndTimestampBetweenOrderByTimestampAsc(
            strategyName, startDate, endDate);
    }
} 