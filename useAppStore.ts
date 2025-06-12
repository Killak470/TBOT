import { create } from "zustand";
import * as api from "./apiClient";

// Define interfaces for your state
interface UserPreferences {
  theme: string;
  defaultChartInterval: string;
  defaultSymbol: string;
  // Add other preferences as needed
}

interface AuthState {
  isAuthenticated: boolean;
  user: { id: string; username: string } | null;
  token: string | null;
}

interface BotConfig {
  id: string;
  name: string;
  strategy: string;
  symbol: string;
  isActive: boolean;
  profit?: number;
  trades?: number;
  lastActive?: string;
}

interface Trade {
  id: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  price: number;
  timestamp: number;
  botId?: string;
  status: 'OPEN' | 'CLOSED' | 'CANCELLED';
  profitLoss?: number;
}

interface ExchangeInfo {
  symbols?: Array<{
    symbol: string;
    status: string;
    baseAsset: string;
    quoteAsset: string;
  }>;
  timezone?: string;
  serverTime?: number;
}

interface SystemStatus {
  status: 'ONLINE' | 'OFFLINE' | 'MAINTENANCE';
  serverTime: number;
  uptime: number;
  version: string;
  connections: number;
}

interface APIState {
  // Data
  bots: BotConfig[];
  trades: Trade[];
  exchangeInfo: ExchangeInfo;
  systemStatus: SystemStatus;
  serverTimeOffset: number;
  
  // Loading states
  isLoadingBots: boolean;
  isLoadingTrades: boolean;
  isLoadingExchangeInfo: boolean;
  isLoadingSystemStatus: boolean;
  
  // Error states
  botsError: string | null;
  tradesError: string | null;
  exchangeInfoError: string | null;
  systemStatusError: string | null;
}

interface AppState {
  userPreferences: UserPreferences;
  auth: AuthState;
  api: APIState;
  
  // User preference actions
  setUserPreferences: (preferences: Partial<UserPreferences>) => void;
  
  // Auth actions
  login: (userData: { id: string; username: string }, token: string) => void;
  logout: () => void;
  
  // API actions
  fetchBots: () => Promise<void>;
  fetchTrades: () => Promise<void>;
  fetchExchangeInfo: (symbol?: string) => Promise<void>;
  fetchSystemStatus: () => Promise<void>;
  startBot: (botId: string) => Promise<void>;
  stopBot: (botId: string) => Promise<void>;
  createBot: (config: Omit<BotConfig, 'id'>) => Promise<void>;
  deleteBot: (botId: string) => Promise<void>;
  syncServerTime: () => Promise<void>;
}

const useAppStore = create<AppState>((set, get) => ({
  // Initial state
  userPreferences: {
    theme: "retro-dark", // Default theme
    defaultChartInterval: "1h",
    defaultSymbol: "BTCUSDT"
  },
  auth: {
    isAuthenticated: false,
    user: null,
    token: null,
  },
  api: {
    // Data
    bots: [],
    trades: [],
    exchangeInfo: {},
    systemStatus: {
      status: 'OFFLINE',
      serverTime: Date.now(),
      uptime: 0,
      version: '0.1.0',
      connections: 0
    },
    serverTimeOffset: 0,
    
    // Loading states
    isLoadingBots: false,
    isLoadingTrades: false,
    isLoadingExchangeInfo: false,
    isLoadingSystemStatus: false,
    
    // Error states
    botsError: null,
    tradesError: null,
    exchangeInfoError: null,
    systemStatusError: null,
  },

  // User preference actions
  setUserPreferences: (preferences) =>
    set((state) => ({
      userPreferences: { ...state.userPreferences, ...preferences },
    })),

  // Auth actions
  login: (userData, token) =>
    set(() => ({
      auth: {
        isAuthenticated: true,
        user: userData,
        token: token,
      },
    })),

  logout: () =>
    set(() => ({
      auth: {
        isAuthenticated: false,
        user: null,
        token: null,
      },
    })),
    
  // API actions
  fetchBots: async () => {
    set((state) => ({ 
      api: { 
        ...state.api, 
        isLoadingBots: true,
        botsError: null
      } 
    }));
    
    try {
      // Simulate API call - in production, use the real API
      // const response = await api.getBots();
      
      // Mock data for demo
      const mockBots = [
        { 
          id: 'bot1', 
          name: 'BTC DOMINATOR', 
          strategy: 'FIBONACCI + SENTIMENT', 
          symbol: 'BTCUSDT', 
          isActive: true,
          profit: 234.56,
          trades: 12,
          lastActive: '2 minutes ago'
        },
        { 
          id: 'bot2', 
          name: 'ETH MOMENTUM', 
          strategy: 'TA + CLAUDE AI', 
          symbol: 'ETHUSDT', 
          isActive: false,
          profit: -45.23,
          trades: 5,
          lastActive: '3 hours ago'
        },
        { 
          id: 'bot3', 
          name: 'ALTCOIN HUNTER', 
          strategy: 'MARKET SCANNER', 
          symbol: 'MULTIPLE', 
          isActive: false,
          profit: 123.45,
          trades: 20,
          lastActive: '1 day ago'
        },
      ];
      
      setTimeout(() => {
        set((state) => ({ 
          api: { 
            ...state.api, 
            bots: mockBots,
            isLoadingBots: false 
          } 
        }));
      }, 1000);
    } catch (error) {
      console.error('Error fetching bots:', error);
      set((state) => ({ 
        api: { 
          ...state.api, 
          isLoadingBots: false,
          botsError: 'Failed to fetch bots' 
        } 
      }));
    }
  },
  
  fetchTrades: async () => {
    set((state) => ({ 
      api: { 
        ...state.api, 
        isLoadingTrades: true,
        tradesError: null 
      } 
    }));
    
    try {
      // Simulate API call - in production, use the real API
      // const response = await api.getTrades();
      
      // Mock data for demo
      const mockTrades = [
        { 
          id: 'trade1', 
          symbol: 'BTCUSDT', 
          side: 'BUY' as const, 
          quantity: 0.1, 
          price: 62435.5, 
          timestamp: Date.now() - 1000000,
          botId: 'bot1',
          status: 'OPEN' as const
        },
        { 
          id: 'trade2', 
          symbol: 'ETHUSDT', 
          side: 'SELL' as const, 
          quantity: 1.2, 
          price: 3452.8, 
          timestamp: Date.now() - 2000000,
          botId: 'bot2',
          status: 'CLOSED' as const,
          profitLoss: 245.67
        }
      ];
      
      setTimeout(() => {
        set((state) => ({ 
          api: { 
            ...state.api, 
            trades: mockTrades,
            isLoadingTrades: false 
          } 
        }));
      }, 800);
    } catch (error) {
      console.error('Error fetching trades:', error);
      set((state) => ({ 
        api: { 
          ...state.api, 
          isLoadingTrades: false,
          tradesError: 'Failed to fetch trades' 
        } 
      }));
    }
  },
  
  fetchExchangeInfo: async (symbol) => {
    set((state) => ({ 
      api: { 
        ...state.api, 
        isLoadingExchangeInfo: true,
        exchangeInfoError: null 
      } 
    }));
    
    try {
      // In production, use the real API
      const response = await api.getSpotExchangeInfo(symbol);
      
      set((state) => ({ 
        api: { 
          ...state.api, 
          exchangeInfo: response.data,
          isLoadingExchangeInfo: false 
        } 
      }));
    } catch (error) {
      console.error('Error fetching exchange info:', error);
      set((state) => ({ 
        api: { 
          ...state.api, 
          isLoadingExchangeInfo: false,
          exchangeInfoError: 'Failed to fetch exchange info' 
        } 
      }));
    }
  },
  
  fetchSystemStatus: async () => {
    set((state) => ({ 
      api: { 
        ...state.api, 
        isLoadingSystemStatus: true,
        systemStatusError: null 
      } 
    }));
    
    try {
      // Simulate API call - in production, use the real API
      // const response = await api.getSystemStatus();
      
      // Mock data for demo
      const mockStatus = {
        status: 'ONLINE' as const,
        serverTime: Date.now(),
        uptime: 86400000, // 1 day in ms
        version: '0.1.0',
        connections: 24
      };
      
      setTimeout(() => {
        set((state) => ({ 
          api: { 
            ...state.api, 
            systemStatus: mockStatus,
            isLoadingSystemStatus: false 
          } 
        }));
      }, 600);
    } catch (error) {
      console.error('Error fetching system status:', error);
      set((state) => ({ 
        api: { 
          ...state.api, 
          isLoadingSystemStatus: false,
          systemStatusError: 'Failed to fetch system status' 
        } 
      }));
    }
  },
  
  startBot: async (botId) => {
    try {
      // In production, use the real API
      // await api.startBot(botId);
      
      // Update local state
      set((state) => ({
        api: {
          ...state.api,
          bots: state.api.bots.map(bot => 
            bot.id === botId 
              ? { ...bot, isActive: true, lastActive: 'just now' } 
              : bot
          )
        }
      }));
    } catch (error) {
      console.error(`Error starting bot ${botId}:`, error);
    }
  },
  
  stopBot: async (botId) => {
    try {
      // In production, use the real API
      // await api.stopBot(botId);
      
      // Update local state
      set((state) => ({
        api: {
          ...state.api,
          bots: state.api.bots.map(bot => 
            bot.id === botId 
              ? { ...bot, isActive: false, lastActive: 'just now' } 
              : bot
          )
        }
      }));
    } catch (error) {
      console.error(`Error stopping bot ${botId}:`, error);
    }
  },
  
  createBot: async (config) => {
    try {
      // In production, use the real API
      // const response = await api.createBot(config);
      
      // Mock response for demo
      const newBot = {
        ...config,
        id: `bot${get().api.bots.length + 1}`,
        isActive: false,
        profit: 0,
        trades: 0,
        lastActive: 'Never'
      };
      
      set((state) => ({
        api: {
          ...state.api,
          bots: [...state.api.bots, newBot]
        }
      }));
    } catch (error) {
      console.error('Error creating bot:', error);
    }
  },
  
  deleteBot: async (botId) => {
    try {
      // In production, use the real API
      // await api.deleteBot(botId);
      
      // Update local state
      set((state) => ({
        api: {
          ...state.api,
          bots: state.api.bots.filter(bot => bot.id !== botId)
        }
      }));
    } catch (error) {
      console.error(`Error deleting bot ${botId}:`, error);
    }
  },
  
  syncServerTime: async () => {
    try {
      const response = await api.getSpotServerTime();
      const serverTime = response.data.serverTime;
      const localTime = Date.now();
      const offset = serverTime - localTime;
      
      set((state) => ({
        api: {
          ...state.api,
          serverTimeOffset: offset
        }
      }));
    } catch (error) {
      console.error('Error syncing server time:', error);
    }
  }

export default useAppStore;

