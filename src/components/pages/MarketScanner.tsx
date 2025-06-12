import React, { useState, useEffect, useCallback, ChangeEvent } from 'react';
import { RetroButton, RetroInput, RetroSelect, RetroCheckbox, RetroLoadingBar } from '../common/CommonComponents';
import apiClient from '../../apiClient';

// Types for scanner data
interface TechnicalIndicator {
  name: string;
  value: number;
  signal: string;
  percentDifference?: number;
  additionalData?: Record<string, any>;
}

interface ScanResult {
  symbol: string;
  interval: string;
  timestamp: number;
  price: number;
  signal: string;
  indicators: Record<string, TechnicalIndicator>;
  aiAnalysis?: string;
  marketType?: string; // "spot" or "futures"
  exchange?: string; // Added exchange to interface
  recommendedLeverage?: number; // For futures trading
  riskLevel?: string; // LOW, MEDIUM, HIGH
}

interface PotentialTrade {
  symbol: string;
  side: 'BUY' | 'SELL';
  entryPrice: number;
  stopLoss: number;
  takeProfit: number;
  rationale: string;
  exchange: string;
  marketType: 'SPOT' | 'LINEAR';
  interval: string;
  title: string;
  confidence: number;
}

interface ScanOptions {
  intervals: string[];
  signalStrengths: string[];
  tradingPairs: string[];
}

interface ScanConfig {
  tradingPairs: string[];
  interval: string;
  minimumSignalStrength: string;
  includeAiAnalysis: boolean;
  exchange: string;
  marketType: string;
}

const MarketScanner: React.FC = () => {
  // State for scan results and configuration
  const [scanResults, setScanResults] = useState<ScanResult[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [options, setOptions] = useState<ScanOptions | null>(null);
  const [isAiAnalysisRunning, setIsAiAnalysisRunning] = useState(false);
  const [scanProgress, setScanProgress] = useState<string>('');
  const [signalGenerationStatus, setSignalGenerationStatus] = useState<string>('');
  const [potentialTrades, setPotentialTrades] = useState<PotentialTrade[]>([]);
  
  // Scan configuration state
  const [selectedInterval, setSelectedInterval] = useState<string>('1h');
  const [selectedPairs, setSelectedPairs] = useState<string[]>([]);
  const [selectedSignalStrength, setSelectedSignalStrength] = useState<string>('NEUTRAL');
  const [includeAiAnalysis, setIncludeAiAnalysis] = useState<boolean>(true);
  const [expandedResult, setExpandedResult] = useState<string | null>(null);
  const [selectedExchange, setSelectedExchange] = useState<string>('MEXC');
  const [selectedMarketType, setSelectedMarketType] = useState<string>('spot');
  const [stagingSignal, setStagingSignal] = useState<string | null>(null); // To track which signal is being staged
  
  // Load available options when component mounts
  useEffect(() => {
    const loadOptions = async () => {
      try {
        const response = await apiClient.get('/scanner/config-options');
        if (response.data && response.data.intervals) {
          setOptions(response.data);
          // Set default selected pairs to first 5
          if (response.data.tradingPairs && response.data.tradingPairs.length > 0) {
            setSelectedPairs(response.data.tradingPairs.slice(0, 5));
          }
        }
      } catch (err) {
        console.error('Error loading scanner options:', err);
        setError('Failed to load scanner configuration options');
      }
    };
    
    loadOptions();
  }, []);
  
  // Function to run a quick scan
  const runQuickScan = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    setScanProgress('Initializing scan...');
    
    try {
      // Build pairs parameter
      const pairsParam = selectedPairs.join(',');
      
      // If AI analysis is enabled, show a warning about longer processing time
      if (includeAiAnalysis) {
        setIsAiAnalysisRunning(true);
        setScanProgress('Running scan with AI analysis - this may take up to 2 minutes...');
      }
      
      // Make API request
      const response = await apiClient.get('/scanner/quick-scan', {
        params: {
          interval: selectedInterval,
          pairs: pairsParam,
          exchange: selectedExchange,
          marketType: selectedMarketType
        }
      });
      
      if (response.data && response.data.results) {
        setScanResults(response.data.results);
        setScanProgress('Scan completed successfully.');
      } else {
        setError('Received invalid scan results format');
      }
    } catch (err: any) {
      console.error('Error running market scan:', err);
      
      // Special handling for timeout errors
      if (err.isTimeout) {
        setError('The scan is taking longer than expected. The results will appear when processing is complete.');
        // Don't set isLoading to false yet, as the operation is still running
        return;
      }
      
      // Handle specific response error format
      if (err.response && err.response.data && err.response.data.error) {
        setError(err.response.data.error);
      } else {
        setError('Failed to run market scan: ' + (err.message || 'Unknown error'));
      }
    } finally {
      setIsLoading(false);
      setIsAiAnalysisRunning(false);
    }
  }, [selectedInterval, selectedPairs, includeAiAnalysis, selectedExchange, selectedMarketType]);
  
  // Function to run custom scan with more options
  const runCustomScan = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    setScanResults([]);
    setPotentialTrades([]);
    setSignalGenerationStatus('');
    setScanProgress('Initializing custom scan...');
    
    try {
      // Build scan configuration
      const scanConfig: ScanConfig = {
        tradingPairs: selectedPairs,
        interval: selectedInterval,
        minimumSignalStrength: selectedSignalStrength,
        includeAiAnalysis: includeAiAnalysis,
        exchange: selectedExchange,
        marketType: selectedMarketType
      };
      
      // If AI analysis is enabled, show a warning about longer processing time
      if (includeAiAnalysis) {
        setIsAiAnalysisRunning(true);
        setScanProgress('Running scan with AI analysis - this may take up to 5 minutes...');
      }
      
      // Make API request
      const response = await apiClient.post('/scanner/custom-scan', scanConfig);
      
      if (response.data) {
        if (response.data.results) {
            setScanResults(response.data.results);
            setScanProgress(`Scan completed successfully. Found ${response.data.results.length} raw results.`);
        }

        if (response.data.tradeOptions && response.data.tradeOptions.length > 0) {
            setPotentialTrades(response.data.tradeOptions);
            setSignalGenerationStatus(`${response.data.tradeOptions.length} trade options generated.`);
        } else {
            setSignalGenerationStatus('Could not generate any trade options from the qualifying results.');
        }

      } else if (response.data && response.data.error) {
        setError(response.data.error);
      } else {
        setError('Received invalid scan results format');
      }
    } catch (err: any) {
      console.error('Error running custom market scan:', err);
      
      // Special handling for timeout errors
      if (err.isTimeout) {
        setError('The scan is taking longer than expected due to AI analysis. The results will appear when processing is complete. Please wait...');
        // Don't set isLoading to false yet, as the operation is still running
        return;
      }
      
      // Handle specific response error format
      if (err.response && err.response.data && err.response.data.error) {
        setError(err.response.data.error);
      } else {
        setError('Failed to run custom market scan: ' + (err.message || 'Unknown error'));
      }
    } finally {
      setIsLoading(false);
      setIsAiAnalysisRunning(false);
    }
  }, [selectedInterval, selectedPairs, selectedSignalStrength, includeAiAnalysis, selectedExchange, selectedMarketType]);
  
  // Helper function to get signal color
  const getSignalColor = (signal: string | undefined) => {
    if (!signal) return 'text-gray-400';
    
    switch (signal.toUpperCase()) {
      case 'STRONG_BUY':
        return 'text-green-400';
      case 'BUY':
        return 'text-green-300';
      case 'STRONG_SELL':
        return 'text-red-400';
      case 'SELL':
        return 'text-red-300';
      case 'BULLISH':
        return 'text-green-300';
      case 'BEARISH':
        return 'text-red-300';
      case 'OVERBOUGHT':
        return 'text-orange-400';
      case 'OVERSOLD':
        return 'text-blue-400';
      case 'NEUTRAL':
      default:
        return 'text-retro-yellow';
    }
  };

  // Helper function to determine market type from symbol if not provided
  const getMarketType = (result: ScanResult): string => {
    // If marketType is provided by backend, use it
    if (result.marketType) {
      return result.marketType;
    }
    
    // Fallback: determine from symbol format
    // Symbols with underscore (ETH_USDT, BTC_USDT) are futures
    // Symbols without underscore (ETHUSDT, BTCUSDT) are spot
    return result.symbol.includes('_') ? 'futures' : 'spot';
  };
  
  // Helper function to format percent values
  const formatPercent = (value: number | undefined): string => {
    if (value === undefined || value === null) return 'N/A';
    return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
  };
  
  // Toggle expanded result
  const toggleExpandResult = (symbol: string) => {
    setExpandedResult(expandedResult === symbol ? null : symbol);
  };
  
  // Display loading indicator or progress message
  const renderLoadingState = () => {
    if (!isLoading && !isAiAnalysisRunning) return null;
    
    return (
      <div className="mt-4 text-center">
        <RetroLoadingBar className="mb-2" />
        <div className="text-retro-green text-sm">
          {scanProgress || 'Processing...'}
        </div>
        {isAiAnalysisRunning && (
          <div className="text-retro-yellow text-xs mt-1">
            AI analysis can take up to 60 seconds. Please be patient...
          </div>
        )}
      </div>
    );
  };
  
  // Function to clear all scan results
  const clearResults = () => {
    setScanResults([]);
    setError(null);
    setScanProgress('');
    setSignalGenerationStatus('');
    setPotentialTrades([]);
  };
  
  // Function to handle signal creation
  const handleCreateSignal = async (trade: PotentialTrade) => {
    setStagingSignal(trade.title); // Use title or a unique ID
    setError(null);
    try {
      await apiClient.post('/signals/create', trade);
      // Optionally, remove the staged trade from the list or mark it as staged
      alert(`Signal "${trade.title}" has been successfully staged for review!`);
    } catch (err: any) {
      console.error('Error creating signal:', err);
      if (err.response && err.response.data && err.response.data.error) {
        setError(`Failed to stage signal: ${err.response.data.error}`);
      } else {
        setError(`Failed to stage signal: ${err.message || 'Unknown error'}`);
      }
    } finally {
      setStagingSignal(null);
    }
  };
  
  return (
    <div className="retro-container p-4">
      <div className="retro-header mb-4">
        <h1 className="text-xl font-bold text-retro-green">MARKET SCANNER</h1>
        <p className="text-sm text-retro-green">Analyze multiple market data points to identify trading opportunities</p>
      </div>
      
      {/* Scanner Configuration Panel */}
      <div className="retro-panel p-4 mb-6">
        <h2 className="text-lg font-bold mb-2">SCAN CONFIGURATION</h2>
        
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
          {/* Exchange Selection */}
          <div>
            <label className="block text-sm mb-1">Exchange</label>
            <RetroSelect
              value={selectedExchange}
              onChange={(e: ChangeEvent<HTMLSelectElement>) => setSelectedExchange(e.target.value)}
              options={[
                { value: 'MEXC', label: 'MEXC' },
                { value: 'BYBIT', label: 'Bybit' }
              ]}
            />
          </div>
          
          {/* Market Type Selection */}
          <div>
            <label className="block text-sm mb-1">Market Type</label>
            <RetroSelect
              value={selectedMarketType}
              onChange={(e: ChangeEvent<HTMLSelectElement>) => setSelectedMarketType(e.target.value)}
              options={[
                { value: 'spot', label: 'Spot' },
                { value: 'futures', label: 'Futures' }
              ]}
            />
          </div>
          
          {/* Interval Selection */}
          <div>
            <label className="block text-sm mb-1">Time Interval</label>
            <RetroSelect
              value={selectedInterval}
              onChange={(e: ChangeEvent<HTMLSelectElement>) => setSelectedInterval(e.target.value)}
              options={
                options?.intervals.map(interval => ({
                  value: interval,
                  label: interval
                })) || []
              }
            />
          </div>
          
          {/* Signal Strength */}
          <div>
            <label className="block text-sm mb-1">Minimum Signal Strength</label>
            <RetroSelect
              value={selectedSignalStrength}
              onChange={(e: ChangeEvent<HTMLSelectElement>) => setSelectedSignalStrength(e.target.value)}
              options={
                options?.signalStrengths.map(signal => ({
                  value: signal,
                  label: signal.replace('_', ' ')
                })) || []
              }
            />
          </div>
        </div>
        
        {/* Trading Pairs Selection */}
        <div className="mb-4">
          <label className="block text-sm mb-1">Trading Pairs</label>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-2 retro-inset p-2">
            {options?.tradingPairs.map(pair => (
              <div key={pair} className="flex items-center">
                <RetroCheckbox
                  id={`pair-${pair}`}
                  checked={selectedPairs.includes(pair)}
                  onChange={(e: ChangeEvent<HTMLInputElement>) => {
                    if (e.target.checked) {
                      setSelectedPairs([...selectedPairs, pair]);
                    } else {
                      setSelectedPairs(selectedPairs.filter(p => p !== pair));
                    }
                  }}
                  label={pair}
                />
              </div>
            ))}
          </div>
        </div>
        
        {/* AI Analysis Option */}
        <div className="mb-4">
          <RetroCheckbox
            id="ai-analysis"
            checked={includeAiAnalysis}
            onChange={(e: ChangeEvent<HTMLInputElement>) => setIncludeAiAnalysis(e.target.checked)}
            label="Include AI Analysis"
          />
          {includeAiAnalysis && (
            <div className="text-retro-yellow text-xs mt-1 ml-6">
              Note: Including AI analysis may increase processing time by 30-60 seconds.
            </div>
          )}
        </div>
        
        {/* Action Buttons */}
        <div className="flex gap-2">
          <RetroButton onClick={runQuickScan} disabled={isLoading || isAiAnalysisRunning || selectedPairs.length === 0}>
            {isLoading ? 'SCANNING...' : 'QUICK SCAN'}
          </RetroButton>
          <RetroButton onClick={runCustomScan} disabled={isLoading || isAiAnalysisRunning || selectedPairs.length === 0}>
            {isLoading ? 'SCANNING...' : 'CUSTOM SCAN'}
          </RetroButton>
          <RetroButton onClick={clearResults} disabled={isLoading} className="retro-button-danger">
            CLEAR RESULTS
          </RetroButton>
        </div>
        
        {/* Status Messages */}
        {scanProgress && (
          <div className="text-retro-yellow text-sm mt-2">
            {scanProgress}
          </div>
        )}
        
        {signalGenerationStatus && (
          <div className={`text-sm mt-2 ${signalGenerationStatus.startsWith('Success') ? 'text-retro-green' : 'text-retro-yellow'}`}>
            {signalGenerationStatus}
          </div>
        )}
        
        {/* Progress/Loading Indicator */}
        {renderLoadingState()}
        
        {/* Error Message */}
        {error && (
          <div className="mt-2 text-retro-red text-sm">{error}</div>
        )}
      </div>
      
      {/* Display Potential Trade Options */}
      {potentialTrades.length > 0 && (
        <div className="mt-6">
          <h2 className="text-2xl font-bold text-green-400 font-mono mb-4">TRADE OPTIONS ({potentialTrades.length})</h2>
          <div className="space-y-4">
            {potentialTrades.map((trade, index) => (
              <div key={index} className="bg-gray-800 p-4 rounded-lg border border-gray-700 shadow-lg">
                <div className="flex justify-between items-center">
                  <div>
                    <h3 className="text-xl font-bold text-white">{trade.title}</h3>
                    <p className="text-md text-cyan-400">{trade.symbol} - {trade.side}</p>
                  </div>
                  <RetroButton 
                    onClick={() => handleCreateSignal(trade)}
                    className="bg-blue-600 hover:bg-blue-700 text-white"
                    disabled={stagingSignal === trade.title}
                  >
                    {stagingSignal === trade.title ? 'STAGING...' : 'Stage Signal'}
                  </RetroButton>
                </div>
                <div className="mt-4 grid grid-cols-3 gap-4 text-center">
                  <div>
                    <p className="text-sm text-gray-400">Entry</p>
                    <p className="text-lg font-mono text-green-400">${trade.entryPrice.toFixed(4)}</p>
                  </div>
                  <div>
                    <p className="text-sm text-gray-400">Stop Loss</p>
                    <p className="text-lg font-mono text-red-400">${trade.stopLoss.toFixed(4)}</p>
                  </div>
                  <div>
                    <p className="text-sm text-gray-400">Take Profit</p>
                    <p className="text-lg font-mono text-green-400">${trade.takeProfit.toFixed(4)}</p>
                  </div>
                </div>
                <div className="mt-3">
                    <p className="text-sm text-gray-400">Confidence</p>
                    <p className="text-lg font-mono text-yellow-400">{(trade.confidence * 100).toFixed(1)}%</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
      
      {/* Display Raw Scan Results */}
      {scanResults.length > 0 && (
        <div className="retro-panel p-4">
          <div className="flex justify-between items-center mb-4">
            <h2 className="text-lg font-bold">SCAN RESULTS ({scanResults.length})</h2>
            <div className="text-sm text-retro-green">
              {scanResults.filter(r => ['STRONG_BUY', 'BUY', 'STRONG_SELL', 'SELL'].includes(r.signal)).length} qualifying for signals
            </div>
          </div>
          
          <div className="grid gap-3">
            {scanResults.map((result, index) => {
              const isQualifying = ['STRONG_BUY', 'BUY', 'STRONG_SELL', 'SELL'].includes(result.signal);
              
              return (
                <div 
                  key={`${result.symbol}-${index}`} 
                  className={`retro-inset p-3 cursor-pointer transition-all ${
                    isQualifying ? 'border-retro-green' : ''
                  } ${expandedResult === `${result.symbol}-${index}` ? 'bg-retro-dark-green bg-opacity-20' : ''}`}
                  onClick={() => setExpandedResult(expandedResult === `${result.symbol}-${index}` ? null : `${result.symbol}-${index}`)}
                >
                  <div className="flex justify-between items-center">
                    <div className="flex items-center space-x-4">
                      <div className="flex items-center space-x-2">
                      <span className="font-mono text-retro-green font-bold">{result.symbol}</span>
                        
                        {/* Market Type Badge */}
                        {(() => {
                          const marketType = getMarketType(result);
                          return (
                            <span className={`text-xs px-2 py-1 rounded ${
                              marketType === 'futures' 
                                ? 'bg-retro-yellow bg-opacity-20 text-retro-yellow border border-retro-yellow' 
                                : 'bg-retro-blue bg-opacity-20 text-retro-blue border border-retro-blue'
                            }`}>
                              {marketType.toUpperCase()}
                            </span>
                          );
                        })()}
                        
                        {/* Leverage Badge for Futures */}
                        {getMarketType(result) === 'futures' && result.recommendedLeverage && (
                          <span className="text-xs px-2 py-1 rounded bg-retro-red bg-opacity-20 text-retro-red border border-retro-red">
                            {result.recommendedLeverage}x
                          </span>
                        )}
                      </div>
                      <span className={`font-bold ${getSignalColor(result.signal)}`}>
                        {result.signal}
                        {isQualifying && <span className="ml-1 text-retro-green">●</span>}
                      </span>
                      <span className="text-retro-yellow">${result.price?.toFixed(6)}</span>
                    </div>
                    <div className="text-sm text-gray-400">
                      {new Date(result.timestamp).toLocaleTimeString()}
                    </div>
                  </div>
                  
                  {/* Expanded Details */}
                  {expandedResult === `${result.symbol}-${index}` && (
                    <div className="mt-3 pt-3 border-t border-retro-green">
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        {/* Technical Indicators */}
                        <div>
                          <h4 className="font-bold mb-2 text-retro-green">TECHNICAL INDICATORS</h4>
                          <div className="space-y-2">
                            {result.indicators && Object.entries(result.indicators).map(([key, indicator]) => (
                              <div key={key} className="flex justify-between text-sm">
                                <span>{indicator.name}:</span>
                                <div className="text-right">
                                  <div>{indicator.value?.toFixed(4) || 'N/A'}</div>
                                  <div className={`text-xs ${getSignalColor(indicator.signal)}`}>
                                    {indicator.signal}
                                  </div>
                                </div>
                              </div>
                            ))}
                          </div>
                        </div>
                        
                        {/* AI Analysis */}
                        {result.aiAnalysis && (
                          <div>
                            <h4 className="font-bold mb-2 text-retro-green">AI ANALYSIS</h4>
                            <div className="text-sm font-mono bg-retro-black bg-opacity-50 p-3 rounded leading-relaxed whitespace-pre-line">
                              {result.aiAnalysis}
                            </div>
                          </div>
                        )}
                      </div>
                      
                      {isQualifying && (
                        <div className="mt-3 p-2 bg-retro-green bg-opacity-10 rounded">
                          <div className="text-sm text-retro-green">
                            ✓ This result qualifies for bot signal generation
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};

export default MarketScanner; 