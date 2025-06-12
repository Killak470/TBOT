package com.tradingbot.backend.service;

import com.tradingbot.backend.config.MexcApiConfig;
import com.tradingbot.backend.service.client.HttpClientService;
import com.tradingbot.backend.service.util.SignatureUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MexcApiClientServiceTest {

    @Mock
    private MexcApiConfig mexcApiConfig;

    @Mock
    private HttpClientService httpClientService;

    @InjectMocks
    private MexcApiClientService mexcApiClientService;

    private final String MOCK_API_KEY = "mx0vglJtjeIJxhWheu";
    private final String MOCK_SECRET_KEY = "ce6589bcd21c4c698e0b607de27422e8";
    private final String MOCK_SPOT_BASE_URL = "https://spot.mexc.test";
    private final String MOCK_FUTURES_BASE_URL = "https://futures.mexc.test";

    @BeforeEach
    void setUp() {
        lenient().when(mexcApiConfig.getApiKey()).thenReturn(MOCK_API_KEY);
        lenient().when(mexcApiConfig.getSecretKey()).thenReturn(MOCK_SECRET_KEY);
    }

    @Test
    void getSpotServerTime_shouldReturnServerTime() {
        String expectedResponse = "{\"serverTime\": 1678886400000}";
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(expectedResponse, HttpStatus.OK);

        when(mexcApiConfig.getSpotApiV3Path("/time")).thenReturn(MOCK_SPOT_BASE_URL + "/api/v3/time");
        when(httpClientService.get(eq(MOCK_SPOT_BASE_URL + "/api/v3/time"), any(HttpHeaders.class), eq(String.class), eq(null)))
                .thenReturn(mockResponseEntity);

        ResponseEntity<String> actualResponse = mexcApiClientService.getSpotServerTime();

        assertNotNull(actualResponse);
        assertEquals(HttpStatus.OK, actualResponse.getStatusCode());
        assertEquals(expectedResponse, actualResponse.getBody());
    }

    @Test
    void getSpotExchangeInfo_withSymbol_shouldReturnExchangeInfo() {
        String symbol = "BTCUSDT";
        String expectedResponse = "{\"symbol\": \"BTCUSDT\", \"status\": \"TRADING\"}";
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(expectedResponse, HttpStatus.OK);
        long currentTimestamp = 1678886400000L; // Fixed timestamp for testing

        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        String queryString = "symbol=BTCUSDT";

        // Mock only SignatureUtil methods
        try (MockedStatic<SignatureUtil> mockedSignatureUtil = Mockito.mockStatic(SignatureUtil.class)) {
            when(mexcApiConfig.getSpotApiV3Path("/exchangeInfo")).thenReturn(MOCK_SPOT_BASE_URL + "/api/v3/exchangeInfo");

            mockedSignatureUtil.when(() -> SignatureUtil.buildQueryString(any(Map.class))).thenReturn(queryString);
            mockedSignatureUtil.when(() -> SignatureUtil.generateSpotV3Signature(eq(MOCK_SECRET_KEY), anyString(), eq(queryString)))
                             .thenReturn("mockedSignature");

            when(httpClientService.get(eq(MOCK_SPOT_BASE_URL + "/api/v3/exchangeInfo?symbol=BTCUSDT"), any(HttpHeaders.class), eq(String.class), eq(null)))
                    .thenReturn(mockResponseEntity);

            ResponseEntity<String> actualResponse = mexcApiClientService.getSpotExchangeInfo(symbol);

            assertNotNull(actualResponse);
            assertEquals(HttpStatus.OK, actualResponse.getStatusCode());
            assertEquals(expectedResponse, actualResponse.getBody());
        }
    }

    @Test
    void getFuturesKlines_shouldReturnKlinesData() {
        String symbol = "BTC_USDT";
        String interval = "Min15";
        Long start = 1678880000L;
        Long end = 1678886400L;
        String expectedResponse = "{\"success\":true, \"code\":0, \"data\":{\"time\":[1678880000], \"open\":[25000]}}";
        ResponseEntity<String> mockResponseEntity = new ResponseEntity<>(expectedResponse, HttpStatus.OK);

        Map<String, String> params = new HashMap<>();
        params.put("symbol", symbol);
        params.put("interval", interval);
        params.put("start", String.valueOf(start));
        params.put("end", String.valueOf(end));
        String queryString = "end=1678886400&interval=Min15&start=1678880000&symbol=BTC_USDT";

        when(mexcApiConfig.getFuturesApiV1Path("/kline")).thenReturn(MOCK_FUTURES_BASE_URL + "/api/v1/contract/kline");
        when(httpClientService.get(eq(MOCK_FUTURES_BASE_URL + "/api/v1/contract/kline?" + queryString), any(HttpHeaders.class), eq(String.class), eq(null)))
                .thenReturn(mockResponseEntity);

        ResponseEntity<String> actualResponse = mexcApiClientService.getFuturesKlines(symbol, interval, start, end);

        assertNotNull(actualResponse);
        assertEquals(HttpStatus.OK, actualResponse.getStatusCode());
        assertEquals(expectedResponse, actualResponse.getBody());
    }
}

