import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { RetroCard } from './CommonComponents';

interface Balance {
  asset: string;
  free: string;
  locked: string;
  total: string;
  estimatedValueUSDT: string;
  exchange: string;
}

interface BalanceSummary {
  totalEstimatedValueUSDT: string;
  balances: Balance[];
  updateTime: number;
  exchange: string;
}

interface AccountBalanceProps {
  className?: string;
  showDetailed?: boolean;
}

const AccountBalance: React.FC<AccountBalanceProps> = ({ className = '', showDetailed = false }) => {
  const [balanceSummaries, setBalanceSummaries] = useState<BalanceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [totalValue, setTotalValue] = useState('0');

  useEffect(() => {
    loadBalances();
    
    // Refresh balances every 30 seconds
    const interval = setInterval(loadBalances, 30000);
    
    return () => clearInterval(interval);
  }, []);

  const loadBalances = async () => {
    try {
      const response = await axios.get('/api/account/balance/all');
      if (response.data && response.data.exchanges) {
        setBalanceSummaries(response.data.exchanges);
        setTotalValue(response.data.totalValueUSDT || '0');
      }
      setError(null);
    } catch (err: any) {
      console.error('Error loading balances:', err);
      setError(err.response?.data?.message || 'Failed to load balances');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className={`retro-terminal p-4 text-center ${className}`}>
        <span className="loading-animation">LOADING BALANCES...</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className={`retro-terminal p-4 text-center text-retro-red ${className}`}>
        <span>ERROR: {error}</span>
      </div>
    );
  }

  const formatNumber = (value: string) => {
    const num = parseFloat(value);
    if (num === 0) return '0.00';
    if (num < 0.01) return num.toFixed(8);
    if (num < 1) return num.toFixed(4);
    return num.toFixed(2);
  };

  const formatUSDT = (value: string) => {
    const num = parseFloat(value);
    return `$${num.toFixed(2)}`;
  };

  return (
    <div className={className}>
      {/* Total Balance Card */}
      <RetroCard className="p-4 mb-4">
        <div className="flex justify-between items-center mb-2">
          <h3 className="text-sm font-bold">TOTAL BALANCE (ALL EXCHANGES)</h3>
          <button 
            onClick={loadBalances}
            className="text-xs text-retro-green hover:text-retro-accent cursor-pointer"
          >
            REFRESH ↻
          </button>
        </div>
        <p className="text-2xl font-mono text-retro-green">
          {formatUSDT(totalValue)}
        </p>
        <p className="text-xs text-retro-dimmed mt-1">
          Last Update: {new Date().toLocaleTimeString()}
        </p>
      </RetroCard>

      {/* Exchange-specific Balance Cards */}
      {showDetailed && balanceSummaries.map((summary) => (
        <RetroCard key={summary.exchange} className="p-4 mb-4">
          <h3 className="text-sm font-bold mb-3">{summary.exchange} BALANCE</h3>
          <p className="text-xl font-mono text-retro-green mb-4">
            {formatUSDT(summary.totalEstimatedValueUSDT)}
          </p>
          
          {/* Asset Breakdown */}
          {summary.balances && summary.balances.length > 0 && (
            <div className="space-y-2">
              {summary.balances.map((balance) => (
                <div key={`${summary.exchange}-${balance.asset}`} className="flex justify-between items-center border-b border-retro-green border-opacity-30 pb-2">
                  <div className="flex-1">
                    <span className="font-mono font-bold">{balance.asset}</span>
                    <div className="text-xs text-retro-dimmed">
                      Free: {formatNumber(balance.free)} | Locked: {formatNumber(balance.locked)}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className="font-mono">{formatNumber(balance.total)}</div>
                    <div className="text-xs text-retro-green">{formatUSDT(balance.estimatedValueUSDT)}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </RetroCard>
      ))}
    </div>
  );
};

export default AccountBalance; 