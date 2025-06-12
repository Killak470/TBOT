package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.MexcApiClientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    private final MexcApiClientService mexcApiClientService;

    public MarketDataController(MexcApiClientService mexcApiClientService) {
        this.mexcApiClientService = mexcApiClientService;
    }

    // --- SPOT V3 --- //
    @GetMapping("/spot/time")
    public ResponseEntity<String> getSpotServerTime() {
        return mexcApiClientService.getSpotServerTime();
    }

    @GetMapping("/spot/exchangeInfo")
    public ResponseEntity<String> getSpotExchangeInfo(@RequestParam(required = false) String symbol) {
        return mexcApiClientService.getSpotExchangeInfo(symbol);
    }

    @GetMapping("/spot/klines")
    public ResponseEntity<String> getSpotKlines(@RequestParam String symbol,
                                                @RequestParam String interval,
                                                @RequestParam(required = false) Long startTime,
                                                @RequestParam(required = false) Long endTime,
                                                @RequestParam(required = false) Integer limit) {
        return mexcApiClientService.getSpotKlines(symbol, interval, startTime, endTime, limit);
    }

    // --- FUTURES V1 --- //
    @GetMapping("/futures/time")
    public ResponseEntity<String> getFuturesServerTime() {
        return mexcApiClientService.getFuturesServerTime();
    }

    @GetMapping("/futures/contract/detail")
    public ResponseEntity<String> getFuturesContractDetail(@RequestParam(required = false) String symbol) {
        return mexcApiClientService.getFuturesContractDetail(symbol);
    }

    @GetMapping("/futures/contract/klines")
    public ResponseEntity<String> getFuturesKlines(@RequestParam String symbol,
                                                   @RequestParam String interval,
                                                   @RequestParam Long start,
                                                   @RequestParam Long end) {
        return mexcApiClientService.getFuturesKlines(symbol, interval, start, end);
    }
}

