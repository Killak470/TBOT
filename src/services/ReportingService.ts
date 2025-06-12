/**
 * ReportingService - Handles generating and exporting performance reports
 */

interface Trade {
  id: string;
  symbol: string;
  entryPrice: number;
  exitPrice: number;
  entryTime: number;
  exitTime: number;
  quantity: number;
  side: string;
  pnl: number;
  pnlPercent: number;
  strategy: string;
  status: string;
}

interface SymbolMetric {
  symbol: string;
  trades: number;
  winRate: number;
  pnl: number;
  avgPnl: number;
}

interface StrategyMetric {
  strategy: string;
  trades: number;
  winRate: number;
  pnl: number;
  avgPnl: number;
}

interface PerformanceMetrics {
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRate: number;
  profitFactor: number;
  averagePnl: number;
  averageWin: number;
  averageLoss: number;
  largestWin: number;
  largestLoss: number;
  totalPnl: number;
  maxDrawdown: number;
  averageDuration: number;
  symbolMetrics: SymbolMetric[];
  strategyMetrics: StrategyMetric[];
  firstTradeDate: Date;
  lastTradeDate: Date;
}

class ReportingService {
  private static instance: ReportingService;
  
  // Singleton pattern
  private constructor() {
    // Private constructor to enforce singleton pattern
  }
  
  /**
   * Get the ReportingService instance
   */
  public static getInstance(): ReportingService {
    if (!ReportingService.instance) {
      ReportingService.instance = new ReportingService();
    }
    return ReportingService.instance;
  }
  
  /**
   * Export trade history to CSV
   * @param trades Array of trade objects
   * @returns Blob URL for the CSV file
   */
  public exportTradeHistoryCSV(trades: Trade[]): string {
    if (!trades || trades.length === 0) {
      console.warn('No trades to export');
      return '';
    }
    
    // Generate CSV header
    const headers = [
      'ID',
      'Symbol',
      'Entry Time',
      'Entry Price',
      'Exit Time',
      'Exit Price',
      'Side',
      'Quantity',
      'P&L',
      'P&L %',
      'Strategy',
      'Status'
    ].join(',');
    
    // Generate CSV rows
    const rows = trades.map(trade => {
      return [
        trade.id,
        trade.symbol,
        new Date(trade.entryTime).toISOString(),
        trade.entryPrice,
        trade.status === 'CLOSED' ? new Date(trade.exitTime).toISOString() : '',
        trade.status === 'CLOSED' ? trade.exitPrice : '',
        trade.side,
        trade.quantity,
        trade.status === 'CLOSED' ? trade.pnl : '',
        trade.status === 'CLOSED' ? trade.pnlPercent : '',
        trade.strategy,
        trade.status
      ].join(',');
    });
    
    // Combine header and rows
    const csvContent = [headers, ...rows].join('\n');
    
    // Create file and download
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    return URL.createObjectURL(blob);
  }
  
  /**
   * Download a file from a Blob URL
   * @param blobUrl The Blob URL
   * @param filename The desired filename
   */
  public downloadFile(blobUrl: string, filename: string): void {
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = filename;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  }
  
  /**
   * Export trading history to CSV and trigger download
   * @param trades Array of trade objects
   */
  public downloadTradeHistoryCSV(trades: Trade[]): void {
    const blobUrl = this.exportTradeHistoryCSV(trades);
    if (blobUrl) {
      this.downloadFile(blobUrl, `trade-history-${new Date().toISOString().split('T')[0]}.csv`);
    }
  }
  
  /**
   * Calculate detailed performance metrics from trade history
   * @param trades Array of trade objects
   * @returns Object containing performance metrics
   */
  public calculatePerformanceMetrics(trades: Trade[]): PerformanceMetrics | null {
    if (!trades || trades.length === 0) {
      return null;
    }
    
    // Filter to only closed trades
    const closedTrades = trades.filter(t => t.status === 'CLOSED');
    if (closedTrades.length === 0) {
      return null;
    }
    
    // Extract wins and losses
    const winningTrades = closedTrades.filter(t => t.pnl > 0);
    const losingTrades = closedTrades.filter(t => t.pnl < 0);
    
    // Calculate P&L metrics
    const totalPnl = closedTrades.reduce((sum, t) => sum + t.pnl, 0);
    const totalWinPnl = winningTrades.reduce((sum, t) => sum + t.pnl, 0);
    const totalLossPnl = Math.abs(losingTrades.reduce((sum, t) => sum + t.pnl, 0));
    
    // Calculate trade durations
    const durations = closedTrades.map(t => t.exitTime - t.entryTime);
    const avgDuration = durations.length > 0 
      ? durations.reduce((sum, d) => sum + d, 0) / durations.length
      : 0;
      
    // Group trades by symbol
    const symbolMap = closedTrades.reduce((map, trade) => {
      if (!map[trade.symbol]) {
        map[trade.symbol] = [];
      }
      map[trade.symbol].push(trade);
      return map;
    }, {} as Record<string, Trade[]>);
    
    // Calculate symbol-specific metrics
    const symbolMetrics = Object.entries(symbolMap).map(([symbol, trades]) => {
      const wins = trades.filter(t => t.pnl > 0).length;
      const total = trades.length;
      const winRate = (wins / total) * 100;
      const pnl = trades.reduce((sum, t) => sum + t.pnl, 0);
      const avgPnl = pnl / total;
      
      return {
        symbol,
        trades: total,
        winRate,
        pnl,
        avgPnl,
      };
    });
    
    // Group trades by strategy
    const strategyMap = closedTrades.reduce((map, trade) => {
      if (!map[trade.strategy]) {
        map[trade.strategy] = [];
      }
      map[trade.strategy].push(trade);
      return map;
    }, {} as Record<string, Trade[]>);
    
    // Calculate strategy-specific metrics
    const strategyMetrics = Object.entries(strategyMap).map(([strategy, trades]) => {
      const wins = trades.filter(t => t.pnl > 0).length;
      const total = trades.length;
      const winRate = (wins / total) * 100;
      const pnl = trades.reduce((sum, t) => sum + t.pnl, 0);
      const avgPnl = pnl / total;
      
      return {
        strategy,
        trades: total,
        winRate,
        pnl,
        avgPnl,
      };
    });
    
    // Calculate drawdown (simplified)
    let maxDrawdown = 0;
    let cumulativePnl = 0;
    let peakPnl = 0;
    
    // Sort trades by time for cumulative performance calculation
    const sortedTrades = [...closedTrades].sort((a, b) => a.exitTime - b.exitTime);
    
    sortedTrades.forEach(trade => {
      cumulativePnl += trade.pnl;
      
      if (cumulativePnl > peakPnl) {
        peakPnl = cumulativePnl;
      }
      
      const drawdown = peakPnl - cumulativePnl;
      if (drawdown > maxDrawdown) {
        maxDrawdown = drawdown;
      }
    });
    
    // Return comprehensive metrics
    return {
      totalTrades: closedTrades.length,
      winningTrades: winningTrades.length,
      losingTrades: losingTrades.length,
      winRate: closedTrades.length > 0 ? (winningTrades.length / closedTrades.length) * 100 : 0,
      profitFactor: totalLossPnl > 0 ? totalWinPnl / totalLossPnl : 0,
      averagePnl: closedTrades.length > 0 ? totalPnl / closedTrades.length : 0,
      averageWin: winningTrades.length > 0 ? totalWinPnl / winningTrades.length : 0,
      averageLoss: losingTrades.length > 0 ? totalLossPnl / losingTrades.length : 0,
      largestWin: winningTrades.length > 0 ? Math.max(...winningTrades.map(t => t.pnl)) : 0,
      largestLoss: losingTrades.length > 0 ? Math.min(...losingTrades.map(t => t.pnl)) : 0,
      totalPnl,
      maxDrawdown,
      averageDuration: avgDuration,
      symbolMetrics,
      strategyMetrics,
      firstTradeDate: new Date(Math.min(...closedTrades.map(t => t.entryTime))),
      lastTradeDate: new Date(Math.max(...closedTrades.map(t => t.exitTime))),
    };
  }
  
  /**
   * Generate a comprehensive performance report
   * @param trades Array of trade objects
   * @param accountHistory Account balance history
   */
  public async generatePerformanceReport(trades: Trade[], accountHistory: any[]): Promise<void> {
    try {
      // Calculate performance metrics
      const metrics = this.calculatePerformanceMetrics(trades);
      
      if (!metrics) {
        alert('Cannot generate report: No closed trades found');
        return;
      }
      
      // In a real application, this would generate a PDF or HTML report
      // For this demonstration, we'll just display the metrics in an alert
      alert(`
        Performance Report Summary:
        
        Total Trades: ${metrics.totalTrades}
        Win Rate: ${metrics.winRate.toFixed(2)}%
        Profit Factor: ${metrics.profitFactor.toFixed(2)}
        Total P&L: $${metrics.totalPnl.toFixed(2)}
        Max Drawdown: $${metrics.maxDrawdown.toFixed(2)}
        
        Best Performing Symbol: ${
          metrics.symbolMetrics.sort((a: SymbolMetric, b: SymbolMetric) => b.pnl - a.pnl)[0]?.symbol || 'N/A'
        }
        
        Best Performing Strategy: ${
          metrics.strategyMetrics.sort((a: StrategyMetric, b: StrategyMetric) => b.pnl - a.pnl)[0]?.strategy || 'N/A'
        }
        
        Period: ${metrics.firstTradeDate.toLocaleDateString()} to ${metrics.lastTradeDate.toLocaleDateString()}
        
        A full report would be generated as a PDF in a real implementation.
      `);
      
    } catch (error) {
      console.error('Error generating performance report:', error);
      alert('Error generating report. Please try again later.');
    }
  }
}

export default ReportingService.getInstance(); 