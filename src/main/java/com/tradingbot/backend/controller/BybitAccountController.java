package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.BybitAccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bybit")
public class BybitAccountController {

    private final BybitAccountService bybitAccountService;

    @Autowired
    public BybitAccountController(BybitAccountService bybitAccountService) {
        this.bybitAccountService = bybitAccountService;
    }

    @GetMapping("/validate-credentials")
    public ResponseEntity<?> validateCredentials() {
        try {
            boolean isValid = bybitAccountService.checkAndUpgradeAccountStatus();
            Map<String, Object> response = new HashMap<>();
            response.put("valid", isValid);
            response.put("message", isValid ? "API credentials are valid" : "Account needs upgrade");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("valid", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/account/balance")
    public ResponseEntity<?> getAccountBalance() {
        try {
            Map<String, Object> balance = bybitAccountService.getAccountBalance();
            return ResponseEntity.ok(balance);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/account/info")
    public ResponseEntity<?> getAccountInfo() {
        try {
            boolean accountStatus = bybitAccountService.checkAndUpgradeAccountStatus();
            Map<String, Object> response = new HashMap<>();
            response.put("accountReady", accountStatus);
            response.put("message", accountStatus ? "Account is ready for trading" : "Account needs upgrade");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
} 