import axios from 'axios';
import MockAdapter from 'axios-mock-adapter';
import { getSpotServerTime, getSpotExchangeInfo, getFuturesKlines } from './apiClient'; // Assuming apiClient.ts is in the same directory or adjust path

// Base URL for the mock API
const BASE_URL = process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080';

describe('apiClient', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(axios);
  });

  afterEach(() => {
    mock.restore();
  });

  describe('getSpotServerTime', () => {
    it('should fetch spot server time successfully', async () => {
      const mockData = { serverTime: 1678886400000 };
      mock.onGet(`${BASE_URL}/api/mexc/spot/time`).reply(200, mockData);

      const response = await getSpotServerTime();
      expect(response.data).toEqual(mockData);
    });

    it('should handle errors when fetching spot server time', async () => {
      mock.onGet(`${BASE_URL}/api/mexc/spot/time`).reply(500);
      await expect(getSpotServerTime()).rejects.toThrow('Request failed with status code 500');
    });
  });

  describe('getSpotExchangeInfo', () => {
    it('should fetch spot exchange info for a symbol successfully', async () => {
      const symbol = 'BTCUSDT';
      const mockData = { symbol: 'BTCUSDT', status: 'TRADING' };
      mock.onGet(`${BASE_URL}/api/mexc/spot/exchangeInfo?symbol=${symbol}`).reply(200, mockData);

      const response = await getSpotExchangeInfo(symbol);
      expect(response.data).toEqual(mockData);
    });

    it('should handle errors when fetching spot exchange info', async () => {
      const symbol = 'BTCUSDT';
      mock.onGet(`${BASE_URL}/api/mexc/spot/exchangeInfo?symbol=${symbol}`).reply(404);
      await expect(getSpotExchangeInfo(symbol)).rejects.toThrow('Request failed with status code 404');
    });
  });

  describe('getFuturesKlines', () => {
    it('should fetch futures klines data successfully', async () => {
      const params = {
        symbol: 'BTC_USDT',
        interval: 'Min15',
        startTime: 1678880000,
        endTime: 1678886400,
      };
      const mockData = { success: true, code: 0, data: { time: [1678880000], open: [25000] } };
      mock.onGet(`${BASE_URL}/api/mexc/futures/klines`, { params }).reply(200, mockData);

      const response = await getFuturesKlines(params.symbol, params.interval, params.startTime, params.endTime);
      expect(response.data).toEqual(mockData);
    });

    it('should handle errors when fetching futures klines data', async () => {
      const params = {
        symbol: 'BTC_USDT',
        interval: 'Min15',
        startTime: 1678880000,
        endTime: 1678886400,
      };
      mock.onGet(`${BASE_URL}/api/mexc/futures/klines`, { params }).reply(500);
      await expect(getFuturesKlines(params.symbol, params.interval, params.startTime, params.endTime))
        .rejects.toThrow('Request failed with status code 500');
    });
  });

  // Add more tests for other API client functions as they are implemented
});

