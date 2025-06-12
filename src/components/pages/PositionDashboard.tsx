import React, { useState, useEffect, useCallback, useRef } from 'react';
import { RetroButton, RetroCard } from '../common/CommonComponents';
import AccountBalance from '../common/AccountBalance';
import PerformanceChart from '../charts/PerformanceChart';
import StrategyComparisonChart from '../charts/StrategyComparisonChart';
import TradeHistoryVisualization from '../charts/TradeHistoryVisualization';
import TradingViewWidget from '../charts/TradingViewWidget';
import apiClient, { getOrderHistory } from '../../apiClient';
import { Trade, Position, AccountMetrics } from '../../types';
import { Client } from '@stomp/stompjs';

// Main Position Dashboard Component
const PositionDashboard: React.FC = () => {
  const [positions, setPositions] = useState<Position[]>([]);
  const [tradeHistory, setTradeHistory] = useState<Trade[]>([]);
  const [accountMetrics, setAccountMetrics] = useState<AccountMetrics | null>(null);
  const [strategyPerformance, setStrategyPerformance] = useState<any[]>([]);
  const [activeSymbol, setActiveSymbol] = useState<string>("BTCUSDT");
  const [balanceHistory, setBalanceHistory] = useState<any[]>([]);
  const [activeExchange, setActiveExchange] = useState<string>("MEXC");
  
  // StompJS client reference
  const stompClient = useRef<Client | null>(null);
  
  // Load account metrics from API
  const loadAccountMetrics = useCallback(async (positionData: Position[]) => {
    try {
      const response = await apiClient.get('/account/balance/all');
      if (response.data) {
        const apiMetrics = response.data;
        
        // Calculate total value across all exchanges
        const totalValue = parseFloat(apiMetrics.totalValueUSDT || '0');
        
        // Get balance history
        const balanceHistoryResponse = await apiClient.get('/performance/balance-history?days=30');
        let balanceHistory = balanceHistoryResponse.data?.data || [];
        
        if (!balanceHistory.length) {
          // Generate mock balance history if none is available
          const now = Date.now();
          balanceHistory = Array(30).fill(0).map((_, i) => {
            const date = new Date(now - (29 - i) * 24 * 60 * 60 * 1000);
            return {
              x: date.toISOString().split('T')[0],
              y: totalValue + (Math.random() * 2000 - 1000) * (i / 30)
            };
          });
        }
        
        setAccountMetrics({
          totalValue,
          availableBalance: totalValue, // Simplified for now
          initialCapital: balanceHistory[0]?.y || totalValue,
          pnl: totalValue - (balanceHistory[0]?.y || totalValue),
          pnlPercent: ((totalValue - (balanceHistory[0]?.y || totalValue)) / (balanceHistory[0]?.y || totalValue)) * 100,
          balanceHistory
        });
      }
    } catch (error) {
      console.error("Error loading account metrics:", error);
      // Fallback to calculated metrics based on positions
      const totalValue = positionData.reduce((sum, pos) => sum + pos.quantity * pos.currentPrice, 0);
      setAccountMetrics({
        totalValue,
        availableBalance: totalValue,
        initialCapital: totalValue,
        pnl: positionData.reduce((sum, pos) => sum + (pos.unrealizedPnl || 0), 0),
        pnlPercent: 0,
        balanceHistory: []
      });
    }
  }, []);
  
  // Initialize WebSocket connection
  const initializeWebSocket = useCallback(() => {
    console.log('Initializing WebSocket connection...');
    
    // Disconnect existing connection if any
    if (stompClient.current && stompClient.current.connected) {
      stompClient.current.deactivate();
    }
    
    // Create new STOMP client over SockJS
    const client = new Client({
      brokerURL: process.env.REACT_APP_WEBSOCKET_URL || 'ws://localhost:8080/ws',
      debug: (str: string) => {
        console.log('STOMP: ' + str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000
    });
    
    // On connect, subscribe to position updates
    client.onConnect = () => {
      console.log('Connected to WebSocket successfully');
      
      // Subscribe to position updates from the message broker
      client.subscribe('/topic/positions', (message: any) => {
        if (message.body) {
          try {
            const positionData = JSON.parse(message.body);
            console.log('Received position update via WebSocket:', positionData);
            
            if (positionData.positions) {
              setPositions(positionData.positions);
              loadAccountMetrics(positionData.positions);
            }
          } catch (error) {
            console.error('Error parsing WebSocket message:', error);
          }
        }
      });
      
      // Load initial data via REST API since we're now connected
      console.log('WebSocket connected, loading initial position data via REST API...');
      loadPositionsViaRest();
    };
    
    // Handle errors
    client.onStompError = (frame: any) => {
      console.error('STOMP error:', frame.headers['message']);
      console.error('Additional details:', frame.body);
      
      // Fallback to REST API if WebSocket fails
      console.log('WebSocket failed, falling back to REST API...');
      loadPositionsViaRest();
    };
    
    // Handle disconnection
    client.onDisconnect = () => {
      console.log('WebSocket disconnected, falling back to REST API...');
      loadPositionsViaRest();
    };
    
    // Start connection
    client.activate();
    stompClient.current = client;
    
    // Return cleanup function
    return () => {
      if (client.connected) {
        client.deactivate();
      }
    };
  }, [loadAccountMetrics]);

  // Fallback method to load positions via REST API
  const loadPositionsViaRest = useCallback(async () => {
    try {
      console.log('Loading positions via REST API for exchange:', activeExchange);
      const response = await apiClient.get(`/positions?exchange=${activeExchange}`);
      if (response.data && response.data.positions) {
        console.log('Loaded positions via REST API:', response.data.positions);
        setPositions(response.data.positions);
        loadAccountMetrics(response.data.positions);
      } else {
        console.log('No positions found via REST API');
        setPositions([]);
      }
    } catch (error) {
      console.error('Error loading positions via REST API:', error);
      setPositions([]);
    }
  }, [activeExchange, loadAccountMetrics]);
  
  // Load trade history
  const loadTradeHistory = useCallback(async () => {
    try {
      const response = await getOrderHistory();
      if (response && response.data && response.data.length > 0) {
        const trades: Trade[] = response.data.map((order: any) => ({
          id: order.orderId,
          symbol: order.symbol,
          entryTime: new Date(order.time).getTime(),
          exitTime: order.status === 'FILLED' ? new Date(order.updateTime).getTime() : Date.now(),
          entryPrice: parseFloat(order.price),
          exitPrice: order.status === 'FILLED' ? parseFloat(order.price) : 0,
          quantity: parseFloat(order.origQty),
          side: order.side as 'LONG' | 'SHORT',
          pnl: order.status === 'FILLED' ? (parseFloat(order.executedQty) * parseFloat(order.price)) : 0,
          pnlPercent: 0,
          status: order.status === 'FILLED' ? 'CLOSED' : 'OPEN',
          strategy: 'auto-trading'
        }));
        setTradeHistory(trades);
      } else {
        // Show empty trade history
        setTradeHistory([]);
        console.log("No trade history found. API returned empty data.");
      }
    } catch (error) {
      console.error("Error loading trade history:", error);
      // Show empty trade history
      setTradeHistory([]);
    }
  }, []);

  // Load balance history for account performance
  const loadBalanceHistory = useCallback(async () => {
    try {
      const response = await fetch('/api/performance/balance-history?days=30');
      if (response.ok) {
        const data = await response.json();
        if (data.success && data.data) {
          const historyData = data.data.map((snapshot: any) => ({
            timestamp: new Date(snapshot.timestamp).getTime(),
            value: parseFloat(snapshot.totalBalanceUsdt),
            profitLoss: snapshot.profitLoss ? parseFloat(snapshot.profitLoss) : 0,
            profitLossPercentage: snapshot.profitLossPercentage ? parseFloat(snapshot.profitLossPercentage) : 0
          }));
          
          setBalanceHistory(historyData);
          
          // Update account metrics
          if (historyData.length > 0) {
            const latest = historyData[historyData.length - 1];
            const initial = historyData[0];
            const totalPnl = latest.value - initial.value;
            const pnlPercent = initial.value > 0 ? (totalPnl / initial.value) * 100 : 0;
            
            setAccountMetrics({
              totalValue: latest.value,
              availableBalance: latest.value, // Simplified for now
              initialCapital: initial.value,
              pnl: totalPnl,
              pnlPercent: pnlPercent,
              balanceHistory: historyData
            });
          }
        }
      }
    } catch (error) {
      console.error('Error loading balance history:', error);
      setBalanceHistory([]);
    }
  }, []);

  // Load strategy performance data
  const loadStrategyPerformance = useCallback(async () => {
    try {
      const response = await fetch('/api/performance/strategy-performance');
      if (response.ok) {
        const data = await response.json();
        if (data.success && data.data) {
          const performanceData = data.data.map((strategy: any) => ({
            name: strategy.strategyName,
            totalTrades: strategy.totalTrades || 0,
            winRate: strategy.winRate ? parseFloat(strategy.winRate) : 0,
            totalPnL: strategy.totalProfitLoss ? parseFloat(strategy.totalProfitLoss) : 0,
            averageWin: strategy.averageWin ? parseFloat(strategy.averageWin) : 0,
            averageLoss: strategy.averageLoss ? parseFloat(strategy.averageLoss) : 0,
            maxDrawdown: strategy.maxDrawdown ? parseFloat(strategy.maxDrawdown) : 0,
            sharpeRatio: strategy.sharpeRatio ? parseFloat(strategy.sharpeRatio) : 0
          }));
          
          setStrategyPerformance(performanceData);
        }
      }
    } catch (error) {
      console.error('Error loading strategy performance:', error);
      setStrategyPerformance([]);
    }
  }, []);
  
  // Load data on component mount
  useEffect(() => {
    // Initialize WebSocket connection
    const cleanup = initializeWebSocket();
    
    // Load all data
    loadTradeHistory();
    loadBalanceHistory();
    loadStrategyPerformance();
    
    // Also load positions via REST API as a fallback/initial load
    loadPositionsViaRest();
    
    // Return cleanup function to disconnect WebSocket when component unmounts
    return cleanup;
  }, [initializeWebSocket, loadTradeHistory, loadBalanceHistory, loadStrategyPerformance, loadPositionsViaRest]);
  
  // Export trade history to CSV
  const exportTradeHistory = () => {
    // Generate CSV content
    const headers = "Symbol,Entry Time,Entry Price,Exit Time,Exit Price,Side,Quantity,P&L,P&L %,Strategy,Status\n";
    
    const csvContent = tradeHistory.reduce((content, trade) => {
      const row = [
        trade.symbol,
        new Date(trade.entryTime).toISOString(),
        trade.entryPrice,
        trade.status === 'CLOSED' ? new Date(trade.exitTime).toISOString() : '',
        trade.status === 'CLOSED' ? trade.exitPrice : '',
        trade.side,
        trade.quantity,
        trade.status === 'CLOSED' ? trade.pnl : '',
        trade.status === 'CLOSED' ? trade.pnlPercent : '',
        trade.strategy,
        trade.status
      ].join(',');
      
      return content + row + '\n';
    }, headers);
    
    // Create blob and download link
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.setAttribute('href', url);
    link.setAttribute('download', `trade-history-${new Date().toISOString().split('T')[0]}.csv`);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };
  
  // Generate performance report
  const generatePerformanceReport = () => {
    alert('Generating comprehensive performance report... This would generate a detailed PDF in a real implementation.');
  };
  
  // Effect to load positions when active exchange changes
  useEffect(() => {
    loadPositionsViaRest();
  }, [activeExchange, loadPositionsViaRest]);
  
  return (
    <div className="p-4 retro-text">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold retro-header">POSITION DASHBOARD</h1>
        <div className="flex items-center space-x-4">
          <select
            value={activeExchange}
            onChange={(e) => setActiveExchange(e.target.value)}
            className="retro-select px-3 py-1 text-sm"
          >
            <option value="MEXC">MEXC</option>
            <option value="BYBIT">BYBIT</option>
          </select>
          <div className="retro-terminal p-2 text-sm">
            <span>CONNECTION STATUS: ONLINE</span>
            <span className="status-cursor ml-1">█</span>
          </div>
        </div>
      </div>
      
      {/* Account Overview */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 mb-6">
        {/* Account Balance */}
        <AccountBalance showDetailed={true} />
        
        {/* Account Performance Chart */}
        <RetroCard className="p-4">
          <h2 className="text-sm font-bold mb-3">ACCOUNT PERFORMANCE</h2>
          {accountMetrics && accountMetrics.balanceHistory.length > 0 ? (
            <PerformanceChart 
              series={[{
                name: 'Portfolio Value',
                data: accountMetrics.balanceHistory,
                color: '#22ff22'
              }]}
              height={200}
              showLegend={false}
            />
          ) : (
            <div className="flex items-center justify-center h-48 text-retro-dimmed">
              <span>NO PERFORMANCE DATA AVAILABLE</span>
            </div>
          )}
        </RetroCard>
      </div>
      
      {/* Active Positions */}
      <div className="mb-6">
        <h2 className="text-lg font-semibold retro-subheader mb-4">ACTIVE POSITIONS ({activeExchange})</h2>
        
        {positions.length === 0 ? (
          <div className="retro-card p-6 text-center">
            <p>NO ACTIVE POSITIONS</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b-2 border-retro-green">
                  <th className="p-2 text-left">SYMBOL</th>
                  <th className="p-2 text-left">STRATEGY</th>
                  <th className="p-2 text-right">QTY</th>
                  <th className="p-2 text-right">ENTRY PRICE</th>
                  <th className="p-2 text-right">CURRENT PRICE</th>
                  <th className="p-2 text-right">ENTRY VALUE</th>
                  <th className="p-2 text-right">CURRENT VALUE</th>
                  <th className="p-2 text-right">P&L</th>
                  <th className="p-2 text-right">P&L %</th>
                  <th className="p-2 text-center">ACTIONS</th>
                </tr>
              </thead>
              <tbody>
                {positions.map(position => (
                  <tr 
                    key={position.id} 
                    className={`border-b border-retro-green border-opacity-30 hover:bg-retro-green hover:bg-opacity-10 cursor-pointer ${
                      position.symbol === activeSymbol ? 'bg-retro-green bg-opacity-10' : ''
                    }`}
                    onClick={() => setActiveSymbol(position.symbol)}
                  >
                    <td className="p-2 text-left font-bold">{position.symbol}</td>
                    <td className="p-2 text-left">{position.strategyName}</td>
                    <td className="p-2 text-right">{position.quantity.toFixed(4)}</td>
                    <td className="p-2 text-right">${position.entryPrice.toFixed(2)}</td>
                    <td className="p-2 text-right">${position.currentPrice.toFixed(2)}</td>
                    <td className="p-2 text-right">${(position.entryPrice * position.quantity).toFixed(2)}</td>
                    <td className="p-2 text-right">${(position.currentPrice * position.quantity).toFixed(2)}</td>
                    <td className={`p-2 text-right ${position.unrealizedPnl >= 0 ? 'text-retro-green' : 'text-retro-red'}`}>
                      {position.unrealizedPnl >= 0 ? '+' : '-'}${Math.abs(position.unrealizedPnl).toFixed(2)}
                    </td>
                    <td className={`p-2 text-right ${position.unrealizedPnl >= 0 ? 'text-retro-green' : 'text-retro-red'}`}>
                      {position.unrealizedPnl >= 0 ? '+' : '-'}{position.pnlPercentage.toFixed(2)}%
                    </td>
                    <td className="p-2 text-center">
                      <RetroButton 
                        className="text-xs py-1 px-2"
                        variant="danger"
                        onClick={(e) => {
                          e.stopPropagation();
                          alert(`Closing position for ${position.symbol} (This would execute a market order in a real implementation)`);
                        }}
                      >
                        CLOSE
                      </RetroButton>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      
      {/* Chart for Selected Symbol */}
      {activeSymbol && (
        <div className="mb-6">
          <h2 className="text-lg font-semibold retro-subheader mb-4">CHART: {activeSymbol}</h2>
          <div className="h-96">
            <TradingViewWidget symbol={`${activeExchange}:${activeSymbol}`} />
          </div>
        </div>
      )}
      
      {/* Strategy Comparison */}
      <div className="mb-6">
        <StrategyComparisonChart strategies={strategyPerformance} />
      </div>
      
      {/* Trade History */}
      <div className="mb-6">
        <TradeHistoryVisualization 
          trades={tradeHistory} 
          onExport={exportTradeHistory}
          generateReport={generatePerformanceReport}
        />
      </div>
    </div>
  );
};

export default PositionDashboard; 