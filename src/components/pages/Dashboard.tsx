import React, { useState, useEffect } from 'react';
import { getSpotServerTime, getSpotExchangeInfo } from '../../apiClient';
import { RetroButton } from '../common/CommonComponents';

interface ExchangeInfo {
  symbols?: Array<{
    symbol: string;
    status: string;
    baseAsset: string;
    quoteAsset: string;
  }>;
  timezone?: string;
  serverTime?: number;
}

const Dashboard: React.FC = () => {
  const [serverTime, setServerTime] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [exchangeInfo, setExchangeInfo] = useState<ExchangeInfo>({});
  const [error, setError] = useState<string>('');
  const [statusMessage, setStatusMessage] = useState<string>('INITIALIZING...');
  
  // Simulated portfolio data - in a real app this would come from API
  const portfolioData = {
    totalValue: 25750.42,
    btcValue: 0.345,
    ethValue: 2.5,
    trades: [
      { id: 1, pair: 'BTC/USDT', type: 'BUY', amount: 0.1, price: 62435, timestamp: new Date().getTime() - 1000000 },
      { id: 2, pair: 'ETH/USDT', type: 'SELL', amount: 1.2, price: 3452, timestamp: new Date().getTime() - 2000000 }
    ]
  };

  useEffect(() => {
    // Fetch server time
    const fetchData = async () => {
      try {
        setIsLoading(true);
        
        // Get server time
        const timeResponse = await getSpotServerTime();
        const serverTimeObj = new Date(timeResponse.data.serverTime);
        setServerTime(serverTimeObj.toLocaleString());
        
        // Get exchange info with a slight delay to show loading state
        setTimeout(async () => {
          try {
            const exchangeResponse = await getSpotExchangeInfo();
            setExchangeInfo(exchangeResponse.data);
            setStatusMessage('ONLINE');
          } catch (exchangeError) {
            console.error('Exchange info error:', exchangeError);
            setError('Error fetching exchange info');
            setStatusMessage('ERROR');
          } finally {
            setIsLoading(false);
          }
        }, 500);
        
      } catch (err) {
        console.error('Server time error:', err);
        setError('Error connecting to server');
        setStatusMessage('OFFLINE');
        setIsLoading(false);
      }
    };

    fetchData();
    
    // Simulated blinking cursor effect for status
    const blinkInterval = setInterval(() => {
      document.querySelector('.status-cursor')?.classList.toggle('opacity-0');
    }, 500);

    return () => clearInterval(blinkInterval);
  }, []);

  return (
    <div className="p-4 retro-text">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold retro-header">DASHBOARD</h1>
        <div className="retro-terminal p-2 text-sm">
          <span>SERVER STATUS: {statusMessage}</span>
          <span className="status-cursor ml-1">█</span>
        </div>
      </div>
      
      {error && (
        <div className="retro-card bg-retro-red bg-opacity-20 p-2 mb-4">
          <p className="text-retro-red">{error}</p>
        </div>
      )}
      
      {isLoading ? (
        <div className="retro-card p-4 text-center animate-pulse">
          <p>LOADING SYSTEM DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
        </div>
      ) : (
        <>
          {/* Main Dashboard Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mb-6">
            <div className="retro-card p-4">
              <h2 className="text-lg font-semibold retro-subheader mb-2">PORTFOLIO VALUE</h2>
              <p className="text-2xl">${portfolioData.totalValue.toLocaleString()}</p>
              <div className="mt-2 text-sm">
                <p>BTC: {portfolioData.btcValue} (${(portfolioData.btcValue * 60000).toLocaleString()})</p>
                <p>ETH: {portfolioData.ethValue} (${(portfolioData.ethValue * 3500).toLocaleString()})</p>
              </div>
            </div>
            
            <div className="retro-card p-4">
              <h2 className="text-lg font-semibold retro-subheader mb-2">RECENT TRADES</h2>
              {portfolioData.trades.length === 0 ? (
                <p>No recent trades.</p>
              ) : (
                <div className="text-sm">
                  {portfolioData.trades.map(trade => (
                    <div key={trade.id} className="mb-2 pb-2 border-b border-retro-green border-opacity-30">
                      <div className="flex justify-between">
                        <span>{trade.pair}</span>
                        <span className={trade.type === 'BUY' ? 'text-retro-green' : 'text-retro-red'}>
                          {trade.type}
                        </span>
                      </div>
                      <div className="flex justify-between text-xs">
                        <span>Amount: {trade.amount}</span>
                        <span>Price: ${trade.price}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
            
            <div className="retro-card p-4">
              <h2 className="text-lg font-semibold retro-subheader mb-2">SYSTEM INFO</h2>
              <div className="text-sm font-mono">
                <p>SERVER TIME: {serverTime || 'N/A'}</p>
                <p>EXCHANGE: MEXC</p>
                <p>TRADING PAIRS: {exchangeInfo.symbols?.length || 'N/A'}</p>
                <p>BOT VERSION: 0.1.0</p>
              </div>
            </div>
          </div>
          
          {/* Bottom Section */}
          <div className="retro-terminal p-4 font-mono text-sm">
            <div className="mb-2">
              <span className="text-retro-green">root@tradingbot</span>
              <span className="text-retro-cyan">:</span>
              <span className="text-retro-blue">~</span>
              <span className="text-retro-cyan">$</span>
              <span className="ml-1">check_market_status</span>
            </div>
            <div className="mb-1">// Running market status check...</div>
            <div className="mb-1">// Available markets: BTC, ETH, XRP, ADA, SOL, DOT</div>
            <div className="mb-1">// Market status: ACTIVE</div>
            <div className="mb-3">// Ready for trading operations</div>
            
            <div className="flex justify-start space-x-2 mt-2">
              <RetroButton 
                className="text-xs"
                onClick={() => alert('Scanning markets function would be implemented here')}
              >
                SCAN MARKETS
              </RetroButton>
              <RetroButton 
                className="text-xs"
                variant="secondary"
                onClick={() => alert('Bot status check function would be implemented here')}
              >
                CHECK BOT STATUS
              </RetroButton>
            </div>
          </div>
        </>
      )}
    </div>
  );
};

export default Dashboard;

