import React, { useState, useEffect } from 'react';
import { RetroButton, RetroInput } from '../common/CommonComponents';
import * as api from '../../apiClient';

// Define data mode and exchange types
type DataMode = 'spot' | 'futures';
type Exchange = 'MEXC' | 'BYBIT';

// List of specific trading pairs from MEXC
const TRADING_PAIRS = [
  { symbol: 'BTCUSDT', name: 'BTC/USDT' },
  { symbol: 'ETHUSDT', name: 'ETH/USDT' },
  { symbol: 'DOGEUSDT', name: 'DOGE/USDT' },
  { symbol: 'NXPCUSDT', name: 'NXPC/USDT' },
  { symbol: 'MOVRUSDT', name: 'MOVR/USDT' },
  { symbol: 'KSMUSDT', name: 'KSM/USDT' }
];

// Exchange options
const EXCHANGES = [
  { id: 'MEXC', name: 'MEXC' },
  { id: 'BYBIT', name: 'Bybit' }
];

// Timeframe to interval mapping
const TIMEFRAME_MAP: Record<string, string> = {
  '5m': '5',
  '15m': '15',
  '1h': '60',
  '4h': '240',
  '1d': 'D',
  '1w': 'W'
};

// Interface for kline data
interface KlineData {
  time: number;
  open: number;
  high: number;
  close: number;
  low: number;
  volume: number;
}

const SimpleCharts: React.FC = () => {
  const [selectedPair, setSelectedPair] = useState(TRADING_PAIRS[0].symbol);
  const [timeframe, setTimeframe] = useState('1d');
  const [dataMode, setDataMode] = useState<DataMode>('spot');
  const [selectedExchange, setSelectedExchange] = useState<Exchange>('MEXC');
  const [klineData, setKlineData] = useState<KlineData[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  
  // Generate mock data function (moved outside useEffect for reuse)
  const generateMockData = () => {
    // Generate appropriate number of data points based on timeframe
    const dataPoints = timeframe === '1w' ? 26 :    // ~6 months of weekly data
                      timeframe === '1d' ? 100 :     // ~100 days of daily data
                      timeframe === '4h' ? 180 :     // ~30 days of 4h data
                      timeframe === '1h' ? 168 :     // ~7 days of hourly data
                      timeframe === '15m' ? 192 :    // ~2 days of 15min data
                      timeframe === '5m' ? 288 : 100; // ~1 day of 5min data
                      
    let lastPrice = selectedPair.includes('BTC') ? 65000 : 
                    selectedPair.includes('ETH') ? 3500 : 
                    selectedPair.includes('DOGE') ? 0.12 : 
                    selectedPair.includes('NXPC') ? 0.8 : 
                    selectedPair.includes('MOVR') ? 45 : 
                    selectedPair.includes('KSM') ? 32 : 1.5;
                    
    const volatility = 0.03; // 3% price movement
    const trend = Math.random() > 0.5 ? 1 : -1; // Random trend
    
    const mockData: KlineData[] = [];
    const now = Date.now();
    
    // Accurate time intervals in milliseconds
    const timeInterval = timeframe === '1w' ? 7 * 24 * 60 * 60 * 1000 :   // 1 week
                        timeframe === '1d' ? 24 * 60 * 60 * 1000 :         // 1 day
                        timeframe === '4h' ? 4 * 60 * 60 * 1000 :          // 4 hours
                        timeframe === '1h' ? 60 * 60 * 1000 :              // 1 hour
                        timeframe === '15m' ? 15 * 60 * 1000 :             // 15 minutes
                        timeframe === '5m' ? 5 * 60 * 1000 : 5 * 60 * 1000; // 5 minutes
    
    for (let i = 0; i < dataPoints; i++) {
      const change = lastPrice * volatility * (Math.random() - 0.5);
      const trendChange = lastPrice * 0.01 * trend; // 1% trend factor
      
      const open = lastPrice;
      lastPrice = Math.max(0.01, lastPrice + change + trendChange);
      const close = lastPrice;
      
      // Create high and low with some random variation
      const rangePercent = Math.random() * 0.02 + 0.01; // 1-3% range
      const highAddition = Math.max(open, close) * rangePercent;
      const lowSubtraction = Math.min(open, close) * rangePercent;
      
      const high = Math.max(open, close) + highAddition;
      const low = Math.max(0.001, Math.min(open, close) - lowSubtraction);
      
      mockData.push({
        time: now - (dataPoints - i) * timeInterval,
        open,
        high,
        close,
        low,
        volume: lastPrice * (Math.random() * 100 + 10)
      });
    }
    
    return mockData;
  };
  
  // Fetch kline data from API
  useEffect(() => {
    const fetchKlineData = async () => {
      setIsLoading(true);
      setError(null);
      
      try {
        const now = Date.now();
        const oneDayMs = 24 * 60 * 60 * 1000;
        
        // Optimized time multipliers for better chart data (aiming for 100-200 data points)
        const timeframeMultiplier = timeframe === '1w' ? 150 :  // ~6 months → ~26 points  
                                   timeframe === '1d' ? 100 :   // ~100 days → ~100 points
                                   timeframe === '4h' ? 30 :    // ~30 days → ~180 points
                                   timeframe === '1h' ? 7 :     // ~7 days → ~168 points
                                   timeframe === '15m' ? 2 :    // ~2 days → ~192 points
                                   timeframe === '5m' ? 1 : 1;  // ~1 day → ~288 points
        
        const lookbackPeriod = oneDayMs * timeframeMultiplier;
        
        let response;
        
        try {
          if (dataMode === 'spot') {
            // Fetch spot klines based on selected exchange
            if (selectedExchange === 'BYBIT') {
              // Use Bybit API for spot data
              response = await api.getBybitKlines(
                selectedPair,
                TIMEFRAME_MAP[timeframe],
                1000  // limit
              );
            } else {
              // Use MEXC API for spot data
              response = await api.getSpotKlines(
                selectedPair, 
                TIMEFRAME_MAP[timeframe], 
                Math.floor((now - lookbackPeriod)), 
                Math.floor(now), 
                1000  // Increased limit to get more data points
              );
            }
            
            if (response && response.data) {
              let formattedData;
              
              if (selectedExchange === 'BYBIT') {
                // Handle Bybit response format (works for both spot and futures)
                const bybitData = response.data.result?.list || response.data;
                if (Array.isArray(bybitData)) {
                  // Bybit format: [startTime, openPrice, highPrice, lowPrice, closePrice, volume, turnover]
                  formattedData = bybitData.map((kline: any) => ({
                    time: parseInt(kline[0]), // Bybit returns timestamp in milliseconds
                    open: parseFloat(kline[1]),
                    high: parseFloat(kline[2]),
                    low: parseFloat(kline[3]),
                    close: parseFloat(kline[4]),
                    volume: parseFloat(kline[5])
                  }));
                }
              } else if (Array.isArray(response.data)) {
                // MEXC spot format: [[timestamp, "open", "high", "low", "close", "volume"]]
                formattedData = response.data.map((kline: any) => ({
                  time: kline[0],
                  open: parseFloat(kline[1]),
                  high: parseFloat(kline[2]),
                  low: parseFloat(kline[3]),
                  close: parseFloat(kline[4]),
                  volume: parseFloat(kline[5])
                }));
              }
              
              if (formattedData && formattedData.length > 0) {
                setKlineData(formattedData);
                setIsLoading(false);
                return;
              }
            }
                  } else {
          // Fetch futures klines based on selected exchange
          if (selectedExchange === 'BYBIT') {
            // Use Bybit futures API specifically for futures data
            console.log(`Fetching Bybit futures data for ${selectedPair} with interval ${TIMEFRAME_MAP[timeframe]}`);
            response = await api.getBybitFuturesKlines(
              selectedPair,
              TIMEFRAME_MAP[timeframe],
              1000  // limit
            );
          } else {
            // Fetch MEXC futures klines with authenticated request
            try {
              console.log(`Fetching MEXC futures data for ${selectedPair} with interval ${TIMEFRAME_MAP[timeframe]}`);
              
              // Simple retry logic directly in the component
              let retries = 0;
              const maxRetries = 2;
              let futuresResponse = null;
              
              while (retries <= maxRetries) {
                try {
                  console.log(`MEXC Futures API attempt ${retries + 1}/${maxRetries + 1}`);
                  futuresResponse = await api.getFuturesKlines(
                    selectedPair, 
                    TIMEFRAME_MAP[timeframe], 
                    Math.floor((now - lookbackPeriod) / 1000), // Convert to seconds for futures API
                    Math.floor(now / 1000)
                  );
                  break; // Success - exit the retry loop
                } catch (retryError) {
                  retries++;
                  if (retries <= maxRetries) {
                    const delay = Math.pow(2, retries) * 1000; // Exponential backoff
                    console.log(`MEXC Futures API retry in ${delay}ms...`);
                    await new Promise(resolve => setTimeout(resolve, delay));
                  } else {
                    throw retryError; // Rethrow after all retries fail
                  }
                }
              }
              
              response = futuresResponse;
              
              if (response && response.data && response.data.data && Array.isArray(response.data.data)) {
                // Format futures data
                const formattedData = response.data.data.map((kline: any) => ({
                  time: kline.time * 1000, // Convert seconds to milliseconds
                  open: parseFloat(kline.open),
                  high: parseFloat(kline.high),
                  low: parseFloat(kline.low),
                  close: parseFloat(kline.close),
                  volume: parseFloat(kline.volume)
                }));
                
                setKlineData(formattedData);
                setIsLoading(false);
                return;
              } else {
                throw new Error("Invalid futures data format received");
              }
                          } catch (futuresError: any) {
                console.error(`MEXC Futures API error for ${selectedPair}:`, futuresError);
                // For MEXC futures specifically, we'll immediately fall back to mock data
                // and provide a more specific error message
                const errorMsg = futuresError.message || 'MEXC Futures data unavailable';
                setError(`${errorMsg} - Using simulated data`);
                
                // Generate fallback mock data
                const mockData = generateMockData();
                setKlineData(mockData);
                setIsLoading(false);
                return;
              }
            }
          }
          
          // Handle Bybit futures response
          if (selectedExchange === 'BYBIT' && response && response.data) {
            const bybitData = response.data.result?.list || response.data;
            if (Array.isArray(bybitData)) {
              // Bybit futures format: [startTime, openPrice, highPrice, lowPrice, closePrice, volume, turnover]
              const formattedData = bybitData.map((kline: any) => ({
                time: parseInt(kline[0]), // Bybit returns timestamp in milliseconds
                open: parseFloat(kline[1]),
                high: parseFloat(kline[2]),
                low: parseFloat(kline[3]),
                close: parseFloat(kline[4]),
                volume: parseFloat(kline[5])
              }));
              
              if (formattedData && formattedData.length > 0) {
                setKlineData(formattedData);
                setIsLoading(false);
                return;
              }
            }
          }
          
          // If we get here, response format was unexpected
          throw new Error("Invalid API response format");
          
        } catch (apiError: any) {
          // API-specific error handling for authentication issues
          console.error(`API error for ${dataMode} data:`, apiError);
          const errorMsg = apiError.response?.data?.msg || apiError.message || 'API authentication error';
          
          if (apiError.response?.status === 400 && errorMsg.includes('signature')) {
            throw new Error(`Authentication error: ${errorMsg}`);
          } else {
            throw new Error(errorMsg);
          }
        }
      } catch (err: any) {
        console.error('Error fetching kline data:', err);
        setError(`Error: ${err.message || 'Failed to fetch market data'}`);
        
        // Generate fallback mock data
        const mockData = generateMockData();
        setKlineData(mockData);
      } finally {
        setIsLoading(false);
      }
    };
    
    fetchKlineData();
  }, [selectedPair, timeframe, dataMode, selectedExchange]);
  
  // Extract price data for charting
  const priceData = klineData.map(k => k.close);
  
  // Find min and max for scaling
  const min = klineData.length > 0 ? Math.min(...klineData.map(k => k.low)) * 0.995 : 0;
  const max = klineData.length > 0 ? Math.max(...klineData.map(k => k.high)) * 1.005 : 0;
  const range = max - min;
  
  // Function to normalize values for display within the chart height
  const normalizeValue = (value: number, height: number) => {
    if (range === 0) return height / 2; // Prevent division by zero
    return height - ((value - min) / range) * height;
  };
  
  // Generate chart elements
  const chartHeight = 300;
  const chartWidth = 600;
  const padding = 40;
  const effectiveWidth = chartWidth - (padding * 2);
  const effectiveHeight = chartHeight - (padding * 2);
  
  // Function to calculate candlestick positions
  const getCandlestickData = (kline: KlineData, index: number) => {
    const x = padding + (index / (klineData.length - 1)) * effectiveWidth;
    const candleWidth = Math.min(10, effectiveWidth / klineData.length * 0.8);
    
    const openY = normalizeValue(kline.open, effectiveHeight) + padding;
    const closeY = normalizeValue(kline.close, effectiveHeight) + padding;
    const highY = normalizeValue(kline.high, effectiveHeight) + padding;
    const lowY = normalizeValue(kline.low, effectiveHeight) + padding;
    
    const isUp = kline.close >= kline.open;
    
    return {
      x,
      openY,
      closeY,
      highY,
      lowY,
      candleWidth,
      candleHeight: Math.abs(closeY - openY) || 1, // Ensure min height of 1px
      isUp
    };
  };
  
  return (
    <div className="p-4 retro-text">
      <h1 className="text-2xl font-bold mb-4 retro-header">SIMPLE CHARTS</h1>
      
      {/* Mode Tabs */}
      {/* Exchange and Data mode selectors */}
      <div className="flex flex-wrap gap-4 mb-4">
        {/* Exchange selector */}
        <div>
          <h3 className="text-sm mb-2">EXCHANGE:</h3>
          <div className="flex">
            {EXCHANGES.map(exchange => (
              <RetroButton
                key={exchange.id}
                className={`text-sm ${exchange.id === 'MEXC' ? 'rounded-r-none' : 'rounded-l-none'} ${selectedExchange === exchange.id ? 'bg-retro-green text-retro-black' : ''}`}
                variant={selectedExchange === exchange.id ? 'primary' : 'secondary'}
                onClick={() => setSelectedExchange(exchange.id as Exchange)}
              >
                {exchange.name}
              </RetroButton>
            ))}
          </div>
        </div>
        
        {/* Data mode selector */}
        <div>
          <h3 className="text-sm mb-2">MARKET TYPE:</h3>
          <div className="flex">
            <RetroButton
              className={`text-sm rounded-r-none ${dataMode === 'spot' ? 'bg-retro-green text-retro-black' : ''}`}
              variant={dataMode === 'spot' ? 'primary' : 'secondary'}
              onClick={() => setDataMode('spot')}
            >
              SPOT
            </RetroButton>
            <RetroButton
              className={`text-sm rounded-l-none ${dataMode === 'futures' ? 'bg-retro-green text-retro-black' : ''}`}
              variant={dataMode === 'futures' ? 'primary' : 'secondary'}
              onClick={() => setDataMode('futures')}
            >
              FUTURES
            </RetroButton>
          </div>
        </div>
      </div>
      
      {/* Chart Controls */}
      <div className="retro-card p-4 mb-4">
        <div className="flex flex-wrap gap-4 mb-4">
          <div>
            <h3 className="text-sm mb-2">SELECT TRADING PAIR:</h3>
            <div className="flex flex-wrap gap-2">
              {TRADING_PAIRS.map(pair => (
                <RetroButton
                  key={pair.symbol}
                  className={`text-xs ${selectedPair === pair.symbol ? 'bg-retro-green text-retro-black' : ''}`}
                  variant={selectedPair === pair.symbol ? 'primary' : 'secondary'}
                  onClick={() => setSelectedPair(pair.symbol)}
                >
                  {pair.name}
                </RetroButton>
              ))}
            </div>
          </div>
          
          <div>
            <h3 className="text-sm mb-2">TIMEFRAME:</h3>
            <div className="flex flex-wrap gap-2">
              {Object.keys(TIMEFRAME_MAP).map(time => (
                <RetroButton
                  key={time}
                  className={`text-xs ${timeframe === time ? 'bg-retro-green text-retro-black' : ''}`}
                  variant={timeframe === time ? 'primary' : 'secondary'}
                  onClick={() => setTimeframe(time)}
                >
                  {time.toUpperCase()}
                </RetroButton>
              ))}
            </div>
          </div>
        </div>
      </div>
      
      {/* Chart Display */}
      <div className="retro-card border-2 border-retro-green p-4 relative">
        <div className="flex justify-between mb-2">
          <span>SYMBOL: {selectedPair} ({selectedExchange} {dataMode.toUpperCase()})</span>
          <span>TIMEFRAME: {timeframe.toUpperCase()}</span>
        </div>
        
        {error && (
          <div className="text-retro-red bg-retro-red bg-opacity-10 p-2 mb-2 border border-retro-red">
            {error} - Showing simulated data
          </div>
        )}
        
        {isLoading ? (
          <div className="flex justify-center items-center" style={{ height: `${chartHeight}px` }}>
            <p className="text-retro-green animate-pulse">LOADING CHART DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
          </div>
        ) : (
          <div className="relative overflow-hidden" style={{ height: `${chartHeight}px` }}>
            {/* Chart grid lines */}
            <svg width="100%" height={chartHeight} className="absolute top-0 left-0">
              {/* Horizontal grid lines */}
              {[0, 0.25, 0.5, 0.75, 1].map((ratio, i) => {
                const y = padding + (effectiveHeight * ratio);
                const price = max - (range * ratio);
                return (
                  <g key={i}>
                    <line 
                      x1={padding} 
                      y1={y} 
                      x2={chartWidth - padding} 
                      y2={y} 
                      stroke="#22ff2230" 
                      strokeWidth="1" 
                    />
                    <text 
                      x={padding - 5} 
                      y={y} 
                      fontSize="10" 
                      textAnchor="end" 
                      dominantBaseline="middle" 
                      fill="#22ff22"
                    >
                      {price.toFixed(price < 1 ? 4 : 2)}
                    </text>
                  </g>
                );
              })}
              
              {/* Vertical grid lines / time labels */}
              {klineData.length > 0 && [0, 0.25, 0.5, 0.75, 1].map((ratio, i) => {
                const x = padding + (effectiveWidth * ratio);
                const dataIndex = Math.min(
                  Math.floor(ratio * (klineData.length - 1)),
                  klineData.length - 1
                );
                const time = new Date(klineData[dataIndex].time);
                const timeLabel = timeframe.includes('d') || timeframe.includes('w') 
                  ? time.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
                  : time.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
                
                return (
                  <g key={`vline-${i}`}>
                    <line 
                      x1={x} 
                      y1={padding} 
                      x2={x} 
                      y2={chartHeight - padding} 
                      stroke="#22ff2220" 
                      strokeWidth="1" 
                    />
                    <text 
                      x={x} 
                      y={chartHeight - padding + 15} 
                      fontSize="9" 
                      textAnchor="middle" 
                      fill="#22ff22"
                    >
                      {timeLabel}
                    </text>
                  </g>
                );
              })}
            </svg>
            
            {/* Candlestick chart */}
            <svg width="100%" height={chartHeight} className="relative z-10">
              {/* Draw candlesticks */}
              {klineData.map((kline, index) => {
                const { x, openY, closeY, highY, lowY, candleWidth, candleHeight, isUp } = getCandlestickData(kline, index);
                
                return (
                  <g key={`candle-${index}`}>
                    {/* High to low line (wick) */}
                    <line 
                      x1={x} 
                      y1={highY} 
                      x2={x} 
                      y2={lowY} 
                      stroke={isUp ? "#22ff22" : "#ff2222"} 
                      strokeWidth="1" 
                    />
                    
                    {/* Candle body */}
                    <rect 
                      x={x - candleWidth / 2} 
                      y={isUp ? closeY : openY} 
                      width={candleWidth} 
                      height={candleHeight} 
                      fill={isUp ? "#22ff22" : "#ff2222"} 
                    />
                  </g>
                );
              })}
            </svg>
            
            {/* Overlay chart line */}
            <svg width="100%" height={chartHeight} className="absolute top-0 left-0 z-20 pointer-events-none opacity-30">
              <polyline
                points={priceData.map((price, index) => {
                  const x = padding + (index / (priceData.length - 1)) * effectiveWidth;
                  const y = normalizeValue(price, effectiveHeight) + padding;
                  return `${x},${y}`;
                }).join(' ')}
                fill="none"
                stroke="#22ffff"
                strokeWidth="1"
              />
            </svg>
          </div>
        )}
        
        <div className="mt-4 retro-terminal p-2 text-xs">
          <p>{"// " + selectedExchange + " " + dataMode.toUpperCase() + " chart for " + selectedPair}</p>
          {klineData.length > 0 && (
            <>
              <p>{"// Price range: " + min.toFixed(min < 1 ? 4 : 2) + " - " + max.toFixed(max < 1 ? 4 : 2)}</p>
              <p>{"// Latest price: " + klineData[klineData.length - 1].close.toFixed(klineData[klineData.length - 1].close < 1 ? 4 : 2)}</p>
              <p>{"// 24h change: " + (klineData.length > 1 ? ((klineData[klineData.length - 1].close / klineData[0].close - 1) * 100).toFixed(2) + "%" : "N/A")}</p>
              {error && <p className="text-retro-red">{"// NOTE: Using simulated data due to API authentication issues"}</p>}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default SimpleCharts;

