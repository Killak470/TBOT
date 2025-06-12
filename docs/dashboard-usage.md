# Trading Bot Dashboard Documentation

## Overview

The Trading Bot Dashboard provides a comprehensive visualization platform for monitoring and analyzing your automated trading strategies. This document will guide you through the features and functionality of the dashboard.

## Dashboard Components

### Position Dashboard

The Position Dashboard provides a real-time view of your current positions, portfolio performance, and trading activity.

**Features:**
- Portfolio Value Tracking
- Active Positions Monitoring
- Real-time Price Updates
- Trade History Visualization
- Strategy Performance Comparison

**How to Use:**
1. Navigate to the "Position Dashboard" from the main menu.
2. View your current positions and their performance metrics.
3. Click on any position to view its detailed chart.
4. Use the strategy comparison tool to analyze which strategies are performing best.
5. Export trade history or generate comprehensive reports as needed.

### Trade History Visualization

The Trade History component allows you to filter, sort, and analyze your past trades.

**Features:**
- Advanced Filtering by Symbol, Strategy, Side, and Date Range
- Sortable Columns for Custom Analysis
- Trade Statistics Overview
- Export to CSV Functionality
- Detailed Performance Report Generation

**How to Use:**
1. Apply filters to focus on specific trades, strategies, or time periods.
2. Sort columns by clicking on column headers.
3. View calculated statistics based on your filtered trades.
4. Export data to CSV for external analysis.
5. Generate performance reports for detailed insights.

### Strategy Comparison

The Strategy Comparison tool helps you evaluate the relative performance of different trading strategies.

**Features:**
- Visual Performance Comparison
- Time Range Selection
- Performance Normalization
- Key Metrics Display
- Interactive Selection

**How to Use:**
1. Select the time range for comparison.
2. Toggle normalization to compare percentage performance.
3. Click on strategy cards to include/exclude them from the chart.
4. Analyze key metrics such as win rate, profit factor, and max drawdown.

## Real-time Updates

The dashboard utilizes WebSocket connections to provide real-time updates to positions, account balances, and trade executions.

**Connection Status:**
- The connection status is displayed in the top right corner.
- "ONLINE" indicates an active connection.
- "OFFLINE" or "ERROR" indicates connection issues.

## Reporting Features

### CSV Export

Export your trade history to CSV format for external analysis.

**How to Use:**
1. Navigate to the Trade History section.
2. Click the "EXPORT CSV" button.
3. The file will automatically download to your computer.

### Performance Reports

Generate comprehensive performance reports with detailed metrics and analysis.

**How to Use:**
1. Navigate to the Trade History section.
2. Click the "GENERATE REPORT" button.
3. View the report or save it for future reference.

**Report Contents:**
- Performance Summary
- Win/Loss Statistics
- Profit Factor Analysis
- Drawdown Analysis
- Symbol Performance Breakdown
- Strategy Performance Breakdown
- Time Period Analysis

## Troubleshooting

If you encounter issues with the dashboard:

1. **No Data Displayed**: Ensure your WebSocket connection is active ("ONLINE" status).
2. **Chart Loading Issues**: Check that your browser supports the required JavaScript features.
3. **Export Failures**: Ensure you have adequate disk space and permissions.

For additional assistance, contact support at support@tradingbot.com. 