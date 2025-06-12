import React, { useState, useEffect } from 'react';
import { RetroButton, RetroInput, RetroSelect } from '../common/CommonComponents';

interface Trade {
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

interface TradeStats {
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRate: number;
  averagePnl: number;
  averageWin: number;
  averageLoss: number;
  profitFactor: number;
  totalPnl: number;
  bestTrade: number;
  worstTrade: number;
  averageDuration: number;
}

interface TradeHistoryVisualizationProps {
  trades: Trade[];
  onExport?: () => void;
  generateReport?: () => void;
}

const TradeHistoryVisualization: React.FC<TradeHistoryVisualizationProps> = ({
  trades,
  onExport,
  generateReport
}) => {
  const [filteredTrades, setFilteredTrades] = useState<Trade[]>([]);
  const [stats, setStats] = useState<TradeStats | null>(null);
  const [filters, setFilters] = useState({
    symbol: '',
    strategy: '',
    side: '',
    status: '',
    dateFrom: '',
    dateTo: ''
  });
  const [sortBy, setSortBy] = useState<string>('exitTime');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('desc');
  
  // Get unique symbols, strategies
  const symbols = [...new Set<string>(trades.map(t => t.symbol))];
  const strategies = [...new Set<string>(trades.map(t => t.strategy))];
  
  // Apply filters and sorting to trades
  useEffect(() => {
    let result = [...trades];
    
    // Apply filters
    if (filters.symbol) {
      result = result.filter(t => t.symbol === filters.symbol);
    }
    
    if (filters.strategy) {
      result = result.filter(t => t.strategy === filters.strategy);
    }
    
    if (filters.side) {
      result = result.filter(t => t.side === filters.side);
    }
    
    if (filters.status) {
      result = result.filter(t => t.status === filters.status);
    }
    
    if (filters.dateFrom) {
      const fromDate = new Date(filters.dateFrom).getTime();
      result = result.filter(t => t.entryTime >= fromDate);
    }
    
    if (filters.dateTo) {
      const toDate = new Date(filters.dateTo).getTime();
      result = result.filter(t => t.entryTime <= toDate);
    }
    
    // Apply sorting
    result.sort((a, b) => {
      let aValue, bValue;
      
      switch (sortBy) {
        case 'symbol':
          return sortDirection === 'asc' 
            ? a.symbol.localeCompare(b.symbol)
            : b.symbol.localeCompare(a.symbol);
        case 'entryTime':
          aValue = a.entryTime;
          bValue = b.entryTime;
          break;
        case 'exitTime':
          aValue = a.exitTime;
          bValue = b.exitTime;
          break;
        case 'entryPrice':
          aValue = a.entryPrice;
          bValue = b.entryPrice;
          break;
        case 'exitPrice':
          aValue = a.exitPrice;
          bValue = b.exitPrice;
          break;
        case 'pnl':
          aValue = a.pnl;
          bValue = b.pnl;
          break;
        case 'pnlPercent':
          aValue = a.pnlPercent;
          bValue = b.pnlPercent;
          break;
        default:
          aValue = a.exitTime;
          bValue = b.exitTime;
      }
      
      if (sortDirection === 'asc') {
        return aValue - bValue;
      } else {
        return bValue - aValue;
      }
    });
    
    setFilteredTrades(result);
    
    // Calculate statistics for filtered trades
    if (result.length > 0) {
      const closedTrades = result.filter(t => t.status === 'CLOSED');
      const winningTrades = closedTrades.filter(t => t.pnl > 0);
      const losingTrades = closedTrades.filter(t => t.pnl < 0);
      
      const totalPnl = closedTrades.reduce((sum, t) => sum + t.pnl, 0);
      const totalWinPnl = winningTrades.reduce((sum, t) => sum + t.pnl, 0);
      const totalLossPnl = Math.abs(losingTrades.reduce((sum, t) => sum + t.pnl, 0));
      
      const durations = closedTrades.map(t => t.exitTime - t.entryTime);
      const avgDuration = durations.length > 0 
        ? durations.reduce((sum, d) => sum + d, 0) / durations.length
        : 0;
      
      setStats({
        totalTrades: closedTrades.length,
        winningTrades: winningTrades.length,
        losingTrades: losingTrades.length,
        winRate: closedTrades.length > 0 ? (winningTrades.length / closedTrades.length) * 100 : 0,
        averagePnl: closedTrades.length > 0 ? totalPnl / closedTrades.length : 0,
        averageWin: winningTrades.length > 0 ? totalWinPnl / winningTrades.length : 0,
        averageLoss: losingTrades.length > 0 ? totalLossPnl / losingTrades.length : 0,
        profitFactor: totalLossPnl > 0 ? totalWinPnl / totalLossPnl : 0,
        totalPnl,
        bestTrade: Math.max(...closedTrades.map(t => t.pnlPercent), 0),
        worstTrade: Math.min(...closedTrades.map(t => t.pnlPercent), 0),
        averageDuration: avgDuration
      });
    } else {
      setStats(null);
    }
  }, [trades, filters, sortBy, sortDirection]);
  
  // Handle filter changes
  const handleFilterChange = (field: string, value: string) => {
    setFilters(prev => ({
      ...prev,
      [field]: value
    }));
  };
  
  // Clear all filters
  const clearFilters = () => {
    setFilters({
      symbol: '',
      strategy: '',
      side: '',
      status: '',
      dateFrom: '',
      dateTo: ''
    });
  };
  
  // Format timestamp to readable date
  const formatDate = (timestamp: number) => {
    return new Date(timestamp).toLocaleString();
  };
  
  // Format duration from milliseconds to readable format
  const formatDuration = (ms: number) => {
    const seconds = Math.floor(ms / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);
    
    if (days > 0) return `${days}d ${hours % 24}h`;
    if (hours > 0) return `${hours}h ${minutes % 60}m`;
    if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
    return `${seconds}s`;
  };
  
  // Handle sort change
  const handleSort = (column: string) => {
    if (sortBy === column) {
      setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc');
    } else {
      setSortBy(column);
      setSortDirection('desc');
    }
  };
  
  return (
    <div className="retro-card p-4">
      <div className="flex flex-wrap justify-between items-center mb-4">
        <h2 className="text-lg font-semibold retro-subheader">TRADE HISTORY</h2>
        
        <div className="flex space-x-2">
          {onExport && (
            <RetroButton 
              onClick={onExport}
              className="text-xs"
              variant="secondary"
            >
              EXPORT CSV
            </RetroButton>
          )}
          
          {generateReport && (
            <RetroButton 
              onClick={generateReport}
              className="text-xs"
            >
              GENERATE REPORT
            </RetroButton>
          )}
        </div>
      </div>
      
      {/* Filters */}
      <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-6">
        <div>
          <label className="block mb-1 text-xs">SYMBOL</label>
          <RetroSelect
            options={[
              { value: '', label: 'ALL' },
              ...symbols.map(s => ({ value: s, label: s }))
            ]}
            value={filters.symbol}
            onChange={e => handleFilterChange('symbol', e.target.value)}
            className="text-xs w-full"
          />
        </div>
        
        <div>
          <label className="block mb-1 text-xs">STRATEGY</label>
          <RetroSelect
            options={[
              { value: '', label: 'ALL' },
              ...strategies.map(s => ({ value: s, label: s }))
            ]}
            value={filters.strategy}
            onChange={e => handleFilterChange('strategy', e.target.value)}
            className="text-xs w-full"
          />
        </div>
        
        <div>
          <label className="block mb-1 text-xs">SIDE</label>
          <RetroSelect
            options={[
              { value: '', label: 'ALL' },
              { value: 'LONG', label: 'LONG' },
              { value: 'SHORT', label: 'SHORT' }
            ]}
            value={filters.side}
            onChange={e => handleFilterChange('side', e.target.value)}
            className="text-xs w-full"
          />
        </div>
        
        <div>
          <label className="block mb-1 text-xs">STATUS</label>
          <RetroSelect
            options={[
              { value: '', label: 'ALL' },
              { value: 'OPEN', label: 'OPEN' },
              { value: 'CLOSED', label: 'CLOSED' },
              { value: 'CANCELLED', label: 'CANCELLED' }
            ]}
            value={filters.status}
            onChange={e => handleFilterChange('status', e.target.value)}
            className="text-xs w-full"
          />
        </div>
        
        <div>
          <label className="block mb-1 text-xs">FROM DATE</label>
          <RetroInput
            type="date"
            value={filters.dateFrom}
            onChange={e => handleFilterChange('dateFrom', e.target.value)}
            className="text-xs w-full"
          />
        </div>
        
        <div>
          <label className="block mb-1 text-xs">TO DATE</label>
          <RetroInput
            type="date"
            value={filters.dateTo}
            onChange={e => handleFilterChange('dateTo', e.target.value)}
            className="text-xs w-full"
          />
        </div>
      </div>
      
      <div className="flex justify-end mb-4">
        <RetroButton
          onClick={clearFilters}
          variant="secondary"
          className="text-xs"
        >
          CLEAR FILTERS
        </RetroButton>
      </div>
      
      {/* Statistics Panel */}
      {stats && (
        <div className="mb-6 p-3 border-2 border-retro-green bg-retro-dark">
          <h3 className="text-sm font-bold mb-2">TRADE STATISTICS</h3>
          
          <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-6 gap-4 text-xs">
            <div className="p-2 border border-retro-green border-opacity-30">
              <div className="text-retro-gray mb-1">TOTAL TRADES</div>
              <div className="font-bold">{stats.totalTrades}</div>
            </div>
            
            <div className="p-2 border border-retro-green border-opacity-30">
              <div className="text-retro-gray mb-1">WIN RATE</div>
              <div className="font-bold">{stats.winRate.toFixed(2)}%</div>
            </div>
            
            <div className="p-2 border border-retro-green border-opacity-30">
              <div className="text-retro-gray mb-1">TOTAL P&L</div>
              <div className={`font-bold ${stats.totalPnl >= 0 ? 'text-retro-green' : 'text-retro-red'}`}>
                {stats.totalPnl >= 0 ? '+' : ''}{stats.totalPnl.toFixed(2)}
              </div>
            </div>
            
            <div className="p-2 border border-retro-green border-opacity-30">
              <div className="text-retro-gray mb-1">AVG PROFIT</div>
              <div className={`font-bold ${stats.averagePnl >= 0 ? 'text-retro-green' : 'text-retro-red'}`}>
                {stats.averagePnl >= 0 ? '+' : ''}{stats.averagePnl.toFixed(2)}
              </div>
            </div>
            
            <div className="p-2 border border-retro-green border-opacity-30">
              <div className="text-retro-gray mb-1">PROFIT FACTOR</div>
              <div className="font-bold">{stats.profitFactor.toFixed(2)}</div>
            </div>
            
            <div className="p-2 border border-retro-green border-opacity-30">
              <div className="text-retro-gray mb-1">AVG DURATION</div>
              <div className="font-bold">{formatDuration(stats.averageDuration)}</div>
            </div>
          </div>
        </div>
      )}
      
      {/* Trades Table */}
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b-2 border-retro-green">
              <th className="p-2 text-left cursor-pointer hover:text-retro-accent" onClick={() => handleSort('symbol')}>
                SYMBOL {sortBy === 'symbol' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-left cursor-pointer hover:text-retro-accent" onClick={() => handleSort('entryTime')}>
                ENTRY TIME {sortBy === 'entryTime' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-right cursor-pointer hover:text-retro-accent" onClick={() => handleSort('entryPrice')}>
                ENTRY {sortBy === 'entryPrice' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-left cursor-pointer hover:text-retro-accent" onClick={() => handleSort('exitTime')}>
                EXIT TIME {sortBy === 'exitTime' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-right cursor-pointer hover:text-retro-accent" onClick={() => handleSort('exitPrice')}>
                EXIT {sortBy === 'exitPrice' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-center">SIDE</th>
              <th className="p-2 text-right">QTY</th>
              <th className="p-2 text-right cursor-pointer hover:text-retro-accent" onClick={() => handleSort('pnl')}>
                P&L {sortBy === 'pnl' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-right cursor-pointer hover:text-retro-accent" onClick={() => handleSort('pnlPercent')}>
                P&L% {sortBy === 'pnlPercent' && (sortDirection === 'asc' ? '↑' : '↓')}
              </th>
              <th className="p-2 text-left">STRATEGY</th>
              <th className="p-2 text-center">STATUS</th>
            </tr>
          </thead>
          <tbody>
            {filteredTrades.length === 0 ? (
              <tr>
                <td colSpan={11} className="p-4 text-center">NO TRADES FOUND</td>
              </tr>
            ) : (
              filteredTrades.map(trade => (
                <tr key={trade.id} className="border-b border-retro-green border-opacity-30 hover:bg-retro-green hover:bg-opacity-10">
                  <td className="p-2 text-left font-bold">{trade.symbol}</td>
                  <td className="p-2 text-left">{formatDate(trade.entryTime)}</td>
                  <td className="p-2 text-right">${trade.entryPrice.toFixed(2)}</td>
                  <td className="p-2 text-left">
                    {trade.status === 'CLOSED' ? formatDate(trade.exitTime) : '-'}
                  </td>
                  <td className="p-2 text-right">
                    {trade.status === 'CLOSED' ? `$${trade.exitPrice.toFixed(2)}` : '-'}
                  </td>
                  <td className={`p-2 text-center ${trade.side === 'LONG' ? 'text-retro-green' : 'text-retro-red'}`}>
                    {trade.side}
                  </td>
                  <td className="p-2 text-right">{trade.quantity.toFixed(4)}</td>
                  <td className={`p-2 text-right ${trade.pnl >= 0 ? 'text-retro-green' : 'text-retro-red'}`}>
                    {trade.status === 'CLOSED' ? (
                      <>
                        {trade.pnl >= 0 ? '+' : '-'}${Math.abs(trade.pnl).toFixed(2)}
                      </>
                    ) : '-'}
                  </td>
                  <td className={`p-2 text-right ${trade.pnlPercent >= 0 ? 'text-retro-green' : 'text-retro-red'}`}>
                    {trade.status === 'CLOSED' ? (
                      <>
                        {trade.pnlPercent >= 0 ? '+' : '-'}{Math.abs(trade.pnlPercent).toFixed(2)}%
                      </>
                    ) : '-'}
                  </td>
                  <td className="p-2 text-left">{trade.strategy}</td>
                  <td className={`p-2 text-center ${
                    trade.status === 'OPEN' ? 'text-retro-blue' : 
                    trade.status === 'CLOSED' ? 'text-retro-green' : 'text-retro-red'
                  }`}>
                    {trade.status}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default TradeHistoryVisualization; 