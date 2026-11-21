package com.kashi.grc.siteassistant.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.siteassistant.dto.SiteChatDtos.SyncRequest;
import com.kashi.grc.siteassistant.service.SiteAssistantIndexer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * The website's deploy pushes its pages here (tools/sync-assistant.mjs in the
 * website repo). Machine-to-machine: no user session, so the credential is a
 * shared key in the X-Site-Sync-Key header, compared in constant time. With
 * kashi.site-assistant.sync-key unset the endpoint is switched off.
 */
@RestController
@RequestMapping("/v1/site-assistant/sync")
@Tag(name = "Website assistant — page sync")
@RequiredArgsConstructor
public class SiteAssistantSyncController {

    private final SiteAssistantIndexer indexer;

    @Value("${kashi.site-assistant.sync-key:}")
    private String syncKey;

    @PostMapping("/pages")
    @Operation(summary = "Replace the assistant's copy of the website's pages (complete=true retires missing pages)")
    public ApiResponse<Map<String, Object>> pages(@RequestHeader(value = "X-Site-Sync-Key", required = false) String key,
                                                  @RequestBody SyncRequest body) {
        if (syncKey == null || syncKey.isBlank()) {
            throw new BusinessException("SITE_SYNC_DISABLED",
                    "Page sync is not configured on this server (kashi.site-assistant.sync-key).", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), syncKey.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("SITE_SYNC_FORBIDDEN", "Invalid sync key.", HttpStatus.FORBIDDEN);
        }
        return ApiResponse.success(indexer.syncPages(body));
    }
}
