import React, { useState } from 'react';
import TradingViewWidget from '../charts/TradingViewWidget';
import { RetroButton, RetroInput } from '../common/CommonComponents';

// List of specific trading pairs from MEXC exchange
const TRADING_PAIRS = [
  { symbol: 'MEXC:BTCUSDT', name: 'BTC/USDT' },
  { symbol: 'MEXC:ETHUSDT', name: 'ETH/USDT' },
  { symbol: 'MEXC:DOGEUSDT', name: 'DOGE/USDT' },
  { symbol: 'MEXC:NXPCUSDT', name: 'NXPC/USDT' },
  { symbol: 'MEXC:MOVRUSDT', name: 'MOVR/USDT' },
  { symbol: 'MEXC:KSMUSDT', name: 'KSM/USDT' }
];

const INTERVALS = [
  { value: '1', label: '1m' },
  { value: '5', label: '5m' },
  { value: '15', label: '15m' },
  { value: '30', label: '30m' },
  { value: '60', label: '1h' },
  { value: 'D', label: '1d' },
  { value: 'W', label: '1w' }
];

const Charts: React.FC = () => {
  const [selectedPair, setSelectedPair] = useState(TRADING_PAIRS[0].symbol);
  const [interval, setInterval] = useState('D');
  const [customSymbol, setCustomSymbol] = useState('');

  const handlePairChange = (symbol: string) => {
    setSelectedPair(symbol);
  };
  
  const handleCustomSymbolSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (customSymbol.trim()) {
      setSelectedPair(customSymbol.trim());
    }
  };

  return (
    <div className="p-4 retro-text">
      <h1 className="text-2xl font-bold mb-4 retro-header">ADVANCED CHARTS</h1>
      
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
                  onClick={() => handlePairChange(pair.symbol)}
                >
                  {pair.name}
                </RetroButton>
              ))}
            </div>
          </div>
          
          <div>
            <h3 className="text-sm mb-2">INTERVAL:</h3>
            <div className="flex flex-wrap gap-2">
              {INTERVALS.map(int => (
                <RetroButton
                  key={int.value}
                  className={`text-xs ${interval === int.value ? 'bg-retro-green text-retro-black' : ''}`}
                  variant={interval === int.value ? 'primary' : 'secondary'}
                  onClick={() => setInterval(int.value)}
                >
                  {int.label}
                </RetroButton>
              ))}
            </div>
          </div>
        </div>
        
        {/* Custom Symbol Input */}
        <form onSubmit={handleCustomSymbolSubmit} className="flex items-end gap-2">
          <div className="flex-grow">
            <label className="text-sm block mb-1">CUSTOM SYMBOL:</label>
            <RetroInput
              value={customSymbol}
              onChange={(e) => setCustomSymbol(e.target.value)}
              placeholder="Example: MEXC:BTCUSDT"
              className="text-sm"
            />
          </div>
          <RetroButton type="submit" className="text-xs ml-2">
            LOAD
          </RetroButton>
        </form>
      </div>
      
      {/* TradingView Chart */}
      <div className="retro-card border-2 border-retro-green p-0 h-[600px] relative">
        <div className="absolute top-0 left-0 right-0 bg-retro-dark py-1 px-2 text-xs border-b border-retro-green">
          <div className="flex justify-between">
            <span>SYMBOL: {selectedPair}</span>
            <span>INTERVAL: {INTERVALS.find(i => i.value === interval)?.label || interval}</span>
          </div>
        </div>
        <div className="h-full pt-6">
          <TradingViewWidget symbol={selectedPair} interval={interval} theme="dark" />
        </div>
        <div className="absolute bottom-0 left-0 right-0 flex justify-between bg-retro-dark py-1 px-2 text-xs border-t border-retro-green">
          <span>POWERED BY TRADINGVIEW</span>
          <span>RETRO TRADING BOT v0.1.0</span>
        </div>
      </div>
      
      {/* Chart Info Section */}
      <div className="retro-terminal mt-4 p-3 text-xs">
        <p className="mb-1">{"// TradingView chart integrated"}</p>
        <p className="mb-1">{"// Trading pairs from MEXC exchange"}</p>
        <p>{"// Tracking BTC, ETH, DOGE, NXPC, MOVR, KSM markets"}</p>
      </div>
    </div>
  );
};

export default Charts;

