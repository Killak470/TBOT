package com.tradingbot.backend.service.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class SignatureUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public static String generateSignature(String secretKey, String httpMethod, String requestPath, String timestamp, String queryString, String requestBody) {
        String message;
        if ("POST".equalsIgnoreCase(httpMethod)) {
            message = timestamp + httpMethod + requestPath + (requestBody == null ? "" : requestBody);
        } else { // GET, DELETE
            message = timestamp + httpMethod + requestPath + (queryString == null ? "" : queryString);
        }
        return hmacSha256(secretKey, message);
    }

    // Overloaded for MEXC V3 Spot API which has a slightly different signature string construction
    public static String generateSpotV3Signature(String secretKey, String timestamp, String params) {
        // For MEXC V3, the signature is an HMAC SHA256 hash of the entire queryString
        // The params string should already include the timestamp
        return hmacSha256(secretKey, params);
    }

    // For MEXC Contract API V1
    public static String generateContractV1Signature(String secretKey, String accessKey, String timestamp, String requestBodyOrParams) {
        String message = accessKey + timestamp + (requestBodyOrParams == null ? "" : requestBodyOrParams);
        return hmacSha256(secretKey, message);
    }

    /**
     * Generate signature for API request
     */
    public static String generateSignature(Map<String, String> params, String secretKey) {
        try {
            // Sort parameters by key
            TreeMap<String, String> sortedParams = new TreeMap<>(params);
            
            // Create query string
            String queryString = sortedParams.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
            
            // Create HMAC SHA256 signature
            Mac hmacSha256 = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            hmacSha256.init(secretKeySpec);
            
            byte[] hash = hmacSha256.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
            
        } catch (Exception e) {
            throw new RuntimeException("Error generating signature: " + e.getMessage(), e);
        }
    }

    private static String hmacSha256(String secret, String message) {
        try {
            Mac sha256_HMAC = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            sha256_HMAC.init(secret_key);
            byte[] hash = sha256_HMAC.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Handle exceptions appropriately in a real application
            throw new RuntimeException("Error generating HMAC-SHA256 signature", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public static String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .filter(entry -> entry.getValue() != null) // Filter out null values
                .sorted(Map.Entry.comparingByKey()) // MEXC might require sorted params for GET
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }
}

