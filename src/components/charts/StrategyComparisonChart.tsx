import React, { useState, useEffect } from 'react';
import PerformanceChart from './PerformanceChart';
import { RetroButton, RetroSelect } from '../common/CommonComponents';

interface StrategyPerformance {
  id: string;
  name: string;
  performance: {
    timestamp: number;
    value: number;
  }[];
  color?: string;
  metrics: {
    totalTrades: number;
    winRate: number;
    avgProfit: number;
    maxDrawdown: number;
    sharpeRatio: number;
  };
}

interface StrategyComparisonChartProps {
  strategies: StrategyPerformance[];
  timeRanges?: { label: string; value: string }[];
}

const StrategyComparisonChart: React.FC<StrategyComparisonChartProps> = ({
  strategies,
  timeRanges = [
    { label: '1D', value: '1d' },
    { label: '1W', value: '1w' },
    { label: '1M', value: '1m' },
    { label: '3M', value: '3m' },
    { label: 'YTD', value: 'ytd' },
    { label: '1Y', value: '1y' },
    { label: 'ALL', value: 'all' }
  ]
}) => {
  const [selectedStrategies, setSelectedStrategies] = useState<string[]>([]);
  const [timeRange, setTimeRange] = useState('1m');
  const [chartData, setChartData] = useState<any[]>([]);
  const [normalizeData, setNormalizeData] = useState(true);

  useEffect(() => {
    // Filter and prepare chart data based on selected strategies
    const filteredStrategies = strategies.filter(s => 
      selectedStrategies.length === 0 || selectedStrategies.includes(s.id)
    );
    
    // If normalize is true, convert all strategies to percentage change from first value
    const preparedData = filteredStrategies.map(strategy => {
      const performance = [...strategy.performance];
      
      // Filter data by time range
      const filteredPerformance = filterByTimeRange(performance, timeRange);
      
      if (normalizeData && filteredPerformance.length > 0) {
        const initialValue = filteredPerformance[0].value;
        return {
          name: strategy.name,
          data: filteredPerformance.map(point => ({
            timestamp: point.timestamp,
            value: ((point.value / initialValue) - 1) * 100
          })),
          color: strategy.color
        };
      }
      
      return {
        name: strategy.name,
        data: filteredPerformance,
        color: strategy.color
      };
    });
    
    setChartData(preparedData);
  }, [strategies, selectedStrategies, timeRange, normalizeData]);
  
  // Filter performance data based on time range
  const filterByTimeRange = (performance: any[], range: string) => {
    const now = new Date().getTime();
    let cutoffTime = now;
    
    switch (range) {
      case '1d':
        cutoffTime = now - 24 * 60 * 60 * 1000;
        break;
      case '1w':
        cutoffTime = now - 7 * 24 * 60 * 60 * 1000;
        break;
      case '1m':
        cutoffTime = now - 30 * 24 * 60 * 60 * 1000;
        break;
      case '3m':
        cutoffTime = now - 90 * 24 * 60 * 60 * 1000;
        break;
      case 'ytd':
        cutoffTime = new Date(new Date().getFullYear(), 0, 1).getTime();
        break;
      case '1y':
        cutoffTime = now - 365 * 24 * 60 * 60 * 1000;
        break;
      case 'all':
      default:
        // No filtering needed for 'all'
        return performance;
    }
    
    return performance.filter(point => point.timestamp >= cutoffTime);
  };
  
  // Toggle strategy selection
  const toggleStrategy = (strategyId: string) => {
    setSelectedStrategies(prev => {
      if (prev.includes(strategyId)) {
        return prev.filter(id => id !== strategyId);
      } else {
        return [...prev, strategyId];
      }
    });
  };

  return (
    <div className="retro-card p-4">
      <div className="flex flex-wrap justify-between items-center mb-4">
        <h2 className="text-lg font-semibold retro-subheader">STRATEGY PERFORMANCE COMPARISON</h2>
        
        <div className="flex items-center space-x-4">
          <div className="flex items-center">
            <label className="mr-2 text-xs">TIME RANGE:</label>
            <RetroSelect
              options={timeRanges.map(range => ({ value: range.value, label: range.label }))}
              value={timeRange}
              onChange={e => setTimeRange(e.target.value)}
              className="text-xs py-1 px-2"
            />
          </div>
          
          <div className="flex items-center">
            <label className="mr-2 text-xs">NORMALIZE:</label>
            <input
              type="checkbox"
              checked={normalizeData}
              onChange={() => setNormalizeData(!normalizeData)}
              className="form-checkbox border-retro-green bg-retro-black checked:bg-retro-green"
            />
          </div>
        </div>
      </div>
      
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 mb-4">
        {strategies.map(strategy => (
          <div 
            key={strategy.id}
            className={`p-3 border-2 cursor-pointer transition-all duration-200 ${
              selectedStrategies.includes(strategy.id) || selectedStrategies.length === 0
                ? 'border-retro-green bg-retro-green bg-opacity-10'
                : 'border-retro-dark'
            }`}
            onClick={() => toggleStrategy(strategy.id)}
          >
            <div className="flex justify-between">
              <h3 className="font-bold text-sm">{strategy.name}</h3>
              <div 
                className="w-3 h-3 rounded-full" 
                style={{ backgroundColor: strategy.color || '#22ff22' }}
              ></div>
            </div>
            
            <div className="grid grid-cols-2 gap-x-4 gap-y-1 mt-3 text-xs">
              <div>Win Rate:</div>
              <div className="text-right">{strategy.metrics.winRate.toFixed(2)}%</div>
              
              <div>Trades:</div>
              <div className="text-right">{strategy.metrics.totalTrades}</div>
              
              <div>Avg Profit:</div>
              <div className="text-right">{strategy.metrics.avgProfit.toFixed(2)}%</div>
              
              <div>Max Drawdown:</div>
              <div className="text-right text-retro-red">-{strategy.metrics.maxDrawdown.toFixed(2)}%</div>
              
              <div>Sharpe Ratio:</div>
              <div className="text-right">{strategy.metrics.sharpeRatio.toFixed(2)}</div>
            </div>
          </div>
        ))}
      </div>
      
      <PerformanceChart 
        series={chartData} 
        title={`Strategy Performance (${timeRanges.find(r => r.value === timeRange)?.label || timeRange})`}
        yAxisLabel={normalizeData ? "% Change" : "Value"}
        height={400}
      />
    </div>
  );
};

export default StrategyComparisonChart; 