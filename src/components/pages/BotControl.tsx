import React, { useState, useEffect } from 'react';
import { RetroButton, RetroModal, RetroInput } from '../common/CommonComponents';

interface BotConfig {
  id: string;
  name: string;
  strategy: string;
  symbol: string;
  isActive: boolean;
  profit?: number;
  trades?: number;
  lastActive?: string;
}

interface LogEntry {
  timestamp: string;
  message: string;
  type: 'info' | 'success' | 'error' | 'warning';
}

const SNIPER_STRATEGY_ID = "SniperEntryStrategy";

const BotControl: React.FC = () => {
  const [bots, setBots] = useState<BotConfig[]>([
    { 
      id: 'bot1', 
      name: 'BTC DOMINATOR', 
      strategy: 'FIBONACCI + SENTIMENT', 
      symbol: 'BTCUSDT', 
      isActive: true,
      profit: 234.56,
      trades: 12,
      lastActive: '2 minutes ago'
    },
    { 
      id: 'bot2', 
      name: 'ETH MOMENTUM', 
      strategy: 'TA + CLAUDE AI', 
      symbol: 'ETHUSDT', 
      isActive: false,
      profit: -45.23,
      trades: 5,
      lastActive: '3 hours ago'
    },
    { 
      id: 'bot3', 
      name: 'ALTCOIN HUNTER', 
      strategy: 'MARKET SCANNER', 
      symbol: 'MULTIPLE', 
      isActive: false,
      profit: 123.45,
      trades: 20,
      lastActive: '1 day ago'
    },
    {
      id: 'bot-sniper',
      name: 'SNIPER ENTRY',
      strategy: SNIPER_STRATEGY_ID,
      symbol: 'MULTIPLE',
      isActive: false,
      profit: 0,
      trades: 0,
      lastActive: 'Never'
    }
  ]);

  const [logs, setLogs] = useState<LogEntry[]>([
    { timestamp: '2025-05-20 23:15:01', message: 'System initialized', type: 'info' },
    { timestamp: '2025-05-20 23:15:05', message: 'BTC DOMINATOR bot started', type: 'success' },
    { timestamp: '2025-05-20 23:22:15', message: 'New trade: BUY BTC at $60,245', type: 'info' },
    { timestamp: '2025-05-20 23:45:30', message: 'API connection error - retrying', type: 'error' },
    { timestamp: '2025-05-21 00:02:10', message: 'API connection restored', type: 'success' },
    { timestamp: '2025-05-21 00:15:22', message: 'Market volatility alert for ETH', type: 'warning' },
  ]);

  const [showNewBotModal, setShowNewBotModal] = useState(false);
  const [showConfigureModal, setShowConfigureModal] = useState(false);
  const [selectedBot, setSelectedBot] = useState<BotConfig | null>(null);
  const [newBotData, setNewBotData] = useState({
    name: '',
    strategy: '',
    symbol: ''
  });
  const [activeTab, setActiveTab] = useState('bots'); // 'bots' or 'logs'

  useEffect(() => {
    // Example: fetch('/api/strategies/status').then(res => res.json()).then(data => {
    //   // Update bots isActive state based on backend data
    // });
  }, []);

  const toggleBotStatus = async (botId: string) => {
    const botToToggle = bots.find(b => b.id === botId);
    if (!botToToggle) return;

    const newStatus = !botToToggle.isActive;
    let backendSuccess = false;

    if (botToToggle.strategy === SNIPER_STRATEGY_ID) {
        const endpoint = newStatus ? '/api/strategies/execution/sniper/start' : '/api/strategies/execution/sniper/stop';
        try {
            console.log(`Attempting to ${newStatus ? 'start' : 'stop'} strategy: ${botToToggle.strategy} via ${endpoint}`);
            const response = await fetch(endpoint, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json', // Keep for consistency, though body is empty
                },
                // No body needed for these dedicated Sniper endpoints
            });
            
            const responseData = await response.json(); 

            if (response.ok) {
                console.log(`Strategy ${botToToggle.strategy} ${newStatus ? 'globally toggled' : 'globally toggled'} successfully via backend. Response:`, responseData);
                backendSuccess = true;
            } else {
                console.error(`Error ${newStatus ? 'starting' : 'stopping'} strategy ${botToToggle.strategy} via backend. Status: ${response.status}`, responseData);
                alert(`Failed to ${newStatus ? 'start' : 'stop'} ${botToToggle.name}: ${responseData.message || 'Unknown error'}`);
            }
        } catch (error) {
            console.error(`Network or other error ${newStatus ? 'starting' : 'stopping'} strategy ${botToToggle.strategy}:`, error);
            alert(`Failed to ${newStatus ? 'start' : 'stop'} ${botToToggle.name}: Network error or server unavailable.`);
        }
    } else {
        // For other bots, assume local toggle is fine or they have a different mechanism
        console.log(`Toggling status locally for bot: ${botToToggle.name}`);
        backendSuccess = true; // Or implement their specific backend calls if any
    }

    if (backendSuccess) {
        const updatedBots = bots.map(bot => {
            if (bot.id === botId) {
                const newLog: LogEntry = {
                    timestamp: new Date().toLocaleString(),
                    message: `${bot.name} bot (Strategy: ${bot.strategy}) ${newStatus ? 'started' : 'stopped'}`,
                    type: newStatus ? 'success' : 'info'
                };
                setLogs(prevLogs => [newLog, ...prevLogs]);
                return { ...bot, isActive: newStatus, lastActive: 'just now' };
            }
            return bot;
        });
        setBots(updatedBots);
    } else {
      // If backend call failed for Sniper, don't update UI to reflect a change that didn't happen
      console.warn(`Backend call failed for ${botToToggle.name}, UI status will not be changed.`);
    }
  };

  const openConfigureModal = (bot: BotConfig) => {
    setSelectedBot(bot);
    setShowConfigureModal(true);
  };

  const handleNewBotSubmit = () => {
    if (!newBotData.name || !newBotData.strategy || !newBotData.symbol) {
      alert('Please fill in all fields');
      return;
    }
    
    const newBot: BotConfig = {
      id: `bot${bots.length + 1}`,
      name: newBotData.name.toUpperCase(),
      strategy: newBotData.strategy.toUpperCase(),
      symbol: newBotData.symbol.toUpperCase(),
      isActive: false,
      profit: 0,
      trades: 0,
      lastActive: 'Never'
    };
    
    setBots([...bots, newBot]);
    
    // Add a log entry
    const newLog: LogEntry = {
      timestamp: new Date().toLocaleString(),
      message: `New bot created: ${newBot.name}`,
      type: 'info'
    };
    setLogs([newLog, ...logs]);
    
    // Reset form and close modal
    setNewBotData({ name: '', strategy: '', symbol: '' });
    setShowNewBotModal(false);
  };

  const getStatusIndicator = (isActive: boolean) => {
    return (
      <div className="flex items-center">
        <div className={`w-3 h-3 rounded-full mr-2 ${isActive ? 'bg-retro-green animate-pulse' : 'bg-retro-red'}`}></div>
        <span className={isActive ? 'text-retro-green' : 'text-retro-red'}>
          {isActive ? 'ONLINE' : 'OFFLINE'}
        </span>
      </div>
    );
  };

  return (
    <div className="p-4 retro-text">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold retro-header">BOT CONTROL PANEL</h1>
        <div className="flex">
          <RetroButton 
            variant={activeTab === 'bots' ? 'primary' : 'secondary'}
            className={`mr-2 text-sm ${activeTab === 'bots' ? 'bg-retro-green text-retro-black' : ''}`}
            onClick={() => setActiveTab('bots')}
          >
            BOTS
          </RetroButton>
          <RetroButton 
            variant={activeTab === 'logs' ? 'primary' : 'secondary'} 
            className={`text-sm ${activeTab === 'logs' ? 'bg-retro-green text-retro-black' : ''}`}
            onClick={() => setActiveTab('logs')}
          >
            SYSTEM LOGS
          </RetroButton>
        </div>
      </div>

      {activeTab === 'bots' ? (
        <>
          <div className="retro-terminal p-3 mb-4 text-sm">
            <div className="flex justify-between">
              <span>ACTIVE BOTS: {bots.filter(b => b.isActive).length}/{bots.length}</span>
              <span>TOTAL PROFIT: ${bots.reduce((sum, bot) => sum + (bot.profit || 0), 0).toFixed(2)}</span>
              <span>TOTAL TRADES: {bots.reduce((sum, bot) => sum + (bot.trades || 0), 0)}</span>
            </div>
          </div>
          
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 mb-6">
            {bots.map(bot => (
              <div key={bot.id} className={`retro-card p-4 ${bot.isActive ? 'border-2 border-retro-green' : ''}`}>
                <div className="flex justify-between items-start mb-2">
                  <h2 className="text-lg font-bold retro-subheader">{bot.name}</h2>
                  {getStatusIndicator(bot.isActive)}
                </div>
                
                <div className="text-sm mb-3">
                  <div className="flex justify-between mb-1">
                    <span className="opacity-70">STRATEGY:</span>
                    <span>{bot.strategy}</span>
                  </div>
                  <div className="flex justify-between mb-1">
                    <span className="opacity-70">SYMBOL:</span>
                    <span>{bot.symbol}</span>
                  </div>
                  <div className="flex justify-between mb-1">
                    <span className="opacity-70">PROFIT:</span>
                    <span className={bot.profit && bot.profit >= 0 ? 'text-retro-green' : 'text-retro-red'}>
                      {bot.profit ? `$${bot.profit.toFixed(2)}` : '$0.00'}
                    </span>
                  </div>
                  <div className="flex justify-between mb-1">
                    <span className="opacity-70">TRADES:</span>
                    <span>{bot.trades || 0}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="opacity-70">LAST ACTIVE:</span>
                    <span>{bot.lastActive || 'Never'}</span>
                  </div>
                </div>
                
                <div className="flex space-x-2">
                  <RetroButton 
                    onClick={() => toggleBotStatus(bot.id)}
                    variant={bot.isActive ? 'danger' : 'primary'}
                    className="text-xs flex-1"
                  >
                    {bot.isActive ? 'STOP' : 'START'}
                  </RetroButton>
                  <RetroButton 
                    onClick={() => openConfigureModal(bot)}
                    variant="secondary"
                    className="text-xs flex-1"
                  >
                    CONFIG
                  </RetroButton>
                </div>
              </div>
            ))}
          </div>
          
          <div className="flex justify-center mt-4">
            <RetroButton 
              onClick={() => setShowNewBotModal(true)}
              className="text-center"
            >
              CREATE NEW BOT
            </RetroButton>
          </div>
        </>
      ) : (
        // Logs Tab
        <div className="retro-terminal p-4 font-mono text-sm h-[600px] overflow-y-auto">
          <div className="mb-2 pb-2 border-b border-retro-green border-opacity-50">
            <span className="text-retro-blue">SYSTEM LOG</span> - Showing {logs.length} entries
          </div>
          {logs.map((log, index) => (
            <div key={index} className="mb-2 border-b border-retro-dark pb-1">
              <span className="text-retro-cyan mr-2">[{log.timestamp}]</span>
              <span className={
                log.type === 'error' ? 'text-retro-red' : 
                log.type === 'success' ? 'text-retro-green' : 
                log.type === 'warning' ? 'text-retro-yellow' : 
                'text-retro-green'
              }>
                {log.message}
              </span>
            </div>
          ))}
        </div>
      )}
      
      {/* New Bot Modal */}
      <RetroModal
        isOpen={showNewBotModal}
        onClose={() => setShowNewBotModal(false)}
        title="CREATE NEW TRADING BOT"
      >
        <div className="p-4">
          <div className="mb-4">
            <label className="block mb-1">BOT NAME:</label>
            <RetroInput
              value={newBotData.name}
              onChange={(e) => setNewBotData({...newBotData, name: e.target.value})}
              placeholder="e.g., BTC MOMENTUM"
            />
          </div>
          <div className="mb-4">
            <label className="block mb-1">STRATEGY:</label>
            <RetroInput
              value={newBotData.strategy}
              onChange={(e) => setNewBotData({...newBotData, strategy: e.target.value})}
              placeholder="e.g., FIBONACCI RETRACEMENT"
            />
          </div>
          <div className="mb-4">
            <label className="block mb-1">SYMBOL:</label>
            <RetroInput
              value={newBotData.symbol}
              onChange={(e) => setNewBotData({...newBotData, symbol: e.target.value})}
              placeholder="e.g., BTCUSDT"
            />
          </div>
          <div className="flex justify-end space-x-2 mt-6">
            <RetroButton variant="secondary" onClick={() => setShowNewBotModal(false)}>
              CANCEL
            </RetroButton>
            <RetroButton onClick={handleNewBotSubmit}>
              CREATE BOT
            </RetroButton>
          </div>
        </div>
      </RetroModal>
      
      {/* Configure Bot Modal */}
      <RetroModal
        isOpen={showConfigureModal && selectedBot !== null}
        onClose={() => setShowConfigureModal(false)}
        title={`CONFIGURE: ${selectedBot?.name || ''}`}
      >
        {selectedBot && (
          <div className="p-4 retro-terminal">
            <p className="mb-4">// Bot configuration interface would go here</p>
            <p className="mb-4">// Settings for trading strategy parameters</p>
            <p className="mb-4">// Risk management controls</p>
            <p className="mb-4">// API configuration</p>
            
            <div className="flex justify-end mt-6">
              <RetroButton onClick={() => setShowConfigureModal(false)}>
                CLOSE
              </RetroButton>
            </div>
          </div>
        )}
      </RetroModal>
    </div>
  );
};

export default BotControl;

