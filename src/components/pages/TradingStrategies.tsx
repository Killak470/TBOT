import React, { useState, useEffect, useCallback } from 'react';
import { RetroButton, RetroSelect, RetroCard, RetroInput, RetroCheckbox } from '../common/CommonComponents';
import apiClient from '../../apiClient';

// Types
interface StrategyDescription {
  description: string;
  parameters: Record<string, string>;
}

interface TradeSignal {
  symbol: string;
  price: number;
  timestamp: number;
  strategyName: string;
  decision: 'BUY' | 'SELL' | 'HOLD';
  rationale: string;
  confidence: number;
}

interface StrategyResponse {
  success: boolean;
  timestamp: number;
  strategy: string;
  signals: TradeSignal[];
  count: number;
  error?: string;
}

const TradingStrategies: React.FC = () => {
  // State for available strategies
  const [strategies, setStrategies] = useState<string[]>([]);
  const [strategyDescriptions, setStrategyDescriptions] = useState<Record<string, StrategyDescription>>({});
  
  // State for strategy selection
  const [selectedStrategy, setSelectedStrategy] = useState<string>('');
  const [customParameters, setCustomParameters] = useState<Record<string, any>>({});
  
  // State for trading pairs
  const [allPairs, setAllPairs] = useState<string[]>([]);
  const [selectedPairs, setSelectedPairs] = useState<string[]>([]);
  
  // State for timeframe
  const [selectedInterval, setSelectedInterval] = useState<string>('1h');
  const [availableIntervals, setAvailableIntervals] = useState<string[]>(['5m', '15m', '30m', '1h', '4h', '1d']);
  
  // State for signals
  const [signals, setSignals] = useState<TradeSignal[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  
  // Fetch available strategies when component mounts
  useEffect(() => {
    const fetchStrategies = async () => {
      try {
        const response = await apiClient.get('/strategy/available');
        if (response.data && response.data.strategies) {
          setStrategies(response.data.strategies);
          setStrategyDescriptions(response.data.descriptions || {});
          if (response.data.strategies.length > 0) {
            setSelectedStrategy(response.data.strategies[0]);
          }
        }
      } catch (err) {
        console.error('Error fetching strategies:', err);
        setError('Failed to fetch available strategies');
      }
    };
    
    const fetchConfigOptions = async () => {
      try {
        const response = await apiClient.get('/scanner/config-options');
        if (response.data) {
          if (response.data.tradingPairs) {
            setAllPairs(response.data.tradingPairs);
            setSelectedPairs(response.data.tradingPairs.slice(0, 5)); // Default to first 5 pairs
          }
          if (response.data.intervals) {
            setAvailableIntervals(response.data.intervals);
          }
        }
      } catch (err) {
        console.error('Error fetching config options:', err);
      }
    };
    
    fetchStrategies();
    fetchConfigOptions();
  }, []);
  
  // Update custom parameters when strategy changes
  useEffect(() => {
    if (selectedStrategy && strategyDescriptions[selectedStrategy]) {
      // Reset custom parameters
      setCustomParameters({});
    }
  }, [selectedStrategy, strategyDescriptions]);
  
  // Handle applying strategy
  const applyStrategy = useCallback(async () => {
    if (!selectedStrategy) {
      setError('Please select a strategy');
      return;
    }
    
    setIsLoading(true);
    setError(null);
    
    try {
      // Format pairs for API
      const pairsParam = selectedPairs.join(',');
      
      // Make API request
      const response = await apiClient.get<StrategyResponse>('/strategy/apply', {
        params: {
          strategyType: selectedStrategy,
          pairs: pairsParam,
          interval: selectedInterval
        }
      });
      
      if (response.data.success) {
        setSignals(response.data.signals);
      } else {
        setError(response.data.error || 'Strategy application failed');
        setSignals([]);
      }
    } catch (err) {
      console.error('Error applying strategy:', err);
      setError('Failed to apply strategy');
      setSignals([]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedStrategy, selectedPairs, selectedInterval]);
  
  // Handle applying custom strategy with parameters
  const applyCustomStrategy = useCallback(async () => {
    if (!selectedStrategy) {
      setError('Please select a strategy');
      return;
    }
    
    setIsLoading(true);
    setError(null);
    
    try {
      // Prepare request body
      const requestBody = {
        strategyType: selectedStrategy,
        tradingPairs: selectedPairs,
        interval: selectedInterval,
        parameters: customParameters
      };
      
      // Make API request
      const response = await apiClient.post<StrategyResponse>('/strategy/apply-custom', requestBody);
      
      if (response.data.success) {
        setSignals(response.data.signals);
      } else {
        setError(response.data.error || 'Custom strategy application failed');
        setSignals([]);
      }
    } catch (err) {
      console.error('Error applying custom strategy:', err);
      setError('Failed to apply custom strategy');
      setSignals([]);
    } finally {
      setIsLoading(false);
    }
  }, [selectedStrategy, selectedPairs, selectedInterval, customParameters]);
  
  // Handle parameter change
  const handleParameterChange = (paramName: string, value: any) => {
    setCustomParameters(prev => ({
      ...prev,
      [paramName]: value
    }));
  };
  
  // Toggle pair selection
  const togglePairSelection = (pair: string) => {
    if (selectedPairs.includes(pair)) {
      setSelectedPairs(prev => prev.filter(p => p !== pair));
    } else {
      setSelectedPairs(prev => [...prev, pair]);
    }
  };
  
  // Render parameters for selected strategy
  const renderStrategyParameters = () => {
    if (!selectedStrategy || !strategyDescriptions[selectedStrategy]) {
      return null;
    }
    
    const description = strategyDescriptions[selectedStrategy];
    const parameters = description.parameters;
    
    if (!parameters || Object.keys(parameters).length === 0) {
      return <p className="text-retro-green">No configurable parameters for this strategy.</p>;
    }
    
    return (
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-4">
        {Object.entries(parameters).map(([paramName, description]) => (
          <div key={paramName}>
            <RetroInput
              label={`${paramName} (${description})`}
              type="text"
              value={customParameters[paramName] || ''}
              onChange={(e) => handleParameterChange(paramName, e.target.value)}
            />
          </div>
        ))}
      </div>
    );
  };
  
  // Render signal color based on decision
  const getSignalColor = (decision: string) => {
    switch (decision) {
      case 'BUY':
        return 'text-green-500';
      case 'SELL':
        return 'text-red-500';
      default:
        return 'text-yellow-500';
    }
  };
  
  // Format timestamp
  const formatTimestamp = (timestamp: number) => {
    return new Date(timestamp).toLocaleString();
  };
  
  return (
    <div className="retro-container p-4">
      <div className="retro-header mb-4">
        <h1 className="text-xl font-bold text-retro-green">TRADING STRATEGIES</h1>
        <p className="text-sm text-retro-green">Apply and backtest different trading strategies</p>
      </div>
      
      {/* Strategy Selection */}
      <RetroCard title="STRATEGY SELECTION" className="mb-6">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
          <div>
            <label className="block text-sm mb-1 text-retro-green">Strategy</label>
            <RetroSelect
              value={selectedStrategy}
              onChange={(e) => setSelectedStrategy(e.target.value)}
              options={strategies.map(strategy => ({
                value: strategy,
                label: strategy.replace(/_/g, ' ')
              }))}
            />
          </div>
          
          <div>
            <label className="block text-sm mb-1 text-retro-green">Timeframe</label>
            <RetroSelect
              value={selectedInterval}
              onChange={(e) => setSelectedInterval(e.target.value)}
              options={availableIntervals.map(interval => ({
                value: interval,
                label: interval
              }))}
            />
          </div>
        </div>
        
        {selectedStrategy && strategyDescriptions[selectedStrategy] && (
          <div className="mb-4">
            <h3 className="text-retro-green font-bold mb-2">Strategy Description</h3>
            <p className="text-retro-green mb-2">{strategyDescriptions[selectedStrategy].description}</p>
            
            <h3 className="text-retro-green font-bold mt-4 mb-2">Parameters</h3>
            {renderStrategyParameters()}
          </div>
        )}
        
        <h3 className="text-retro-green font-bold mt-4 mb-2">Trading Pairs</h3>
        <div className="grid grid-cols-2 md:grid-cols-5 gap-2 mb-4">
          {allPairs.map(pair => (
            <div key={pair} className="flex items-center">
              <RetroCheckbox
                id={`pair-${pair}`}
                label={pair}
                checked={selectedPairs.includes(pair)}
                onChange={() => togglePairSelection(pair)}
              />
            </div>
          ))}
        </div>
        
        <div className="flex space-x-4 mt-4">
          <RetroButton onClick={applyStrategy} disabled={isLoading}>
            {isLoading ? 'PROCESSING...' : 'APPLY STRATEGY'}
          </RetroButton>
          
          <RetroButton onClick={applyCustomStrategy} disabled={isLoading} variant="secondary">
            {isLoading ? 'PROCESSING...' : 'APPLY WITH CUSTOM PARAMETERS'}
          </RetroButton>
        </div>
        
        {error && (
          <div className="mt-4 text-red-500 font-mono">
            ERROR: {error}
          </div>
        )}
      </RetroCard>
      
      {/* Strategy Results */}
      {signals.length > 0 && (
        <RetroCard title="STRATEGY SIGNALS">
          <div className="overflow-x-auto">
            <table className="w-full border-collapse">
              <thead>
                <tr>
                  <th className="p-2 border border-retro-green bg-retro-black text-left">Symbol</th>
                  <th className="p-2 border border-retro-green bg-retro-black text-right">Price</th>
                  <th className="p-2 border border-retro-green bg-retro-black text-center">Signal</th>
                  <th className="p-2 border border-retro-green bg-retro-black text-center">Confidence</th>
                  <th className="p-2 border border-retro-green bg-retro-black text-left">Rationale</th>
                  <th className="p-2 border border-retro-green bg-retro-black text-center">Time</th>
                </tr>
              </thead>
              <tbody>
                {signals.map((signal, index) => (
                  <tr key={index} className="hover:bg-retro-black/20">
                    <td className="p-2 border border-retro-green font-mono">{signal.symbol}</td>
                    <td className="p-2 border border-retro-green text-right font-mono">{signal.price.toFixed(4)}</td>
                    <td className={`p-2 border border-retro-green text-center font-bold ${getSignalColor(signal.decision)}`}>
                      {signal.decision}
                    </td>
                    <td className="p-2 border border-retro-green text-center">
                      {(signal.confidence * 100).toFixed(0)}%
                    </td>
                    <td className="p-2 border border-retro-green">{signal.rationale}</td>
                    <td className="p-2 border border-retro-green text-center font-mono text-xs">
                      {formatTimestamp(signal.timestamp)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </RetroCard>
      )}
    </div>
  );
};

export default TradingStrategies; 