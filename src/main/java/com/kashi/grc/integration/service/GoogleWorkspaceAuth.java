package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * GoogleWorkspaceAuth — service-account (domain-wide delegation) auth for the
 * Google Admin SDK Directory API. No Google SDK needed: we sign the SA JWT with
 * RS256 (via jjwt, already in the pom) and exchange it for an access token,
 * impersonating a super-admin ("subject") so the token can read directory data.
 *
 * authConfig JSON (paste the fields from the downloaded service-account key +
 * the admin to impersonate):
 *   {
 *     "clientEmail":   "svc@project.iam.gserviceaccount.com",
 *     "privateKey":    "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
 *     "delegatedAdmin":"superadmin@yourdomain.com",   // super-admin to impersonate
 *     "customerId":    "my_customer"                   // optional; default "my_customer"
 *   }
 *
 * Setup (super-admin, one-time):
 *   - Create a GCP service account + JSON key.
 *   - Enable the Admin SDK API in that GCP project.
 *   - In Google Admin console → Security → API controls → Domain-wide delegation,
 *     add the SA client ID with read-only scope:
 *       https://www.googleapis.com/auth/admin.directory.user.readonly
 */
final class GoogleWorkspaceAuth {

    private GoogleWorkspaceAuth() {}

    static final String DIR_BASE = "https://admin.googleapis.com/admin/directory/v1";
    static final String USER_READONLY_SCOPE =
            "https://www.googleapis.com/auth/admin.directory.user.readonly";

    /** Sign the SA assertion JWT and exchange it for an access token.
     *  Accepts the WHOLE downloaded service-account JSON (serviceAccountJson) plus
     *  the super-admin to impersonate (adminEmail) — matching the connect form.
     *  Also tolerates pre-split fields (clientEmail/privateKey/delegatedAdmin). */
    static String accessToken(JsonNode auth, RestTemplate rt) {
        try {
            String clientEmail, pemKey, delegatedAdmin;

            if (auth.has("serviceAccountJson")) {
                // The frontend stores the full SA key JSON as a string.
                JsonNode saNode = auth.get("serviceAccountJson");
                JsonNode sa = saNode.isTextual()
                        ? new com.fasterxml.jackson.databind.ObjectMapper().readTree(saNode.asText())
                        : saNode;   // tolerate object form too
                clientEmail = sa.get("client_email").asText();
                pemKey      = sa.get("private_key").asText();
                delegatedAdmin = auth.has("adminEmail") ? auth.get("adminEmail").asText()
                        : auth.path("delegatedAdmin").asText();
            } else {
                // Pre-split fields (backward compatible).
                clientEmail    = auth.get("clientEmail").asText();
                pemKey         = auth.get("privateKey").asText();
                delegatedAdmin = auth.get("delegatedAdmin").asText();
            }

            PrivateKey key = parsePrivateKey(pemKey);
            Instant now = Instant.now();

            String assertion = Jwts.builder()
                    .setIssuer(clientEmail)
                    .claim("scope", USER_READONLY_SCOPE)
                    .setAudience("https://oauth2.googleapis.com/token")
                    .setSubject(delegatedAdmin)                 // impersonate super-admin
                    .setIssuedAt(Date.from(now))
                    .setExpiration(Date.from(now.plusSeconds(3600)))
                    .signWith(key, SignatureAlgorithm.RS256)
                    .compact();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", assertion);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, headers);

            ResponseEntity<JsonNode> resp = rt.exchange(
                    "https://oauth2.googleapis.com/token", HttpMethod.POST, req, JsonNode.class);
            JsonNode body = resp.getBody();
            if (body == null || !body.has("access_token")) {
                throw new IllegalStateException("Google token exchange failed: "
                        + (body != null ? body.toString() : "empty response"));
            }
            return body.get("access_token").asText();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("Google SA auth error: " + e.getMessage(), e);
        }
    }

    static HttpEntity<Void> authGet(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    /** Parse a PKCS#8 PEM private key (the format Google SA JSON keys use). */
    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String clean = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(clean);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}
