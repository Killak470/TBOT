import axios, { AxiosError, AxiosResponse } from 'axios';

// Config
const API_BASE_URL = process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080/api'; 
const REQUEST_TIMEOUT = 60000; // Increase timeout to 60 seconds for AI analysis

// Create axios instance
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: REQUEST_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Add request interceptor for auth, logging, etc.
apiClient.interceptors.request.use(
  (config) => {
    // Custom timeout for specific endpoints that involve AI processing
    if (config.url?.includes('/scanner/custom-scan') && config.method === 'post') {
      config.timeout = 120000; // 2 minutes for custom scans with AI analysis
    }
    
    // You could add auth token here:
    // const token = localStorage.getItem('token');
    // if (token) {
    //   config.headers['Authorization'] = `Bearer ${token}`;
    // }
    
    // Log requests in dev mode
    if (process.env.NODE_ENV === 'development') {
      console.log(`API Request: ${config.method?.toUpperCase()} ${config.url}`, config.params || {});
    }
    return config;
  },
  (error) => {
    console.error('API Request Error:', error);
    return Promise.reject(error);
  }
);

// Add response interceptor for error handling
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    // Log responses in dev mode
    if (process.env.NODE_ENV === 'development') {
      console.log(`API Response: ${response.status} ${response.config.url}`, response.data);
    }
    return response;
  },
  (error: AxiosError) => {
    if (error.response) {
      // Server responded with an error status code
      console.error(`API Error ${error.response.status}:`, error.response.data);
      
      // Handle specific error codes
      if (error.response.status === 401) {
        // Handle unauthorized (e.g., redirect to login)
        console.error('Authentication error - please log in again');
        // Example: store.dispatch(logout());
      }
    } else if (error.code === 'ECONNABORTED') {
      // Request timed out
      console.error('Request timeout - the operation is taking longer than expected', error.message);
      // Return a custom error message for timeouts
      return Promise.reject({
        isTimeout: true,
        message: 'Request timed out. AI analysis may take longer to process.'
      });
    } else if (error.request) {
      // Request was made but no response received (network error)
      console.error('Network Error - No response received:', error.request);
    } else {
      // Something else happened while setting up the request
      console.error('API Request Setup Error:', error.message);
    }
    
    return Promise.reject(error);
  }
);

// API Methods
// 1. Market Data API
export const getSpotServerTime = () => apiClient.get('/market/spot/time');
export const getSpotExchangeInfo = (symbol?: string) => 
  apiClient.get('/market/spot/exchangeInfo', { params: { symbol } });
export const getSpotKlines = (symbol: string, interval: string, startTime?: number, endTime?: number, limit?: number) =>
  apiClient.get('/market/spot/klines', { params: { symbol, interval, startTime, endTime, limit } });

// Bybit API
export const getBybitKlines = (symbol: string, interval: string, limit?: number) =>
  apiClient.get('/bybit/klines', { 
    params: { symbol, interval, limit },
    timeout: 30000
  });

export const getBybitFuturesKlines = (symbol: string, interval: string, limit?: number) =>
  apiClient.get('/bybit/futures/klines', { 
    params: { symbol, interval, limit },
    timeout: 30000
  });

export const getFuturesServerTime = () => apiClient.get('/market/futures/time');
export const getFuturesContractDetail = (symbol?: string) => 
  apiClient.get('/market/futures/contract/detail', { params: { symbol } });
export const getFuturesKlines = (symbol: string, interval: string, start: number, end: number) =>
  apiClient.get('/market/futures/contract/klines', { params: { symbol, interval, start, end } });

// 2. Bot Management API
interface BotConfig {
  id?: string;
  name: string;
  strategy: string;
  symbol: string;
  isActive?: boolean;
  parameters?: Record<string, any>;
}

export const getBots = () => apiClient.get('/bots');
export const getBotById = (id: string) => apiClient.get(`/bots/${id}`);
export const createBot = (config: BotConfig) => apiClient.post('/bots', config);
export const updateBot = (id: string, config: Partial<BotConfig>) => apiClient.put(`/bots/${id}`, config);
export const deleteBot = (id: string) => apiClient.delete(`/bots/${id}`);
export const startBot = (id: string) => apiClient.post(`/bots/${id}/start`);
export const stopBot = (id: string) => apiClient.post(`/bots/${id}/stop`);
export const getBotPerformance = (id: string) => apiClient.get(`/bots/${id}/performance`);

// 3. Trading API
interface TradeRequest {
  symbol: string;
  quantity: number;
  price?: number;
  type: 'MARKET' | 'LIMIT';
  side: 'BUY' | 'SELL';
  botId?: string;
}

export const placeTrade = (trade: TradeRequest) => apiClient.post('/trades', trade);
export const getTrades = (botId?: string) => apiClient.get('/trades', { params: { botId } });
export const getTradeById = (id: string) => apiClient.get(`/trades/${id}`);
export const cancelTrade = (id: string) => apiClient.delete(`/trades/${id}`);

// Position Management API
export const getPositions = (status?: string) => apiClient.get('/positions', { params: { status } });
export const getPositionsBySymbol = (symbol: string, status?: string) => 
  apiClient.get(`/positions/symbol/${symbol}`, { params: { status } });
export const closePosition = (positionId: string, reason?: string) => 
  apiClient.post(`/positions/${positionId}/close`, null, { params: { reason } });
export const updatePositionSettings = (positionId: string, updates: any) => 
  apiClient.patch(`/positions/${positionId}`, updates);

// Orders API
export const getOpenOrders = (symbol?: string) => 
  apiClient.get('/orders/open', { params: { symbol } });
export const getOrderHistory = (symbol?: string) => 
  apiClient.get('/orders/history', { params: { symbol } });
export const placeOrder = (order: any) => 
  apiClient.post('/orders', order);
export const cancelOrder = (symbol: string, orderId: string) => 
  apiClient.delete(`/orders/${orderId}`, { params: { symbol } });

// 4. Account API
export const getAccountInfo = () => apiClient.get('/account');
export const getAccountBalance = () => apiClient.get('/account/balance');
export const getAccountOpenOrders = () => apiClient.get('/account/openOrders');

// 5. System API
export const getSystemStatus = () => apiClient.get('/system/status');
export const getSystemLogs = (level?: string) => apiClient.get('/system/logs', { params: { level } });
export const triggerSystemBackup = () => apiClient.post('/system/backup');

// Bot Signal API
export const getPendingSignals = () => apiClient.get('/signals/bot/pending');
export const getRecentSignals = () => apiClient.get('/signals/bot/recent');
export const getSignalsByStatus = (status: string) => apiClient.get(`/signals/bot/status/${status}`);
export const getSignalStats = () => apiClient.get('/signals/bot/stats');
export const approveSignal = (signalId: number, approvedBy: string) => 
  apiClient.post(`/signals/bot/${signalId}/approve`, { approvedBy });
export const rejectSignal = (signalId: number, rejectedBy: string, reason: string) => 
  apiClient.post(`/signals/bot/${signalId}/reject`, { rejectedBy, reason });

export default apiClient; 