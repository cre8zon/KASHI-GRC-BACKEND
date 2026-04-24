package com.kashi.grc.common.config.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Handles JWT creation, parsing, and validation.
 *
 * Token payload:
 *   { user_id, tenant_id, email, roles, permissions, iat, exp }
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")         // 24 h default
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms:604800000}") // 7 days default
    private long refreshExpirationMs;

    // ── Build access token ────────────────────────────────────────
    public String generateAccessToken(Long userId, Long tenantId, String email,
                                       List<String> roleIds, List<String> permissions) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenant_id",   tenantId)
                .claim("email",       email)
                .claim("roles",       roleIds)
                .claim("permissions", permissions)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(secretKey())
                .compact();
    }

    // ── Build refresh token (minimal payload) ─────────────────────
    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(secretKey())
                .compact();
    }

    // ── Parse ─────────────────────────────────────────────────────
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    public Long getTenantId(String token) {
        return parseClaims(token).get("tenant_id", Long.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions(String token) {
        return (List<String>) parseClaims(token).get("permissions");
    }

    // ── Validate ──────────────────────────────────────────────────
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
        }
        return false;
    }

    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    // ── Key ───────────────────────────────────────────────────────
    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
}
