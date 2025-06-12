package com.tradingbot.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "balance_snapshots")
public class BalanceSnapshot {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;
    
    @Column(name = "total_balance_usdt", precision = 20, scale = 8)
    private BigDecimal totalBalanceUsdt;
    
    @Column(name = "available_balance_usdt", precision = 20, scale = 8)
    private BigDecimal availableBalanceUsdt;
    
    @Column(name = "locked_balance_usdt", precision = 20, scale = 8)
    private BigDecimal lockedBalanceUsdt;
    
    @Column(name = "profit_loss", precision = 20, scale = 8)
    private BigDecimal profitLoss;
    
    @Column(name = "profit_loss_percentage", precision = 10, scale = 4)
    private BigDecimal profitLossPercentage;
    
    // Constructors
    public BalanceSnapshot() {}
    
    public BalanceSnapshot(LocalDateTime timestamp, BigDecimal totalBalanceUsdt, 
                          BigDecimal availableBalanceUsdt, BigDecimal lockedBalanceUsdt) {
        this.timestamp = timestamp;
        this.totalBalanceUsdt = totalBalanceUsdt;
        this.availableBalanceUsdt = availableBalanceUsdt;
        this.lockedBalanceUsdt = lockedBalanceUsdt;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    
    public BigDecimal getTotalBalanceUsdt() { return totalBalanceUsdt; }
    public void setTotalBalanceUsdt(BigDecimal totalBalanceUsdt) { this.totalBalanceUsdt = totalBalanceUsdt; }
    
    public BigDecimal getAvailableBalanceUsdt() { return availableBalanceUsdt; }
    public void setAvailableBalanceUsdt(BigDecimal availableBalanceUsdt) { this.availableBalanceUsdt = availableBalanceUsdt; }
    
    public BigDecimal getLockedBalanceUsdt() { return lockedBalanceUsdt; }
    public void setLockedBalanceUsdt(BigDecimal lockedBalanceUsdt) { this.lockedBalanceUsdt = lockedBalanceUsdt; }
    
    public BigDecimal getProfitLoss() { return profitLoss; }
    public void setProfitLoss(BigDecimal profitLoss) { this.profitLoss = profitLoss; }
    
    public BigDecimal getProfitLossPercentage() { return profitLossPercentage; }
    public void setProfitLossPercentage(BigDecimal profitLossPercentage) { this.profitLossPercentage = profitLossPercentage; }
} 