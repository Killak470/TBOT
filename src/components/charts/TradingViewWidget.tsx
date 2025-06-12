import React, { useEffect, useRef } from 'react';

interface TradingViewWidgetProps {
  symbol: string;
  interval?: string;
  theme?: 'light' | 'dark';
  autosize?: boolean;
  height?: number;
  width?: number;
}

declare global {
  interface Window {
    TradingView: any;
  }
}

const TradingViewWidget: React.FC<TradingViewWidgetProps> = ({
  symbol = 'MEXC:BTCUSDT',
  interval = 'D',
  theme = 'dark',
  autosize = true,
  height = 500,
  width = 980,
}) => {
  const container = useRef<HTMLDivElement>(null);
  const scriptRef = useRef<HTMLScriptElement | null>(null);

  useEffect(() => {
    if (!container.current) return;

    // Clean up any existing widgets
    if (container.current.childNodes.length > 0) {
      container.current.innerHTML = '';
    }

    // Load TradingView script if not already loaded
    if (!window.TradingView) {
      scriptRef.current = document.createElement('script');
      scriptRef.current.src = 'https://s3.tradingview.com/tv.js';
      scriptRef.current.async = true;
      scriptRef.current.onload = () => createWidget();
      document.head.appendChild(scriptRef.current);
    } else {
      createWidget();
    }

    function createWidget() {
      if (!container.current || !window.TradingView) return;

      // Apply retro styling to TradingView widget
      const customCSS = `
        .tv-chart-container iframe {
          filter: sepia(0.2) hue-rotate(65deg) !important;
        }
      `;
      const style = document.createElement('style');
      style.textContent = customCSS;
      document.head.appendChild(style);

      new window.TradingView.widget({
        autosize,
        height: autosize ? '100%' : height,
        width: autosize ? '100%' : width,
        symbol,
        interval,
        timezone: 'exchange',
        theme: theme === 'dark' ? 'dark' : 'light',
        style: '1',
        locale: 'en',
        toolbar_bg: '#000',
        enable_publishing: false,
        allow_symbol_change: true,
        container_id: container.current.id,
        hide_side_toolbar: false,
        studies: [
          'RSI@tv-basicstudies',
          'MACD@tv-basicstudies',
          'MassIndex@tv-basicstudies'
        ],
        overrides: {
          // Override chart colors for retro theme
          "paneProperties.background": "#000000",
          "paneProperties.vertGridProperties.color": "#1E2125",
          "paneProperties.horzGridProperties.color": "#1E2125",
          "symbolWatermarkProperties.transparency": 90,
          "scalesProperties.textColor": "#22ff22",
          "mainSeriesProperties.candleStyle.upColor": "#22ff22",
          "mainSeriesProperties.candleStyle.downColor": "#ff2222",
          "mainSeriesProperties.candleStyle.wickUpColor": "#22ff22",
          "mainSeriesProperties.candleStyle.wickDownColor": "#ff2222"
        }
      });
    }

    // Cleanup
    return () => {
      if (scriptRef.current && document.head.contains(scriptRef.current)) {
        document.head.removeChild(scriptRef.current);
      }
    };
  }, [symbol, interval, theme, autosize, height, width]);

  return (
    <div 
      id={`tradingview_widget_${Math.floor(Math.random() * 1000000)}`} 
      ref={container} 
      className="h-full w-full"
    />
  );
};

export default TradingViewWidget;

