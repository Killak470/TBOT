# Trading Bot Codebase Review & Strategy Integration

## Project Overview
Review and enhance existing trading bot that uses Claude API for analysis with Bybit futures trading. You are not changing anything that already exists in the project. Consider this a extreme strategy to be implemented Goal: Transform $100 to $32,000 (or $1,000 to $32,000) in 30 days using high-frequency sniper entry strategies with minimal drawdown.
BEFORE CREATING ANYTHING NEW CHECK IF IT ALREADY EXISTS IN THE PROJECT, IF SO MAKE PROPER ADDITIONS.
## Core Requirements to Implement

### 1. Multi-Coin Continuous Scanning System
- **Coins to monitor**: BTC, ETH, SOL, KSM, MOVR, DOGE, WAL
- **Scanning frequency**: Every 1-3 minutes during high volatility, every 3-5 minutes during low volatility
- **Data collection**: Price action, volume spikes, order book depth, correlation analysis
- **Market session awareness**: Adjust scanning frequency based on Asian/European/US sessions

### 2. Sniper Entry Strategy Implementation
- **Entry criteria confluence**:
  - Support/resistance level + Fibonacci retracement (61.8%, 78.6%)
  - Volume spike confirmation (2x+ average volume)
  - Moving average confluence + multi-timeframe alignment
  - RSI oversold/overbought in trend direction
- **Ultra-tight stop losses**: 0.5-1% maximum from entry point
- **Risk/reward ratios**: Minimum 3:1, target 5:1 for premium setups

### 3. High-Frequency Trading Logic
- **Trade frequency**: 10-25 trades per day based on win rate
- **Win rate thresholds**:
  - 50-60% win rate: 5-8 trades/day
  - 60-70% win rate: 10-15 trades/day  
  - >70% win rate: 15-25 trades/day
- **Position sizing**:
  - Tier 1 signals: 1-2% risk, 20-30x leverage
  - Tier 2 signals: 0.5-1% risk, 30-50x leverage
  - Tier 3 signals: 0.3-0.5% risk, 50-100x leverage

### 4. Claude API Integration (Cost-Optimized)
- **API call frequency**: 10-15 calls per day maximum
- **Cost budget**: $3-7.50 per month using prompt caching and batching
- **Call structure**:
  - Batch analysis of 5-8 coins per API call
  - Cache general market analysis for 2-3 minutes
  - Emergency local execution if API response >3 seconds
- **Analysis timing**:
  - Market open analysis (2 calls)
  - Mid-session updates (4-6 calls) 
  - Position management (4-6 calls)
  - End-of-session review (2 calls)

### 5. Risk Management Framework
- **Portfolio heat**: Maximum 30% capital deployed across all positions
- **Single coin limit**: Never >5% in one position
- **Daily loss circuit breaker**: Stop trading if daily loss >10%
- **Consecutive loss protection**: Pause 2 hours after 3 consecutive losses
- **Drawdown scaling**: Reduce position sizes 50% if weekly drawdown >20%

### 6. Real-Time Position Management
- **Profit taking**: Close 50% at 2:1 R/R, let remainder run to 3-5:1
- **Trailing stops**: ATR-based dynamic stops
- **Intraday reinvestment**: Compound profits every 4-6 hours
- **Position scaling**: Increase sizes 10-20% after 3 consecutive winning days

### 7. Performance Targets
- **$100 starting capital**: 15-20% daily growth target, 22-25 day timeline
- **$1,000 starting capital**: 8-12% daily growth target, 20-24 day timeline
- **Minimum win rate required**: 65% to sustain aggressive growth targets

## Technical Implementation Requirements

### Code Architecture to Review/Implement:
1. **Multi-threaded scanning engine** for continuous market monitoring
2. **Hybrid API strategy** - separate thread for Claude analysis with shared cache
3. **Local algorithm execution** for time-sensitive entries/exits
4. **Robust error handling** for API failures and network issues
5. **Real-time risk monitoring** with automatic position adjustments
6. **Bybit API integration** with isolated margin and high leverage support
7. **Data persistence** for trade logging and performance tracking
8. **WebSocket connections** for real-time price feeds

### Data Structures Needed:
- Market state cache (updated by Claude analysis)
- Active position tracking with P&L monitoring  
- Signal generation queue with priority levels
- Risk metrics dashboard (heat map, drawdown tracking)
- Historical performance analytics

### Error Handling & Failsafes:
- API timeout fallbacks (use cached analysis)
- Network connectivity monitoring
- Exchange API rate limit management
- Position size validation before execution
- Emergency stop-loss triggers

## Output Requirements:
1. **Identify gaps** in current codebase for implementing this strategy
2. **Suggest architectural improvements** for high-frequency execution
3. **Provide implementation plan** with priority order
4. **Recommend specific libraries/frameworks** for optimal performance
5. **Create**

## TODO List for Sniper Strategy Implementation

- **MarketScannerController & MarketScannerService Review:**
    - Analyze `MarketScannerController`'s `ScheduledExecutorService` for suitability for general-purpose strategy execution.
    - Evaluate if `MarketScannerService`'s `CompletableFuture` usage for AI analysis is sufficient or if the main symbol loop needs parallelization.
- **RiskManagementService.updateRiskMetrics():**
    - Confirm the hourly `@Scheduled` task is appropriate and does not conflict with high-frequency strategy needs.
- **BotSignalService:**
    - Review its `@Scheduled` method for potential conflicts or integration points.
- **Multi-threaded Scanning Engine (Addressing NewTS.md Requirement):**
    - **Gap Analysis:** Current `StrategyExecutionService`'s `@Scheduled` task is single-threaded for symbol iteration.
    - **Dedicated ExecutorService for Sniper Strategy:** Implement a `ThreadPoolExecutor` for concurrent scanning of specified coins (BTC, ETH, SOL, KSM, MOVR, DOGE, WAL).
    - **Scheduler for Sniper Strategy:**
        - A new/modified scheduled task to submit scanning tasks for each coin to the dedicated `ExecutorService`.
        - **Variable Frequency Implementation:**
            - **Option A (Simpler):** Use a fixed, frequent schedule (e.g., every 1 minute). Strategy internally decides work based on volatility.
            - **Option B (More Complex):** Implement dynamic scheduling for each coin based on its assessed volatility.
    - **Market Session Awareness:** Incorporate logic to adjust scanning frequency/parameters based on Asian/European/US market sessions.
- **Hybrid API Strategy (Claude + Local Execution):**
    - **Claude API:**
        - Verify `MarketScannerService`'s `CompletableFuture` for Claude API calls.
        - Implement/verify shared cache for Claude analysis (2-3 minutes, Spring's `@Cacheable` or custom).
        - Ensure adherence to "10-15 calls per day maximum" and budget for Claude API.
    - **Local Algorithm Execution:**
        - Ensure core `SniperEntryStrategy` logic (S/R, Fib, MA, RSI, Volume) runs locally and quickly.
        - Confirm `MarketScannerService.getAnalysisWithRetry` handles "Emergency local execution if API response >3 seconds" (for Claude).
- **Data Structures (as per NewTS.md):**
    - **Market state cache:** Confirm Spring Cache or custom cache for Claude analysis.
    - **Active position tracking:** Enhance `StrategyExecutionService.activePositions` and `Position` model for real-time P&L if needed.
    - **Signal generation queue with priority levels (Tier 1, 2, 3):**
        - Decide if system-wide (new concept for `StrategyExecutionService`/`OrderManagementService`) or handled by `SniperEntryStrategy` (more aligned with `TradingStrategy` interface).
    - **Risk metrics dashboard data:** Ensure backend supplies data for heat map, drawdown. `RiskManagementService` to track portfolio heat (max 30% capital deployed).
    - **Historical performance analytics:** Verify `PositionRepository` and implement aggregation/analysis service if needed.
- **Architectural Changes for Scanning & Concurrency:**
    - Implement dedicated `ExecutorService` for Sniper Strategy Scanning.
    - Implement/refine Scheduler for Sniper Strategy.
    - Refine Caching for Claude API.
    - Enhance `RiskManagementService` for portfolio-level risk (heat, single coin limit).
- **SniperEntryStrategy.java Implementation:**
    - Define class structure and core logic.
    - Identify new helper methods/enhancements for `TechnicalAnalysisUtil` (e.g., ATR).
    - Identify new helper methods/enhancements for `RiskManagementService`.
    - Integrate isolated margin setting for Bybit.
- **Concurrent Scanning Mechanism:**
    - Tackle implementation after `SniperEntryStrategy` core is defined.
- **Resolve Linter Errors:**
    - Address all unresolved import errors in `SniperEntryStrategy.java` and `RiskManagementService.java` by adding necessary dependencies to `pom.xml` or correcting import statements. This is critical for compilation and further development.
      - `org.slf4j` (Logger, LoggerFactory)
      - `org.springframework` (beans, http, scheduling, stereotype, transaction)
      - `com.fasterxml.jackson.core` (JsonProcessingException)
      - `com.fasterxml.jackson.databind` (JsonNode, ObjectMapper)
      - `org.apache.commons.math3.stat` (correlation, descriptive)
    - Fix `The method save(Position) is undefined for the type PositionRepository` - this likely means checking the `PositionRepository` interface and its implementation, or ensuring it extends a Spring Data repository interface that provides `save`.