import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class BybitConnectionTestSimple {
    
    private static final String API_KEY = "fVlAwEcmeYNRiA5Pna";
    private static final String SECRET_KEY = "v8Z3PNsf0HCmHaytA9fngMPl8shS7kbxsZ3J";
    private static final String BASE_URL = "https://api-testnet.bybit.com";
    private static final String RECV_WINDOW = "5000";
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    public static void main(String[] args) {
        System.out.println("=== Bybit API Connection Test ===");
        
        // Test 1: Get server time for sync
        testServerTime();
        
        // Test 2: Public endpoints (no auth required)
        testPublicEndpoints();
        
        // Test 3: Private endpoints (auth required)
        testPrivateEndpoints();
        
        System.out.println("\n=== Test completed ===");
    }
    
    private static void testServerTime() {
        System.out.println("\n--- Test 1: Server Time Sync ---");
        
        try {
            String url = BASE_URL + "/v5/market/time";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println("Status: " + response.statusCode());
            System.out.println("Response: " + response.body());
            
            if (response.statusCode() == 200) {
                // Simple JSON parsing using regex - timeSecond is a string in Bybit API
                String responseBody = response.body();
                Pattern timePattern = Pattern.compile("\"timeSecond\"\\s*:\\s*\"(\\d+)\"");
                Matcher matcher = timePattern.matcher(responseBody);
                
                if (matcher.find()) {
                    long serverTime = Long.parseLong(matcher.group(1)) * 1000; // Convert to milliseconds
                    long localTime = System.currentTimeMillis();
                    long timeDiff = Math.abs(serverTime - localTime);
                    
                    System.out.println("[SUCCESS] Server time retrieved successfully");
                    System.out.println("Server time: " + serverTime);
                    System.out.println("Local time: " + localTime);
                    System.out.println("Time difference: " + timeDiff + "ms");
                    
                    if (timeDiff > 1000) {
                        System.out.println("[WARNING] Time difference > 1 second - may cause auth issues");
                    } else {
                        System.out.println("[INFO] Time sync is good!");
                    }
                } else {
                    System.out.println("[ERROR] Could not parse server time from response");
                }
            } else {
                System.out.println("[ERROR] Server time test failed: " + response.statusCode());
            }
            
        } catch (Exception e) {
            System.out.println("[ERROR] Server time test error: " + e.getMessage());
        }
    }
    
    private static void testPublicEndpoints() {
        System.out.println("\n--- Test 2: Public Endpoints ---");
        
        // Test kline data - Bybit uses minutes for interval (60 = 1 hour)
        testPublicEndpoint("Kline Data", 
            "/v5/market/kline?category=spot&symbol=BTCUSDT&interval=60&limit=5");
        
        // Test tickers
        testPublicEndpoint("Ticker Data", 
            "/v5/market/tickers?category=spot&symbol=BTCUSDT");
        
        // Test orderbook
        testPublicEndpoint("Orderbook", 
            "/v5/market/orderbook?category=spot&symbol=BTCUSDT&limit=10");
        
        // Test mark price kline (futures) - using minutes
        testPublicEndpoint("Mark Price Kline", 
            "/v5/market/mark-price-kline?category=linear&symbol=BTCUSDT&interval=60&limit=5");
    }
    
    private static void testPublicEndpoint(String name, String endpoint) {
        try {
            String url = BASE_URL + endpoint;
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println(name + ": " + response.statusCode());
            
            if (response.statusCode() == 200) {
                String responseBody = response.body();
                if (responseBody.contains("\"retCode\":0")) {
                    System.out.println("[SUCCESS] " + name);
                } else if (responseBody.contains("\"retCode\"")) {
                    // Extract error message
                    Pattern msgPattern = Pattern.compile("\"retMsg\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher matcher = msgPattern.matcher(responseBody);
                    String errorMsg = matcher.find() ? matcher.group(1) : "Unknown error";
                    System.out.println("[ERROR] " + name + " - API Error: " + errorMsg);
                } else {
                    System.out.println("[SUCCESS] " + name + " (no retCode found, assuming success)");
                }
            } else {
                System.out.println("[ERROR] " + name + " - HTTP Error: " + response.statusCode());
                System.out.println("Response: " + response.body());
            }
            
        } catch (Exception e) {
            System.out.println("[ERROR] " + name + " - Exception: " + e.getMessage());
        }
    }
    
    private static void testPrivateEndpoints() {
        System.out.println("\n--- Test 3: Private Endpoints ---");
        
        // Get server time for accurate timestamp
        long serverTime = getServerTime();
        
        // Test wallet balance
        testPrivateEndpoint("Wallet Balance", "GET", 
            "/v5/account/wallet-balance?accountType=SPOT", null, serverTime);
        
        // Test open orders
        testPrivateEndpoint("Open Orders", "GET", 
            "/v5/order/realtime?category=spot", null, serverTime);
    }
    
    private static void testPrivateEndpoint(String name, String method, String endpoint, String body, long serverTime) {
        try {
            String timestamp = String.valueOf(serverTime != 0 ? serverTime : System.currentTimeMillis());
            String queryString = endpoint.contains("?") ? endpoint.substring(endpoint.indexOf("?") + 1) : "";
            String signature = generateSignature(timestamp, queryString, body);
            
            String url = BASE_URL + endpoint;
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-BAPI-API-KEY", API_KEY)
                    .header("X-BAPI-TIMESTAMP", timestamp)
                    .header("X-BAPI-SIGN", signature)
                    .header("X-BAPI-RECV-WINDOW", RECV_WINDOW);
            
            if ("POST".equals(method) && body != null) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                requestBuilder.GET();
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            System.out.println(name + ": " + response.statusCode());
            
            if (response.statusCode() == 200) {
                String responseBody = response.body();
                
                // Extract retCode
                Pattern retCodePattern = Pattern.compile("\"retCode\"\\s*:\\s*(\\d+)");
                Matcher retCodeMatcher = retCodePattern.matcher(responseBody);
                
                if (retCodeMatcher.find()) {
                    int retCode = Integer.parseInt(retCodeMatcher.group(1));
                    
                    if (retCode == 0) {
                        System.out.println("[SUCCESS] " + name);
                    } else {
                        // Extract error message
                        Pattern msgPattern = Pattern.compile("\"retMsg\"\\s*:\\s*\"([^\"]+)\"");
                        Matcher msgMatcher = msgPattern.matcher(responseBody);
                        String retMsg = msgMatcher.find() ? msgMatcher.group(1) : "Unknown error";
                        
                        System.out.println("[ERROR] " + name + " - API Error " + retCode + ": " + retMsg);
                        
                        // Provide specific guidance for common errors
                        if (retCode == 10002) {
                            System.out.println("   [INFO] Timestamp sync issue - server time: " + serverTime + ", local time: " + System.currentTimeMillis());
                        } else if (retCode == 10003) {
                            System.out.println("   [INFO] Invalid API key or permissions");
                        } else if (retCode == 10004) {
                            System.out.println("   [INFO] Invalid signature");
                        }
                    }
                } else {
                    System.out.println("[ERROR] " + name + " - Could not parse response");
                }
            } else {
                System.out.println("[ERROR] " + name + " - HTTP Error: " + response.statusCode());
                System.out.println("Response: " + response.body());
            }
            
        } catch (Exception e) {
            System.out.println("[ERROR] " + name + " - Exception: " + e.getMessage());
        }
    }
    
    private static long getServerTime() {
        try {
            String url = BASE_URL + "/v5/market/time";
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                String responseBody = response.body();
                Pattern timePattern = Pattern.compile("\"timeSecond\"\\s*:\\s*\"(\\d+)\"");
                Matcher matcher = timePattern.matcher(responseBody);
                
                if (matcher.find()) {
                    return Long.parseLong(matcher.group(1)) * 1000; // Convert to milliseconds
                }
            }
        } catch (Exception e) {
            System.out.println("Warning: Could not get server time, using local time");
        }
        
        return System.currentTimeMillis();
    }
    
    private static String generateSignature(String timestamp, String queryString, String body) {
        try {
            // Bybit signature format: timestamp + apiKey + recvWindow + queryString/body
            String payload = timestamp + API_KEY + RECV_WINDOW + 
                           (body != null ? body : (queryString != null ? queryString : ""));
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET_KEY.getBytes(), "HmacSHA256");
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(payload.getBytes());
            return HexFormat.of().formatHex(hash).toLowerCase();
            
        } catch (Exception e) {
            throw new RuntimeException("Error generating signature", e);
        }
    }
} 