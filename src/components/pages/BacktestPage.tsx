import React, { useState, useEffect } from 'react';
import { RetroButton, RetroInput, RetroSelect, RetroCard } from '../common/CommonComponents';
import axios from 'axios';

interface Strategy {
  id: string;
  name: string;
  description: string;
}

interface BacktestParams {
  strategyName: string;
  symbol: string;
  interval: string;
  startTime: number;
  endTime: number;
  initialCapital: number;
}

interface BacktestResult {
  success: boolean;
  message?: string;
  strategy: string;
  symbol: string;
  interval: string;
  startTime: number;
  endTime: number;
  initialCapital: number;
  finalCapital: number;
  totalReturn: number;
  percentReturn: number;
  totalTrades: number;
  winningTrades: number;
  winRate: number;
  profitFactor: number;
  trades: Array<any>;
}

const BacktestPage: React.FC = () => {
  const [strategies, setStrategies] = useState<Strategy[]>([]);
  const [availableSymbols, setAvailableSymbols] = useState<string[]>([
    'BTCUSDT', 'ETHUSDT', 'DOGEUSDT', 'BNBUSDT', 'ADAUSDT', 'SOLUSDT', 'DOTUSDT'
  ]);
  const [availableIntervals, setAvailableIntervals] = useState<{ value: string, label: string }[]>([
    { value: '1m', label: '1 Minute' },
    { value: '5m', label: '5 Minutes' },
    { value: '15m', label: '15 Minutes' },
    { value: '30m', label: '30 Minutes' },
    { value: '1h', label: '1 Hour' },
    { value: '4h', label: '4 Hours' },
    { value: '1d', label: '1 Day' },
    { value: '1w', label: '1 Week' },
  ]);
  
  const [params, setParams] = useState<BacktestParams>({
    strategyName: '',
    symbol: 'BTCUSDT',
    interval: '1d',
    startTime: Date.now() - 90 * 24 * 60 * 60 * 1000, // 90 days ago
    endTime: Date.now(),
    initialCapital: 10000
  });
  
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [error, setError] = useState<string>('');

  // Fetch available strategies on component mount
  useEffect(() => {
    fetchStrategies();
  }, []);

  // Fetch available strategies from the backend
  const fetchStrategies = async () => {
    try {
      const response = await axios.get('/api/backtest/strategies');
      
      if (response.data.success) {
        const strategiesData = response.data.strategies;
        const formattedStrategies = Object.keys(strategiesData).map(id => ({
          id,
          name: strategiesData[id].name,
          description: strategiesData[id].description
        }));
        
        setStrategies(formattedStrategies);
        
        if (formattedStrategies.length > 0 && !params.strategyName) {
          setParams(prev => ({
            ...prev,
            strategyName: formattedStrategies[0].id
          }));
        }
      } else {
        setError('Failed to load strategies');
      }
    } catch (error) {
      console.error('Error fetching strategies:', error);
      setError('Error loading strategies');
    }
  };

  // Handle form input changes
  const handleInputChange = (field: keyof BacktestParams, value: any) => {
    setParams(prev => ({
      ...prev,
      [field]: value
    }));
  };

  // Format timestamp to readable date
  const formatTimestamp = (timestamp: number): string => {
    return new Date(timestamp).toLocaleString();
  };

  // Convert date string to timestamp
  const dateToTimestamp = (dateStr: string): number => {
    return new Date(dateStr).getTime();
  };

  // Run the backtest
  const runBacktest = async () => {
    setIsRunning(true);
    setError('');
    setResult(null);
    
    try {
      const response = await axios.post('/api/backtest/run', null, {
        params: params
      });
      
      if (response.data.success) {
        setResult(response.data);
      } else {
        setError(response.data.message || 'Backtest failed');
      }
    } catch (error) {
      console.error('Error running backtest:', error);
      setError('Error running backtest');
    } finally {
      setIsRunning(false);
    }
  };

  // Render the results table
  const renderResults = () => {
    if (!result) return null;
    
    return (
      <RetroCard title="Backtest Results" className="mt-6">
        <div className="grid grid-cols-2 gap-4 mb-6">
          <div>
            <h3 className="text-lg text-retro-green mb-2">Summary</h3>
            <table className="w-full text-retro-green">
              <tbody>
                <tr>
                  <td className="py-1">Strategy:</td>
                  <td className="py-1">{result.strategy}</td>
                </tr>
                <tr>
                  <td className="py-1">Symbol:</td>
                  <td className="py-1">{result.symbol}</td>
                </tr>
                <tr>
                  <td className="py-1">Interval:</td>
                  <td className="py-1">{result.interval}</td>
                </tr>
                <tr>
                  <td className="py-1">Period:</td>
                  <td className="py-1">{formatTimestamp(result.startTime)} to {formatTimestamp(result.endTime)}</td>
                </tr>
              </tbody>
            </table>
          </div>
          
          <div>
            <h3 className="text-lg text-retro-green mb-2">Performance</h3>
            <table className="w-full text-retro-green">
              <tbody>
                <tr>
                  <td className="py-1">Initial Capital:</td>
                  <td className="py-1">${result.initialCapital.toFixed(2)}</td>
                </tr>
                <tr>
                  <td className="py-1">Final Capital:</td>
                  <td className="py-1">${result.finalCapital.toFixed(2)}</td>
                </tr>
                <tr>
                  <td className="py-1">Total Return:</td>
                  <td className={`py-1 ${result.totalReturn >= 0 ? 'text-green-500' : 'text-red-500'}`}>
                    ${result.totalReturn.toFixed(2)} ({result.percentReturn.toFixed(2)}%)
                  </td>
                </tr>
                <tr>
                  <td className="py-1">Win Rate:</td>
                  <td className="py-1">{result.winRate.toFixed(2)}% ({result.winningTrades}/{result.totalTrades})</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        
        <h3 className="text-lg text-retro-green mb-2">Trade History</h3>
        <div className="overflow-x-auto">
          <table className="w-full text-sm text-retro-green">
            <thead>
              <tr className="border-b border-retro-green">
                <th className="py-2 text-left">Type</th>
                <th className="py-2 text-left">Date</th>
                <th className="py-2 text-right">Price</th>
                <th className="py-2 text-right">Size</th>
                <th className="py-2 text-right">PnL</th>
                <th className="py-2 text-right">Capital After</th>
              </tr>
            </thead>
            <tbody>
              {result.trades.map((trade, index) => (
                <tr key={index} className="border-b border-retro-green-dark">
                  <td className="py-2 text-left">{trade.type}</td>
                  <td className="py-2 text-left">{formatTimestamp(trade.timestamp)}</td>
                  <td className="py-2 text-right">${parseFloat(trade.price).toFixed(2)}</td>
                  <td className="py-2 text-right">{parseFloat(trade.positionSize).toFixed(4)}</td>
                  <td className={`py-2 text-right ${trade.pnl >= 0 ? 'text-green-500' : 'text-red-500'}`}>
                    {trade.pnl ? `$${parseFloat(trade.pnl).toFixed(2)}` : '-'}
                  </td>
                  <td className="py-2 text-right">${parseFloat(trade.capitalAfter).toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </RetroCard>
    );
  };

  return (
    <div className="container mx-auto p-4">
      <h1 className="text-2xl font-bold text-retro-green mb-6">Backtest Trading Strategies</h1>
      
      <RetroCard title="Backtest Parameters">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="space-y-4">
            <div>
              <label className="block text-retro-green mb-2">Strategy:</label>
              <RetroSelect
                options={strategies.map(s => ({ value: s.id, label: s.name }))}
                value={params.strategyName}
                onChange={(e) => handleInputChange('strategyName', e.target.value)}
                disabled={isRunning}
              />
            </div>
            
            <div>
              <label className="block text-retro-green mb-2">Symbol:</label>
              <RetroSelect
                options={availableSymbols.map(s => ({ value: s, label: s }))}
                value={params.symbol}
                onChange={(e) => handleInputChange('symbol', e.target.value)}
                disabled={isRunning}
              />
            </div>
            
            <div>
              <label className="block text-retro-green mb-2">Interval:</label>
              <RetroSelect
                options={availableIntervals}
                value={params.interval}
                onChange={(e) => handleInputChange('interval', e.target.value)}
                disabled={isRunning}
              />
            </div>
          </div>
          
          <div className="space-y-4">
            <div>
              <label className="block text-retro-green mb-2">Start Date:</label>
              <RetroInput
                type="date"
                value={new Date(params.startTime).toISOString().split('T')[0]}
                onChange={(e) => handleInputChange('startTime', dateToTimestamp(e.target.value))}
                disabled={isRunning}
              />
            </div>
            
            <div>
              <label className="block text-retro-green mb-2">End Date:</label>
              <RetroInput
                type="date"
                value={new Date(params.endTime).toISOString().split('T')[0]}
                onChange={(e) => handleInputChange('endTime', dateToTimestamp(e.target.value))}
                disabled={isRunning}
              />
            </div>
            
            <div>
              <label className="block text-retro-green mb-2">Initial Capital:</label>
              <RetroInput
                type="number"
                value={params.initialCapital}
                onChange={(e) => handleInputChange('initialCapital', parseFloat(e.target.value))}
                step={100}
                min={1}
                disabled={isRunning}
              />
            </div>
          </div>
        </div>
        
        <div className="mt-6">
          <RetroButton
            variant="primary"
            onClick={runBacktest}
            disabled={isRunning}
          >
            {isRunning ? 'Running Backtest...' : 'Run Backtest'}
          </RetroButton>
          
          {error && (
            <div className="mt-4 p-2 text-red-500">
              {error}
            </div>
          )}
        </div>
      </RetroCard>
      
      {renderResults()}
    </div>
  );
};

export default BacktestPage; 