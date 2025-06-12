package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.BalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BalanceSnapshotRepository extends JpaRepository<BalanceSnapshot, Long> {
    
    // Find snapshots within a date range
    List<BalanceSnapshot> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime start, LocalDateTime end);
    
    // Find the most recent snapshot
    Optional<BalanceSnapshot> findTopByOrderByTimestampDesc();
    
    // Find snapshots for the last N days
    @Query("SELECT bs FROM BalanceSnapshot bs WHERE bs.timestamp >= :startDate ORDER BY bs.timestamp ASC")
    List<BalanceSnapshot> findSnapshotsFromDate(@Param("startDate") LocalDateTime startDate);
    
    // Get daily snapshots (one per day)
    @Query("SELECT bs FROM BalanceSnapshot bs WHERE bs.timestamp IN " +
           "(SELECT MAX(bs2.timestamp) FROM BalanceSnapshot bs2 " +
           "WHERE DATE(bs2.timestamp) = DATE(bs.timestamp) " +
           "GROUP BY DATE(bs2.timestamp)) " +
           "AND bs.timestamp >= :startDate ORDER BY bs.timestamp ASC")
    List<BalanceSnapshot> findDailySnapshots(@Param("startDate") LocalDateTime startDate);
    
    // Delete old snapshots to keep database size manageable
    void deleteByTimestampBefore(LocalDateTime cutoffDate);
    
    // Get performance metrics
    @Query("SELECT COUNT(bs) FROM BalanceSnapshot bs WHERE bs.timestamp >= :startDate")
    Long countSnapshotsFromDate(@Param("startDate") LocalDateTime startDate);
} 