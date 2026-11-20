package com.kashi.grc.uiconfig.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.uiconfig.domain.UserTablePreference;
import com.kashi.grc.uiconfig.repository.UserTablePreferenceRepository;
import com.kashi.grc.usermanagement.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A user's own table layouts — /v1/me scope, like notification preferences:
 * no permission needed, and every call reads and writes ONLY the caller's
 * rows (userId and tenantId always come from the JWT context, never from the
 * request).
 *
 * Frontend contract:
 *   GET    /v1/me/table-preferences/{tableKey}  → { tableKey, prefs } (prefs null = default)
 *   PUT    /v1/me/table-preferences/{tableKey}  → body: the prefs object; replaces it
 *   DELETE /v1/me/table-preferences/{tableKey}  → back to the table's default
 */
@RestController
@RequestMapping("/v1/me/table-preferences")
@Tag(name = "My Table Preferences",
        description = "Per-user column widths, visibility and order for each table")
@RequiredArgsConstructor
public class TablePreferenceController {

    /** Screen keys and similar: letters, digits and _ . : - only. */
    private static final Pattern TABLE_KEY = Pattern.compile("^[A-Za-z0-9_.:\\-]{1,120}$");
    /** A layout is a few column keys and numbers; anything bigger is not one. */
    private static final int MAX_PREFS_CHARS = 16_000;

    private final UserTablePreferenceRepository repository;
    private final UtilityService utilityService;
    private final ObjectMapper objectMapper;

    @GetMapping("/{tableKey}")
    @Operation(summary = "My saved layout for one table (prefs null = the table's default)")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(@PathVariable String tableKey) {
        checkKey(tableKey);
        User me = utilityService.getLoggedInDataContext();
        Object prefs = repository.findOne(me.getId(), tenantOf(me), tableKey)
                .map(p -> readJson(p.getPrefsJson()))
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.success(body(tableKey, prefs)));
    }

    @PutMapping("/{tableKey}")
    @Operation(summary = "Save my layout for one table (replaces what was saved)")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> put(
            @PathVariable String tableKey, @RequestBody JsonNode prefs) {
        checkKey(tableKey);
        if (prefs == null || !prefs.isObject()) {
            throw new BusinessException("TABLE_PREFS_INVALID",
                    "Table preferences must be a JSON object", HttpStatus.BAD_REQUEST);
        }
        String json = prefs.toString();
        if (json.length() > MAX_PREFS_CHARS) {
            throw new BusinessException("TABLE_PREFS_TOO_LARGE",
                    "Table preferences are too large", HttpStatus.BAD_REQUEST);
        }
        User me = utilityService.getLoggedInDataContext();
        Long tenantId = tenantOf(me);
        UserTablePreference row = repository.findOne(me.getId(), tenantId, tableKey)
                .orElseGet(() -> UserTablePreference.builder()
                        .userId(me.getId()).tenantId(tenantId).tableKey(tableKey).build());
        row.setPrefsJson(json);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
        return ResponseEntity.ok(ApiResponse.success(body(tableKey, prefs)));
    }

    @DeleteMapping("/{tableKey}")
    @Operation(summary = "Reset one table to its default layout")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> reset(@PathVariable String tableKey) {
        checkKey(tableKey);
        User me = utilityService.getLoggedInDataContext();
        repository.deleteOne(me.getId(), tenantOf(me), tableKey);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private static Long tenantOf(User me) {
        return me.getTenantId() != null ? me.getTenantId() : UserTablePreference.NO_TENANT;
    }

    private static void checkKey(String tableKey) {
        if (tableKey == null || !TABLE_KEY.matcher(tableKey).matches()) {
            throw new BusinessException("TABLE_KEY_INVALID",
                    "Invalid table key", HttpStatus.BAD_REQUEST);
        }
    }

    /** A stored row that no longer parses is treated as no row — the default. */
    private Object readJson(String json) {
        try { return objectMapper.readTree(json); }
        catch (Exception e) { return null; }
    }

    private static Map<String, Object> body(String tableKey, Object prefs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableKey", tableKey);
        m.put("prefs", prefs);
        return m;
    }
}
