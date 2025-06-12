import React, { useState, useEffect, useRef, useCallback } from 'react';
import apiClient from '../../apiClient';
import { Client } from '@stomp/stompjs';
import { FiPlay, FiPause, FiEdit, FiTrash2, FiTrendingUp, FiTrendingDown, FiCheckCircle, FiAlertTriangle, FiDollarSign, FiBarChart2, FiInfo } from 'react-icons/fi';
// import TradingViewWidget from '../charts/TradingViewWidget';
import VolumeProfileChart from '../charts/VolumeProfileChart';
// import RiskMetricsChart from '../charts/RiskMetricsChart';

interface Position {
  id: string;
  symbol: string;
  side: 'LONG' | 'SHORT';
  size: number;
  entryPrice: number;
  markPrice: number;
  unrealizedPnl: number;
  percentage: number;
  strategyName?: string;
  botId?: string;
  openTime: string;
  stopLoss?: number;
  takeProfit?: number;
  leverage?: number;
  margin?: number;
  drawdownPercentage?: number;
  liquidationPrice?: number;
  positionValue?: number;
}

interface TradeAnalysis {
  volumeProfile: {
    highLiquidityZones: Array<{
      priceLevel: number;
      volume: number;
      type: 'BUY' | 'SELL';
      strength: number;
    }>;
    volumeWeightedAvgPrice: number;
    totalBuyVolume: number;
    totalSellVolume: number;
    volumeImbalance: number;
  };
  supportLevels: Array<{
    price: number;
    strength: number;
    type: string;
    confidence: number;
  }>;
  resistanceLevels: Array<{
    price: number;
    strength: number;
    type: string;
    confidence: number;
  }>;
  marketSentiment: {
    buyPressure: number;
    sellPressure: number;
    netFlow: number;
    overallSentiment: string;
    momentum: number;
    volatility: number;
  };
  suggestedAdjustments: Array<{
    type: string;
    action: string;
    currentValue: number;
    suggestedValue: number;
    adjustmentPercentage: number;
    reason: string;
    confidence: number;
    riskImpact: {
      newRiskRewardRatio: number;
      maxLossChange: number;
      probabilityOfSuccess: number;
    };
  }>;
}

interface PositionAnalysis {
  position: Position;
  analysis: TradeAnalysis;
}

const ActiveTrades: React.FC = () => {
  const [positions, setPositions] = useState<Position[]>([]);
  const [selectedPosition, setSelectedPosition] = useState<Position | null>(null);
  const [analysis, setAnalysis] = useState<TradeAnalysis | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const stompClient = useRef<Client | null>(null);
  const [socketStatus, setSocketStatus] = useState<'connecting' | 'connected' | 'disconnected' | 'error'>('connecting');

  const connectWebSocket = useCallback(() => {
    // WebSocket connection logic here
    console.log('Connecting WebSocket...');
    setSocketStatus('connecting');

    const client = new Client({
      brokerURL: process.env.REACT_APP_WEBSOCKET_URL || 'ws://localhost:8080/ws', // Adjust if your backend URL is different
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        console.log('WebSocket connected');
        setSocketStatus('connected');
        client.subscribe('/topic/positions/active', message => { // Adjust topic as needed
          try {
            const positionAnalysis: PositionAnalysis = JSON.parse(message.body);
            updatePosition(positionAnalysis);
          } catch (e) {
            console.error('Error processing WebSocket message:', e, message.body);
          }
        });
        // Optionally, subscribe to other topics like position closures, new positions etc.
        client.subscribe('/topic/positions/updates', message => { // General updates
            try {
                const update = JSON.parse(message.body);
                // Assuming the update could be a single position or an array
                if (Array.isArray(update)) {
                    setPositions(prev => {
                        let newPositions = [...prev];
                        update.forEach(posUpd => {
                            const idx = newPositions.findIndex(p => p.id === posUpd.id);
                            if (idx !== -1) {
                                newPositions[idx] = { ...newPositions[idx], ...posUpd };
                            } else {
                                newPositions.push(posUpd);
                            }
                        });
                        return newPositions;
                    });
                } else if (update.id) { // Single position update
                     updatePosition(update); // Reuse if it's PositionAnalysis
                } else {
                    // Potentially a delete message or other format
                    if (update.action === 'DELETE' && update.positionId) {
                        setPositions(prev => prev.filter(p => p.id !== update.positionId));
                    }
                }
            } catch (e) {
                console.error('Error processing /topic/positions/updates:', e, message.body);
            }
        });
      },
      onStompError: (frame) => {
        console.error('Broker reported error: ' + frame.headers['message']);
        console.error('Additional details: ' + frame.body);
        setSocketStatus('error');
      },
      onDisconnect: () => {
        console.log('WebSocket disconnected');
        setSocketStatus('disconnected');
      },
      onWebSocketError: (error) => {
        console.error('WebSocket error:', error);
        setSocketStatus('error');
      }
    });

    client.activate();
    stompClient.current = client;

  }, []);

  useEffect(() => {
    loadData(); // Load initial data
    connectWebSocket();

    return () => {
      if (stompClient.current && stompClient.current.connected) {
        stompClient.current.deactivate();
        console.log('WebSocket deactivated');
      }
    };
  }, [connectWebSocket]);

  const updatePosition = (positionData: Position | PositionAnalysis) => {
    let position: Position;
    let analysis: TradeAnalysis | null = null;

    if ('analysis' in positionData) { // It's PositionAnalysis
        position = positionData.position;
        analysis = positionData.analysis;
    } else { // It's just Position
        position = positionData;
    }

    setPositions(prev => {
      const index = prev.findIndex(p => p.id === position.id);
      if (index >= 0) {
        const updated = [...prev];
        updated[index] = position;
        return updated;
      }
      return [...prev, position];
    });

    if (selectedPosition?.id === position.id) {
      setAnalysis(analysis);
    }
    setLoading(false);
  };

  const loadData = async () => {
    try {
      const response = await apiClient.get('/positions?status=OPEN&exchange=BYBIT');
      // Ensure that we are setting positions to an array
      if (Array.isArray(response.data)) {
        setPositions(response.data);
      } else if (response.data && Array.isArray(response.data.positions)) {
        // Check for response.data.positions based on provided API response structure
        setPositions(response.data.positions);
      } else if (response.data && Array.isArray(response.data.data)) { 
        // Common pattern: API response is an object like { data: [...], ... }
        setPositions(response.data.data);
      } else if (response.data && Array.isArray(response.data.content)) {
        // Another common pattern for paginated responses
        setPositions(response.data.content);
      } else if (response.data && Array.isArray(response.data.results)) {
        // Yet another common pattern
        setPositions(response.data.results);
      } else {
        // If data is not in expected array format or is null/undefined, default to empty array
        console.warn('API response for /positions?status=OPEN was not an array or a known object structure. Defaulting to empty positions array. Response:', response.data);
        setPositions([]); 
      }
      setError(null);
    } catch (err) {
      setError('Failed to load active trades');
      console.error('Error loading active trades:', err);
    } finally {
      setLoading(false);
    }
  };

  const handlePositionSelect = async (position: Position) => {
    setSelectedPosition(position);
    try {
      const response = await apiClient.get(`/positions/${position.id}/analysis`);
      setAnalysis(response.data);
    } catch (err) {
      console.error('Error loading position analysis:', err);
    }
  };

  const closePosition = async (positionId: string) => {
    try {
      await apiClient.post(`/positions/${positionId}/close`, {
        reason: 'Manual close by user'
      });
      await loadData(); // Refresh data
    } catch (err) {
      setError('Failed to close position');
      console.error('Error closing position:', err);
    }
  };

  const formatCurrency = (value: number) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2
    }).format(value);
  };

  const formatPercentage = (value?: number) => {
    const numValue = typeof value === 'number' ? value : 0;
    const sign = numValue >= 0 ? '+' : '';
    return `${sign}${numValue.toFixed(2)}%`;
  };

  const getPnlColor = (pnl: number) => {
    return pnl >= 0 ? 'text-green-600' : 'text-red-600';
  };

  const getSideColor = (side: string) => {
    return side === 'LONG' ? 'text-green-600 bg-green-100' : 'text-red-600 bg-red-100';
  };

  const getStrategyForPosition = (position: Position) => {
    // Try to find the executed signal that created this position
    const relatedSignal = positions.find(pos => 
      pos.symbol === position.symbol &&
      Math.abs(new Date(pos.openTime).getTime() - new Date(position.openTime).getTime()) < 3600000 // Within 1 hour
    );
    
    return position.strategyName || relatedSignal?.strategyName || 'Manual Trade';
  };

  if (loading) {
    return (
      <div className="p-6">
        <div className="animate-pulse">
          <div className="h-8 bg-gray-300 rounded w-1/4 mb-6"></div>
          <div className="space-y-4">
            {[1, 2, 3].map(i => (
              <div key={i} className="h-24 bg-gray-300 rounded"></div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  // Determine overall status for display
  let statusDisplay = "STATUS: INITIALIZING";
  let statusColor = "bg-yellow-400";

  if (!loading && error) {
      statusDisplay = "STATUS: ERROR LOADING DATA";
      statusColor = "bg-red-500";
  } else if (!loading && socketStatus === 'connected') {
      statusDisplay = "STATUS: CONNECTED";
      statusColor = "bg-green-500";
  } else if (!loading && (socketStatus === 'disconnected' || socketStatus === 'error')) {
      statusDisplay = `STATUS: WEBSOCKET ${socketStatus.toUpperCase()}`;
      statusColor = "bg-red-500";
  } else if (!loading && socketStatus === 'connecting') {
      statusDisplay = "STATUS: CONNECTING WEBSOCKET...";
      statusColor = "bg-yellow-400";
  }

  return (
    <div className="p-6">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Active Trades</h1>
        <div className={`px-3 py-1 text-sm font-semibold text-white rounded ${statusColor}`}>
            {statusDisplay} (Socket: {socketStatus})
        </div>
        <div className="text-sm text-gray-500">
          Last updated: {new Date().toLocaleTimeString()}
        </div>
      </div>

      {error && (
        <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">
          {error}
        </div>
      )}

      {/* Summary Cards */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <div className="bg-blue-50 p-4 rounded-lg">
          <div className="text-blue-600 text-sm font-medium">Active Positions</div>
          <div className="text-2xl font-bold text-blue-700">{positions.length}</div>
        </div>
        <div className="bg-green-50 p-4 rounded-lg">
          <div className="text-green-600 text-sm font-medium">Total P&L</div>
          <div className={`text-2xl font-bold ${getPnlColor(positions.reduce((sum, pos) => sum + pos.unrealizedPnl, 0))}`}>
            {formatCurrency(positions.reduce((sum, pos) => sum + pos.unrealizedPnl, 0))}
          </div>
        </div>
        <div className="bg-yellow-50 p-4 rounded-lg">
          <div className="text-yellow-600 text-sm font-medium">Winning Trades</div>
          <div className="text-2xl font-bold text-yellow-700">
            {positions.filter(pos => pos.unrealizedPnl > 0).length}
          </div>
        </div>
        <div className="bg-red-50 p-4 rounded-lg">
          <div className="text-red-600 text-sm font-medium">Losing Trades</div>
          <div className="text-2xl font-bold text-red-700">
            {positions.filter(pos => pos.unrealizedPnl < 0).length}
          </div>
        </div>
      </div>
      
      {/* Main Content Grid */}
      <div className="grid grid-cols-12 gap-6">
        {/* Positions Table */}
        <div className="col-span-8">
          <div className="bg-white rounded-lg shadow-md overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Symbol/Strategy
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Side
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Size
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Entry Price
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Mark Price
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      P&L
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      P&L %
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Drawdown %
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Liq. Price
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Pos. Value
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Open Time
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                      Actions
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {positions.map((position) => (
                    <tr 
                      key={position.id} 
                      className={`cursor-pointer hover:bg-gray-50 ${
                        selectedPosition?.id === position.id ? 'bg-blue-50' : ''
                      }`}
                      onClick={() => handlePositionSelect(position)}
                    >
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div>
                          <div className="text-sm font-medium text-gray-900">{position.symbol}</div>
                          <div className="text-sm text-gray-500">{getStrategyForPosition(position)}</div>
                        </div>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span className={`px-2 py-1 rounded text-xs font-medium ${getSideColor(position.side)}`}>
                          {position.side}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {position.size}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {formatCurrency(position.entryPrice)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {formatCurrency(position.markPrice)}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span className={`text-sm font-medium ${getPnlColor(position.unrealizedPnl)}`}>
                          {formatCurrency(position.unrealizedPnl)}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span className={`text-sm font-medium ${getPnlColor(position.percentage)}`}>
                          {formatPercentage(position.percentage)}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span className={`text-sm font-medium ${getPnlColor(position.drawdownPercentage ?? 0)}`}>
                          {formatPercentage(position.drawdownPercentage)}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {position.liquidationPrice ? formatCurrency(position.liquidationPrice) : 'N/A'}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        {position.positionValue ? formatCurrency(position.positionValue) : 'N/A'}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        {new Date(position.openTime).toLocaleString()}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap text-sm font-medium">
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            closePosition(position.id);
                          }}
                          className="text-red-600 hover:text-red-900"
                        >
                          Close
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        {/* Analysis Panel */}
        <div className="col-span-4">
          {selectedPosition && analysis ? (
            <div className="space-y-6">
              {/* Market Sentiment */}
              <div className="bg-white rounded-lg shadow-md p-4">
                <h3 className="text-lg font-semibold mb-4">Market Sentiment</h3>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="text-sm text-gray-500">Overall</div>
                    <div className={`text-lg font-bold ${
                      analysis.marketSentiment.overallSentiment === 'BULLISH' ? 'text-green-600' :
                      analysis.marketSentiment.overallSentiment === 'BEARISH' ? 'text-red-600' :
                      'text-yellow-600'
                    }`}>
                      {analysis.marketSentiment.overallSentiment}
                    </div>
                  </div>
                  <div>
                    <div className="text-sm text-gray-500">Net Flow</div>
                    <div className={`text-lg font-bold ${
                      analysis.marketSentiment.netFlow >= 0 ? 'text-green-600' : 'text-red-600'
                    }`}>
                      {(analysis.marketSentiment.netFlow * 100).toFixed(2)}%
                    </div>
                  </div>
                </div>
              </div>

              {/* Volume Profile */}
              <div className="bg-white rounded-lg shadow-md p-4">
                <h3 className="text-lg font-semibold mb-4">Volume Profile</h3>
                <VolumeProfileChart data={analysis.volumeProfile.highLiquidityZones.map(zone => ({ price: zone.priceLevel, volume: zone.volume, type: zone.type }))} />
              </div>

              {/* Support/Resistance Levels */}
              <div className="bg-white rounded-lg shadow-md p-4">
                <h3 className="text-lg font-semibold mb-4">Key Levels</h3>
                <div className="space-y-2">
                  {analysis.resistanceLevels.map((level, i) => (
                    <div key={`r-${i}`} className="flex justify-between items-center">
                      <div className="text-red-600">R{i + 1}</div>
                      <div className="font-mono">${level.price.toFixed(2)}</div>
                      <div className="text-sm text-gray-500">{(level.strength * 100).toFixed(0)}%</div>
                    </div>
                  ))}
                  {analysis.supportLevels.map((level, i) => (
                    <div key={`s-${i}`} className="flex justify-between items-center">
                      <div className="text-green-600">S{i + 1}</div>
                      <div className="font-mono">${level.price.toFixed(2)}</div>
                      <div className="text-sm text-gray-500">{(level.strength * 100).toFixed(0)}%</div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Trade Adjustments */}
              <div className="bg-white rounded-lg shadow-md p-4">
                <h3 className="text-lg font-semibold mb-4">Suggested Adjustments</h3>
                <div className="space-y-4">
                  {analysis.suggestedAdjustments.map((adjustment, i) => (
                    <div key={i} className="border-b pb-4 last:border-b-0 last:pb-0">
                      <div className="flex justify-between items-center mb-2">
                        <div className="font-semibold">{adjustment.type}</div>
                        <div className={`text-sm font-medium ${
                          adjustment.action === 'INCREASE' ? 'text-green-600' :
                          adjustment.action === 'DECREASE' ? 'text-red-600' :
                          'text-blue-600'
                        }`}>
                          {adjustment.action}
                        </div>
                      </div>
                      <div className="text-sm text-gray-600 mb-2">{adjustment.reason}</div>
                      <div className="grid grid-cols-2 gap-4 text-sm">
                        <div>
                          <div className="text-gray-500">Current</div>
                          <div className="font-mono">${adjustment.currentValue.toFixed(2)}</div>
                        </div>
                        <div>
                          <div className="text-gray-500">Suggested</div>
                          <div className="font-mono">${adjustment.suggestedValue.toFixed(2)}</div>
                        </div>
                      </div>
                      <div className="mt-2">
                        <div className="text-xs text-gray-500">Confidence</div>
                        <div className="w-full bg-gray-200 rounded-full h-2">
                          <div 
                            className="bg-blue-600 rounded-full h-2" 
                            style={{ width: `${adjustment.confidence * 100}%` }}
                          />
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="bg-gray-50 rounded-lg p-8 text-center">
              <div className="text-gray-500">
                Select a position to view detailed analysis
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ActiveTrades;

