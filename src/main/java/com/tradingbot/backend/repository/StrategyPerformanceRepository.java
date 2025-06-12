package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.StrategyPerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StrategyPerformanceRepository extends JpaRepository<StrategyPerformance, Long> {
    
    // Find performance records for a specific strategy
    List<StrategyPerformance> findByStrategyNameOrderByTimestampDesc(String strategyName);
    
    // Find latest performance for each strategy
    @Query("SELECT sp FROM StrategyPerformance sp WHERE sp.timestamp IN " +
           "(SELECT MAX(sp2.timestamp) FROM StrategyPerformance sp2 " +
           "GROUP BY sp2.strategyName) ORDER BY sp.strategyName")
    List<StrategyPerformance> findLatestPerformanceForAllStrategies();
    
    // Find performance within date range for a strategy
    List<StrategyPerformance> findByStrategyNameAndTimestampBetweenOrderByTimestampAsc(
        String strategyName, LocalDateTime start, LocalDateTime end);
    
    // Get the most recent performance for a strategy
    Optional<StrategyPerformance> findTopByStrategyNameOrderByTimestampDesc(String strategyName);
    
    // Find all unique strategy names
    @Query("SELECT DISTINCT sp.strategyName FROM StrategyPerformance sp ORDER BY sp.strategyName")
    List<String> findAllStrategyNames();
    
    // Delete old performance records to keep database size manageable
    void deleteByTimestampBefore(LocalDateTime cutoffDate);
} 