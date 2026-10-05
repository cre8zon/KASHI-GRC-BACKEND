package com.kashi.grc.collab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The self-hosted LiveKit server behind in-app calls — access tokens and the
 * one server API call we make (who is in a room).
 *
 * ── CONFIGURATION (environment variables or application.properties) ──────────
 *   LIVEKIT_URL         wss://calls.example.com   (what browsers connect to)
 *   LIVEKIT_API_KEY     the key in the LiveKit server's config (keys:)
 *   LIVEKIT_API_SECRET  its secret — at least 32 characters
 *   LIVEKIT_TOKEN_TTL_SECONDS  default 900
 * Not configured → in-app calls are off: the UI hides them and every call
 * endpoint answers 503. Nothing else depends on LiveKit.
 *
 * ── WHY THIS IS SAFE TO EXPOSE ────────────────────────────────────────────────
 * A browser can only enter a room with a token, and tokens are minted here
 * only after CollabCallService has checked the caller may join that meeting
 * or room. A token names ONE room and expires after the TTL (LiveKit refreshes
 * it for participants already connected, so a long call is not cut off; a
 * leaked token is useless once expired). Recording is never granted.
 *
 * Tokens are HS256 JWTs (LiveKit's format), signed with the JDK's HMAC so no
 * extra library is needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveKitService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final ObjectMapper objectMapper;

    @Value("${livekit.url:}")
    private String url;

    @Value("${livekit.api-key:}")
    private String apiKey;

    @Value("${livekit.api-secret:}")
    private String apiSecret;

    @Value("${livekit.token-ttl-seconds:900}")
    private long ttlSeconds;

    public boolean enabled() {
        return !blank(url) && !blank(apiKey) && !blank(apiSecret) && apiSecret.length() >= 32;
    }

    public String url() { return url; }

    public long ttlSeconds() { return ttlSeconds; }

    /**
     * A token to join one room. identity is unique per connection
     * ("u{userId}.{random}") so the same person can join from two devices;
     * metadata carries the user id for the UI.
     */
    public String joinToken(Long userId, String displayName, String room, boolean canPublish) {
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("room", room);
        grant.put("roomJoin", true);
        grant.put("canPublish", canPublish);
        grant.put("canSubscribe", true);
        grant.put("canPublishData", true);
        grant.put("canUpdateOwnMetadata", false);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "u" + userId + "." + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36).substring(0, 6));
        claims.put("name", displayName);
        claims.put("metadata", "{\"userId\":" + userId + "}");
        claims.put("video", grant);
        return sign(claims, ttlSeconds);
    }

    /**
     * Who is in these rooms right now: room → distinct user ids. Missing from
     * the map when the server could not be asked (calls still work; the UI
     * simply shows no live count).
     */
    public Map<String, List<Long>> participants(List<String> rooms) {
        Map<String, List<Long>> out = new LinkedHashMap<>();
        if (!enabled() || rooms.isEmpty()) return out;
        String base = url.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://").replaceAll("/+$", "");
        for (String room : rooms) {
            try {
                Map<String, Object> grant = Map.of("room", room, "roomAdmin", true);
                String token = sign(Map.of("sub", "kashigrc-server", "video", grant), 60);
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/twirp/livekit.RoomService/ListParticipants"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("room", room))))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 404) { out.put(room, List.of()); continue; }   // room not open
                if (res.statusCode() / 100 != 2) continue;
                List<Long> ids = new ArrayList<>();
                JsonNode ps = objectMapper.readTree(res.body()).path("participants");
                for (JsonNode p : ps) {
                    String id = p.path("identity").asText("");
                    if (id.startsWith("u")) {
                        try {
                            Long uid = Long.parseLong(id.substring(1, id.contains(".") ? id.indexOf('.') : id.length()));
                            if (!ids.contains(uid)) ids.add(uid);
                        } catch (NumberFormatException ignored) { /* not one of ours */ }
                    }
                }
                out.put(room, ids);
            } catch (Exception e) {
                log.debug("[LIVEKIT] Could not list participants of {}: {}", room, e.getMessage());
            }
        }
        return out;
    }

    // ── JWT (HS256) ───────────────────────────────────────────────────────────

    private String sign(Map<String, Object> claims, long ttl) {
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> body = new LinkedHashMap<>(claims);
            body.put("iss", apiKey);
            body.put("nbf", now - 10);
            body.put("exp", now + ttl);
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String head = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64.encodeToString(objectMapper.writeValueAsBytes(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = b64.encodeToString(mac.doFinal((head + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return head + "." + payload + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign a LiveKit token", e);
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
