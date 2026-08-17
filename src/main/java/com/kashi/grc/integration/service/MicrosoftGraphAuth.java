package com.kashi.grc.integration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * MicrosoftGraphAuth — shared OAuth2 (client-credentials) helper for Microsoft
 * Entra ID (Azure AD) integration checks.
 *
 * The app is registered in the Entra admin center with application permissions
 * (admin-consented). We use the client-credentials grant to get an app-only
 * access token for Microsoft Graph — no user is involved.
 *
 * authConfig JSON:
 *   {
 *     "tenantId":     "xxxxxxxx-....",   // the Microsoft 365 tenant (directory) ID
 *     "clientId":     "xxxxxxxx-....",   // the registered app's Application (client) ID
 *     "clientSecret": "..."              // a client secret from the app registration
 *   }
 *
 * Required Graph application permissions (admin consent):
 *   User.Read.All, UserAuthenticationMethod.Read.All
 * (For the MFA registration report the reporting API alternatively needs
 *  AuditLog.Read.All + Reports.Read.All; this helper works for both approaches.)
 */
final class MicrosoftGraphAuth {

    private MicrosoftGraphAuth() {}

    static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";

    /** Client-credentials token for Microsoft Graph. */
    static String accessToken(JsonNode auth, RestTemplate rt) {
        String tenantId     = auth.get("tenantId").asText();
        String clientId     = auth.get("clientId").asText();
        String clientSecret = auth.get("clientSecret").asText();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type",    "client_credentials");
        form.add("scope",         "https://graph.microsoft.com/.default");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> req = new HttpEntity<>(form, headers);

        String url = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";
        ResponseEntity<JsonNode> resp = rt.exchange(url, HttpMethod.POST, req, JsonNode.class);

        JsonNode body = resp.getBody();
        if (body == null || !body.has("access_token")) {
            throw new IllegalStateException("Microsoft token request failed: "
                    + (body != null ? body.toString() : "empty response"));
        }
        return body.get("access_token").asText();
    }

    /** Build an authorized GET request entity for Graph. */
    static HttpEntity<Void> authGet(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }
}
