package com.tradingbot.backend.service;

import com.tradingbot.backend.model.BotSignal;
import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.repository.BotSignalRepository;
import com.tradingbot.backend.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class SignalPositionLinkService {
    
    private static final Logger logger = LoggerFactory.getLogger(SignalPositionLinkService.class);
    
    @Autowired
    private PositionRepository positionRepository;
    
    @Autowired
    private BotSignalRepository botSignalRepository;
    
    @Autowired
    private EnhancedSLTPService enhancedSLTPService;
    
    @PostConstruct
    public void restoreSignalPositionLinks() {
        logger.info("Starting signal-position link restoration on startup...");
        
        try {
            List<Position> unlinkledPositions = positionRepository.findByOriginalSignalIdIsNull();
            logger.info("Found {} positions without signal links", unlinkledPositions.size());
            
            int linkedCount = 0;
            for (Position position : unlinkledPositions) {
                Optional<BotSignal> linkedSignal = findSignalForPosition(position);
                if (linkedSignal.isPresent()) {
                    BotSignal signal = linkedSignal.get();
                    
                    // Update position with signal tracking
                    position.setOriginalSignalId(signal.getId());
                    position.setOrderLinkId(signal.getOrderLinkId());
                    position.setSignalSource(signal.getSignalType() != null ? signal.getSignalType().name() : "UNKNOWN");
                    position.setIsScalpTrade(isScalpTrade(signal));
                    position.setSltpApplied(false);
                    position.setTrailingStopInitialized(false);
                    position.setLastSltpCheck(LocalDateTime.now());
                    
                    positionRepository.save(position);
                    linkedCount++;
                    
                    logger.info("Linked Position {} to Signal {} ({})", 
                        position.getId(), signal.getId(), signal.getOrderLinkId());
                    
                    // Apply SL/TP if not already applied
                    if (!position.getSltpApplied()) {
                        try {
                            enhancedSLTPService.applySignalSpecificSLTP(position, signal);
                            logger.info("Applied SL/TP for Position {} using Signal {}", 
                                position.getId(), signal.getId());
                        } catch (Exception e) {
                            logger.error("Failed to apply SL/TP for Position {}: {}", 
                                position.getId(), e.getMessage());
                        }
                    }
                }
            }
            
            logger.info("Successfully linked {} positions to signals", linkedCount);
            
        } catch (Exception e) {
            logger.error("Error during signal-position link restoration: {}", e.getMessage(), e);
        }
    }
    
    public Optional<BotSignal> findSignalForPosition(Position position) {
        try {
            // Method 1: Try exact orderLinkId match
            if (position.getOrderLinkId() != null) {
                List<BotSignal> signalsByOrderLink = botSignalRepository.findByOrderLinkId(position.getOrderLinkId());
                if (!signalsByOrderLink.isEmpty()) {
                    return Optional.of(signalsByOrderLink.get(0));
                }
            }
            
            // Method 2: Try symbol, side, and timing analysis
            List<BotSignal> candidateSignals = botSignalRepository.findBySymbolAndSide(
                position.getSymbol(), 
                position.getSide()
            );
            
            for (BotSignal signal : candidateSignals) {
                if (isSignalTimeMatch(signal, position)) {
                    return Optional.of(signal);
                }
            }
            
            // Method 3: Try recent signals for this symbol
            List<BotSignal> recentSignals = botSignalRepository.findBySymbolOrderByCreatedAtDesc(
                position.getSymbol()
            );
            
            if (!recentSignals.isEmpty() && 
                isSignalTimeMatch(recentSignals.get(0), position)) {
                return Optional.of(recentSignals.get(0));
            }
            
            return Optional.empty();
            
        } catch (Exception e) {
            logger.error("Error finding signal for position {}: {}", position.getId(), e.getMessage());
            return Optional.empty();
        }
    }
    
    private boolean isSignalTimeMatch(BotSignal signal, Position position) {
        if (signal.getCreatedAt() == null || position.getCreatedAt() == null) {
            return false;
        }
        
        // Check if signal was created within 5 minutes of position
        long timeDiff = Math.abs(
            signal.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC) - 
            position.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC)
        );
        
        return timeDiff <= 300; // 5 minutes
    }
    
    private boolean isScalpTrade(BotSignal signal) {
        // Determine if this is a scalp trade based on signal characteristics
        if (signal.getStrategy() != null && signal.getStrategy().toLowerCase().contains("scalp")) {
            return true;
        }
        
        // Check if it's a quick trade based on targets
        if (signal.getTakeProfit() != null && signal.getStopLoss() != null && signal.getEntryPrice() != null) {
            double tpDistance = Math.abs(signal.getTakeProfit() - signal.getEntryPrice());
            double slDistance = Math.abs(signal.getEntryPrice() - signal.getStopLoss());
            double ratio = tpDistance / slDistance;
            
            // Scalp trades typically have tight risk/reward ratios
            return ratio <= 2.0;
        }
        
        return false;
    }
    
    public void linkPositionToSignal(Position position, BotSignal signal) {
        try {
            position.setOriginalSignalId(signal.getId());
            position.setOrderLinkId(signal.getOrderLinkId());
            position.setSignalSource(signal.getSignalType() != null ? signal.getSignalType().name() : "UNKNOWN");
            position.setIsScalpTrade(isScalpTrade(signal));
            position.setSltpApplied(false);
            position.setTrailingStopInitialized(false);
            position.setLastSltpCheck(LocalDateTime.now());
            
            positionRepository.save(position);
            
            logger.info("Manually linked Position {} to Signal {}", position.getId(), signal.getId());
            
        } catch (Exception e) {
            logger.error("Error linking position {} to signal {}: {}", 
                position.getId(), signal.getId(), e.getMessage());
        }
    }
} 