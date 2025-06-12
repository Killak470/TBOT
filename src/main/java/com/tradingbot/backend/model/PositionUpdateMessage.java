package com.tradingbot.backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Message model for WebSocket position updates
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PositionUpdateMessage {
    private List<Position> positions;
    private String type;  // Can be "INITIAL", "UPDATE", "DELETE" etc.
    private long timestamp;
    
    public static PositionUpdateMessage createUpdate(List<Position> positions) {
        return new PositionUpdateMessage(
            positions,
            "UPDATE",
            System.currentTimeMillis()
        );
    }
    
    public static PositionUpdateMessage createInitial(List<Position> positions) {
        return new PositionUpdateMessage(
            positions,
            "INITIAL",
            System.currentTimeMillis()
        );
    }
} 