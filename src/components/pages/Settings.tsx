import React, { useState, useEffect } from "react";
import { RetroButton, RetroInput } from '../common/CommonComponents';
import apiClient from '../../apiClient';

interface AppSettings {
  theme: string;
  defaultChartInterval: string;
  newsSources: string[];
  mexcApiKey: string;
  claudeApiKey: string;
}

interface SettingsSection {
  id: string;
  title: string;
  icon?: string;
}

const Settings: React.FC = () => {
  const [activeSection, setActiveSection] = useState('general');
  const [apiKey, setApiKey] = useState('');
  const [apiSecret, setApiSecret] = useState('');
  const [tradeSize, setTradeSize] = useState('100');
  const [maxOpenTrades, setMaxOpenTrades] = useState('5');
  const [riskLevel, setRiskLevel] = useState('medium');
  const [notificationsEnabled, setNotificationsEnabled] = useState(true);
  const [emailAlerts, setEmailAlerts] = useState(true);
  const [pushNotifications, setPushNotifications] = useState(false);
  const [telegramNotifications, setTelegramNotifications] = useState(false);
  const [telegramChatId, setTelegramChatId] = useState('');
  const [theme, setTheme] = useState('retro-dark');
  
  // Bot signal settings
  const [signalInterval, setSignalInterval] = useState('1h');
  const [autoGenerateSignals, setAutoGenerateSignals] = useState(false);
  const [defaultPairs, setDefaultPairs] = useState<string[]>([]);
  const [selectedPairs, setSelectedPairs] = useState<string[]>([]);
  const [isGeneratingSignals, setIsGeneratingSignals] = useState(false);
  const [lastGenerationResult, setLastGenerationResult] = useState<any>(null);
  
  // Settings management state
  const [isSaving, setIsSaving] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [saveMessage, setSaveMessage] = useState('');
  
  // List of setting sections
  const sections: SettingsSection[] = [
    { id: 'general', title: 'GENERAL SETTINGS' },
    { id: 'exchange', title: 'EXCHANGE API' },
    { id: 'trading', title: 'TRADING PARAMETERS' },
    { id: 'bot-signals', title: 'BOT SIGNALS' },
    { id: 'notifications', title: 'NOTIFICATIONS' },
    { id: 'appearance', title: 'APPEARANCE' },
    { id: 'backups', title: 'BACKUPS & RECOVERY' },
  ];
  
  // Load settings and default trading pairs on component mount
  useEffect(() => {
    const loadData = async () => {
      await Promise.all([loadSettings(), loadDefaultPairs()]);
    };
    
    loadData();
  }, []);
  
  // Load settings from backend
  const loadSettings = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get('/settings');
      if (response.data && response.data.success && response.data.settings) {
        const settings = response.data.settings;
        
        // Apply loaded settings to state
        if (settings.apiKey) setApiKey(settings.apiKey);
        if (settings.apiSecret) setApiSecret(settings.apiSecret);
        if (settings.tradeSize) setTradeSize(settings.tradeSize.toString());
        if (settings.maxOpenTrades) setMaxOpenTrades(settings.maxOpenTrades.toString());
        if (settings.riskLevel) setRiskLevel(settings.riskLevel);
        if (settings.notificationsEnabled !== undefined) setNotificationsEnabled(settings.notificationsEnabled);
        if (settings.emailAlerts !== undefined) setEmailAlerts(settings.emailAlerts);
        if (settings.pushNotifications !== undefined) setPushNotifications(settings.pushNotifications);
        if (settings.telegramNotifications !== undefined) setTelegramNotifications(settings.telegramNotifications);
        if (settings.telegramChatId) setTelegramChatId(settings.telegramChatId);
        if (settings.theme) setTheme(settings.theme);
        if (settings.signalInterval) setSignalInterval(settings.signalInterval);
        if (settings.autoGenerateSignals !== undefined) setAutoGenerateSignals(settings.autoGenerateSignals);
        if (settings.selectedPairs) {
          // Parse selectedPairs if it's a JSON string, otherwise use as array
          const pairs = typeof settings.selectedPairs === 'string' 
            ? JSON.parse(settings.selectedPairs) 
            : settings.selectedPairs;
          setSelectedPairs(pairs);
        }
      }
    } catch (error) {
      console.error('Error loading settings:', error);
      setSaveMessage('Error loading settings');
    } finally {
      setIsLoading(false);
    }
  };
  
  // Load default trading pairs
  const loadDefaultPairs = async () => {
    try {
      const response = await apiClient.get('/signals/default-pairs');
      if (response.data && response.data.tradingPairs) {
        setDefaultPairs(response.data.tradingPairs);
        // Initialize selectedPairs with all pairs if none are selected yet
        if (selectedPairs.length === 0) {
          setSelectedPairs(response.data.tradingPairs);
        }
      }
    } catch (error) {
      console.error('Error loading default trading pairs:', error);
    }
  };
  
  // Manual signal generation
  const handleGenerateSignals = async () => {
    setIsGeneratingSignals(true);
    setLastGenerationResult(null);
    
    try {
      let response;
      
      if (selectedPairs.length === 0) {
        // If no pairs selected, show error
        setLastGenerationResult({
          success: false,
          message: 'No trading pairs selected. Please select at least one pair from the Default Pairs list.'
        });
        return;
      }
      
      if (selectedPairs.length === defaultPairs.length && 
          selectedPairs.every(pair => defaultPairs.includes(pair))) {
        // If all pairs are selected, use the general endpoint
        response = await apiClient.post(`/signals/generate-bot-signals?interval=${signalInterval}`);
      } else {
        // If specific pairs are selected, use the pairs-specific endpoint
        response = await apiClient.post('/signals/generate-bot-signals/custom', {
          symbols: selectedPairs,
          interval: signalInterval
        });
      }
      
      setLastGenerationResult(response.data);
    } catch (error: any) {
      console.error('Error generating signals:', error);
      setLastGenerationResult({
        success: false,
        message: 'Error generating signals: ' + (error.message || 'Unknown error')
      });
    } finally {
      setIsGeneratingSignals(false);
    }
  };
  
  // Handle saving settings
  const handleSaveSettings = async () => {
    setIsSaving(true);
    setSaveMessage('');
    
    try {
      // Collect all settings into an object
      const settingsToSave = {
        apiKey,
        apiSecret,
        tradeSize: parseFloat(tradeSize),
        maxOpenTrades: parseInt(maxOpenTrades),
        riskLevel,
        notificationsEnabled,
        emailAlerts,
        pushNotifications,
        telegramNotifications,
        telegramChatId,
        theme,
        signalInterval,
        autoGenerateSignals,
        selectedPairs: JSON.stringify(selectedPairs)
      };
      
      const response = await apiClient.post('/settings', settingsToSave);
      
      if (response.data && response.data.success) {
        setSaveMessage('Settings saved successfully!');
        setTimeout(() => setSaveMessage(''), 3000);
      } else {
        setSaveMessage('Failed to save settings: ' + (response.data?.message || 'Unknown error'));
      }
    } catch (error: any) {
      console.error('Error saving settings:', error);
      setSaveMessage('Error saving settings: ' + (error.message || 'Unknown error'));
    } finally {
      setIsSaving(false);
    }
  };
  
  // Handle reset to defaults
  const handleResetDefaults = async () => {
    if (window.confirm('Are you sure you want to reset all settings to defaults?')) {
      // Reset all settings to default values
      setTradeSize('100');
      setMaxOpenTrades('5');
      setRiskLevel('medium');
      setNotificationsEnabled(true);
      setEmailAlerts(true);
      setPushNotifications(false);
      setTelegramNotifications(false);
      setTelegramChatId('');
      setTheme('retro-dark');
      setSignalInterval('1h');
      setAutoGenerateSignals(false);
      setSelectedPairs(defaultPairs); // Reset to all pairs selected
      
      // Save the reset settings
      await handleSaveSettings();
    }
  };
  
  // Handle individual pair selection
  const handlePairToggle = (pair: string) => {
    setSelectedPairs(prev => {
      if (prev.includes(pair)) {
        return prev.filter(p => p !== pair);
      } else {
        return [...prev, pair];
      }
    });
  };
  
  // Handle select all pairs
  const handleSelectAllPairs = () => {
    setSelectedPairs([...defaultPairs]);
  };
  
  // Handle deselect all pairs
  const handleDeselectAllPairs = () => {
    setSelectedPairs([]);
  };
  
  // Generate mock API keys
  const generateMockApiKey = () => {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    for (let i = 0; i < 32; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
  };

  return (
    <div className="p-4 retro-text">
      <h1 className="text-2xl font-bold mb-6 retro-header">SYSTEM SETTINGS</h1>
      
      <div className="flex flex-col md:flex-row gap-4">
        {/* Settings Navigation */}
        <div className="retro-card p-3 md:w-64">
          <ul>
            {sections.map(section => (
              <li key={section.id} className="mb-1">
                <button
                  className={`w-full text-left p-2 hover:bg-retro-green hover:bg-opacity-20 transition-colors ${
                    activeSection === section.id ? 'bg-retro-green bg-opacity-20 border-l-2 border-retro-green pl-3' : 'pl-4'
                  }`}
                  onClick={() => setActiveSection(section.id)}
                >
                  {section.title}
                </button>
              </li>
            ))}
          </ul>
        </div>
        
        {/* Settings Content */}
        <div className="retro-card p-4 flex-grow">
          {/* General Settings */}
          {activeSection === 'general' && (
            <div>
              <h2 className="text-lg font-bold mb-4">GENERAL SETTINGS</h2>
              
              <div className="mb-4">
                <label className="block mb-1">SYSTEM LANGUAGE:</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                >
                  <option value="en">English</option>
                  <option value="es">Spanish</option>
                  <option value="fr">French</option>
                  <option value="de">German</option>
                  <option value="ja">Japanese</option>
                </select>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">TIMEZONE:</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                >
                  <option value="UTC">UTC</option>
                  <option value="EST">EST (UTC-5)</option>
                  <option value="CST">CST (UTC-6)</option>
                  <option value="PST">PST (UTC-8)</option>
                  <option value="JST">JST (UTC+9)</option>
                </select>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">AUTO-START BOTS ON SYSTEM STARTUP:</label>
                <div className="flex items-center mt-2">
                  <label className="inline-flex items-center mr-4">
                    <input type="radio" name="autostart" value="yes" className="form-radio text-retro-green" />
                    <span className="ml-2">YES</span>
                  </label>
                  <label className="inline-flex items-center">
                    <input type="radio" name="autostart" value="no" className="form-radio text-retro-green" defaultChecked />
                    <span className="ml-2">NO</span>
                  </label>
                </div>
              </div>
            </div>
          )}
          
          {/* Exchange API Settings */}
          {activeSection === 'exchange' && (
            <div>
              <h2 className="text-lg font-bold mb-4">EXCHANGE API SETTINGS</h2>
              
              <div className="mb-4">
                <label className="block mb-1">EXCHANGE:</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                >
                  <option value="mexc">MEXC</option>
                  <option value="binance">Binance</option>
                  <option value="kucoin">KuCoin</option>
                  <option value="gate">Gate.io</option>
                </select>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">API KEY:</label>
                <div className="flex">
                  <RetroInput 
                    type="password" 
                    value={apiKey} 
                    onChange={(e) => setApiKey(e.target.value)}
                    placeholder="Enter your API key" 
                    className="flex-grow"
                  />
                  <RetroButton 
                    className="ml-2"
                    onClick={() => setApiKey(generateMockApiKey())}
                  >
                    GENERATE
                  </RetroButton>
                </div>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">API SECRET:</label>
                <div className="flex">
                  <RetroInput 
                    type="password" 
                    value={apiSecret} 
                    onChange={(e) => setApiSecret(e.target.value)}
                    placeholder="Enter your API secret" 
                    className="flex-grow"
                  />
                  <RetroButton 
                    className="ml-2"
                    onClick={() => setApiSecret(generateMockApiKey())}
                  >
                    GENERATE
                  </RetroButton>
                </div>
              </div>
              
              <div className="mt-4 p-3 retro-terminal text-xs">
                <p className="mb-1">// IMPORTANT: API keys should have TRADE permissions only</p>
                <p className="mb-1">// Do NOT enable withdrawal permissions</p>
                <p>// Store your keys securely and never share them</p>
              </div>
              
              <div className="flex mt-4">
                <RetroButton
                  onClick={() => alert('API connection test would run here')}
                >
                  TEST CONNECTION
                </RetroButton>
              </div>
            </div>
          )}
          
          {/* Trading Parameters */}
          {activeSection === 'trading' && (
            <div>
              <h2 className="text-lg font-bold mb-4">TRADING PARAMETERS</h2>
              
              <div className="mb-4">
                <label className="block mb-1">DEFAULT TRADE SIZE (USDT):</label>
                <RetroInput 
                  type="number" 
                  value={tradeSize} 
                  onChange={(e) => setTradeSize(e.target.value)}
                  min="10"
                  step="10"
                />
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">MAXIMUM OPEN TRADES:</label>
                <RetroInput 
                  type="number" 
                  value={maxOpenTrades} 
                  onChange={(e) => setMaxOpenTrades(e.target.value)}
                  min="1"
                  max="20"
                />
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">RISK LEVEL:</label>
                <div className="flex items-center mt-2">
                  <label className="inline-flex items-center mr-4">
                    <input 
                      type="radio" 
                      name="risk" 
                      value="low" 
                      checked={riskLevel === 'low'} 
                      onChange={() => setRiskLevel('low')} 
                      className="form-radio text-retro-green" 
                    />
                    <span className="ml-2">LOW</span>
                  </label>
                  <label className="inline-flex items-center mr-4">
                    <input 
                      type="radio" 
                      name="risk" 
                      value="medium" 
                      checked={riskLevel === 'medium'} 
                      onChange={() => setRiskLevel('medium')} 
                      className="form-radio text-retro-green" 
                    />
                    <span className="ml-2">MEDIUM</span>
                  </label>
                  <label className="inline-flex items-center">
                    <input 
                      type="radio" 
                      name="risk" 
                      value="high" 
                      checked={riskLevel === 'high'} 
                      onChange={() => setRiskLevel('high')} 
                      className="form-radio text-retro-green" 
                    />
                    <span className="ml-2">HIGH</span>
                  </label>
                </div>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">DEFAULT STOP LOSS (%):</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                >
                  <option value="5">5%</option>
                  <option value="10">10%</option>
                  <option value="15">15%</option>
                  <option value="20">20%</option>
                </select>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">DEFAULT TAKE PROFIT (%):</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                >
                  <option value="10">10%</option>
                  <option value="20">20%</option>
                  <option value="30">30%</option>
                  <option value="50">50%</option>
                </select>
              </div>
            </div>
          )}
          
          {/* Bot Signals */}
          {activeSection === 'bot-signals' && (
            <div>
              <h2 className="text-lg font-bold mb-4">BOT SIGNALS</h2>
              
              <div className="mb-4">
                <label className="block mb-1">SIGNAL INTERVAL:</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                  value={signalInterval}
                  onChange={(e) => setSignalInterval(e.target.value)}
                >
                  <option value="1h">1 Hour</option>
                  <option value="4h">4 Hours</option>
                  <option value="12h">12 Hours</option>
                  <option value="24h">24 Hours</option>
                </select>
              </div>
              
              <div className="mb-4">
                <label className="flex items-center">
                  <input 
                    type="checkbox" 
                    checked={autoGenerateSignals} 
                    onChange={() => setAutoGenerateSignals(!autoGenerateSignals)} 
                    className="form-checkbox text-retro-green" 
                  />
                  <span className="ml-2">AUTO-GENERATE SIGNALS</span>
                </label>
              </div>
              
              <div className="mb-4">
                <div className="flex justify-between items-center mb-2">
                  <label className="block">DEFAULT PAIRS:</label>
                  <div className="flex gap-2">
                    <RetroButton
                      onClick={handleSelectAllPairs}
                      variant="secondary"
                      className="text-xs px-2 py-1"
                    >
                      SELECT ALL
                    </RetroButton>
                    <RetroButton
                      onClick={handleDeselectAllPairs}
                      variant="secondary"
                      className="text-xs px-2 py-1"
                    >
                      DESELECT ALL
                    </RetroButton>
                  </div>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2 pl-4 max-h-32 overflow-y-auto border border-retro-green border-opacity-30 p-2">
                  {defaultPairs.map((pair, index) => (
                    <label key={index} className="flex items-center">
                      <input 
                        type="checkbox" 
                        checked={selectedPairs.includes(pair)}
                        onChange={() => handlePairToggle(pair)}
                        className="form-checkbox text-retro-green" 
                      />
                      <span className="ml-2">{pair}</span>
                    </label>
                  ))}
                </div>
                <div className="text-xs retro-text mt-1">
                  {selectedPairs.length} of {defaultPairs.length} pairs selected
                </div>
              </div>
              
              <div className="mb-4">
                <label className="block mb-2">MANUAL SIGNAL GENERATION:</label>
                <div className="flex gap-2">
                  <RetroButton
                    onClick={handleGenerateSignals}
                    disabled={isGeneratingSignals || selectedPairs.length === 0}
                    className="flex-shrink-0"
                  >
                    {isGeneratingSignals ? 'GENERATING...' : 'GENERATE SIGNALS NOW'}
                  </RetroButton>
                  <div className="text-xs retro-text flex items-center ml-2">
                    Scans {selectedPairs.length} selected pairs for trading opportunities
                    {selectedPairs.length === 0 && (
                      <span className="text-retro-red ml-2">(No pairs selected)</span>
                    )}
                  </div>
                </div>
              </div>
              
              {lastGenerationResult && (
                <div className="mb-4 p-3 retro-terminal text-xs">
                  <p className="mb-1">// Last Generation Result:</p>
                  {lastGenerationResult.success ? (
                    <>
                      <p className="text-retro-green mb-1">✓ SUCCESS: {lastGenerationResult.message}</p>
                      <p className="mb-1">• Generated: {lastGenerationResult.signalsGenerated} signals</p>
                      <p className="mb-1">• Skipped: {lastGenerationResult.signalsSkipped} signals</p>
                      <p>• Total Processed: {lastGenerationResult.totalProcessed} pairs</p>
                    </>
                  ) : (
                    <p className="text-retro-red">✗ ERROR: {lastGenerationResult.message}</p>
                  )}
                </div>
              )}
              
              <div className="mt-4 p-3 retro-terminal text-xs">
                <p className="mb-1">// SIGNAL GENERATION INFO:</p>
                <p className="mb-1">// • Manual generation scans only selected pairs</p>
                <p className="mb-1">// • Auto-generation is disabled - use manual control instead</p>
                <p className="mb-1">// • Signals require multi-factor confirmation before approval</p>
                <p className="mb-1">// • Generated signals appear in Bot Signals tab for review</p>
                <p>// • Select/deselect pairs to customize which symbols to scan</p>
              </div>
            </div>
          )}
          
          {/* Notifications Settings */}
          {activeSection === 'notifications' && (
            <div>
              <h2 className="text-lg font-bold mb-4">NOTIFICATION SETTINGS</h2>
              
              <div className="mb-4">
                <label className="flex items-center">
                  <input 
                    type="checkbox" 
                    checked={notificationsEnabled} 
                    onChange={() => setNotificationsEnabled(!notificationsEnabled)} 
                    className="form-checkbox text-retro-green" 
                  />
                  <span className="ml-2">ENABLE NOTIFICATIONS</span>
                </label>
              </div>
              
              <div className="pl-6 mb-4">
                <label className="flex items-center mb-2">
                  <input 
                    type="checkbox" 
                    checked={emailAlerts} 
                    onChange={() => setEmailAlerts(!emailAlerts)} 
                    disabled={!notificationsEnabled}
                    className="form-checkbox text-retro-green" 
                  />
                  <span className="ml-2">EMAIL ALERTS</span>
                </label>
                
                {emailAlerts && notificationsEnabled && (
                  <div className="ml-6 mb-3">
                    <label className="block mb-1 text-sm">EMAIL ADDRESS:</label>
                    <RetroInput 
                      type="email" 
                      placeholder="your@email.com" 
                    />
                  </div>
                )}
                
                <label className="flex items-center mb-2">
                  <input 
                    type="checkbox" 
                    checked={pushNotifications} 
                    onChange={() => setPushNotifications(!pushNotifications)} 
                    disabled={!notificationsEnabled}
                    className="form-checkbox text-retro-green" 
                  />
                  <span className="ml-2">BROWSER PUSH NOTIFICATIONS</span>
                </label>
                
                <label className="flex items-center">
                  <input 
                    type="checkbox" 
                    checked={telegramNotifications} 
                    onChange={() => setTelegramNotifications(!telegramNotifications)} 
                    disabled={!notificationsEnabled}
                    className="form-checkbox text-retro-green" 
                  />
                  <span className="ml-2">TELEGRAM NOTIFICATIONS</span>
                </label>
                
                {telegramNotifications && notificationsEnabled && (
                  <div className="ml-6 mb-3 mt-2">
                    <label className="block mb-1 text-sm">TELEGRAM CHAT ID:</label>
                    <RetroInput 
                      value={telegramChatId}
                      onChange={(e) => setTelegramChatId(e.target.value)}
                      placeholder="Enter Telegram chat ID" 
                    />
                  </div>
                )}
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">NOTIFICATION EVENTS:</label>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2 pl-4">
                  <label className="flex items-center">
                    <input type="checkbox" defaultChecked className="form-checkbox text-retro-green" />
                    <span className="ml-2">TRADE EXECUTED</span>
                  </label>
                  <label className="flex items-center">
                    <input type="checkbox" defaultChecked className="form-checkbox text-retro-green" />
                    <span className="ml-2">TRADE CLOSED</span>
                  </label>
                  <label className="flex items-center">
                    <input type="checkbox" defaultChecked className="form-checkbox text-retro-green" />
                    <span className="ml-2">STOP LOSS TRIGGERED</span>
                  </label>
                  <label className="flex items-center">
                    <input type="checkbox" defaultChecked className="form-checkbox text-retro-green" />
                    <span className="ml-2">TAKE PROFIT TRIGGERED</span>
                  </label>
                  <label className="flex items-center">
                    <input type="checkbox" defaultChecked className="form-checkbox text-retro-green" />
                    <span className="ml-2">BOT STARTED/STOPPED</span>
                  </label>
                  <label className="flex items-center">
                    <input type="checkbox" defaultChecked className="form-checkbox text-retro-green" />
                    <span className="ml-2">ERROR ALERTS</span>
                  </label>
                </div>
              </div>
            </div>
          )}
          
          {/* Appearance Settings */}
          {activeSection === 'appearance' && (
            <div>
              <h2 className="text-lg font-bold mb-4">APPEARANCE SETTINGS</h2>
              
              <div className="mb-4">
                <label className="block mb-1">THEME:</label>
                <div className="flex flex-wrap gap-2 mt-2">
                  <div 
                    className={`p-4 bg-retro-dark border-2 cursor-pointer ${theme === 'retro-dark' ? 'border-retro-green' : 'border-gray-600'}`}
                    onClick={() => setTheme('retro-dark')}
                  >
                    <div className="text-retro-green text-center">RETRO DARK</div>
                  </div>
                  <div 
                    className={`p-4 bg-gray-800 border-2 cursor-pointer ${theme === 'blue-dark' ? 'border-blue-400' : 'border-gray-600'}`}
                    onClick={() => setTheme('blue-dark')}
                  >
                    <div className="text-blue-400 text-center">BLUE DARK</div>
                  </div>
                  <div 
                    className={`p-4 bg-amber-950 border-2 cursor-pointer ${theme === 'amber-dark' ? 'border-amber-400' : 'border-gray-600'}`}
                    onClick={() => setTheme('amber-dark')}
                  >
                    <div className="text-amber-400 text-center">AMBER DARK</div>
                  </div>
                </div>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">FONT SIZE:</label>
                <select 
                  className="bg-retro-dark border-2 border-retro-green text-retro-green p-2 w-full"
                >
                  <option value="small">SMALL</option>
                  <option value="medium" selected>MEDIUM</option>
                  <option value="large">LARGE</option>
                </select>
              </div>
              
              <div className="mb-4">
                <label className="block mb-1">ANIMATION EFFECTS:</label>
                <div className="flex items-center mt-2">
                  <label className="inline-flex items-center mr-4">
                    <input type="radio" name="animations" value="on" className="form-radio text-retro-green" defaultChecked />
                    <span className="ml-2">ON</span>
                  </label>
                  <label className="inline-flex items-center">
                    <input type="radio" name="animations" value="off" className="form-radio text-retro-green" />
                    <span className="ml-2">OFF</span>
                  </label>
                </div>
              </div>
            </div>
          )}
          
          {/* Backups Settings */}
          {activeSection === 'backups' && (
            <div>
              <h2 className="text-lg font-bold mb-4">BACKUPS & RECOVERY</h2>
              
              <div className="mb-6">
                <p className="mb-4">Create and manage backups of your bot configurations and trading history.</p>
                
                <div className="flex space-x-2 mb-4">
                  <RetroButton>
                    CREATE BACKUP
                  </RetroButton>
                  <RetroButton variant="secondary">
                    RESTORE BACKUP
                  </RetroButton>
                </div>
                
                <div className="retro-terminal p-3 text-xs">
                  <p className="mb-1">// Last backup: NEVER</p>
                  <p>// Backup location: ./backups/</p>
                </div>
              </div>
              
              <h3 className="text-md font-bold mb-2 mt-6">DATA MANAGEMENT</h3>
              <div className="mb-4">
                <div className="flex space-x-2">
                  <RetroButton variant="danger">
                    CLEAR TRADE HISTORY
                  </RetroButton>
                  <RetroButton variant="danger">
                    RESET ALL SETTINGS
                  </RetroButton>
                </div>
              </div>
            </div>
          )}
          
          {/* Footer with save/reset buttons */}
          <div className="flex flex-col mt-6 pt-4 border-t border-retro-green">
            {/* Save Message */}
            {saveMessage && (
              <div className={`mb-4 p-3 text-center ${
                saveMessage.includes('Error') || saveMessage.includes('Failed') 
                  ? 'text-red-400 bg-red-900 bg-opacity-20 border border-red-400' 
                  : 'text-retro-green bg-retro-green bg-opacity-20 border border-retro-green'
              }`}>
                {saveMessage}
              </div>
            )}
            
            {/* Loading Indicator */}
            {(isLoading || isSaving) && (
              <div className="mb-4 p-3 text-center retro-terminal">
                <p className="mb-1">
                  {isLoading ? '// LOADING SETTINGS...' : '// SAVING SETTINGS...'}
                </p>
                <div className="flex justify-center">
                  <div className="retro-green animate-pulse">{'>'} PROCESSING {'<'}</div>
                </div>
              </div>
            )}
            
            {/* Action Buttons */}
            <div className="flex justify-between">
              <RetroButton
                variant="secondary"
                onClick={handleResetDefaults}
                disabled={isSaving || isLoading}
              >
                {isSaving ? 'SAVING...' : 'RESET TO DEFAULTS'}
              </RetroButton>
              <RetroButton
                onClick={handleSaveSettings}
                disabled={isSaving || isLoading}
              >
                {isSaving ? 'SAVING...' : 'SAVE SETTINGS'}
              </RetroButton>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Settings;

