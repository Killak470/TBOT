import React, { useState, useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';

// Import common components
import { RetroButton, RetroModal } from './components/common/CommonComponents';

// Import page components
import Dashboard from './components/pages/Dashboard';
import Charts from './components/pages/Charts';
import SimpleCharts from './components/pages/SimpleCharts';
import ActiveTrades from './components/pages/ActiveTrades';
import PendingTrades from './components/pages/PendingTrades';
import BotSignals from './components/pages/BotSignals';
import MarketNews from './components/pages/MarketNews';
import MarketScanner from './components/pages/MarketScanner';
import TradingStrategies from './components/pages/TradingStrategies';
import BotControl from './components/pages/BotControl';
import Settings from './components/pages/Settings';
import PositionDashboard from './components/pages/PositionDashboard';

// Import services
import WebSocketService from './services/WebSocketService';

function App() {
  const [showInfoModal, setShowInfoModal] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('INITIALIZING');
  const [activeStrategies, setActiveStrategies] = useState(0);

  // Initialize WebSocket connection when app starts
  useEffect(() => {
    // In a production environment, this would be a proper WebSocket URL
    // WebSocketService.init('wss://your-tradingbot-backend-api/ws');
    
    // Listen for connection status updates
    WebSocketService.on('connection', (data) => {
      if (data.status === 'connected') {
        setConnectionStatus('ONLINE');
      } else if (data.status === 'disconnected') {
        setConnectionStatus('OFFLINE');
      } else if (data.status === 'error') {
        setConnectionStatus('ERROR');
      }
    });
    
    // Listen for strategy updates
    WebSocketService.on('strategy_update', (data) => {
      if (data.activeStrategies) {
        setActiveStrategies(data.activeStrategies.length);
      }
    });
    
    // Cleanup when component unmounts
    return () => {
      WebSocketService.off('connection', () => {});
      WebSocketService.off('strategy_update', () => {});
      WebSocketService.disconnect();
    };
  }, []);

  return (
    <Router>
      <div className="App">
        <header className="App-header retro-text">
          <h1 className="text-2xl font-bold retro-header">Trading Bot - Retro Gaming Edition</h1>
          <div className="flex justify-end p-2">
            <div className="retro-terminal p-2 text-sm mr-4">
              <span>STATUS: {connectionStatus}</span>
              <span className="status-cursor ml-1">█</span>
            </div>
            <RetroButton onClick={() => setShowInfoModal(true)} variant="secondary" className="text-sm">
              INFO
            </RetroButton>
          </div>
          <nav className="p-4">
            <ul className="flex flex-wrap space-x-4">
              <li><Link to="/" className="hover:text-retro-accent">Dashboard</Link></li>
              <li><Link to="/position-dashboard" className="hover:text-retro-accent">Position Dashboard</Link></li>
              <li><Link to="/charts" className="hover:text-retro-accent">Charts</Link></li>
              <li><Link to="/simple-charts" className="hover:text-retro-accent">Simple Charts</Link></li>
              <li><Link to="/active-trades" className="hover:text-retro-accent">Active Trades</Link></li>
              <li><Link to="/pending-trades" className="hover:text-retro-accent">Pending Trades</Link></li>
              <li><Link to="/bot-signals" className="hover:text-retro-accent">Bot Signals</Link></li>
              <li><Link to="/market-news" className="hover:text-retro-accent">Market News</Link></li>
              <li><Link to="/market-scanner" className="hover:text-retro-accent">Market Scanner</Link></li>
              <li><Link to="/trading-strategies" className="hover:text-retro-accent">Trading Strategies</Link></li>
              <li><Link to="/bot-control" className="hover:text-retro-accent">Bot Control</Link></li>
              <li><Link to="/settings" className="hover:text-retro-accent">Settings</Link></li>
            </ul>
          </nav>
        </header>
        
        <main className="App-content retro-text">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/position-dashboard" element={<PositionDashboard />} />
            <Route path="/charts" element={<Charts />} />
            <Route path="/simple-charts" element={<SimpleCharts />} />
            <Route path="/active-trades" element={<ActiveTrades />} />
            <Route path="/pending-trades" element={<PendingTrades />} />
            <Route path="/bot-signals" element={<BotSignals />} />
            <Route path="/market-news" element={<MarketNews />} />
            <Route path="/market-scanner" element={<MarketScanner />} />
            <Route path="/trading-strategies" element={<TradingStrategies />} />
            <Route path="/bot-control" element={<BotControl />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </main>
        
        <footer className="App-footer retro-text p-4 text-center">
          <p>© 2025 Trading Bot - Retro Gaming Edition</p>
        </footer>
        
        {/* Info Modal */}
        <RetroModal 
          isOpen={showInfoModal} 
          onClose={() => setShowInfoModal(false)} 
          title="Trading Bot Info">
          <div className="p-4 retro-terminal">
            <p className="mb-2">TRADING BOT v0.1.0</p>
            <p className="mb-2">STATUS: {connectionStatus}</p>
            <p className="mb-2">CONNECTED EXCHANGE: MEXC</p>
            <p className="mb-2">ACTIVE STRATEGIES: {activeStrategies}</p>
            <p className="mb-2">RUNNING TASKS: SYSTEM MONITORING</p>
            <div className="mt-4">
              <RetroButton onClick={() => setShowInfoModal(false)}>
                CLOSE
              </RetroButton>
            </div>
          </div>
        </RetroModal>
      </div>
    </Router>
  );
}

export default App;
