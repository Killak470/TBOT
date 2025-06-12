import React, { useState, useEffect } from 'react';
import {
  getPendingSignals,
  getRecentSignals,
  getSignalStats,
  approveSignal,
  rejectSignal
} from '../../apiClient';

interface BotSignal {
  id: number;
  symbol: string;
  signalType: 'BUY' | 'SELL';
  status: 'PENDING' | 'APPROVED' | 'EXECUTED' | 'REJECTED' | 'EXPIRED';
  strategyName: string;
  entryPrice: number;
  quantity: number;
  stopLoss?: number;
  takeProfit?: number;
  confidence: number;
  rationale: string;
  riskRewardRatio?: number;
  potentialLoss?: number;
  potentialProfit?: number;
  generatedAt: string;
  processedAt?: string;
  processedBy?: string;
  rejectionReason?: string;
}

interface SignalStats {
  pending: number;
  approved: number;
  executed: number;
  rejected: number;
  expired: number;
}

const BotSignals: React.FC = () => {
  const [pendingSignals, setPendingSignals] = useState<BotSignal[]>([]);
  const [recentSignals, setRecentSignals] = useState<BotSignal[]>([]);
  const [stats, setStats] = useState<SignalStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'pending' | 'recent'>('pending');

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 30000); // Refresh every 30 seconds
    return () => clearInterval(interval);
  }, []);

  const loadData = async () => {
    try {
      const [pendingResponse, recentResponse, statsResponse] = await Promise.all([
        getPendingSignals(),
        getRecentSignals(),
        getSignalStats()
      ]);

      setPendingSignals(pendingResponse.data);
      setRecentSignals(recentResponse.data);
      setStats(statsResponse.data);
      setError(null);
    } catch (err) {
      setError('Failed to load signals');
      console.error('Error loading signals:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleApproveSignal = async (signalId: number) => {
    try {
      const response = await approveSignal(signalId, 'USER');
      if (response.data.success) {
        await loadData(); // Refresh data
      } else {
        setError('Failed to approve signal');
      }
    } catch (err) {
      setError('Error approving signal');
      console.error('Error approving signal:', err);
    }
  };

  const handleRejectSignal = async (signalId: number, reason?: string) => {
    try {
      const response = await rejectSignal(signalId, 'USER', reason || 'No reason provided');
      if (response.data.success) {
        await loadData(); // Refresh data
      } else {
        setError('Failed to reject signal');
      }
    } catch (err) {
      setError('Error rejecting signal');
      console.error('Error rejecting signal:', err);
    }
  };

  const formatCurrency = (value: number | undefined) => {
    if (value === undefined || value === null) return 'N/A';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2
    }).format(value);
  };

  const formatPercentage = (value: number | undefined) => {
    if (value === undefined || value === null) return 'N/A';
    return `${value.toFixed(2)}%`;
  };

  const getSignalTypeColor = (type: string) => {
    return type === 'BUY' ? 'text-green-600' : 'text-red-600';
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'PENDING': return 'text-yellow-600 bg-yellow-100';
      case 'APPROVED': return 'text-blue-600 bg-blue-100';
      case 'EXECUTED': return 'text-green-600 bg-green-100';
      case 'REJECTED': return 'text-red-600 bg-red-100';
      case 'EXPIRED': return 'text-gray-600 bg-gray-100';
      default: return 'text-gray-600 bg-gray-100';
    }
  };

  if (loading) {
    return (
      <div className="p-6">
        <div className="animate-pulse">
          <div className="h-8 bg-gray-300 rounded w-1/4 mb-6"></div>
          <div className="space-y-4">
            {[1, 2, 3].map(i => (
              <div key={i} className="h-24 bg-gray-300 rounded"></div>
            ))}
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="p-6">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Bot Signals</h1>
      </div>

      {error && (
        <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">
          {error}
        </div>
      )}

      {/* Statistics Cards */}
      {stats && (
        <div className="grid grid-cols-5 gap-4 mb-6">
          <div className="bg-yellow-50 p-4 rounded-lg">
            <div className="text-yellow-600 text-sm font-medium">Pending</div>
            <div className="text-2xl font-bold text-yellow-700">{stats.pending}</div>
          </div>
          <div className="bg-blue-50 p-4 rounded-lg">
            <div className="text-blue-600 text-sm font-medium">Approved</div>
            <div className="text-2xl font-bold text-blue-700">{stats.approved}</div>
          </div>
          <div className="bg-green-50 p-4 rounded-lg">
            <div className="text-green-600 text-sm font-medium">Executed</div>
            <div className="text-2xl font-bold text-green-700">{stats.executed}</div>
          </div>
          <div className="bg-red-50 p-4 rounded-lg">
            <div className="text-red-600 text-sm font-medium">Rejected</div>
            <div className="text-2xl font-bold text-red-700">{stats.rejected}</div>
          </div>
          <div className="bg-gray-50 p-4 rounded-lg">
            <div className="text-gray-600 text-sm font-medium">Expired</div>
            <div className="text-2xl font-bold text-gray-700">{stats.expired}</div>
          </div>
        </div>
      )}

      {/* Tab Navigation */}
      <div className="border-b border-gray-200 mb-6">
        <nav className="-mb-px flex space-x-8">
          <button
            onClick={() => setActiveTab('pending')}
            className={`py-2 px-1 border-b-2 font-medium text-sm ${
              activeTab === 'pending'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            Pending Signals ({pendingSignals.length})
          </button>
          <button
            onClick={() => setActiveTab('recent')}
            className={`py-2 px-1 border-b-2 font-medium text-sm ${
              activeTab === 'recent'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            Recent Signals ({recentSignals.length})
          </button>
        </nav>
      </div>

      {/* Signals List */}
      <div className="space-y-4">
        {(activeTab === 'pending' ? pendingSignals : recentSignals).map((signal) => (
          <div key={signal.id} className="bg-white rounded-lg shadow-md p-6 border">
            <div className="flex justify-between items-start mb-4">
              <div className="flex items-center space-x-4">
                <div className="text-lg font-bold">{signal.symbol}</div>
                <span className={`px-2 py-1 rounded text-sm font-medium ${getSignalTypeColor(signal.signalType)}`}>
                  {signal.signalType}
                </span>
                <span className={`px-2 py-1 rounded text-xs font-medium ${getStatusColor(signal.status)}`}>
                  {signal.status}
                </span>
              </div>
              <div className="text-sm text-gray-500">
                {new Date(signal.generatedAt).toLocaleString()}
              </div>
            </div>

            <div className="grid grid-cols-3 gap-6 mb-4">
              <div>
                <div className="text-sm text-gray-600">Strategy</div>
                <div className="font-medium">{signal.strategyName}</div>
              </div>
              <div>
                <div className="text-sm text-gray-600">Entry Price</div>
                <div className="font-medium">{formatCurrency(signal.entryPrice)}</div>
              </div>
              <div>
                <div className="text-sm text-gray-600">Quantity</div>
                <div className="font-medium">{signal.quantity}</div>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-6 mb-4">
              <div>
                <div className="text-sm text-gray-600">Confidence</div>
                <div className="font-medium">{formatPercentage(signal.confidence)}</div>
              </div>
              {signal.riskRewardRatio && (
                <div>
                  <div className="text-sm text-gray-600">Risk/Reward</div>
                  <div className="font-medium">1:{signal.riskRewardRatio}</div>
                </div>
              )}
              {signal.potentialProfit && (
                <div>
                  <div className="text-sm text-gray-600">Potential Profit</div>
                  <div className="font-medium text-green-600">{formatCurrency(signal.potentialProfit)}</div>
                </div>
              )}
            </div>

            <div className="mb-4">
              <div className="text-sm text-gray-600 mb-1">Rationale</div>
              <div className="text-gray-800">{signal.rationale}</div>
            </div>

            {signal.status === 'PENDING' && (
              <div className="flex justify-end space-x-3">
                <button
                  onClick={() => handleRejectSignal(signal.id)}
                  className="bg-red-500 hover:bg-red-600 text-white px-4 py-2 rounded-lg text-sm"
                >
                  Reject
                </button>
                <button
                  onClick={() => handleApproveSignal(signal.id)}
                  className="bg-green-500 hover:bg-green-600 text-white px-4 py-2 rounded-lg text-sm"
                >
                  Approve
                </button>
              </div>
            )}

            {signal.status === 'REJECTED' && signal.rejectionReason && (
              <div className="mt-4 p-3 bg-red-50 rounded-lg">
                <div className="text-sm text-red-600">
                  <strong>Rejected:</strong> {signal.rejectionReason}
                </div>
              </div>
            )}
          </div>
        ))}

        {(activeTab === 'pending' ? pendingSignals : recentSignals).length === 0 && (
          <div className="text-center py-8 text-gray-500">
            {activeTab === 'pending' ? 'No pending signals' : 'No recent signals'}
          </div>
        )}
      </div>
    </div>
  );
};

export default BotSignals; 