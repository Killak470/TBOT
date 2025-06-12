package com.tradingbot.backend.controller;

import com.tradingbot.backend.model.Position;
import com.tradingbot.backend.service.OrderManagementService;
import com.tradingbot.backend.service.PositionService;
import com.tradingbot.backend.model.TradeAnalysis;
import com.tradingbot.backend.model.PositionMonitoringResult;
import com.tradingbot.backend.repository.PositionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/positions")
@CrossOrigin(origins = "http://localhost:3000", allowCredentials = "true")
public class PositionController {

    private final OrderManagementService orderManagementService;
    private final PositionService positionService;
    private final PositionRepository positionRepository;

    @Autowired
    public PositionController(OrderManagementService orderManagementService, 
                              PositionService positionService, 
                              PositionRepository positionRepository) {
        this.orderManagementService = orderManagementService;
        this.positionService = positionService;
        this.positionRepository = positionRepository;
    }

    /**
     * Get all positions
     * 
     * @param status Optional filter by status (OPEN, CLOSED)
     * @param exchange Optional filter by exchange (MEXC, BYBIT)
     * @return Object containing list of positions
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllPositions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        List<Position> positions = orderManagementService.getPositions(status, exchange);
        Map<String, Object> response = new HashMap<>();
        response.put("positions", positions);
        response.put("success", true);
        response.put("count", positions.size());
        return ResponseEntity.ok(response);
    }

    /**
     * Get positions for a specific symbol
     * 
     * @param symbol Trading pair
     * @param status Optional filter by status (OPEN, CLOSED)
     * @param exchange Optional filter by exchange (MEXC, BYBIT)
     * @return Object containing list of positions for the symbol
     */
    @GetMapping("/symbol/{symbol}")
    public ResponseEntity<Map<String, Object>> getPositionsBySymbol(
            @PathVariable String symbol,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        List<Position> positions = orderManagementService.getPositionsBySymbol(symbol, status, exchange);
        Map<String, Object> response = new HashMap<>();
        response.put("positions", positions);
        response.put("success", true);
        response.put("count", positions.size());
        response.put("symbol", symbol);
        return ResponseEntity.ok(response);
    }

    /**
     * Close a position
     * 
     * @param positionId Position ID to close
     * @param reason Reason for closing
     * @param exchange Exchange where the position is held (MEXC, BYBIT)
     * @return The closed position
     */
    @PostMapping("/{positionId}/close")
    public ResponseEntity<Position> closePosition(
            @PathVariable String positionId,
            @RequestBody Map<String, String> request,
            @RequestParam(required = false, defaultValue = "MEXC") String exchange) {
        String reason = request.get("reason");
        Position closedPosition = orderManagementService.closePosition(positionId, reason, exchange);
        return ResponseEntity.ok(closedPosition);
    }

    /**
     * Update stop loss and take profit for a position
     * 
     * @param positionId Position ID
     * @param updates Map containing stopLossPrice and/or takeProfitPrice
     * @return The updated position
     */
    @PatchMapping("/{positionId}")
    public ResponseEntity<Position> updatePosition(
            @PathVariable String positionId,
            @RequestBody Map<String, Object> updates) {
        try {
            // Get the position
            Position position = orderManagementService.getPositions(null, null).stream()
                .filter(p -> p.getId().equals(positionId))
                .findFirst()
                .orElse(null);
            
            if (position == null) {
                return ResponseEntity.notFound().build();
            }
            
            // Update stop loss if provided
            if (updates.containsKey("stopLossPrice")) {
                position.setStopLossPrice(java.math.BigDecimal.valueOf(
                    Double.parseDouble(updates.get("stopLossPrice").toString())));
            }
            
            // Update take profit if provided
            if (updates.containsKey("takeProfitPrice")) {
                position.setTakeProfitPrice(java.math.BigDecimal.valueOf(
                    Double.parseDouble(updates.get("takeProfitPrice").toString())));
            }
            
            return ResponseEntity.ok(position);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get analysis for a specific position
     * 
     * @param positionId ID of the position to analyze
     * @return TradeAnalysis for the position
     */
    @GetMapping("/{positionId}/analysis")
    public ResponseEntity<TradeAnalysis> getPositionAnalysis(@PathVariable String positionId) {
        try {
            Long id = Long.parseLong(positionId);
            // Find the position by ID using PositionRepository
            Position position = positionRepository.findById(id)
                .orElse(null);

            if (position == null) {
                return ResponseEntity.notFound().build();
            }

            // Use PositionService to get the monitoring result which contains the analysis
            // Assuming monitorPosition requires an orderbook JsonNode which we might not have here.
            // For now, let's assume PositionService might have a simpler getAnalysis method or adapt.
            // This part might need adjustment based on how TradeAnalysis is actually generated.
            // If PositionService.monitorPosition is the only way, you might need to fetch order book data first.
            
            // Simplified: Directly call a hypothetical getAnalysis method or adapt PositionService
            // For a more direct approach if monitorPosition is too complex for just fetching analysis:
            // Option 1: Add a method to PositionService: getTradeAnalysis(Position position)
            // Option 2: Inject TradeAnalysisService directly and call it.
            
            // Using PositionService.monitorPosition and extracting analysis
            // This is a placeholder for how you might get the orderbook. For now, passing null.
            // You'll need to decide how to obtain the 'orderbook' JsonNode for this context.
            // For a simple fetch, you might not have live order book data readily available.
            // Consider if TradeAnalysisService can be called directly if it doesn't strictly need live orderbook for all analysis types.
            
            PositionMonitoringResult monitoringResult = positionService.monitorPosition(position); 
            // The above line will throw an NPE if monitorPosition tries to use a null orderbook and doesn't handle it.
            // Ensure `monitorPosition` or the services it calls can handle a null orderbook if that's a valid scenario for this endpoint,
            // or fetch the orderbook data here.
            
            if (monitoringResult != null && monitoringResult.getAnalysis() != null) {
                return ResponseEntity.ok(monitoringResult.getAnalysis());
            } else {
                // This case might mean analysis failed or wasn't generated.
                return ResponseEntity.status(500).body(null); // Or a more specific error response
            }

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build(); // Invalid positionId format
        } catch (Exception e) {
            // Log the exception e.printStackTrace(); or logger.error("Error getting position analysis", e);
            return ResponseEntity.status(500).build(); // Internal server error
        }
    }
} 