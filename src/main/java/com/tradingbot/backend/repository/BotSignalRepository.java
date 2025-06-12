package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.BotSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BotSignalRepository extends JpaRepository<BotSignal, Long> {
    
    List<BotSignal> findByStatusOrderByGeneratedAtDesc(BotSignal.SignalStatus status);
    
    List<BotSignal> findByStatusInOrderByGeneratedAtDesc(List<BotSignal.SignalStatus> statuses);
    
    List<BotSignal> findBySymbolAndStatusOrderByGeneratedAtDesc(String symbol, BotSignal.SignalStatus status);
    
    List<BotSignal> findByStrategyNameAndStatusOrderByGeneratedAtDesc(String strategyName, BotSignal.SignalStatus status);
    
    @Query("SELECT s FROM BotSignal s WHERE s.generatedAt < :expiredBefore AND s.status = :status")
    List<BotSignal> findExpiredSignals(@Param("expiredBefore") LocalDateTime expiredBefore, 
                                     @Param("status") BotSignal.SignalStatus status);
    
    @Query("SELECT s FROM BotSignal s WHERE s.status IN ('APPROVED', 'EXECUTED') ORDER BY s.processedAt DESC")
    List<BotSignal> findApprovedSignals();
    
    @Query("SELECT COUNT(s) FROM BotSignal s WHERE s.status = :status")
    long countByStatus(@Param("status") BotSignal.SignalStatus status);
    
    @Query("SELECT s FROM BotSignal s WHERE s.generatedAt >= :fromDate ORDER BY s.generatedAt DESC")
    List<BotSignal> findSignalsSince(@Param("fromDate") LocalDateTime fromDate);
} 