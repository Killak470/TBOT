# Trading Bot Dashboard

A comprehensive visualization platform for monitoring and analyzing automated trading strategies.

## Features

### Position Dashboard
- Real-time position monitoring
- Portfolio performance tracking
- Interactive charts for market analysis
- Strategy performance comparison

### Trade History Visualization
- Advanced filtering and sorting capabilities
- Trade statistics calculation
- Data export to CSV

### Strategy Performance Comparison
- Visual comparison of trading strategies
- Performance metrics calculation
- Time range selection and normalization

### Real-time Updates
- WebSocket integration for live data
- Streaming position and order updates
- Performance metrics in real-time

### Reporting
- CSV export for trade history
- Performance report generation
- Metrics calculation and visualization

## Installation

1. Install the required dependencies:
   ```
   npm install
   ```

2. Start the development server:
   ```
   npm start
   ```

3. Build for production:
   ```
   npm run build
   ```

## Usage

1. Navigate to the Position Dashboard to view your current positions and portfolio performance.
2. Use the Strategy Comparison tool to analyze the performance of different trading strategies.
3. Explore the Trade History Visualization to filter and analyze past trades.
4. Export data or generate reports using the reporting features.

For detailed usage instructions, see the [Dashboard Documentation](docs/dashboard-usage.md).

## Architecture

- **Frontend**: React with TypeScript
- **State Management**: React Hooks and Context
- **Styling**: Tailwind CSS with custom retro theme
- **Charts**: ApexCharts for performance visualization
- **Real-time Updates**: WebSocket integration
- **Reporting**: CSV export and PDF generation

## Directory Structure

- `/src/components/charts`: Chart components for data visualization
- `/src/components/pages`: Page components including dashboards
- `/src/services`: Service layer for WebSocket and reporting
- `/docs`: Documentation files

## Development

To add new features to the dashboard:

1. Create new components in the appropriate directories.
2. Update the routing in `App.tsx` to include new pages.
3. Extend the WebSocket service to handle new data types if needed.
4. Update the documentation to reflect new features.

## License

This project is licensed under the MIT License - see the LICENSE file for details. 