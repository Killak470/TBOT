package com.tradingbot.backend.service;

import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.model.PositionUpdateMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for broadcasting position updates to connected clients via WebSocket
 */
@Service
public class PositionUpdateService {

    private final SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    public PositionUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }
    
    /**
     * Broadcasts position updates to all subscribed clients
     * @param positions List of positions to broadcast
     */
    public void broadcastPositionUpdate(List<Position> positions) {
        PositionUpdateMessage message = PositionUpdateMessage.createUpdate(positions);
        messagingTemplate.convertAndSend("/topic/positions", message);
    }
    
    /**
     * Sends initial positions data when a client first subscribes
     * @param positions List of positions to send
     */
    public void sendInitialPositions(List<Position> positions) {
        PositionUpdateMessage message = PositionUpdateMessage.createInitial(positions);
        messagingTemplate.convertAndSend("/topic/positions", message);
    }
} 