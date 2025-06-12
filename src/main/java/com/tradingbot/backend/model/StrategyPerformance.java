package com.tradingbot.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_performance")
public class StrategyPerformance {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "strategy_name", nullable = false)
    private String strategyName;
    
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;
    
    @Column(name = "total_trades")
    private Integer totalTrades;
    
    @Column(name = "winning_trades")
    private Integer winningTrades;
    
    @Column(name = "losing_trades")
    private Integer losingTrades;
    
    @Column(name = "win_rate", precision = 5, scale = 2)
    private BigDecimal winRate;
    
    @Column(name = "total_profit_loss", precision = 20, scale = 8)
    private BigDecimal totalProfitLoss;
    
    @Column(name = "average_win", precision = 20, scale = 8)
    private BigDecimal averageWin;
    
    @Column(name = "average_loss", precision = 20, scale = 8)
    private BigDecimal averageLoss;
    
    @Column(name = "largest_win", precision = 20, scale = 8)
    private BigDecimal largestWin;
    
    @Column(name = "largest_loss", precision = 20, scale = 8)
    private BigDecimal largestLoss;
    
    @Column(name = "sharpe_ratio", precision = 10, scale = 4)
    private BigDecimal sharpeRatio;
    
    @Column(name = "max_drawdown", precision = 10, scale = 4)
    private BigDecimal maxDrawdown;
    
    @Column(name = "profit_factor", precision = 10, scale = 4)
    private BigDecimal profitFactor;
    
    @Column(name = "volatility", precision = 10, scale = 4)
    private BigDecimal volatility;
    
    @Column(name = "total_volume", precision = 20, scale = 8)
    private BigDecimal totalVolume;
    
    @Column(name = "average_holding_time_minutes")
    private Integer averageHoldingTimeMinutes;
    
    // Constructors
    public StrategyPerformance() {}
    
    public StrategyPerformance(String strategyName, LocalDateTime timestamp) {
        this.strategyName = strategyName;
        this.timestamp = timestamp;
        this.totalTrades = 0;
        this.winningTrades = 0;
        this.losingTrades = 0;
        this.totalProfitLoss = BigDecimal.ZERO;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }
    
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    
    public Integer getTotalTrades() { return totalTrades; }
    public void setTotalTrades(Integer totalTrades) { this.totalTrades = totalTrades; }
    
    public Integer getWinningTrades() { return winningTrades; }
    public void setWinningTrades(Integer winningTrades) { this.winningTrades = winningTrades; }
    
    public Integer getLosingTrades() { return losingTrades; }
    public void setLosingTrades(Integer losingTrades) { this.losingTrades = losingTrades; }
    
    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }
    
    public BigDecimal getTotalProfitLoss() { return totalProfitLoss; }
    public void setTotalProfitLoss(BigDecimal totalProfitLoss) { this.totalProfitLoss = totalProfitLoss; }
    
    public BigDecimal getAverageWin() { return averageWin; }
    public void setAverageWin(BigDecimal averageWin) { this.averageWin = averageWin; }
    
    public BigDecimal getAverageLoss() { return averageLoss; }
    public void setAverageLoss(BigDecimal averageLoss) { this.averageLoss = averageLoss; }
    
    public BigDecimal getLargestWin() { return largestWin; }
    public void setLargestWin(BigDecimal largestWin) { this.largestWin = largestWin; }
    
    public BigDecimal getLargestLoss() { return largestLoss; }
    public void setLargestLoss(BigDecimal largestLoss) { this.largestLoss = largestLoss; }
    
    public BigDecimal getSharpeRatio() { return sharpeRatio; }
    public void setSharpeRatio(BigDecimal sharpeRatio) { this.sharpeRatio = sharpeRatio; }
    
    public BigDecimal getMaxDrawdown() { return maxDrawdown; }
    public void setMaxDrawdown(BigDecimal maxDrawdown) { this.maxDrawdown = maxDrawdown; }
    
    public BigDecimal getProfitFactor() { return profitFactor; }
    public void setProfitFactor(BigDecimal profitFactor) { this.profitFactor = profitFactor; }
    
    public BigDecimal getVolatility() { return volatility; }
    public void setVolatility(BigDecimal volatility) { this.volatility = volatility; }
    
    public BigDecimal getTotalVolume() { return totalVolume; }
    public void setTotalVolume(BigDecimal totalVolume) { this.totalVolume = totalVolume; }
    
    public Integer getAverageHoldingTimeMinutes() { return averageHoldingTimeMinutes; }
    public void setAverageHoldingTimeMinutes(Integer averageHoldingTimeMinutes) { this.averageHoldingTimeMinutes = averageHoldingTimeMinutes; }
} 