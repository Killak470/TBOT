import { EventEmitter } from 'events';

/**
 * WebSocketService - Manages WebSocket connection to the trading bot backend
 * 
 * This service handles:
 * - Connection and reconnection to the WebSocket server
 * - Processing incoming messages and emitting events
 * - Subscribing to specific updates (positions, orders, account)
 */
class WebSocketService {
  private static instance: WebSocketService;
  private socket: WebSocket | null = null;
  private reconnectTimeout: any = null;
  private eventEmitter = new EventEmitter();
  private isConnected = false;
  private messageQueue: any[] = [];
  private subscriptions: Set<string> = new Set();
  private baseUrl = '';
  
  // Singleton pattern
  private constructor() {
    // Private constructor to enforce singleton pattern
  }
  
  /**
   * Get the WebSocketService instance
   */
  public static getInstance(): WebSocketService {
    if (!WebSocketService.instance) {
      WebSocketService.instance = new WebSocketService();
    }
    return WebSocketService.instance;
  }
  
  /**
   * Initialize the WebSocket connection
   * @param baseUrl The base WebSocket URL
   */
  public init(baseUrl: string): void {
    this.baseUrl = baseUrl;
    this.connect();
  }
  
  /**
   * Connect to the WebSocket server
   */
  private connect(): void {
    if (this.socket) {
      this.socket.close();
    }
    
    try {
      this.socket = new WebSocket(this.baseUrl);
      
      this.socket.onopen = this.handleOpen.bind(this);
      this.socket.onmessage = this.handleMessage.bind(this);
      this.socket.onclose = this.handleClose.bind(this);
      this.socket.onerror = this.handleError.bind(this);
    } catch (error) {
      console.error('WebSocket connection error:', error);
      this.scheduleReconnect();
    }
  }
  
  /**
   * Handle WebSocket open event
   */
  private handleOpen(): void {
    console.log('WebSocket connected');
    this.isConnected = true;
    this.eventEmitter.emit('connection', { status: 'connected' });
    
    // Resubscribe to previous subscriptions
    this.subscriptions.forEach(channel => {
      this.subscribe(channel);
    });
    
    // Send any queued messages
    while (this.messageQueue.length > 0 && this.isConnected) {
      const message = this.messageQueue.shift();
      this.sendMessage(message);
    }
  }
  
  /**
   * Handle incoming WebSocket messages
   */
  private handleMessage(event: MessageEvent): void {
    try {
      const data = JSON.parse(event.data);
      
      // Emit event based on message type
      if (data && data.type) {
        this.eventEmitter.emit(data.type, data);
        this.eventEmitter.emit('message', data); // Also emit a general message event
      }
    } catch (error) {
      console.error('Error parsing WebSocket message:', error);
    }
  }
  
  /**
   * Handle WebSocket close event
   */
  private handleClose(event: CloseEvent): void {
    console.log(`WebSocket disconnected: ${event.code} ${event.reason}`);
    this.isConnected = false;
    this.eventEmitter.emit('connection', { status: 'disconnected', code: event.code, reason: event.reason });
    
    // Don't reconnect if closed normally
    if (event.code !== 1000) {
      this.scheduleReconnect();
    }
  }
  
  /**
   * Handle WebSocket error
   */
  private handleError(error: Event): void {
    console.error('WebSocket error:', error);
    this.eventEmitter.emit('connection', { status: 'error', error });
  }
  
  /**
   * Schedule a reconnection attempt
   */
  private scheduleReconnect(): void {
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
    }
    
    this.reconnectTimeout = setTimeout(() => {
      console.log('Attempting to reconnect WebSocket...');
      this.connect();
    }, 3000);
  }
  
  /**
   * Subscribe to a specific channel
   * @param channel The channel to subscribe to (e.g., 'positions', 'orders', 'account')
   */
  public subscribe(channel: string): void {
    this.subscriptions.add(channel);
    
    if (this.isConnected) {
      this.sendMessage({
        action: 'subscribe',
        channel
      });
    }
  }
  
  /**
   * Unsubscribe from a channel
   * @param channel The channel to unsubscribe from
   */
  public unsubscribe(channel: string): void {
    this.subscriptions.delete(channel);
    
    if (this.isConnected) {
      this.sendMessage({
        action: 'unsubscribe',
        channel
      });
    }
  }
  
  /**
   * Send a message to the WebSocket server
   * @param message The message to send
   */
  public sendMessage(message: any): void {
    if (this.isConnected && this.socket && this.socket.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    } else {
      // Queue the message to be sent when reconnected
      this.messageQueue.push(message);
    }
  }
  
  /**
   * Add an event listener
   * @param event The event to listen for
   * @param listener The callback function
   */
  public on(event: string, listener: (...args: any[]) => void): void {
    this.eventEmitter.on(event, listener);
  }
  
  /**
   * Remove an event listener
   * @param event The event to stop listening for
   * @param listener The callback function to remove
   */
  public off(event: string, listener: (...args: any[]) => void): void {
    this.eventEmitter.off(event, listener);
  }
  
  /**
   * Add a one-time event listener
   * @param event The event to listen for once
   * @param listener The callback function
   */
  public once(event: string, listener: (...args: any[]) => void): void {
    this.eventEmitter.once(event, listener);
  }
  
  /**
   * Check if the WebSocket is connected
   */
  public getConnectionStatus(): boolean {
    return this.isConnected;
  }
  
  /**
   * Close the WebSocket connection
   */
  public disconnect(): void {
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    
    if (this.socket) {
      this.socket.close(1000, 'Client disconnected');
      this.socket = null;
    }
    
    this.isConnected = false;
  }
}

export default WebSocketService.getInstance(); 