import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;

public class BybitConnectionTest {
    
    private static final String API_KEY = "fVlAwEcmeYNRiA5Pna";
    private static final String SECRET_KEY = "v8Z3PNsf0HCmHaytA9fngMPl8shS7kbxsZ3J";
    private static final String BASE_URL = "https://api.bybit.com/v5";
    
    public static void main(String[] args) {
        try {
            // Test 1: Public endpoint (no auth required)
            testPublicEndpoint();
            
            // Test 2: Private endpoint (with authentication)
            testPrivateEndpoint();
            
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void testPublicEndpoint() throws IOException, InterruptedException {
        System.out.println("=== Testing Bybit Public Endpoint ===");
        
        String url = BASE_URL + "/market/tickers?category=spot&symbol=BTCUSDT";
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("Status Code: " + response.statusCode());
        System.out.println("Response: " + response.body());
        System.out.println("=== Public Endpoint Test Complete ===\n");
    }
    
    private static void testPrivateEndpoint() throws Exception {
        System.out.println("=== Testing Bybit Private Endpoint ===");
        
        String timestamp = String.valueOf(System.currentTimeMillis());
        String recvWindow = "5000";
        String queryString = "accountType=SPOT";
        
        // Generate signature
        String payload = timestamp + API_KEY + recvWindow + queryString;
        String signature = generateSignature(payload, SECRET_KEY);
        
        String url = BASE_URL + "/account/wallet-balance?" + queryString;
        
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-BAPI-API-KEY", API_KEY)
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-SIGN", signature)
                .header("X-BAPI-RECV-WINDOW", recvWindow)
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        System.out.println("Status Code: " + response.statusCode());
        System.out.println("Response: " + response.body());
        System.out.println("=== Private Endpoint Test Complete ===");
    }
    
    private static String generateSignature(String payload, String secretKey) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
        mac.init(secretKeySpec);
        
        byte[] hash = mac.doFinal(payload.getBytes());
        return DatatypeConverter.printHexBinary(hash).toLowerCase();
    }
} 