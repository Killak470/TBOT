package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.model.PositionUpdateMessage;
import com.tradingbot.backend.service.PositionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * Controller for handling WebSocket connections and messages for position updates
 */
@Controller
public class WebSocketController {

    private final PositionService positionService;
    
    @Autowired
    public WebSocketController(PositionService positionService) {
        this.positionService = positionService;
    }
    
    /**
     * Handles client subscription to positions
     * @return Initial position data when a client subscribes
     */
    @SubscribeMapping("/positions")
    public PositionUpdateMessage getPositions() {
        List<Position> positions = positionService.getAllPositions();
        return PositionUpdateMessage.createInitial(positions);
    }
    
    /**
     * Receives requests for position updates and broadcasts them
     * @return Updated position data
     */
    @MessageMapping("/requestPositionUpdate")
    @SendTo("/topic/positions")
    public PositionUpdateMessage updatePositions() {
        List<Position> positions = positionService.getAllPositions();
        return PositionUpdateMessage.createUpdate(positions);
    }
} 