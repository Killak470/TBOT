package com.tradingbot.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "trading_strategies")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradingStrategy {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false)
    private String symbol;
    
    @Column(nullable = false)
    private String exchange;
    
    @Column(nullable = false)
    private String type; // MA_CROSSOVER, RSI, FIBONACCI, etc.
    
    @Column(nullable = false)
    private Boolean isActive = true;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    // Strategy parameters stored as JSON
    @ElementCollection
    @CollectionTable(name = "strategy_parameters", joinColumns = @JoinColumn(name = "strategy_id"))
    @MapKeyColumn(name = "param_key")
    @Column(name = "param_value")
    private Map<String, String> parameters = new HashMap<>();
    
    // Performance metrics
    private Double winRate;
    private Double profitFactor;
    private Integer totalTrades;
    private Integer winningTrades;
    private Integer losingTrades;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
} 