/**
 * Common type definitions used throughout the application
 */

// Trade interface for consistent usage across components
export interface Trade {
  id: string;
  symbol: string;
  entryPrice: number;
  exitPrice: number;
  entryTime: number;
  exitTime: number;
  quantity: number;
  side: 'LONG' | 'SHORT';
  pnl: number;
  pnlPercent: number;
  strategy: string;
  status: 'OPEN' | 'CLOSED' | 'CANCELLED';
}

// Position interface for active trades
export interface Position {
  id: string;
  symbol: string;
  side: 'LONG' | 'SHORT';
  entryPrice: number;
  quantity: number;
  currentPrice: number;
  unrealizedPnl: number;
  pnlPercentage: number;
  openTime: string;
  updateTime: string;
  closeTime: string | null;
  status: string;
  botId: string;
  strategyName: string;
  stopLossPrice: number | null;
  takeProfitPrice: number | null;
}

// Order interface from API
export interface Order {
  orderId: string;
  symbol: string;
  side: string;
  price: number;
  origQty: number;
  executedQty: number;
  status: string;
  type: string;
  time: number;
  botId: string;
  strategyName: string;
}

// Account metrics interface
export interface AccountMetrics {
  totalValue: number;
  availableBalance: number;
  initialCapital: number;
  pnl: number;
  pnlPercent: number;
  balanceHistory: { timestamp: number; value: number }[];
} 