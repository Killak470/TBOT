package com.tradingbot.backend.model;

// Using BigDecimal for financial data is generally preferred, but sticking to double for now
// to match the existing structure in SniperEntryStrategy. Consider refactoring to BigDecimal later.
public class Candlestick {
    private long openTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private long closeTime; // Optional, as Bybit's kline structure might not provide it directly for each candle

    // Constructors
    public Candlestick() {
    }

    public Candlestick(long openTime, double open, double high, double low, double close, double volume, long closeTime) {
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.closeTime = closeTime;
    }

    // Getters
    public long getOpenTime() {
        return openTime;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public double getVolume() {
        return volume;
    }

    public long getCloseTime() {
        return closeTime;
    }

    // Setters
    public void setOpenTime(long openTime) {
        this.openTime = openTime;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public void setCloseTime(long closeTime) {
        this.closeTime = closeTime;
    }

    @Override
    public String toString() {
        return "Candlestick{" +
                "openTime=" + openTime +
                ", open=" + open +
                ", high=" + high +
                ", low=" + low +
                ", close=" + close +
                ", volume=" + volume +
                ", closeTime=" + closeTime +
                '}';
    }
} 