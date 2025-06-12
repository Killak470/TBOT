import axios, { AxiosError, AxiosResponse, AxiosRequestConfig, AxiosInstance } from 'axios';

// Extend the AxiosInstance interface to include our custom methods
interface CustomAxiosInstance extends AxiosInstance {
  getNews: (coins?: string, limit?: number) => Promise<AxiosResponse>;
  getSentiment: () => Promise<AxiosResponse>;
  getEventsCalendar: () => Promise<AxiosResponse>;
  getOnchainMetrics: (coin?: string) => Promise<AxiosResponse>;
  getCorrelationMatrix: () => Promise<AxiosResponse>;
  getFearGreedIndex: () => Promise<AxiosResponse>;
}

// Config
const API_BASE_URL = process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080/api'; 
const REQUEST_TIMEOUT = 30000; // Increase default timeout to 30 seconds for all requests
const FUTURES_REQUEST_TIMEOUT = 20000; // 20 seconds timeout for futures API
const NEWS_REQUEST_TIMEOUT = 15000; // 15 seconds timeout for news API

// Create axios instance
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: REQUEST_TIMEOUT, // Increased timeout
  headers: {
    'Content-Type': 'application/json',
  },
}) as CustomAxiosInstance;

// Add request interceptor for auth, logging, etc.
apiClient.interceptors.request.use(
  (config) => {
    // You could add auth token here:
    // const token = localStorage.getItem('token');
    // if (token) {
    //   config.headers['Authorization'] = `Bearer ${token}`;
    // }
    
    // Increase timeout for futures endpoints
    if (config.url?.includes('/futures/')) {
      config.timeout = FUTURES_REQUEST_TIMEOUT;
    }
    
    // Set timeout for news endpoints
    if (config.url?.includes('/news/')) {
      config.timeout = NEWS_REQUEST_TIMEOUT;
    }
    
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

// Create a wrapper for futures API calls with retries
const withRetry = (apiCall: Function, retries = 2, delay = 1000) => {
  return async (...args: any[]) => {
    let lastError;
    
    for (let attempt = 0; attempt <= retries; attempt++) {
      try {
        return await apiCall(...args);
      } catch (error) {
        lastError = error;
        console.log(`Attempt ${attempt + 1}/${retries + 1} failed, retrying in ${delay}ms...`);
        
        if (attempt < retries) {
          await new Promise(resolve => setTimeout(resolve, delay));
          // Increase delay for next retry (exponential backoff)
          delay *= 2;
        }
      }
    }
    
    throw lastError;
  };
};

// API Methods
// 1. Market Data API
const getSpotServerTime = () => apiClient.get('/market/spot/time');
const getSpotExchangeInfo = (symbol?: string) => 
  apiClient.get('/market/spot/exchangeInfo', { params: { symbol } });
const getSpotKlines = (symbol: string, interval: string, startTime?: number, endTime?: number, limit?: number) =>
  apiClient.get('/market/spot/klines', { params: { symbol, interval, startTime, endTime, limit } });

// Bybit API  
const getBybitKlines = (symbol: string, interval: string, limit?: number) =>
  apiClient.get('/bybit/klines', { 
    params: { symbol, interval, limit },
    timeout: 30000
  });

const getFuturesServerTime = () => apiClient.get('/market/futures/time');
const getFuturesContractDetail = (symbol?: string) => 
  apiClient.get('/market/futures/contract/detail', { 
    params: { symbol },
    timeout: 20000
  });

const getFuturesKlines = (symbol: string, interval: string, start: number, end: number) => 
  apiClient.get('/market/futures/contract/klines', {
    params: { symbol, interval, start, end },
    timeout: 30000
  });

// 2. News & Market Data API - Updated to work with real API endpoints
// Add methods directly to the apiClient object
apiClient.getNews = (coins?: string, limit: number = 10) => 
  apiClient.get('/news/market-headlines', {
    params: { coins, limit },
    timeout: NEWS_REQUEST_TIMEOUT
  });

apiClient.getSentiment = () => 
  apiClient.get('/news/sentiment', {
    timeout: NEWS_REQUEST_TIMEOUT
  });

apiClient.getEventsCalendar = () => 
  apiClient.get('/news/events-calendar', {
    timeout: NEWS_REQUEST_TIMEOUT
  });

apiClient.getOnchainMetrics = (coin: string = 'BTC') => 
  apiClient.get('/news/onchain-metrics', {
    params: { coin },
    timeout: NEWS_REQUEST_TIMEOUT
  });

apiClient.getCorrelationMatrix = () => 
  apiClient.get('/news/correlation', {
    timeout: NEWS_REQUEST_TIMEOUT
  });

apiClient.getFearGreedIndex = () => 
  apiClient.get('/news/fear-greed-index', {
    timeout: NEWS_REQUEST_TIMEOUT
  });

// 3. Bot Management API
interface BotConfig {
  id?: string;
  name: string;
  strategy: string;
  symbol: string;
  isActive?: boolean;
  parameters?: Record<string, any>;
}

// Bot Management API
const getBots = () => apiClient.get('/bots');
const getBotById = (id: string) => apiClient.get(`/bots/${id}`);
const createBot = (config: BotConfig) => apiClient.post('/bots', config);
const updateBot = (id: string, config: Partial<BotConfig>) => apiClient.put(`/bots/${id}`, config);
const deleteBot = (id: string) => apiClient.delete(`/bots/${id}`);
const startBot = (id: string) => apiClient.post(`/bots/${id}/start`);
const stopBot = (id: string) => apiClient.post(`/bots/${id}/stop`);
const getBotPerformance = (id: string) => apiClient.get(`/bots/${id}/performance`);

// Bot Signal API
const getPendingSignals = () => apiClient.get('/api/signals/bot/pending');
const getRecentSignals = () => apiClient.get('/api/signals/bot/recent');
const getSignalsByStatus = (status: string) => apiClient.get(`/api/signals/bot/status/${status}`);
const getSignalStats = () => apiClient.get('/api/signals/bot/stats');
const approveSignal = (signalId: number, approvedBy: string) => 
  apiClient.post(`/api/signals/bot/${signalId}/approve`, { approvedBy });
const rejectSignal = (signalId: number, rejectedBy: string, reason: string) => 
  apiClient.post(`/api/signals/bot/${signalId}/reject`, { rejectedBy, reason });

// 4. Trading API
interface TradeRequest {
  symbol: string;
  quantity: number;
  price?: number;
  type: 'MARKET' | 'LIMIT';
  side: 'BUY' | 'SELL';
  botId?: string;
}

const placeTrade = (trade: TradeRequest) => apiClient.post('/trades', trade);
const getTrades = (botId?: string) => apiClient.get('/trades', { params: { botId } });
const getTradeById = (id: string) => apiClient.get(`/trades/${id}`);
const cancelTrade = (id: string) => apiClient.delete(`/trades/${id}`);

// 5. Account API
const getAccountInfo = () => apiClient.get('/account');
const getAccountBalance = () => apiClient.get('/account/balance');
const getOpenOrders = () => apiClient.get('/account/openOrders');

// 6. System API
const getSystemStatus = () => apiClient.get('/system/status');
const getSystemLogs = (level?: string) => apiClient.get('/system/logs', { params: { level } });
const triggerSystemBackup = () => apiClient.post('/system/backup');

// Export all functions
export {
  getSpotServerTime,
  getSpotExchangeInfo,
  getSpotKlines,
  getBybitKlines,
  getFuturesServerTime,
  getFuturesContractDetail,
  getFuturesKlines,
  getBots,
  getBotById,
  createBot,
  updateBot,
  deleteBot,
  startBot,
  stopBot,
  getBotPerformance,
  getPendingSignals,
  getRecentSignals,
  getSignalsByStatus,
  getSignalStats,
  approveSignal,
  rejectSignal,
  placeTrade,
  getTrades,
  getTradeById,
  cancelTrade,
  getAccountInfo,
  getAccountBalance,
  getOpenOrders,
  getSystemStatus,
  getSystemLogs,
  triggerSystemBackup
};

// Export the API client as default
export default apiClient;

