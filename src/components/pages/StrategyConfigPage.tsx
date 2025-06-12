import React, { useState, useEffect } from 'react';
import { RetroButton, RetroInput, RetroSelect, RetroCard } from '../common/CommonComponents';
import axios from 'axios';

interface Strategy {
  id: string;
  name: string;
  description: string;
  configuration: Record<string, any>;
}

const StrategyConfigPage: React.FC = () => {
  const [strategies, setStrategies] = useState<Strategy[]>([]);
  const [selectedStrategy, setSelectedStrategy] = useState<string>('');
  const [config, setConfig] = useState<Record<string, any>>({});
  const [message, setMessage] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(false);

  // Fetch available strategies on component mount
  useEffect(() => {
    fetchStrategies();
  }, []);

  // Fetch available strategies from the backend
  const fetchStrategies = async () => {
    setIsLoading(true);
    
    try {
      const response = await axios.get('/api/backtest/strategies');
      
      if (response.data.success) {
        const strategiesData = response.data.strategies;
        const formattedStrategies = Object.keys(strategiesData).map(id => ({
          id,
          name: strategiesData[id].name,
          description: strategiesData[id].description,
          configuration: strategiesData[id].configuration
        }));
        
        setStrategies(formattedStrategies);
        
        if (formattedStrategies.length > 0 && !selectedStrategy) {
          setSelectedStrategy(formattedStrategies[0].id);
          setConfig(formattedStrategies[0].configuration);
        }
      } else {
        setMessage('Failed to load strategies');
      }
    } catch (error) {
      console.error('Error fetching strategies:', error);
      setMessage('Error loading strategies');
    } finally {
      setIsLoading(false);
    }
  };

  // Handle strategy selection change
  const handleStrategyChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const strategyId = e.target.value;
    setSelectedStrategy(strategyId);
    
    const selectedStrategyObj = strategies.find(s => s.id === strategyId);
    if (selectedStrategyObj) {
      setConfig(selectedStrategyObj.configuration);
    }
  };

  // Handle configuration parameter change
  const handleConfigChange = (key: string, value: any) => {
    setConfig(prevConfig => ({
      ...prevConfig,
      [key]: parseConfigValue(key, value)
    }));
  };

  // Parse configuration value based on its type
  const parseConfigValue = (key: string, value: string): any => {
    const currentConfig = strategies.find(s => s.id === selectedStrategy)?.configuration || {};
    
    // Determine value type based on the current config
    if (typeof currentConfig[key] === 'number') {
      return parseFloat(value);
    } else if (typeof currentConfig[key] === 'boolean') {
      return value === 'true';
    }
    
    return value;
  };

  // Save strategy configuration
  const saveConfig = async () => {
    setIsLoading(true);
    setMessage('');
    
    try {
      const response = await axios.put(`/api/backtest/strategies/${selectedStrategy}/config`, config);
      
      if (response.data.success) {
        setMessage('Configuration saved successfully!');
        
        // Update local strategies with new config
        setStrategies(prevStrategies => 
          prevStrategies.map(strategy => 
            strategy.id === selectedStrategy 
              ? { ...strategy, configuration: response.data.configuration } 
              : strategy
          )
        );
      } else {
        setMessage(`Failed to save: ${response.data.message}`);
      }
    } catch (error) {
      console.error('Error saving configuration:', error);
      setMessage('Error saving configuration');
    } finally {
      setIsLoading(false);
    }
  };

  // Run a backtest with current strategy
  const runBacktest = () => {
    // Navigate to backtest page with current strategy selected
    // This would be implemented with react-router or similar
    console.log(`Run backtest with strategy: ${selectedStrategy}`);
  };

  // Render configuration form based on selected strategy
  const renderConfigForm = () => {
    if (!selectedStrategy || isLoading) {
      return <p>Select a strategy to configure</p>;
    }
    
    return (
      <div className="space-y-4">
        {Object.entries(config).map(([key, value]) => (
          <div key={key} className="grid grid-cols-2 gap-4 items-center">
            <label className="text-retro-green capitalize">
              {key.replace(/([A-Z])/g, ' $1').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()}:
            </label>
            
            {renderConfigInput(key, value)}
          </div>
        ))}
        
        <div className="flex mt-6 space-x-4">
          <RetroButton 
            variant="primary" 
            onClick={saveConfig}
            disabled={isLoading}
          >
            {isLoading ? 'Saving...' : 'Save Configuration'}
          </RetroButton>
          
          <RetroButton 
            variant="secondary" 
            onClick={runBacktest}
          >
            Run Backtest
          </RetroButton>
        </div>
        
        {message && (
          <div className={`mt-4 p-2 ${message.includes('success') ? 'text-retro-green' : 'text-retro-red'}`}>
            {message}
          </div>
        )}
      </div>
    );
  };

  // Render appropriate input type based on config value type
  const renderConfigInput = (key: string, value: any) => {
    if (typeof value === 'boolean') {
      return (
        <RetroSelect
          options={[
            { value: 'true', label: 'True' },
            { value: 'false', label: 'False' }
          ]}
          value={value.toString()}
          onChange={(e) => handleConfigChange(key, e.target.value)}
        />
      );
    } else if (typeof value === 'number') {
      return (
        <RetroInput
          type="number"
          value={value}
          onChange={(e) => handleConfigChange(key, e.target.value)}
          step={value < 1 ? 0.01 : 1}
        />
      );
    } else {
      return (
        <RetroInput
          type="text"
          value={value}
          onChange={(e) => handleConfigChange(key, e.target.value)}
        />
      );
    }
  };

  return (
    <div className="container mx-auto p-4">
      <h1 className="text-2xl font-bold text-retro-green mb-6">Trading Strategy Configuration</h1>
      
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <RetroCard title="Available Strategies">
          <div className="space-y-4">
            <div className="mb-4">
              <label className="block text-retro-green mb-2">Select Strategy:</label>
              <RetroSelect
                options={strategies.map(s => ({ value: s.id, label: s.name }))}
                value={selectedStrategy}
                onChange={handleStrategyChange}
                disabled={isLoading}
              />
            </div>
            
            {selectedStrategy && (
              <div>
                <h3 className="text-lg text-retro-green mb-2">Description:</h3>
                <p className="text-retro-green-light">
                  {strategies.find(s => s.id === selectedStrategy)?.description || 'No description available'}
                </p>
              </div>
            )}
          </div>
        </RetroCard>
        
        <RetroCard title="Strategy Configuration">
          {renderConfigForm()}
        </RetroCard>
      </div>
      
      {/* Backtest Results Section (Preview) */}
      <RetroCard title="Recent Backtest Results" className="mt-6">
        <p className="text-retro-green">
          Run a backtest with your configured strategy to see results here.
        </p>
      </RetroCard>
    </div>
  );
};

export default StrategyConfigPage; 