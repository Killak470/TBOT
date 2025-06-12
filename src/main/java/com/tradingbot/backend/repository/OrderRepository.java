package com.tradingbot.backend.repository;

import com.tradingbot.backend.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findBySymbol(String symbol);
    List<Order> findByStatus(String status);
    List<Order> findBySymbolAndStatus(String symbol, String status);
    List<Order> findByStrategyName(String strategyName);
    List<Order> findByBotId(String botId);
} 