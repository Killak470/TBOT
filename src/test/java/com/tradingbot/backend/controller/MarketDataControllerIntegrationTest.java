package com.tradingbot.backend.controller;

import com.tradingbot.backend.service.MexcApiClientService;
import com.tradingbot.backend.service.MexcFuturesApiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MarketDataController.class)
public class MarketDataControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MexcApiClientService mexcApiClientService;

    @MockBean
    private MexcFuturesApiService mexcFuturesApiService;

    @Test
    void getSpotServerTime_shouldReturnServerTimeFromService() throws Exception {
        String mockResponseData = "{\"serverTime\": 1678886400000}";
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(mockResponseData, HttpStatus.OK);

        when(mexcApiClientService.getSpotServerTime()).thenReturn(mockResponseEntity);

        mockMvc.perform(get("/api/mexc/spot/time"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(mockResponseData));
    }

    @Test
    void getSpotExchangeInformation_withSymbol_shouldReturnExchangeInfoFromService() throws Exception {
        String symbol = "BTCUSDT";
        String mockResponseData = "{\"symbol\": \"BTCUSDT\", \"status\": \"TRADING\"}";
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(mockResponseData, HttpStatus.OK);

        when(mexcApiClientService.getSpotExchangeInfo(eq(symbol))).thenReturn(mockResponseEntity);

        mockMvc.perform(get("/api/mexc/spot/exchangeInfo").param("symbol", symbol))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(mockResponseData));
    }

    @Test
    void getSpotExchangeInformation_withoutSymbol_shouldReturnExchangeInfoFromService() throws Exception {
        String mockResponseData = "{\"symbols\": [{\"symbol\": \"BTCUSDT\"},{\"symbol\": \"ETHUSDT\"}]}"; // Example response for all symbols
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(mockResponseData, HttpStatus.OK);

        when(mexcApiClientService.getSpotExchangeInfo(eq(null))).thenReturn(mockResponseEntity);

        mockMvc.perform(get("/api/mexc/spot/exchangeInfo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(mockResponseData));
    }

    @Test
    void getFuturesKlinesData_shouldReturnKlinesFromService() throws Exception {
        String symbol = "BTC_USDT";
        String interval = "Min15";
        Long start = 1678880000L;
        Long end = 1678886400L;
        String mockResponseData = "{\"success\":true, \"code\":0, \"data\":{\"time\":[1678880000], \"open\":[25000]}}";
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(mockResponseData, HttpStatus.OK);

        when(mexcFuturesApiService.getFuturesKlines(eq(symbol), eq(interval), eq(start), eq(end))).thenReturn(mockResponseEntity);

        mockMvc.perform(get("/api/mexc/futures/klines")
                        .param("symbol", symbol)
                        .param("interval", interval)
                        .param("start", String.valueOf(start))
                        .param("end", String.valueOf(end)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(mockResponseData));
    }

    // Add more integration tests for other controller endpoints
}

