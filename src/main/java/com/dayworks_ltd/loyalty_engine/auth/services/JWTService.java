package com.dayworks_ltd.loyalty_engine.auth.services;

import com.dayworks_ltd.loyalty_engine.auth.model.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static io.jsonwebtoken.Jwts.*;

@Slf4j
@Service
public class JWTService {

    @Value("${loyalty-engine.secret-key}")
    private String secretKey;

    @Value("${loyalty-engine.jwt-expiration}")
    private long jwtExpiration;

    public JWTService() {
        log.info("JWTService initialized - Version: Non-Expiring Tokens (2025-10-04)");
        System.out.println("JWTService initialized - Console check");
    }

    public String generateToken(CustomUserDetails user) {
        log.info("Generating token for user: {}", user.getUsername());
        System.out.println("Generating token for user: " + user.getUsername());
        Map<String, Object> claims = new HashMap<>();
        long currentTime = System.currentTimeMillis();
        Date issuedAt = new Date(currentTime);

        log.info("JWT Generated - User: {}, Issued: {}, No Expiration",
                user.getUsername(), issuedAt);
        System.out.println("JWT Generated - User: " + user.getUsername() + ", Issued: " + issuedAt);
        System.out.println("User  ID: " + user.getUserId() + ", Issued: " + issuedAt);

        String businessType = user.getBusinessType(); // null for non-merchants
        if (businessType != null) {
            claims.put("businessType", businessType);
        }
        claims.put("isWholesaler", user.getIsWholesaler());

        String token = builder()
                .claims(claims)
                .claim("id", user.getUserId())
                .claim("role", user.getUserRole())
                .subject(user.getUsername())
                .issuer("Loyalty-engine")
                .issuedAt(issuedAt)
                .expiration(new Date(currentTime + jwtExpiration))
                .signWith(generateKey())
                .compact();
        System.out.println("Generated token: " + token);
        return token;
    }

    private SecretKey generateKey() {
        // Your secret is plain text in application.properties → use getBytes(StandardCharsets.UTF_8)
        byte[] keyBytes = getSecretKey().getBytes(StandardCharsets.UTF_8);

        // This line guarantees the key is strong enough for HS512 (throws clear error if not)
        return Keys.hmacShaKeyFor(keyBytes); // jjwt auto-selects HS384 or HS512 based on length
    }

//    private SecretKey generateKey() {
//        return Keys.hmacShaKeyFor(getSecretKey().getBytes());
//    }

    private String getSecretKey() {
        return this.secretKey;
    }

    public String extractUsername(String jwtToken) {
        return extractClaims(jwtToken, Claims::getSubject);
    }

    private <T> T extractClaims(String jwtToken, Function<Claims, T> claimsResolver) {
        Claims claims = extractClaims(jwtToken);
        return claimsResolver.apply(claims);
    }

    private Claims extractClaims(String jwtToken) {
        return Jwts.parser()
                .verifyWith(generateKey())
                .setAllowedClockSkewSeconds(300) // 5 minutes tolerance
                .build()
                .parseSignedClaims(jwtToken)
                .getPayload();
    }


    public boolean isTokenValid(String jwtToken, UserDetails user) {
        final String username = extractUsername(jwtToken);
        return username.equals(user.getUsername()) && !isTokenExpired(jwtToken);
    }



    public boolean isTokenExpired(String jwtToken) {
        return extractExpiration(jwtToken).before(new Date());
    }

    private Date extractExpiration(String jwtToken) {
        return extractClaims(jwtToken, Claims::getExpiration);
    }





//    public boolean isTokenExpired(String jwtToken) {
//        Date expiration = extractExpiration(jwtToken);
//        // If expiration is null, token never expires
//        if (expiration == null) {
//            return false;
//        }
//        return expiration.before(new Date());
//    }

//    public boolean isTokenExpired(String jwtToken) {
//        Date expiration = extractExpiration(jwtToken);
//        return expiration != null && expiration.before(new Date());
//    }

//    private Date extractExpiration(String jwtToken) {
//        try {
//            return extractClaims(jwtToken, Claims::getExpiration);
//        } catch (Exception e) {
//            log.warn("No expiration claim in token: {}", e.getMessage());
//            return null; // Non-expiring token
//        }
//    }
}