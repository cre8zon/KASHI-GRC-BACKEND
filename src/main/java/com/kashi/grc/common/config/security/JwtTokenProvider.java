package com.kashi.grc.common.config.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * Handles JWT creation, parsing, and validation.
 *
 * Access token payload:
 *   { sub=user_id, tenant_id, email, roles, permissions, iat, exp }
 * Refresh token payload:
 *   { sub=user_id, type="refresh", iat, exp }
 *
 * A refresh token is signed with the same key as an access token, so the two
 * MUST be told apart by the "type" claim. Previously validateToken() accepted
 * either, which meant the 7-day refresh token worked as a bearer token on every
 * API call (and on the STOMP socket). validateToken() now rejects it; only
 * {@link #validateRefreshToken} accepts it.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /** Result of inspecting a bearer token — lets the 401 say WHY. */
    public enum TokenStatus { VALID, EXPIRED, INVALID }

    /** Request attribute the entry point reads to word its 401. */
    public static final String TOKEN_STATUS_ATTR = "kashi.auth.tokenStatus";

    private static final String CLAIM_TYPE   = "type";
    private static final String TYPE_REFRESH = "refresh";

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    /**
     * Access-token lifetime. application.properties has always set
     * app.jwt.expiration-milliseconds=3600000 (1 h), but this read
     * app.jwt.expiration-ms — a key nobody set — so every token silently got
     * the 24 h default. Both keys are honoured now (expiration-ms wins if both
     * exist); with silent refresh in place, the configured 1 h is safe to use.
     */
    @Value("${app.jwt.expiration-ms:${app.jwt.expiration-milliseconds:3600000}}")
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
                // jti makes each refresh token individually revocable
                // (logout, rotation) — see RevokedToken.
                .id(java.util.UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
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

    private static boolean isRefresh(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    // ── Validate ──────────────────────────────────────────────────

    /**
     * Inspects an ACCESS token. A refresh token is reported INVALID here — it
     * is a credential for /v1/auth/refresh only, never a bearer token.
     */
    public TokenStatus inspectAccessToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (isRefresh(claims)) {
                log.warn("Refresh token presented as a bearer token — rejected");
                return TokenStatus.INVALID;
            }
            return TokenStatus.VALID;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return TokenStatus.EXPIRED;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return TokenStatus.INVALID;
        }
    }

    /** True only for a valid, unexpired ACCESS token. */
    public boolean validateToken(String token) {
        return inspectAccessToken(token) == TokenStatus.VALID;
    }

    /** What a valid refresh token carries. jti is null for tokens minted before revocation existed. */
    public record RefreshClaims(Long userId, String jti, LocalDateTime expiresAt) { }

    /**
     * Parses a REFRESH token, or returns null when it is expired, tampered
     * with, or not a refresh token at all. Revocation (the jti denylist) is
     * checked by the caller, which has the repository.
     */
    public RefreshClaims parseRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            if (!isRefresh(claims)) return null;
            return new RefreshClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.getId(),
                    LocalDateTime.ofInstant(claims.getExpiration().toInstant(), ZoneId.systemDefault()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Refresh token rejected: {}", e.getMessage());
            return null;
        }
    }

    /** User id of a valid refresh token, or null. */
    public Long validateRefreshToken(String token) {
        RefreshClaims c = parseRefreshToken(token);
        return c == null ? null : c.userId();
    }

    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    /** The token's real expiry as a LocalDateTime (server zone) for API responses. */
    public LocalDateTime getExpirationDateTime(String token) {
        return LocalDateTime.ofInstant(getExpiration(token).toInstant(), ZoneId.systemDefault());
    }

    public long getAccessTokenTtlMs()  { return jwtExpirationMs; }
    public long getRefreshTokenTtlMs() { return refreshExpirationMs; }

    // ── Key ───────────────────────────────────────────────────────
    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
}
