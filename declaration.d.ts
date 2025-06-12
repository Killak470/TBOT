// Module declarations for libraries without TypeScript type definitions

declare module 'sockjs-client' {
  export default class SockJS {
    constructor(url: string, _reserved?: any, options?: {
      transports?: string | string[],
      timeout?: number,
      server?: string
    });
    close(): void;
    send(data: string): void;
    onopen: () => void;
    onmessage: (e: {data: string}) => void;
    onclose: () => void;
    onerror: (error: any) => void;
  }
}

declare module '@stomp/stompjs' {
  export interface StompHeaders {
    [key: string]: string;
  }

  export interface StompFrame {
    command: string;
    headers: StompHeaders;
    body: string;
  }

  export interface StompSubscription {
    id: string;
    unsubscribe(): void;
  }

  export interface StompMessage {
    command: string;
    headers: StompHeaders;
    body: string;
    ack(): void;
    nack(): void;
  }

  export interface ClientOptions {
    brokerURL?: string;
    connectHeaders?: StompHeaders;
    disconnectHeaders?: StompHeaders;
    heartbeatIncoming?: number;
    heartbeatOutgoing?: number;
    reconnectDelay?: number;
    debug?: (msg: string) => void;
    webSocketFactory?: () => any;
    onConnect?: (frame: StompFrame) => void;
    onDisconnect?: (frame: StompFrame) => void;
    onStompError?: (frame: StompFrame) => void;
    onWebSocketClose?: () => void;
    onWebSocketError?: (error: any) => void;
    logRawCommunication?: boolean;
    forceBinaryWSFrames?: boolean;
    appendMissingNULLonIncoming?: boolean;
  }

  export class Client {
    connected: boolean;
    
    constructor(config?: ClientOptions);
    configure(config: ClientOptions): void;
    activate(): void;
    deactivate(): void;
    subscribe(destination: string, callback: (message: any) => void, headers?: StompHeaders): StompSubscription;
    unsubscribe(id: string, headers?: StompHeaders): void;
    publish(destination: string, body?: string, headers?: StompHeaders): void;
    watchForReceipt(receiptId: string, callback: (frame: StompFrame) => void): void;
    onConnect: (frame: StompFrame) => void;
    onDisconnect: (frame: StompFrame) => void;
    onStompError: (frame: StompFrame) => void;
  }
} 