import React, { useState, useEffect, useCallback } from 'react';
import { RetroButton } from '../common/CommonComponents';
import { getOpenOrders as getOrdersOpen, cancelOrder } from '../../apiClient';

interface PendingTrade {
  id: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  type: 'LIMIT' | 'STOP_LIMIT' | 'TRAILING_STOP';
  quantity: number;
  price: number;
  currentPrice: number;
  distance: number; // Distance from current price in %
  timestamp: number;
  botId: string;
  botName: string;
  status: 'WAITING' | 'PARTIAL_FILL';
  timeInForce: 'GTC' | 'IOC' | 'FOK';
}

const PendingTrades: React.FC = () => {
  const [pendingTrades, setPendingTrades] = useState<PendingTrade[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [filterBot, setFilterBot] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadPendingOrders = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      const response = await getOrdersOpen();
      
      if (response.data && Array.isArray(response.data)) {
        // Map API response to our PendingTrade interface
        const mappedTrades: PendingTrade[] = response.data.map(order => ({
          id: order.orderId,
          symbol: order.symbol,
          side: order.side,
          type: mapOrderType(order.type),
          quantity: parseFloat(order.origQty),
          price: parseFloat(order.price),
          currentPrice: parseFloat(order.currentPrice || order.price),
          distance: Math.abs((parseFloat(order.price) / parseFloat(order.currentPrice || order.price) - 1) * 100),
          timestamp: order.time,
          botId: order.botId || 'manual',
          botName: order.botName || 'MANUAL TRADE',
          status: order.status === 'PARTIALLY_FILLED' ? 'PARTIAL_FILL' : 'WAITING',
          timeInForce: order.timeInForce as 'GTC' | 'IOC' | 'FOK'
        }));
        
        setPendingTrades(mappedTrades);
      } else {
        // Show empty state instead of mock data
        setPendingTrades([]);
        setError('No pending orders found. API returned empty or invalid data.');
      }
    } catch (err) {
      console.error('Error fetching pending trades:', err);
      setError('Failed to load pending orders from API.');
      // Show empty state instead of mock data
      setPendingTrades([]);
    } finally {
      setIsLoading(false);
    }
  }, []);
  
  useEffect(() => {
    // Load orders from API
    loadPendingOrders();
  }, [loadPendingOrders]);
  
  // Helper function to map exchange order types to our internal types
  const mapOrderType = (exchangeType: string): 'LIMIT' | 'STOP_LIMIT' | 'TRAILING_STOP' => {
    switch (exchangeType.toUpperCase()) {
      case 'STOP_LOSS_LIMIT':
      case 'STOP_LOSS':
      case 'STOP':
        return 'STOP_LIMIT';
      case 'TRAILING_STOP':
      case 'TRAILING_STOP_MARKET':
        return 'TRAILING_STOP';
      case 'LIMIT':
      default:
        return 'LIMIT';
    }
  };
  
  // Generate mock data function will be kept but it's not being used anymore
  const generateMockPendingTrades = () => {
    // Keeping the function but it's not being used anymore
    console.warn('Mock pending trade generation disabled - using real data only');
    return [];
  };
  
  // Handle cancel order
  const handleCancelOrder = async (orderId: string, symbol: string) => {
    try {
      await cancelOrder(symbol, orderId);
      
      // Remove from local state
      setPendingTrades(prev => prev.filter(trade => trade.id !== orderId));
      
      // Optionally, reload to ensure we're in sync with the server
      loadPendingOrders();
    } catch (err) {
      console.error('Error cancelling order:', err);
      alert('Failed to cancel order. Please try again.');
    }
  };
  
  // Handle edit order
  const handleEditOrder = (orderId: string) => {
    alert(`Would open edit UI for order ${orderId} in a real app`);
    // In a real app: open modal with edit form
  };
  
  // Filter trades by bot
  const filteredTrades = filterBot 
    ? pendingTrades.filter(trade => trade.botId === filterBot) 
    : pendingTrades;
    
  // Sort trades by timestamp (newest first)
  const sortedTrades = [...filteredTrades].sort((a, b) => b.timestamp - a.timestamp);
  
  // Get unique bot IDs and names for filtering
  const bots = pendingTrades.reduce((acc, trade) => {
    if (!acc.find(bot => bot.id === trade.botId)) {
      acc.push({ id: trade.botId, name: trade.botName });
    }
    return acc;
  }, [] as { id: string, name: string }[]);
  
  // Format timestamp
  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleString();
  };

  return (
    <div className="p-4 retro-text">
      <h1 className="text-2xl font-bold mb-4 retro-header">PENDING TRADES</h1>
      
      {/* Filter controls */}
      <div className="retro-card p-4 mb-4">
        <div className="flex flex-wrap justify-between items-center">
          <div>
            <h3 className="text-sm mb-2">FILTER BY BOT:</h3>
            <div className="flex flex-wrap gap-2">
              <RetroButton
                className={`text-xs ${filterBot === null ? 'bg-retro-green text-retro-black' : ''}`}
                variant={filterBot === null ? 'primary' : 'secondary'}
                onClick={() => setFilterBot(null)}
              >
                ALL BOTS
              </RetroButton>
              {bots.map(bot => (
                <RetroButton
                  key={bot.id}
                  className={`text-xs ${filterBot === bot.id ? 'bg-retro-green text-retro-black' : ''}`}
                  variant={filterBot === bot.id ? 'primary' : 'secondary'}
                  onClick={() => setFilterBot(bot.id)}
                >
                  {bot.name}
                </RetroButton>
              ))}
            </div>
          </div>
          
          <div className="text-right">
            <h3 className="text-sm mb-2">SUMMARY:</h3>
            <p>PENDING ORDERS: {filteredTrades.length}</p>
            <p>
              LIMIT ORDERS: {filteredTrades.filter(t => t.type === 'LIMIT').length} | 
              STOP ORDERS: {filteredTrades.filter(t => t.type.includes('STOP')).length}
            </p>
          </div>
        </div>
      </div>
      
      {/* Trades Table */}
      <div className="retro-card border-2 border-retro-green overflow-x-auto">
        {isLoading ? (
          <div className="flex justify-center items-center p-10">
            <p className="text-retro-green animate-pulse">LOADING ORDER DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
          </div>
        ) : filteredTrades.length === 0 ? (
          <div className="p-6 text-center">
            <p>NO PENDING ORDERS FOUND</p>
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-retro-green">
                <th className="p-2 text-left">TIME</th>
                <th className="p-2 text-left">SYMBOL</th>
                <th className="p-2 text-left">TYPE</th>
                <th className="p-2 text-left">SIDE</th>
                <th className="p-2 text-right">QUANTITY</th>
                <th className="p-2 text-right">PRICE</th>
                <th className="p-2 text-right">CURRENT</th>
                <th className="p-2 text-right">DISTANCE</th>
                <th className="p-2 text-left">STATUS</th>
                <th className="p-2 text-left">BOT</th>
                <th className="p-2 text-right">ACTIONS</th>
              </tr>
            </thead>
            <tbody>
              {sortedTrades.map(trade => (
                <tr key={trade.id} className="border-b border-retro-green border-opacity-30 hover:bg-retro-green hover:bg-opacity-10">
                  <td className="p-2 text-left">{formatTime(trade.timestamp)}</td>
                  <td className="p-2 text-left font-bold">{trade.symbol}</td>
                  <td className="p-2 text-left">
                    <span className="px-1 py-0.5 rounded text-xs bg-retro-dark">
                      {trade.type}
                    </span>
                  </td>
                  <td className={`p-2 text-left ${trade.side === 'BUY' ? 'text-retro-green' : 'text-retro-red'}`}>
                    {trade.side}
                  </td>
                  <td className="p-2 text-right">
                    {trade.quantity.toFixed(trade.symbol.includes('BTC') ? 4 : 2)}
                  </td>
                  <td className="p-2 text-right">${trade.price.toFixed(2)}</td>
                  <td className="p-2 text-right">${trade.currentPrice.toFixed(2)}</td>
                  <td className="p-2 text-right">{trade.distance.toFixed(2)}%</td>
                  <td className="p-2 text-left">
                    <span className={`px-1 py-0.5 rounded text-xs ${
                      trade.status === 'PARTIAL_FILL' ? 'bg-retro-yellow text-retro-black' : 'bg-retro-blue text-retro-dark'
                    }`}>
                      {trade.status} {trade.timeInForce}
                    </span>
                  </td>
                  <td className="p-2 text-left">{trade.botName}</td>
                  <td className="p-2 text-right">
                    <div className="flex justify-end space-x-1">
                      <RetroButton
                        className="text-xs"
                        variant="secondary"
                        onClick={() => handleEditOrder(trade.id)}
                      >
                        EDIT
                      </RetroButton>
                      <RetroButton
                        className="text-xs"
                        variant="danger"
                        onClick={() => handleCancelOrder(trade.id, trade.symbol)}
                      >
                        CANCEL
                      </RetroButton>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      
      {/* Terminal Section */}
      <div className="retro-terminal mt-4 p-3 text-xs">
        <p className="mb-1">{/* Pending orders awaiting execution */}</p>
        <p className="mb-1">{/* Orders will be executed when price conditions are met */}</p>
        <p>{/* Use EDIT button to modify price or quantity of pending orders */}</p>
      </div>
    </div>
  );
};

export default PendingTrades;

