// Fetch futures klines with authenticated request
try {
  console.log(`Fetching futures data for ${selectedPair} with interval ${TIMEFRAME_MAP[timeframe]}`);
  
  // Simple retry logic directly in the component
  let retries = 0;
  const maxRetries = 2;
  let futuresResponse = null;
  
  while (retries <= maxRetries) {
    try {
      console.log(`Futures API attempt ${retries + 1}/${maxRetries + 1}`);
      futuresResponse = await api.getFuturesKlines(
        selectedPair, 
        TIMEFRAME_MAP[timeframe], 
        Math.floor((now - lookbackPeriod) / 1000), // Convert to seconds for futures API
        Math.floor(now / 1000)
      );
      break; // Success - exit the retry loop
    } catch (retryError) {
      retries++;
      if (retries <= maxRetries) {
        const delay = Math.pow(2, retries) * 1000; // Exponential backoff
        console.log(`Futures API retry in ${delay}ms...`);
        await new Promise(resolve => setTimeout(resolve, delay));
      } else {
        throw retryError; // Rethrow after all retries fail
      }
    }
  }
  
  response = futuresResponse;
  
  if (response.data && response.data.data && Array.isArray(response.data.data)) {
    // Format futures data
    const formattedData = response.data.data.map((kline: any) => ({
      time: kline.time * 1000, // Convert seconds to milliseconds
      open: parseFloat(kline.open),
      high: parseFloat(kline.high),
      low: parseFloat(kline.low),
      close: parseFloat(kline.close),
      volume: parseFloat(kline.volume)
    }));
    
    setKlineData(formattedData);
    setIsLoading(false);
    return;
  } else {
    throw new Error("Invalid futures data format received");
  }
} catch (futuresError: any) {
  console.error(`Futures API error for ${selectedPair}:`, futuresError);
  // For futures specifically, we'll immediately fall back to mock data
  // and provide a more specific error message
  const errorMsg = futuresError.message || 'Futures data unavailable';
  setError(`${errorMsg} - Using simulated data`);
  
  // Generate fallback mock data
  const mockData = generateMockData();
  setKlineData(mockData);
  setIsLoading(false);
  return;
} 