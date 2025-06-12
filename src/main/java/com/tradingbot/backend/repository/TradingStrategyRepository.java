package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.TradingStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradingStrategyRepository extends JpaRepository<TradingStrategy, Long> {
    
    Optional<TradingStrategy> findBySymbol(String symbol);
    
    @Query("SELECT DISTINCT t.symbol FROM TradingStrategy t WHERE t.isActive = true")
    List<String> findAllActiveSymbols();
    
    List<TradingStrategy> findByIsActiveTrue();
    
    Optional<TradingStrategy> findBySymbolAndExchange(String symbol, String exchange);
} 