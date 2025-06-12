import React, { useState, useEffect, useCallback } from 'react';
import { RetroButton, RetroInput } from '../common/CommonComponents';
import apiClient from '../../apiClient';

// Helper functions to wrap API calls
const getNews = (coins?: string, limit: number = 10) => 
  apiClient.get('/news/market-headlines', {
    params: { coins, limit }
  });

const getSentiment = () => 
  apiClient.get('/news/sentiment');

const getEventsCalendar = () => 
  apiClient.get('/news/events-calendar');

const getOnchainMetrics = (coin: string = 'BTC') => 
  apiClient.get('/news/onchain-metrics', {
    params: { coin }
  });

const getCorrelationMatrix = () => 
  apiClient.get('/news/correlation');

const getFearGreedIndex = () => 
  apiClient.get('/news/fear-greed-index');

// New interfaces for different types of data
interface NewsItem {
  id: string;
  title: string;
  summary: string;
  source: string;
  url: string;
  timestamp: number;
  sentiment: 'positive' | 'negative' | 'neutral';
  impactLevel: 'high' | 'medium' | 'low';
  relatedCoins: string[];
}

interface SentimentData {
  twitter: {
    score: number;
    change: number;
    volume: number;
  };
  reddit: {
    score: number;
    change: number;
    volume: number;
  };
  news: {
    score: number;
    change: number;
    volume: number;
  };
  overall: {
    score: number;
    label: string;
  };
}

interface CalendarEvent {
  id: string;
  title: string;
  description: string;
  date: number;
  type: string;
  relatedCoins: string[];
  source: string;
  impact: number;
}

interface OnchainMetrics {
  transactions: {
    daily: number;
    change: number;
    avgFee: number;
    avgValue: number;
  };
  mining?: {
    hashrate: string;
    difficulty: string;
  };
  staking?: {
    validators: number;
    staked: string;
  };
  whales: {
    largeTransactions: number;
    concentration: string;
    recentMovement: string;
  };
  network: {
    activeAddresses: number;
    newAddresses: number;
    addressGrowth: string;
  };
}

interface FearGreedIndex {
  value: number;
  classification: string;
  previousDay: number;
  previousWeek: number;
  history: {
    date: number;
    value: number;
  }[];
}

// Tabs for different news sections
type NewsTab = 'headlines' | 'sentiment' | 'calendar' | 'onchain' | 'correlation' | 'feargreed';

const MarketNews: React.FC = () => {
  // State for news items
  const [news, setNews] = useState<NewsItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [sentimentFilter, setSentimentFilter] = useState<string | null>(null);
  const [selectedCoin, setSelectedCoin] = useState<string | null>(null);
  
  // State for active tab
  const [activeTab, setActiveTab] = useState<NewsTab>('headlines');

  // Top coins for filtering - updated to match trading pairs from Charts component
  const topCoins = ['BTC', 'ETH', 'DOGE', 'NXPC', 'MOVR', 'KSM'];

  // Additional states for other features
  const [sentimentData, setSentimentData] = useState<Record<string, SentimentData>>({});
  const [calendarEvents, setCalendarEvents] = useState<CalendarEvent[]>([]);
  const [onchainMetrics, setOnchainMetrics] = useState<OnchainMetrics | null>(null);
  const [selectedMetricCoin, setSelectedMetricCoin] = useState('BTC');
  const [correlationMatrix, setCorrelationMatrix] = useState<Record<string, Record<string, number>>>({});
  const [fearGreedIndex, setFearGreedIndex] = useState<FearGreedIndex | null>(null);

  // Fetch functions for each data type - defined with useCallback to prevent recreation on each render
  const fetchHeadlines = useCallback(async () => {
    setIsLoading(true);
    try {
      // Call the real API
      const response = await getNews(selectedCoin || undefined);
      
      if (response.data && response.data.Data) {
        // Process CryptoCompare API response
        const newsItems: NewsItem[] = response.data.Data.map((item: any) => ({
          id: item.id || `news-${item.published_on}`,
          title: item.title,
          summary: item.body,
          source: item.source_info?.name || item.source,
          url: item.url || item.guid,
          timestamp: item.published_on * 1000, // Convert to milliseconds
          sentiment: determineSentiment(item.title, item.body),
          impactLevel: determineImpactLevel(item.categories),
          relatedCoins: extractRelatedCoins(item.categories, item.tags)
        }));
        
        setNews(newsItems);
      } else {
        // Fallback to mock data
        console.warn("API returned unexpected format, using mock data");
        setNews(generateMockNews());
      }
    } catch (error) {
      console.error('Error fetching headlines:', error);
      setNews(generateMockNews());
    } finally {
      setIsLoading(false);
    }
  }, [selectedCoin]);

  const fetchSentimentData = useCallback(async () => {
    setIsLoading(true);
    try {
      // Call the real API
      const response = await getSentiment();
      
      if (response.data && response.data.data) {
        setSentimentData(response.data.data);
      } else {
        // Fallback to mock data
        console.warn("API returned unexpected format, using mock data");
        const mockData: Record<string, SentimentData> = {};
        topCoins.forEach(coin => {
          mockData[coin] = generateMockSentiment();
        });
        setSentimentData(mockData);
      }
    } catch (error) {
      console.error('Error fetching sentiment data:', error);
      // Fallback to mock data
      const mockData: Record<string, SentimentData> = {};
      topCoins.forEach(coin => {
        mockData[coin] = generateMockSentiment();
      });
      setSentimentData(mockData);
    } finally {
      setIsLoading(false);
    }
  }, [topCoins]);

  const fetchEventsCalendar = useCallback(async () => {
    setIsLoading(true);
    try {
      // Call the real API
      const response = await getEventsCalendar();
      
      if (response.data && response.data.events) {
        setCalendarEvents(response.data.events);
      } else {
        // Fallback to mock data
        console.warn("API returned unexpected format, using mock data");
        setCalendarEvents(generateMockEvents());
      }
    } catch (error) {
      console.error('Error fetching events calendar:', error);
      setCalendarEvents(generateMockEvents());
    } finally {
      setIsLoading(false);
    }
  }, []);

  const fetchOnchainMetrics = useCallback(async (coin: string) => {
    setIsLoading(true);
    try {
      // Call the real API
      const response = await getOnchainMetrics(coin);
      
      if (response.data && response.data.data) {
        setOnchainMetrics(response.data.data);
      } else {
        // Fallback to mock data
        console.warn("API returned unexpected format, using mock data");
        setOnchainMetrics(generateMockOnchainMetrics(coin));
      }
    } catch (error) {
      console.error('Error fetching onchain metrics:', error);
      setOnchainMetrics(generateMockOnchainMetrics(coin));
    } finally {
      setIsLoading(false);
    }
  }, []);

  const fetchCorrelationMatrix = useCallback(async () => {
    setIsLoading(true);
    try {
      // Call the real API
      const response = await getCorrelationMatrix();
      
      if (response.data && response.data.data) {
        setCorrelationMatrix(response.data.data);
      } else {
        // Fallback to mock data
        console.warn("API returned unexpected format, using mock data");
        setCorrelationMatrix(generateMockCorrelationMatrix());
      }
    } catch (error) {
      console.error('Error fetching correlation matrix:', error);
      setCorrelationMatrix(generateMockCorrelationMatrix());
    } finally {
      setIsLoading(false);
    }
  }, []);

  const fetchFearGreedIndex = useCallback(async () => {
    setIsLoading(true);
    try {
      // Call the real API
      const response = await getFearGreedIndex();
      
      if (response.data && response.data.data) {
        // Process the Fear and Greed data
        const apiData = response.data.data[0]; // Get latest value
        const historyData = response.data.data.slice(1); // Get historical values
        
        // Convert to our FearGreedIndex format
        const fearGreedData: FearGreedIndex = {
          value: parseInt(apiData.value),
          classification: apiData.value_classification,
          previousDay: parseInt(response.data.data[1]?.value || "50"),
          previousWeek: parseInt(response.data.data[7]?.value || "50"),
          history: historyData.map((item: any) => ({
            date: parseInt(item.timestamp) * 1000, // Convert to milliseconds
            value: parseInt(item.value)
          }))
        };
        
        setFearGreedIndex(fearGreedData);
      } else {
        // Fallback to mock data
        console.warn("API returned unexpected format, using mock data");
        setFearGreedIndex(generateMockFearGreedIndex());
      }
    } catch (error) {
      console.error('Error fetching fear & greed index:', error);
      setFearGreedIndex(generateMockFearGreedIndex());
    } finally {
      setIsLoading(false);
    }
  }, []);

  // Load news headlines
  useEffect(() => {
    if (activeTab === 'headlines') {
      fetchHeadlines();
    }
  }, [activeTab, selectedCoin, fetchHeadlines]);

  // Load market sentiment data
  useEffect(() => {
    if (activeTab === 'sentiment') {
      fetchSentimentData();
    }
  }, [activeTab, fetchSentimentData]);

  // Load events calendar
  useEffect(() => {
    if (activeTab === 'calendar') {
      fetchEventsCalendar();
    }
  }, [activeTab, fetchEventsCalendar]);

  // Load onchain metrics
  useEffect(() => {
    if (activeTab === 'onchain') {
      fetchOnchainMetrics(selectedMetricCoin);
    }
  }, [activeTab, selectedMetricCoin, fetchOnchainMetrics]);

  // Load correlation matrix
  useEffect(() => {
    if (activeTab === 'correlation') {
      fetchCorrelationMatrix();
    }
  }, [activeTab, fetchCorrelationMatrix]);

  // Load fear & greed index
  useEffect(() => {
    if (activeTab === 'feargreed') {
      fetchFearGreedIndex();
    }
  }, [activeTab, fetchFearGreedIndex]);

  // Helper functions for processing API data
  
  const determineSentiment = (title: string, body: string): 'positive' | 'negative' | 'neutral' => {
    const text = (title + ' ' + body).toLowerCase();
    
    const positiveWords = ['surge', 'gain', 'rally', 'bull', 'bullish', 'soar', 'rise', 'up', 'high', 'growth', 'approval'];
    const negativeWords = ['crash', 'drop', 'fall', 'bear', 'bearish', 'slump', 'decline', 'down', 'low', 'hack', 'warn'];
    
    let positiveScore = 0;
    let negativeScore = 0;
    
    positiveWords.forEach(word => {
      if (text.includes(word)) positiveScore++;
    });
    
    negativeWords.forEach(word => {
      if (text.includes(word)) negativeScore++;
    });
    
    if (positiveScore > negativeScore) return 'positive';
    if (negativeScore > positiveScore) return 'negative';
    return 'neutral';
  };
  
  const determineImpactLevel = (categories: string): 'high' | 'medium' | 'low' => {
    if (!categories) return 'medium';
    
    const categoriesLower = categories.toLowerCase();
    
    if (categoriesLower.includes('regulation') || 
        categoriesLower.includes('sec') || 
        categoriesLower.includes('hack') || 
        categoriesLower.includes('exchange')) {
      return 'high';
    }
    
    if (categoriesLower.includes('price') || 
        categoriesLower.includes('market') || 
        categoriesLower.includes('investing')) {
      return 'medium';
    }
    
    return 'low';
  };
  
  const extractRelatedCoins = (categories: string, tags: string): string[] => {
    const relatedCoins: string[] = [];
    const allText = (categories + ' ' + (tags || '')).toUpperCase();
    
    // Check for our trading pairs in the categories or tags
    topCoins.forEach(coin => {
      if (allText.includes(coin)) {
        relatedCoins.push(coin);
      }
    });
    
    // If no coins were found, add at least one coin
    if (relatedCoins.length === 0) {
      relatedCoins.push(topCoins[Math.floor(Math.random() * topCoins.length)]);
    }
    
    return relatedCoins;
  };

  // Generate mock data for each data type
  const generateMockNews = () => {
    const sources = ['CryptoDaily', 'CoinDesk', 'CoinTelegraph', 'Bloomberg', 'Reuters', 'CNBC'];
    const sentiments = ['positive', 'negative', 'neutral'] as const;
    const impactLevels = ['high', 'medium', 'low'] as const;
    
    const titles = [
      "Bitcoin surges past $70,000 as institutional adoption increases",
      "SEC approves new crypto ETFs in unexpected move",
      "Major security vulnerability found in smart contract platform",
      "Central banks propose new regulations for stablecoins",
      "Ethereum 2.0 upgrade faces further delays amidst testing issues",
      "Retail traders flock to new meme tokens despite warnings",
      "DeFi protocol hacked, millions in crypto assets stolen",
      "Major exchange announces support for new layer-2 solution",
      "Algorand partners with central bank for CBDC exploration",
      "Mining difficulty reaches all-time high as hashrate grows",
      "NFT market shows signs of recovery after months of decline",
      "New blockchain scaling solution promises 100,000 TPS",
      "Crypto tax reporting requirements tightened in new bill",
      "Institutional investors continue accumulating Bitcoin despite volatility",
      "El Salvador adds more Bitcoin to national reserves",
    ];
    
    const mockNews: NewsItem[] = [];
    
    // Generate random news items
    for (let i = 0; i < titles.length; i++) {
      const sentiment = sentiments[Math.floor(Math.random() * sentiments.length)];
      const impactLevel = impactLevels[Math.floor(Math.random() * impactLevels.length)];
      const source = sources[Math.floor(Math.random() * sources.length)];
      
      // Create related coins with more relevant connections to the titles
      let relatedCoins: string[] = [];
      if (titles[i].toLowerCase().includes('bitcoin') || titles[i].includes('BTC')) {
        relatedCoins.push('BTC');
      }
      if (titles[i].toLowerCase().includes('ethereum') || titles[i].includes('ETH')) {
        relatedCoins.push('ETH');
      }
      if (titles[i].toLowerCase().includes('solana') || titles[i].includes('SOL')) {
        relatedCoins.push('SOL');
      }
      
      // Add some random related coins if none are explicitly mentioned
      if (relatedCoins.length === 0) {
        const numRelatedCoins = Math.floor(Math.random() * 3) + 1;
        const availableCoins = [...topCoins];
        
        for (let j = 0; j < numRelatedCoins; j++) {
          if (availableCoins.length === 0) break;
          const randomIndex = Math.floor(Math.random() * availableCoins.length);
          relatedCoins.push(availableCoins[randomIndex]);
          availableCoins.splice(randomIndex, 1);
        }
      }
      
      mockNews.push({
        id: `news${i + 1}`,
        title: titles[i],
        summary: `This is a summary of the article about ${titles[i].toLowerCase()}. It would contain key points and insights from the full article.`,
        source,
        url: `https://example.com/news/${i}`,
        timestamp: Date.now() - (Math.random() * 86400000 * 7), // Random time in the last week
        sentiment,
        impactLevel,
        relatedCoins
      });
    }
    
    // Sort by timestamp (newest first)
    return mockNews.sort((a, b) => b.timestamp - a.timestamp);
  };

  const generateMockSentiment = (): SentimentData => {
    const random = () => Math.random();
    
    return {
      twitter: {
        score: 0.1 + random() * 0.8,
        change: -0.2 + random() * 0.4,
        volume: 1000 + Math.floor(random() * 9000)
      },
      reddit: {
        score: 0.1 + random() * 0.8,
        change: -0.2 + random() * 0.4,
        volume: 500 + Math.floor(random() * 5000)
      },
      news: {
        score: 0.1 + random() * 0.8,
        change: -0.2 + random() * 0.4,
        volume: 100 + Math.floor(random() * 900)
      },
      overall: {
        score: 0.1 + random() * 0.8,
        label: ['very negative', 'negative', 'neutral', 'positive', 'very positive'][Math.floor(random() * 5)]
      }
    };
  };

  const generateMockEvents = (): CalendarEvent[] => {
    const eventTypes = ['conference', 'listing', 'fork', 'airdrop', 'release', 'regulatory'];
    const events: CalendarEvent[] = [];
    
    for (let i = 0; i < 10; i++) {
      const eventType = eventTypes[Math.floor(Math.random() * eventTypes.length)];
      events.push({
        id: `event-${i}`,
        title: `${eventType.charAt(0).toUpperCase() + eventType.slice(1)} Event ${i+1}`,
        description: `This is a description of the ${eventType} event.`,
        date: Date.now() + (Math.random() * 30 * 86400000), // Random date in next 30 days
        type: eventType,
        relatedCoins: generateRandomCoins(Math.floor(Math.random() * 3) + 1),
        source: `https://example.com/events/${i}`,
        impact: Math.floor(Math.random() * 3) + 1 // 1=low, 2=medium, 3=high
      });
    }
    
    return events.sort((a, b) => a.date - b.date); // Sort by date (earliest first)
  };

  const generateRandomCoins = (count: number): string[] => {
    const result: string[] = [];
    const availableCoins = [...topCoins];
    
    for (let i = 0; i < count; i++) {
      if (availableCoins.length === 0) break;
      const randomIndex = Math.floor(Math.random() * availableCoins.length);
      result.push(availableCoins[randomIndex]);
      availableCoins.splice(randomIndex, 1);
    }
    
    return result;
  };

  const generateMockOnchainMetrics = (coin: string): OnchainMetrics => {
    const random = () => Math.random();
    
    const metrics: OnchainMetrics = {
      transactions: {
        daily: 250000 + Math.floor(random() * 100000),
        change: -10 + Math.floor(random() * 21), // -10% to +10%
        avgFee: coin === 'BTC' ? 2.5 + random() * 2 : 0.1 + random(),
        avgValue: coin === 'BTC' ? 5000 + Math.floor(random() * 5000) : 500 + Math.floor(random() * 500)
      },
      whales: {
        largeTransactions: 500 + Math.floor(random() * 500),
        concentration: `${30 + Math.floor(random() * 40)}%`, // 30-70%
        recentMovement: `${-5 + Math.floor(random() * 11)}%` // -5% to +5%
      },
      network: {
        activeAddresses: 200000 + Math.floor(random() * 300000),
        newAddresses: 10000 + Math.floor(random() * 10000),
        addressGrowth: `${1 + Math.floor(random() * 5)}%` // 1-5%
      }
    };
    
    // Add mining or staking metrics based on coin type
    if (coin === 'BTC' || coin === 'ETH') {
      metrics.mining = {
        hashrate: coin === 'BTC' ? '250 EH/s' : '950 TH/s',
        difficulty: coin === 'BTC' ? '45.6T' : '12.5P'
      };
    } else {
      metrics.staking = {
        validators: 1000 + Math.floor(random() * 9000),
        staked: `${40 + Math.floor(random() * 30)}%` // 40-70%
      };
    }
    
    return metrics;
  };

  const generateMockCorrelationMatrix = (): Record<string, Record<string, number>> => {
    const matrix: Record<string, Record<string, number>> = {};
    
    for (const coin1 of topCoins) {
      matrix[coin1] = {};
      
      for (const coin2 of topCoins) {
        let correlation: number;
        
        if (coin1 === coin2) {
          correlation = 1.0; // Perfect correlation with self
        } else if (coin1 === 'BTC' || coin2 === 'BTC') {
          correlation = 0.7 + (Math.random() * 0.2); // Higher correlation with BTC
        } else {
          correlation = 0.3 + (Math.random() * 0.5); // Random correlation
        }
        
        matrix[coin1][coin2] = Math.round(correlation * 100) / 100; // Round to 2 decimal places
      }
    }
    
    return matrix;
  };

  const generateMockFearGreedIndex = (): FearGreedIndex => {
    const currentValue = Math.floor(Math.random() * 101); // 0-100
    
    // Classification based on value
    let classification: string;
    if (currentValue <= 24) classification = "Extreme Fear";
    else if (currentValue <= 44) classification = "Fear";
    else if (currentValue <= 54) classification = "Neutral";
    else if (currentValue <= 74) classification = "Greed";
    else classification = "Extreme Greed";
    
    // Generate historical data
    const history: { date: number; value: number }[] = [];
    let historicalValue = currentValue;
    
    for (let i = 30; i > 0; i--) {
      // Random daily change (-8 to +8)
      const change = -8 + Math.floor(Math.random() * 17);
      historicalValue = Math.max(0, Math.min(100, historicalValue + change));
      
      history.push({
        date: Date.now() - (i * 86400000), // i days ago
        value: historicalValue
      });
    }
    
    return {
      value: currentValue,
      classification,
      previousDay: Math.max(0, Math.min(100, currentValue + (-5 + Math.floor(Math.random() * 11)))),
      previousWeek: Math.max(0, Math.min(100, currentValue + (-15 + Math.floor(Math.random() * 31)))),
      history
    };
  };
  
  // Filter news by search term, sentiment, and coin
  const filteredNews = news.filter(item => {
    const matchesSearch = searchTerm === '' || 
      item.title.toLowerCase().includes(searchTerm.toLowerCase()) || 
      item.summary.toLowerCase().includes(searchTerm.toLowerCase());
      
    const matchesSentiment = sentimentFilter === null || item.sentiment === sentimentFilter;
    
    const matchesCoin = selectedCoin === null || item.relatedCoins.includes(selectedCoin);
    
    return matchesSearch && matchesSentiment && matchesCoin;
  });
  
  // Format timestamp
  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleString();
  };
  
  // Get sentiment color class
  const getSentimentClass = (sentiment: string) => {
    switch (sentiment) {
      case 'positive': return 'text-retro-green';
      case 'negative': return 'text-retro-red';
      case 'neutral': return 'text-retro-cyan';
      default: return '';
    }
  };
  
  // Get impact level class
  const getImpactClass = (impact: string | number) => {
    if (typeof impact === 'number') {
      switch (impact) {
        case 3: return 'bg-retro-red text-retro-black';
        case 2: return 'bg-retro-yellow text-retro-black';
        case 1: return 'bg-retro-green text-retro-black';
        default: return '';
      }
    }
    
    switch (impact) {
      case 'high': return 'bg-retro-red text-retro-black';
      case 'medium': return 'bg-retro-yellow text-retro-black';
      case 'low': return 'bg-retro-green text-retro-black';
      default: return '';
    }
  };

  return (
    <div className="p-4 retro-text">
      <h1 className="text-2xl font-bold mb-4 retro-header">MARKET NEWS</h1>
      
      {/* Main tabs */}
      <div className="flex mb-4 overflow-x-auto">
        <RetroButton
          className={`text-sm ${activeTab === 'headlines' ? 'bg-retro-green text-retro-black' : ''}`}
          variant={activeTab === 'headlines' ? 'primary' : 'secondary'}
          onClick={() => setActiveTab('headlines')}
        >
          HEADLINES
        </RetroButton>
        <RetroButton
          className={`text-sm ${activeTab === 'sentiment' ? 'bg-retro-green text-retro-black' : ''}`}
          variant={activeTab === 'sentiment' ? 'primary' : 'secondary'}
          onClick={() => setActiveTab('sentiment')}
        >
          SENTIMENT
        </RetroButton>
        <RetroButton
          className={`text-sm ${activeTab === 'calendar' ? 'bg-retro-green text-retro-black' : ''}`}
          variant={activeTab === 'calendar' ? 'primary' : 'secondary'}
          onClick={() => setActiveTab('calendar')}
        >
          EVENTS
        </RetroButton>
        <RetroButton
          className={`text-sm ${activeTab === 'onchain' ? 'bg-retro-green text-retro-black' : ''}`}
          variant={activeTab === 'onchain' ? 'primary' : 'secondary'}
          onClick={() => setActiveTab('onchain')}
        >
          ON-CHAIN
        </RetroButton>
        <RetroButton
          className={`text-sm ${activeTab === 'correlation' ? 'bg-retro-green text-retro-black' : ''}`}
          variant={activeTab === 'correlation' ? 'primary' : 'secondary'}
          onClick={() => setActiveTab('correlation')}
        >
          CORRELATION
        </RetroButton>
        <RetroButton
          className={`text-sm ${activeTab === 'feargreed' ? 'bg-retro-green text-retro-black' : ''}`}
          variant={activeTab === 'feargreed' ? 'primary' : 'secondary'}
          onClick={() => setActiveTab('feargreed')}
        >
          FEAR & GREED
        </RetroButton>
      </div>
      
      {/* Headlines Tab */}
      {activeTab === 'headlines' && (
        <>
          {/* Filters */}
          <div className="retro-card p-4 mb-4">
            <div className="flex flex-wrap gap-4">
              <div className="flex-grow max-w-md">
                <label className="block mb-1 text-sm">SEARCH:</label>
                <RetroInput 
                  placeholder="Search news..." 
                  value={searchTerm} 
                  onChange={(e) => setSearchTerm(e.target.value)}
                />
              </div>
              
              <div>
                <label className="block mb-1 text-sm">SENTIMENT FILTER:</label>
                <div className="flex space-x-2">
                  <RetroButton
                    className={`text-xs ${sentimentFilter === null ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={sentimentFilter === null ? 'primary' : 'secondary'}
                    onClick={() => setSentimentFilter(null)}
                  >
                    ALL
                  </RetroButton>
                  <RetroButton
                    className={`text-xs ${sentimentFilter === 'positive' ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={sentimentFilter === 'positive' ? 'primary' : 'secondary'}
                    onClick={() => setSentimentFilter('positive')}
                  >
                    POSITIVE
                  </RetroButton>
                  <RetroButton
                    className={`text-xs ${sentimentFilter === 'neutral' ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={sentimentFilter === 'neutral' ? 'primary' : 'secondary'}
                    onClick={() => setSentimentFilter('neutral')}
                  >
                    NEUTRAL
                  </RetroButton>
                  <RetroButton
                    className={`text-xs ${sentimentFilter === 'negative' ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={sentimentFilter === 'negative' ? 'primary' : 'secondary'}
                    onClick={() => setSentimentFilter('negative')}
                  >
                    NEGATIVE
                  </RetroButton>
                </div>
              </div>
            </div>
            
            <div className="mt-4">
              <label className="block mb-1 text-sm">FILTER BY COIN:</label>
              <div className="flex flex-wrap gap-2">
                <RetroButton
                  className={`text-xs ${selectedCoin === null ? 'bg-retro-green text-retro-black' : ''}`}
                  variant={selectedCoin === null ? 'primary' : 'secondary'}
                  onClick={() => setSelectedCoin(null)}
                >
                  ALL COINS
                </RetroButton>
                {topCoins.map(coin => (
                  <RetroButton
                    key={coin}
                    className={`text-xs ${selectedCoin === coin ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={selectedCoin === coin ? 'primary' : 'secondary'}
                    onClick={() => setSelectedCoin(coin)}
                  >
                    {coin}
                  </RetroButton>
                ))}
              </div>
            </div>
          </div>
          
          {/* News feed */}
          <div className="retro-card border-2 border-retro-green">
            {isLoading ? (
              <div className="flex justify-center items-center p-10">
                <p className="text-retro-green animate-pulse">LOADING NEWS DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
              </div>
            ) : filteredNews.length === 0 ? (
              <div className="p-6 text-center">
                <p>NO NEWS FOUND MATCHING FILTERS</p>
              </div>
            ) : (
              <div className="divide-y divide-retro-green divide-opacity-30">
                {filteredNews.map(item => (
                  <div key={item.id} className="p-4 hover:bg-retro-green hover:bg-opacity-10">
                    <div className="flex justify-between mb-2">
                      <h3 className="text-lg font-bold">{item.title}</h3>
                      <div className="flex items-center space-x-2">
                        <span className={`px-2 py-0.5 rounded text-xs ${getImpactClass(item.impactLevel)}`}>
                          {item.impactLevel.toUpperCase()} IMPACT
                        </span>
                        <span className={`text-sm ${getSentimentClass(item.sentiment)}`}>
                          {item.sentiment.toUpperCase()}
                        </span>
                      </div>
                    </div>
                    <p className="text-sm mb-2">{item.summary}</p>
                    <div className="flex justify-between text-xs">
                      <div>
                        <span className="mr-2">{item.source}</span>
                        <span>{formatTime(item.timestamp)}</span>
                      </div>
                      <div className="flex space-x-1">
                        {item.relatedCoins.map(coin => (
                          <span key={coin} className="bg-retro-cyan bg-opacity-20 text-retro-cyan px-1 rounded">
                            {coin}
                          </span>
                        ))}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
      
      {/* Sentiment Analysis Tab */}
      {activeTab === 'sentiment' && (
        <>
          {/* Coin Selection */}
          <div className="retro-card p-4 mb-4">
            <div className="mt-1">
              <label className="block mb-1 text-sm">SELECT COIN:</label>
              <div className="flex flex-wrap gap-2">
                {topCoins.map(coin => (
                  <RetroButton
                    key={coin}
                    className={`text-xs ${selectedCoin === coin ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={selectedCoin === coin ? 'primary' : 'secondary'}
                    onClick={() => setSelectedCoin(coin)}
                  >
                    {coin}
                  </RetroButton>
                ))}
              </div>
            </div>
          </div>
          
          {/* Sentiment Data */}
          <div className="retro-card border-2 border-retro-green p-4">
            {isLoading ? (
              <div className="flex justify-center items-center p-10">
                <p className="text-retro-green animate-pulse">LOADING SENTIMENT DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
              </div>
            ) : !selectedCoin || !sentimentData[selectedCoin] ? (
              <div className="p-6 text-center">
                <p>SELECT A COIN TO VIEW SENTIMENT ANALYSIS</p>
              </div>
            ) : (
              <div>
                <h2 className="text-xl font-bold mb-4 text-center">{selectedCoin} SENTIMENT ANALYSIS</h2>
                
                {/* Overall Sentiment Gauge */}
                <div className="mb-6">
                  <h3 className="text-lg mb-2">OVERALL MARKET SENTIMENT</h3>
                  <div className="flex items-center justify-center mb-2">
                    <div className="relative w-64 h-16">
                      {/* Sentiment gauge background */}
                      <div className="absolute inset-0 flex">
                        <div className="w-1/5 h-full bg-retro-red"></div>
                        <div className="w-1/5 h-full bg-retro-orange"></div>
                        <div className="w-1/5 h-full bg-retro-yellow"></div>
                        <div className="w-1/5 h-full bg-retro-lime"></div>
                        <div className="w-1/5 h-full bg-retro-green"></div>
                      </div>
                      
                      {/* Sentiment indicator */}
                      <div 
                        className="absolute top-0 w-2 h-full bg-retro-white" 
                        style={{ left: `${sentimentData[selectedCoin].overall.score * 100}%`, transform: 'translateX(-50%)' }}
                      ></div>
                      
                      {/* Sentiment value */}
                      <div className="absolute bottom-full left-1/2 transform -translate-x-1/2 mb-1">
                        <span className="text-retro-white text-lg font-bold">
                          {Math.round(sentimentData[selectedCoin].overall.score * 100)}%
                        </span>
                      </div>
                      
                      {/* Sentiment label */}
                      <div className="absolute top-full left-1/2 transform -translate-x-1/2 mt-1">
                        <span className="text-retro-cyan">
                          {sentimentData[selectedCoin].overall.label.toUpperCase()}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
                
                {/* Sentiment Sources */}
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-4">
                  {/* Twitter Sentiment */}
                  <div className="retro-panel p-4">
                    <h4 className="text-md font-bold mb-2">TWITTER</h4>
                    <div className="flex justify-between items-center mb-2">
                      <span>Sentiment Score:</span>
                      <span className={getScoreColorClass(sentimentData[selectedCoin].twitter.score)}>
                        {Math.round(sentimentData[selectedCoin].twitter.score * 100)}%
                      </span>
                    </div>
                    <div className="flex justify-between items-center mb-2">
                      <span>24h Change:</span>
                      <span className={sentimentData[selectedCoin].twitter.change >= 0 ? 'text-retro-green' : 'text-retro-red'}>
                        {sentimentData[selectedCoin].twitter.change >= 0 ? '+' : ''}
                        {Math.round(sentimentData[selectedCoin].twitter.change * 100)}%
                      </span>
                    </div>
                    <div className="flex justify-between items-center">
                      <span>Volume:</span>
                      <span>{sentimentData[selectedCoin].twitter.volume.toLocaleString()} mentions</span>
                    </div>
                  </div>
                  
                  {/* Reddit Sentiment */}
                  <div className="retro-panel p-4">
                    <h4 className="text-md font-bold mb-2">REDDIT</h4>
                    <div className="flex justify-between items-center mb-2">
                      <span>Sentiment Score:</span>
                      <span className={getScoreColorClass(sentimentData[selectedCoin].reddit.score)}>
                        {Math.round(sentimentData[selectedCoin].reddit.score * 100)}%
                      </span>
                    </div>
                    <div className="flex justify-between items-center mb-2">
                      <span>24h Change:</span>
                      <span className={sentimentData[selectedCoin].reddit.change >= 0 ? 'text-retro-green' : 'text-retro-red'}>
                        {sentimentData[selectedCoin].reddit.change >= 0 ? '+' : ''}
                        {Math.round(sentimentData[selectedCoin].reddit.change * 100)}%
                      </span>
                    </div>
                    <div className="flex justify-between items-center">
                      <span>Volume:</span>
                      <span>{sentimentData[selectedCoin].reddit.volume.toLocaleString()} posts</span>
                    </div>
                  </div>
                  
                  {/* News Sentiment */}
                  <div className="retro-panel p-4">
                    <h4 className="text-md font-bold mb-2">NEWS</h4>
                    <div className="flex justify-between items-center mb-2">
                      <span>Sentiment Score:</span>
                      <span className={getScoreColorClass(sentimentData[selectedCoin].news.score)}>
                        {Math.round(sentimentData[selectedCoin].news.score * 100)}%
                      </span>
                    </div>
                    <div className="flex justify-between items-center mb-2">
                      <span>24h Change:</span>
                      <span className={sentimentData[selectedCoin].news.change >= 0 ? 'text-retro-green' : 'text-retro-red'}>
                        {sentimentData[selectedCoin].news.change >= 0 ? '+' : ''}
                        {Math.round(sentimentData[selectedCoin].news.change * 100)}%
                      </span>
                    </div>
                    <div className="flex justify-between items-center">
                      <span>Volume:</span>
                      <span>{sentimentData[selectedCoin].news.volume.toLocaleString()} articles</span>
                    </div>
                  </div>
                </div>
                
                {/* Sentiment Explanation */}
                <div className="retro-panel p-4 mt-4">
                  <h4 className="text-md font-bold mb-2">SENTIMENT INTERPRETATION</h4>
                  <p className="text-sm">
                    The overall sentiment for {selectedCoin} is currently <strong>{sentimentData[selectedCoin].overall.label}</strong>. 
                    This analysis is based on a weighted average of social media sentiment (Twitter, Reddit) and news coverage,
                    with Twitter having the highest impact (50%), followed by Reddit (30%) and News (20%).
                  </p>
                  <p className="text-sm mt-2">
                    {generateSentimentInterpretation(selectedCoin, sentimentData[selectedCoin])}
                  </p>
                </div>
              </div>
            )}
          </div>
        </>
      )}
      
      {/* Events Calendar Tab */}
      {activeTab === 'calendar' && (
        <>
          {/* Filters for events */}
          <div className="retro-card p-4 mb-4">
            <div className="flex flex-wrap gap-4">
              <div className="mt-1">
                <label className="block mb-1 text-sm">FILTER BY COIN:</label>
                <div className="flex flex-wrap gap-2">
                  <RetroButton
                    className={`text-xs ${selectedCoin === null ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={selectedCoin === null ? 'primary' : 'secondary'}
                    onClick={() => setSelectedCoin(null)}
                  >
                    ALL COINS
                  </RetroButton>
                  {topCoins.map(coin => (
                    <RetroButton
                      key={coin}
                      className={`text-xs ${selectedCoin === coin ? 'bg-retro-green text-retro-black' : ''}`}
                      variant={selectedCoin === coin ? 'primary' : 'secondary'}
                      onClick={() => setSelectedCoin(coin)}
                    >
                      {coin}
                    </RetroButton>
                  ))}
                </div>
              </div>
            </div>
          </div>
          
          {/* Events Calendar */}
          <div className="retro-card border-2 border-retro-green">
            {isLoading ? (
              <div className="flex justify-center items-center p-10">
                <p className="text-retro-green animate-pulse">LOADING EVENTS CALENDAR<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
              </div>
            ) : (
              <div>
                <h2 className="text-xl font-bold m-4 text-center">UPCOMING CRYPTO EVENTS</h2>
                
                {/* Filter events by selected coin */}
                {(() => {
                  const filteredEvents = calendarEvents.filter(event => 
                    selectedCoin === null || event.relatedCoins.includes(selectedCoin)
                  );
                  
                  if (filteredEvents.length === 0) {
                    return (
                      <div className="p-6 text-center">
                        <p>NO UPCOMING EVENTS FOUND FOR SELECTED FILTER</p>
                      </div>
                    );
                  }
                  
                  // Group events by month and day
                  const today = new Date();
                  today.setHours(0, 0, 0, 0);
                  const todayTimestamp = today.getTime();
                  
                  // Group events by timeframe
                  const thisWeekEvents: CalendarEvent[] = [];
                  const thisMonthEvents: CalendarEvent[] = [];
                  const laterEvents: CalendarEvent[] = [];
                  
                  filteredEvents.forEach(event => {
                    // Using the date directly in JSX
                    const daysDiff = Math.floor((event.date - todayTimestamp) / (1000 * 60 * 60 * 24));
                    
                    if (daysDiff < 7) {
                      thisWeekEvents.push(event);
                    } else if (daysDiff < 30) {
                      thisMonthEvents.push(event);
                    } else {
                      laterEvents.push(event);
                    }
                  });
                  
                  return (
                    <div className="divide-y divide-retro-green divide-opacity-30">
                      {/* This Week Events */}
                      {thisWeekEvents.length > 0 && (
                        <div className="p-4">
                          <h3 className="text-lg font-bold mb-3 text-retro-cyan">THIS WEEK</h3>
                          <div className="space-y-3">
                            {thisWeekEvents.map(event => (
                              <EventCard key={event.id} event={event} />
                            ))}
                          </div>
                        </div>
                      )}
                      
                      {/* This Month Events */}
                      {thisMonthEvents.length > 0 && (
                        <div className="p-4">
                          <h3 className="text-lg font-bold mb-3 text-retro-lime">THIS MONTH</h3>
                          <div className="space-y-3">
                            {thisMonthEvents.map(event => (
                              <EventCard key={event.id} event={event} />
                            ))}
                          </div>
                        </div>
                      )}
                      
                      {/* Later Events */}
                      {laterEvents.length > 0 && (
                        <div className="p-4">
                          <h3 className="text-lg font-bold mb-3 text-retro-yellow">UPCOMING</h3>
                          <div className="space-y-3">
                            {laterEvents.map(event => (
                              <EventCard key={event.id} event={event} />
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })()}
              </div>
            )}
          </div>
        </>
      )}
      
      {/* On-Chain Metrics Tab */}
      {activeTab === 'onchain' && (
        <>
          {/* Coin selection for on-chain metrics */}
          <div className="retro-card p-4 mb-4">
            <div className="mt-1">
              <label className="block mb-1 text-sm">SELECT COIN:</label>
              <div className="flex flex-wrap gap-2">
                {topCoins.map(coin => (
                  <RetroButton
                    key={coin}
                    className={`text-xs ${selectedMetricCoin === coin ? 'bg-retro-green text-retro-black' : ''}`}
                    variant={selectedMetricCoin === coin ? 'primary' : 'secondary'}
                    onClick={() => setSelectedMetricCoin(coin)}
                  >
                    {coin}
                  </RetroButton>
                ))}
              </div>
            </div>
          </div>
          
          {/* On-Chain Metrics Dashboard */}
          <div className="retro-card border-2 border-retro-green">
            {isLoading ? (
              <div className="flex justify-center items-center p-10">
                <p className="text-retro-green animate-pulse">LOADING ON-CHAIN METRICS<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
              </div>
            ) : !onchainMetrics ? (
              <div className="p-6 text-center">
                <p>SELECT A COIN TO VIEW ON-CHAIN METRICS</p>
              </div>
            ) : (
              <div className="p-4">
                <h2 className="text-xl font-bold mb-4 text-center">{selectedMetricCoin} ON-CHAIN METRICS</h2>
                
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
                  {/* Transaction Metrics */}
                  <div className="retro-panel p-4">
                    <h3 className="text-lg font-bold mb-3">TRANSACTION METRICS</h3>
                    
                    <div className="space-y-4">
                      <div className="flex justify-between items-center">
                        <span>Daily Transactions:</span>
                        <span className="font-mono">{onchainMetrics.transactions.daily.toLocaleString()}</span>
                      </div>
                      
                      <div>
                        <div className="flex justify-between items-center mb-1">
                          <span>24h Change:</span>
                          <span className={onchainMetrics.transactions.change >= 0 ? 'text-retro-green' : 'text-retro-red'}>
                            {onchainMetrics.transactions.change >= 0 ? '+' : ''}
                            {onchainMetrics.transactions.change}%
                          </span>
                        </div>
                        {/* Progress bar visualization */}
                        <div className="relative h-3 bg-retro-black">
                          <div 
                            className={`absolute h-full ${onchainMetrics.transactions.change >= 0 ? 'bg-retro-green' : 'bg-retro-red'}`}
                            style={{ 
                              width: `${Math.min(Math.abs(onchainMetrics.transactions.change) * 5, 100)}%`, 
                              left: onchainMetrics.transactions.change >= 0 ? '50%' : `${50 - Math.min(Math.abs(onchainMetrics.transactions.change) * 5, 50)}%`
                            }}
                          ></div>
                          <div className="absolute h-full w-px bg-retro-white left-1/2 z-10"></div>
                        </div>
                      </div>
                      
                      <div className="flex justify-between items-center">
                        <span>Avg Transaction Fee:</span>
                        <span className="font-mono">${onchainMetrics.transactions.avgFee.toFixed(2)}</span>
                      </div>
                      
                      <div className="flex justify-between items-center">
                        <span>Avg Transaction Value:</span>
                        <span className="font-mono">${onchainMetrics.transactions.avgValue.toLocaleString()}</span>
                      </div>
                    </div>
                  </div>
                  
                  {/* Mining or Staking Metrics */}
                  <div className="retro-panel p-4">
                    <h3 className="text-lg font-bold mb-3">
                      {onchainMetrics.mining ? 'MINING METRICS' : 'STAKING METRICS'}
                    </h3>
                    
                    <div className="space-y-4">
                      {onchainMetrics.mining ? (
                        // Mining metrics
                        <>
                          <div className="flex justify-between items-center">
                            <span>Network Hashrate:</span>
                            <span className="font-mono">{onchainMetrics.mining.hashrate}</span>
                          </div>
                          
                          <div className="flex justify-between items-center">
                            <span>Mining Difficulty:</span>
                            <span className="font-mono">{onchainMetrics.mining.difficulty}</span>
                          </div>
                        </>
                      ) : (
                        // Staking metrics
                        <>
                          <div className="flex justify-between items-center">
                            <span>Active Validators:</span>
                            <span className="font-mono">{onchainMetrics.staking?.validators.toLocaleString()}</span>
                          </div>
                          
                          <div className="flex justify-between items-center">
                            <span>Total Staked:</span>
                            <span className="font-mono">{onchainMetrics.staking?.staked}</span>
                          </div>
                        </>
                      )}
                      
                      {/* Network security visualization */}
                      <div>
                        <div className="flex justify-between items-center mb-1">
                          <span>Network Security:</span>
                          <span className="text-retro-green">
                            {onchainMetrics.mining 
                              ? 'STRONG (POW)' 
                              : 'STRONG (POS)'}
                          </span>
                        </div>
                        <div className="w-full bg-retro-black h-3">
                          <div 
                            className="bg-retro-green h-full" 
                            style={{ width: '85%' }}
                          ></div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Whale Activity */}
                  <div className="retro-panel p-4">
                    <h3 className="text-lg font-bold mb-3">WHALE ACTIVITY</h3>
                    
                    <div className="space-y-4">
                      <div className="flex justify-between items-center">
                        <span>Large Transactions (24h):</span>
                        <span className="font-mono">{onchainMetrics.whales.largeTransactions.toLocaleString()}</span>
                      </div>
                      
                      <div className="flex justify-between items-center">
                        <span>Whale Concentration:</span>
                        <span className="font-mono">{onchainMetrics.whales.concentration}</span>
                      </div>
                      
                      <div>
                        <div className="flex justify-between items-center mb-1">
                          <span>Recent Movement:</span>
                          <span className={onchainMetrics.whales.recentMovement.startsWith('-') ? 'text-retro-red' : 'text-retro-green'}>
                            {onchainMetrics.whales.recentMovement}
                          </span>
                        </div>
                        <div className="text-sm text-retro-cyan">
                          {onchainMetrics.whales.recentMovement.startsWith('-') 
                            ? 'Whales are distributing'
                            : 'Whales are accumulating'}
                        </div>
                      </div>
                    </div>
                  </div>
                  
                  {/* Network Growth */}
                  <div className="retro-panel p-4">
                    <h3 className="text-lg font-bold mb-3">NETWORK GROWTH</h3>
                    
                    <div className="space-y-4">
                      <div className="flex justify-between items-center">
                        <span>Active Addresses:</span>
                        <span className="font-mono">{onchainMetrics.network.activeAddresses.toLocaleString()}</span>
                      </div>
                      
                      <div className="flex justify-between items-center">
                        <span>New Addresses (24h):</span>
                        <span className="font-mono">{onchainMetrics.network.newAddresses.toLocaleString()}</span>
                      </div>
                      
                      <div>
                        <div className="flex justify-between items-center mb-1">
                          <span>Address Growth Rate:</span>
                          <span className="text-retro-green">{onchainMetrics.network.addressGrowth}</span>
                        </div>
                        <div className="w-full bg-retro-black h-3">
                          <div 
                            className="bg-retro-green h-full" 
                            style={{ 
                              width: `${parseInt(onchainMetrics.network.addressGrowth) * 20}%` 
                            }}
                          ></div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                
                {/* Interpretation */}
                <div className="retro-panel p-4 mt-6">
                  <h3 className="text-lg font-bold mb-2">ANALYSIS SUMMARY</h3>
                  <p className="text-sm">
                    {generateOnchainAnalysis(selectedMetricCoin, onchainMetrics)}
                  </p>
                </div>
              </div>
            )}
          </div>
        </>
      )}
      
      {/* Correlation Matrix Tab */}
      {activeTab === 'correlation' && (
        <>
          <div className="retro-card p-4 mb-4">
            <div className="flex flex-wrap justify-between items-center">
              <h3 className="text-lg font-bold">CORRELATION MATRIX</h3>
              <div className="text-sm text-retro-cyan">
                Data shows 30-day price correlation between assets
              </div>
            </div>
          </div>
          
          <div className="retro-card border-2 border-retro-green">
            {isLoading ? (
              <div className="flex justify-center items-center p-10">
                <p className="text-retro-green animate-pulse">LOADING CORRELATION DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
              </div>
            ) : Object.keys(correlationMatrix).length === 0 ? (
              <div className="p-6 text-center">
                <p>NO CORRELATION DATA AVAILABLE</p>
              </div>
            ) : (
              <div className="p-4">
                <div className="mb-5">
                  <div className="flex items-center justify-end space-x-6 text-sm">
                    <div className="flex items-center">
                      <span className="inline-block w-3 h-3 bg-retro-red mr-2"></span>
                      <span>Strong Negative</span>
                    </div>
                    <div className="flex items-center">
                      <span className="inline-block w-3 h-3 bg-retro-yellow mr-2"></span>
                      <span>Neutral</span>
                    </div>
                    <div className="flex items-center">
                      <span className="inline-block w-3 h-3 bg-retro-green mr-2"></span>
                      <span>Strong Positive</span>
                    </div>
                  </div>
                </div>
                
                {/* Correlation Matrix Table */}
                <div className="overflow-x-auto">
                  <table className="w-full border-collapse">
                    <thead>
                      <tr>
                        <th className="p-2 border border-retro-green bg-retro-black"></th>
                        {topCoins.map(coin => (
                          <th key={coin} className="p-2 border border-retro-green bg-retro-black">
                            {coin}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {topCoins.map(coin1 => (
                        <tr key={coin1}>
                          <th className="p-2 border border-retro-green bg-retro-black">
                            {coin1}
                          </th>
                          {topCoins.map(coin2 => {
                            const correlation = correlationMatrix[coin1]?.[coin2] || 0;
                            return (
                              <td 
                                key={`${coin1}-${coin2}`} 
                                className="p-2 border border-retro-green text-center"
                                style={{ backgroundColor: getCorrelationColor(correlation) }}
                              >
                                {correlation.toFixed(2)}
                              </td>
                            );
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                
                {/* Correlation Analysis */}
                <div className="mt-6 retro-panel p-4">
                  <h3 className="text-lg font-bold mb-2">CORRELATION ANALYSIS</h3>
                  
                  <div className="space-y-4">
                    {/* Highest Correlation Pairs */}
                    <div>
                      <h4 className="font-bold">Highest Correlation Pairs:</h4>
                      <ul className="list-disc list-inside space-y-1 mt-1">
                        {getTopCorrelationPairs(correlationMatrix, 3, true).map((pair, index) => (
                          <li key={index}>
                            <span className="text-retro-cyan">{pair.coin1}</span> / <span className="text-retro-cyan">{pair.coin2}</span>: 
                            <span className="text-retro-green ml-1">{pair.correlation.toFixed(2)}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                    
                    {/* Lowest Correlation Pairs */}
                    <div>
                      <h4 className="font-bold">Lowest Correlation Pairs:</h4>
                      <ul className="list-disc list-inside space-y-1 mt-1">
                        {getTopCorrelationPairs(correlationMatrix, 3, false).map((pair, index) => (
                          <li key={index}>
                            <span className="text-retro-cyan">{pair.coin1}</span> / <span className="text-retro-cyan">{pair.coin2}</span>: 
                            <span className="text-retro-red ml-1">{pair.correlation.toFixed(2)}</span>
                          </li>
                        ))}
                      </ul>
                    </div>
                    
                    {/* Portfolio Diversification */}
                    <div>
                      <h4 className="font-bold">Portfolio Diversification Opportunities:</h4>
                      <p className="text-sm mt-1">
                        {generateDiversificationAdvice(correlationMatrix)}
                      </p>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
        </>
      )}
      
      {/* Fear & Greed Index Tab */}
      {activeTab === 'feargreed' && (
        <div className="retro-card border-2 border-retro-green">
          {isLoading ? (
            <div className="flex justify-center items-center p-10">
              <p className="text-retro-green animate-pulse">LOADING FEAR & GREED DATA<span className="dot-1">.</span><span className="dot-2">.</span><span className="dot-3">.</span></p>
            </div>
          ) : !fearGreedIndex ? (
            <div className="p-6 text-center">
              <p>FEAR & GREED INDEX DATA UNAVAILABLE</p>
            </div>
          ) : (
            <div className="p-4">
              <h2 className="text-xl font-bold mb-4 text-center">CRYPTO FEAR & GREED INDEX</h2>
              
              {/* Current Fear & Greed Index */}
              <div className="flex flex-col items-center mb-6">
                <div className="relative w-64 h-64">
                  {/* Gauge background */}
                  <div className="absolute inset-0 rounded-full overflow-hidden">
                    <div className="absolute inset-0 bg-gradient-to-r from-retro-red via-retro-yellow to-retro-green"></div>
                  </div>
                  
                  {/* Gauge needle */}
                  <div 
                    className="absolute top-0 left-1/2 w-1 h-32 bg-retro-white origin-bottom transform -translate-x-1/2" 
                    style={{ transform: `translateX(-50%) rotate(${(fearGreedIndex.value / 100) * 180 - 90}deg)` }}
                  ></div>
                  
                  {/* Center dot */}
                  <div className="absolute inset-0 flex items-center justify-center">
                    <div className="bg-retro-black rounded-full w-20 h-20 flex items-center justify-center border-2 border-retro-white">
                      <div className="text-center">
                        <div className="text-2xl font-bold text-retro-white">{fearGreedIndex.value}</div>
                        <div 
                          className={`text-xs ${getFearGreedColor(fearGreedIndex.classification)}`}
                        >
                          {fearGreedIndex.classification.toUpperCase()}
                        </div>
                      </div>
                    </div>
                  </div>
                  
                  {/* Labels */}
                  <div className="absolute top-full left-0 mt-4 text-retro-red">
                    EXTREME FEAR
                  </div>
                  <div className="absolute top-full right-0 mt-4 text-retro-green">
                    EXTREME GREED
                  </div>
                </div>
              </div>
              
              {/* Historical Comparison */}
              <div className="retro-panel p-4 mb-6">
                <h3 className="text-lg font-bold mb-2">HISTORICAL COMPARISON</h3>
                
                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                  <div className="flex flex-col items-center">
                    <div className="text-sm text-retro-cyan mb-1">CURRENT</div>
                    <div className="text-3xl font-bold">{fearGreedIndex.value}</div>
                    <div className={`text-sm ${getFearGreedColor(fearGreedIndex.classification)}`}>
                      {fearGreedIndex.classification}
                    </div>
                  </div>
                  
                  <div className="flex flex-col items-center">
                    <div className="text-sm text-retro-cyan mb-1">YESTERDAY</div>
                    <div className="text-3xl font-bold">{fearGreedIndex.previousDay}</div>
                    <div className={`text-sm ${getFearGreedColor(getFearGreedClassification(fearGreedIndex.previousDay))}`}>
                      {getFearGreedClassification(fearGreedIndex.previousDay)}
                    </div>
                    <div className="text-xs">
                      {fearGreedIndex.value > fearGreedIndex.previousDay ? (
                        <span className="text-retro-green">▲ {fearGreedIndex.value - fearGreedIndex.previousDay}</span>
                      ) : fearGreedIndex.value < fearGreedIndex.previousDay ? (
                        <span className="text-retro-red">▼ {fearGreedIndex.previousDay - fearGreedIndex.value}</span>
                      ) : (
                        <span className="text-retro-yellow">◆ No Change</span>
                      )}
                    </div>
                  </div>
                  
                  <div className="flex flex-col items-center">
                    <div className="text-sm text-retro-cyan mb-1">LAST WEEK</div>
                    <div className="text-3xl font-bold">{fearGreedIndex.previousWeek}</div>
                    <div className={`text-sm ${getFearGreedColor(getFearGreedClassification(fearGreedIndex.previousWeek))}`}>
                      {getFearGreedClassification(fearGreedIndex.previousWeek)}
                    </div>
                    <div className="text-xs">
                      {fearGreedIndex.value > fearGreedIndex.previousWeek ? (
                        <span className="text-retro-green">▲ {fearGreedIndex.value - fearGreedIndex.previousWeek}</span>
                      ) : fearGreedIndex.value < fearGreedIndex.previousWeek ? (
                        <span className="text-retro-red">▼ {fearGreedIndex.previousWeek - fearGreedIndex.value}</span>
                      ) : (
                        <span className="text-retro-yellow">◆ No Change</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>
              
              {/* Historical Chart */}
              <div className="retro-panel p-4 mb-6">
                <h3 className="text-lg font-bold mb-4">30-DAY HISTORY</h3>
                
                <div className="w-full h-40 relative">
                  {/* X-axis (time) */}
                  <div className="absolute bottom-0 left-0 right-0 h-px bg-retro-green"></div>
                  
                  {/* Y-axis (score) */}
                  <div className="absolute top-0 bottom-0 left-0 w-px bg-retro-green"></div>
                  
                  {/* Y-axis labels */}
                  <div className="absolute top-0 left-0 transform -translate-y-1/2 -translate-x-2 text-xs">100</div>
                  <div className="absolute top-1/2 left-0 transform -translate-y-1/2 -translate-x-2 text-xs">50</div>
                  <div className="absolute bottom-0 left-0 transform translate-y-1/2 -translate-x-2 text-xs">0</div>
                  
                  {/* Data points */}
                  {fearGreedIndex.history.map((point, index) => {
                    const x = `${(index / (fearGreedIndex.history.length - 1)) * 100}%`;
                    const y = `${100 - point.value}%`;
                    
                    return (
                      <div 
                        key={index}
                        className="absolute w-1 h-1 bg-retro-cyan transform -translate-x-1/2 -translate-y-1/2"
                        style={{ left: x, top: y }}
                        title={`${new Date(point.date).toLocaleDateString()}: ${point.value} (${getFearGreedClassification(point.value)})`}
                      ></div>
                    );
                  })}
                  
                  {/* Connect the dots with lines */}
                  <svg className="absolute inset-0" style={{ width: '100%', height: '100%' }}>
                    <polyline
                      points={fearGreedIndex.history.map((point, index) => {
                        const x = (index / (fearGreedIndex.history.length - 1)) * 100;
                        const y = point.value;
                        return `${x},${100 - y}`;
                      }).join(' ')}
                      fill="none"
                      stroke="#00FFFF"
                      strokeWidth="1"
                    />
                  </svg>
                  
                  {/* Current value indicator */}
                  <div 
                    className="absolute w-2 h-2 bg-retro-white rounded-full transform -translate-x-1/2 -translate-y-1/2"
                    style={{ 
                      left: '100%', 
                      top: `${100 - fearGreedIndex.value}%` 
                    }}
                  ></div>
                </div>
                
                <div className="flex justify-between text-xs mt-2">
                  <span>{new Date(fearGreedIndex.history[0].date).toLocaleDateString()}</span>
                  <span>{new Date(fearGreedIndex.history[Math.floor(fearGreedIndex.history.length / 2)].date).toLocaleDateString()}</span>
                  <span>Today</span>
                </div>
              </div>
              
              {/* Interpretation */}
              <div className="retro-panel p-4">
                <h3 className="text-lg font-bold mb-2">MARKET INTERPRETATION</h3>
                
                <p className="text-sm mb-2">
                  {generateFearGreedAnalysis(fearGreedIndex)}
                </p>
                
                <div className="mt-4">
                  <h4 className="font-bold">What is the Fear & Greed Index?</h4>
                  <p className="text-sm mt-1">
                    The Crypto Fear & Greed Index measures market sentiment from 0 (Extreme Fear) to 100 (Extreme Greed).
                    It considers factors like volatility, market momentum, social media, surveys, and Bitcoin dominance.
                    Extreme fear often signals buying opportunities, while extreme greed may indicate market corrections.
                  </p>
                </div>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// Helper function to get color class based on sentiment score
const getScoreColorClass = (score: number): string => {
  if (score >= 0.7) return 'text-retro-green';
  if (score >= 0.5) return 'text-retro-lime';
  if (score >= 0.4) return 'text-retro-yellow';
  if (score >= 0.3) return 'text-retro-orange';
  return 'text-retro-red';
};

// Helper function to generate sentiment interpretation
const generateSentimentInterpretation = (coin: string, data: SentimentData): string => {
  // Twitter trend
  // twitter trend direction for future UI enhancement
// const twitterTrend = data.twitter.change >= 0 ? 'positive' : 'negative';
  const twitterDesc = data.twitter.change >= 0 
    ? `Twitter sentiment for ${coin} is improving` 
    : `Twitter sentiment for ${coin} is declining`;
  
  // Reddit trend  
  // reddit trend direction for future UI enhancement
// const redditTrend = data.reddit.change >= 0 ? 'positive' : 'negative';
  const redditDesc = data.reddit.change >= 0
    ? `Reddit discussions are mostly favorable`
    : `Reddit discussions show some concerns`;
    
  // News trend
  // news trend direction for future UI enhancement
// const newsTrend = data.news.change >= 0 ? 'positive' : 'negative';
  const newsDesc = data.news.change >= 0
    ? `Recent news articles are generally positive`
    : `Recent news coverage has been somewhat negative`;
    
  // Overall recommendation
  let recommendation = '';
  if (data.overall.score >= 0.7) {
    recommendation = `Market sentiment strongly favors ${coin} right now, which might indicate a bullish trend.`;
  } else if (data.overall.score >= 0.5) {
    recommendation = `Overall sentiment for ${coin} is positive, suggesting cautious optimism.`;
  } else if (data.overall.score >= 0.4) {
    recommendation = `Sentiment for ${coin} is neutral, suggesting a wait-and-see approach.`;
  } else if (data.overall.score >= 0.3) {
    recommendation = `${coin} sentiment leans negative, indicating potential caution is warranted.`;
  } else {
    recommendation = `Sentiment for ${coin} is strongly negative, which might signal bearish pressure.`;
  }
  
  return `${twitterDesc}, while ${redditDesc}. ${newsDesc}. ${recommendation}`;
};

// Event Card Component
interface EventCardProps {
  event: CalendarEvent;
}

const EventCard: React.FC<EventCardProps> = ({ event }) => {
  // Format date
  const formatEventDate = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleDateString('en-US', { 
      weekday: 'short',
      month: 'short', 
      day: 'numeric', 
      year: 'numeric'
    });
  };
  
  // Get impact level class for styling
  const getEventImpactClass = (impact: number) => {
    switch (impact) {
      case 3: return 'bg-retro-red text-retro-black';
      case 2: return 'bg-retro-yellow text-retro-black';
      case 1: return 'bg-retro-green text-retro-black';
      default: return '';
    }
  };
  
  // Get icon based on event type
  const getEventTypeIcon = (type: string) => {
    switch (type) {
      case 'conference': return '🎯';
      case 'listing': return '📊';
      case 'fork': return '🔀';
      case 'airdrop': return '🪂';
      case 'release': return '📦';
      case 'regulatory': return '⚖️';
      default: return '📅';
    }
  };
  
  return (
    <div className="retro-panel p-3 hover:bg-retro-green hover:bg-opacity-10">
      <div className="flex justify-between items-start">
        <div className="flex items-start space-x-3">
          <div className="text-2xl" title={event.type}>
            {getEventTypeIcon(event.type)}
          </div>
          <div>
            <h4 className="text-md font-bold">{event.title}</h4>
            <p className="text-sm mt-1">{event.description}</p>
          </div>
        </div>
        <div className="flex items-center space-x-2">
          <span className={`px-2 py-0.5 rounded text-xs ${getEventImpactClass(event.impact)}`}>
            {event.impact === 3 ? 'HIGH' : event.impact === 2 ? 'MEDIUM' : 'LOW'} IMPACT
          </span>
        </div>
      </div>
      
      <div className="flex justify-between mt-2 text-xs">
        <div className="flex items-center">
          <span className="text-retro-cyan mr-1">📅</span>
          <span>{formatEventDate(event.date)}</span>
        </div>
        <div className="flex items-center space-x-1">
          {event.relatedCoins.map(coin => (
            <span key={coin} className="bg-retro-cyan bg-opacity-20 text-retro-cyan px-1 rounded">
              {coin}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
};

// Helper function to generate onchain analysis
const generateOnchainAnalysis = (coin: string, metrics: OnchainMetrics): string => {
  const transactionTrend = metrics.transactions.change >= 0 ? 'increasing' : 'decreasing';
  const transactionVolume = metrics.transactions.daily > 300000 ? 'high' : (metrics.transactions.daily > 100000 ? 'moderate' : 'low');
  
  const networkType = metrics.mining ? 'Proof-of-Work' : 'Proof-of-Stake';
  const networkStrength = metrics.mining 
    ? `The ${coin} network is secured by ${networkType} with a hashrate of ${metrics.mining.hashrate}`
    : `The ${coin} network is secured by ${networkType} with ${metrics.staking?.validators.toLocaleString()} active validators and ${metrics.staking?.staked} of total supply staked`;
  
  const whaleActivity = metrics.whales.recentMovement.startsWith('-')
    ? `Whale addresses have been distributing (${metrics.whales.recentMovement}) over the past week, which might indicate selling pressure`
    : `Whale addresses have been accumulating (${metrics.whales.recentMovement}) over the past week, which might indicate buying interest`;
  
  const networkGrowth = parseInt(metrics.network.addressGrowth) >= 3
    ? `The network is experiencing strong growth with ${metrics.network.addressGrowth} new addresses daily`
    : `The network is showing ${parseInt(metrics.network.addressGrowth) >= 1 ? 'steady' : 'slow'} growth with ${metrics.network.addressGrowth} new addresses daily`;
  
  const overallHealth = (metrics.transactions.change >= 0 && !metrics.whales.recentMovement.startsWith('-') && parseInt(metrics.network.addressGrowth) >= 1)
    ? `Overall, on-chain metrics for ${coin} appear healthy and suggest positive network activity`
    : `Overall, on-chain metrics for ${coin} show mixed signals and warrant careful monitoring`;

  return `${coin} is currently experiencing ${transactionTrend} transaction activity with ${transactionVolume} volume (${metrics.transactions.daily.toLocaleString()} transactions daily). ${networkStrength}. ${whaleActivity}. ${networkGrowth}. ${overallHealth}.`;
};

// Helper functions for correlation matrix
const getCorrelationColor = (correlation: number): string => {
  // Red (-1.0) to Yellow (0.0) to Green (1.0)
  if (correlation >= 0.8) return 'rgba(0, 255, 0, 0.3)'; // Strong positive - green
  if (correlation >= 0.5) return 'rgba(100, 255, 0, 0.25)'; // Moderate positive
  if (correlation >= 0.2) return 'rgba(180, 255, 0, 0.2)'; // Weak positive
  if (correlation > -0.2) return 'rgba(255, 255, 0, 0.15)'; // Neutral - yellow
  if (correlation > -0.5) return 'rgba(255, 180, 0, 0.15)'; // Weak negative
  if (correlation > -0.8) return 'rgba(255, 100, 0, 0.2)'; // Moderate negative
  return 'rgba(255, 0, 0, 0.25)'; // Strong negative - red
};

// Get top correlation pairs (highest or lowest)
const getTopCorrelationPairs = (
  matrix: Record<string, Record<string, number>>,
  count: number,
  highest: boolean
): {coin1: string; coin2: string; correlation: number}[] => {
  const pairs: {coin1: string; coin2: string; correlation: number}[] = [];
  
  // Extract unique pairs
  Object.keys(matrix).forEach(coin1 => {
    Object.keys(matrix[coin1]).forEach(coin2 => {
      if (coin1 !== coin2) { // Skip self correlations
        const correlation = matrix[coin1][coin2];
        
        // Only add each pair once (avoid both A-B and B-A)
        const pairExists = pairs.some(
          p => (p.coin1 === coin1 && p.coin2 === coin2) || (p.coin1 === coin2 && p.coin2 === coin1)
        );
        
        if (!pairExists) {
          pairs.push({ coin1, coin2, correlation });
        }
      }
    });
  });
  
  // Sort by correlation (ascending or descending)
  pairs.sort((a, b) => {
    return highest 
      ? b.correlation - a.correlation // Highest first
      : a.correlation - b.correlation; // Lowest first
  });
  
  return pairs.slice(0, count);
};

// Generate diversification advice
const generateDiversificationAdvice = (matrix: Record<string, Record<string, number>>): string => {
  const lowCorrelationPairs = getTopCorrelationPairs(matrix, 5, false);
  const highCorrelationPairs = getTopCorrelationPairs(matrix, 5, true);
  
  const diversePairs = lowCorrelationPairs
    .map(pair => `${pair.coin1}/${pair.coin2} (${pair.correlation.toFixed(2)})`)
    .slice(0, 2)
    .join(' and ');
    
  const similarPairs = highCorrelationPairs
    .map(pair => `${pair.coin1}/${pair.coin2} (${pair.correlation.toFixed(2)})`)
    .slice(0, 2)
    .join(' and ');
  
  return `For maximum diversification, consider pairing assets with low correlation such as ${diversePairs}. Assets with high correlation like ${similarPairs} tend to move together, reducing portfolio diversification benefits. A well-balanced portfolio typically includes assets across the correlation spectrum to manage risk.`;
};

// Helper functions for Fear & Greed Index
const getFearGreedColor = (classification: string): string => {
  switch (classification) {
    case 'Extreme Fear':
      return 'text-retro-red';
    case 'Fear':
      return 'text-retro-orange';
    case 'Neutral':
      return 'text-retro-yellow';
    case 'Greed':
      return 'text-retro-lime';
    case 'Extreme Greed':
      return 'text-retro-green';
    default:
      return '';
  }
};

const getFearGreedClassification = (value: number): string => {
  if (value <= 24) return "Extreme Fear";
  if (value <= 44) return "Fear";
  if (value <= 54) return "Neutral";
  if (value <= 74) return "Greed";
  return "Extreme Greed";
};

const generateFearGreedAnalysis = (data: FearGreedIndex): string => {
  const currentValue = data.value;
  const currentClass = data.classification;
  const previousDayValue = data.previousDay;
  const previousWeekValue = data.previousWeek;
  
  // Calculate trend (daily and weekly change)
  const dailyChange = currentValue - previousDayValue;
  const weeklyChange = currentValue - previousWeekValue;
  
  // Interpret daily trend
  let dailyTrend = '';
  if (Math.abs(dailyChange) <= 3) {
    dailyTrend = 'remained relatively stable';
  } else if (dailyChange > 0) {
    dailyTrend = `increased by ${dailyChange} points`;
  } else {
    dailyTrend = `decreased by ${Math.abs(dailyChange)} points`;
  }
  
  // Interpret weekly trend
  let weeklyTrend = '';
  if (Math.abs(weeklyChange) <= 5) {
    weeklyTrend = 'shows little change';
  } else if (weeklyChange > 0) {
    weeklyTrend = `has increased significantly by ${weeklyChange} points`;
  } else {
    weeklyTrend = `has decreased significantly by ${Math.abs(weeklyChange)} points`;
  }
  
  // Market interpretation based on current value
  let marketInterpretation = '';
  if (currentValue <= 20) {
    marketInterpretation = 'Extreme fear suggests a potential buying opportunity as markets often react irrationally when fear is high. Historically, extreme fear has marked market bottoms.';
  } else if (currentValue <= 40) {
    marketInterpretation = 'Fear in the market often precedes price stabilization. Investors should watch for signs of sentiment reversal which might signal a change in trend.';
  } else if (currentValue <= 60) {
    marketInterpretation = 'Neutral sentiment suggests market equilibrium. Neither fear nor greed is dominating, indicating a potentially balanced market.';
  } else if (currentValue <= 80) {
    marketInterpretation = 'Greed indicates growing optimism, which can lead to further price increases. However, increasing greed can also be a warning sign of potential corrections ahead.';
  } else {
    marketInterpretation = 'Extreme greed often precedes market corrections. When investors become overly optimistic, markets typically become overvalued and vulnerable to pullbacks.';
  }
  
  return `The current Fear & Greed Index stands at ${currentValue} (${currentClass}) and has ${dailyTrend} since yesterday. Compared to last week, the index ${weeklyTrend}. ${marketInterpretation}`;
};

export default MarketNews;

