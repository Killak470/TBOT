package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PositionRepository extends JpaRepository<Position, Long> {
    List<Position> findBySymbol(String symbol);
    List<Position> findByStatus(String status);
    List<Position> findBySymbolAndStatus(String symbol, String status);
    List<Position> findBySymbolAndStatusAndExchangeAndMarketType(String symbol, String status, String exchange, String marketType);
    List<Position> findByStrategyName(String strategyName);
    List<Position> findByBotId(String botId);
} 