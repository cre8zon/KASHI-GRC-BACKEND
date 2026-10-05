package com.kashi.grc.collab.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.collab.domain.CollabPlanItem;
import com.kashi.grc.collab.domain.CollabPlanLayout;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.domain.CollabWorkspaceMember;
import com.kashi.grc.collab.repository.CollabPlanLayoutRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceMemberRepository;
import com.kashi.grc.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The plan sheet's columns — configurable per workspace.
 *
 * ── BUILT-IN COLUMNS ──────────────────────────────────────────────────────────
 *   title, kind, parent, owner, start, end, status, progress, notes.
 *   They carry the plan itself (the timeline, My week, reminders and the
 *   engagement link read them), so they can be renamed, moved, resized and
 *   hidden — never removed. Task (title) is always shown.
 *
 * ── CUSTOM COLUMNS ────────────────────────────────────────────────────────────
 *   Up to 30 per workspace, keys "c_" + 8 characters, types:
 *     TEXT      up to 2000 characters
 *     NUMBER    any decimal
 *     DATE      YYYY-MM-DD
 *     SELECT    one of the column's options (up to 50)
 *     PERSON    a member of the workspace (user id)
 *     CHECKBOX  true / false
 *   Values live on each item (custom_json). Removing a column hides its values;
 *   adding a column with the same key back is not possible (new key each time),
 *   so stale values never resurface.
 *
 * Changing the layout: whoever can edit the plan (CollabPlanService). Filling
 * custom cells: plan editors and the item's owner, like status and notes.
 */
@Service
@RequiredArgsConstructor
public class CollabPlanColumnsService {

    static final List<String[]> BUILT_INS = List.of(
            new String[]{"title", "Task"},     new String[]{"kind", "Type"},
            new String[]{"parent", "Under phase"}, new String[]{"owner", "Owner"},
            new String[]{"start", "Start"},    new String[]{"end", "End"},
            new String[]{"status", "Status"},  new String[]{"progress", "%"},
            new String[]{"notes", "Notes"});
    static final Set<String> TYPES = Set.of("TEXT", "NUMBER", "DATE", "SELECT", "PERSON", "CHECKBOX");
    private static final int MAX_CUSTOM = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CollabPlanLayoutRepository      layoutRepository;
    private final CollabWorkspaceMemberRepository memberRepository;
    private final ObjectMapper                    objectMapper;

    // ══════════════════════ LAYOUT ═══════════════════════════════════════════

    /** The workspace's columns in display order: stored layout + any built-in it lacks. */
    public List<Map<String, Object>> columns(Long workspaceId) {
        List<Map<String, Object>> stored = layoutRepository.findByWorkspaceId(workspaceId)
                .map(l -> readList(l.getColumnsJson())).orElse(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> c : stored) {
            String key = str(c.get("key"));
            if (key == null || !seen.add(key)) continue;
            String[] b = builtIn(key);
            if (b == null && !key.startsWith("c_")) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", blank(str(c.get("label"))) ? (b != null ? b[1] : key) : str(c.get("label")));
            m.put("builtIn", b != null);
            m.put("type", b != null ? null : str(c.get("type")));
            m.put("options", b != null ? List.of() : c.getOrDefault("options", List.of()));
            m.put("width", c.get("width"));
            m.put("hidden", !"title".equals(key) && Boolean.TRUE.equals(c.get("hidden")));
            out.add(m);
        }
        for (String[] b : BUILT_INS) {
            if (seen.contains(b[0])) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", b[0]); m.put("label", b[1]); m.put("builtIn", true); m.put("type", null);
            m.put("options", List.of()); m.put("width", null); m.put("hidden", false);
            if ("title".equals(b[0])) out.add(0, m); else out.add(m);
        }
        return out;
    }

    /** Custom columns by key. */
    Map<String, Map<String, Object>> customColumns(Long workspaceId) {
        return columns(workspaceId).stream().filter(c -> !Boolean.TRUE.equals(c.get("builtIn")))
                .collect(Collectors.toMap(c -> (String) c.get("key"), c -> c, (a, b) -> a, LinkedHashMap::new));
    }

    /** Replaces the layout. Validates every column; new custom columns get a key. */
    List<Map<String, Object>> save(CollabWorkspace ws, Long userId, Object raw) {
        if (!(raw instanceof List<?> list)) throw bad("COLLAB_BAD_COLUMNS", "Expected a list of columns");
        Set<String> existingCustom = customColumns(ws.getId()).keySet();
        List<Map<String, Object>> clean = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int custom = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> c)) throw bad("COLLAB_BAD_COLUMNS", "Each column must be an object");
            String key = str(c.get("key"));
            String label = str(c.get("label"));
            label = label == null ? null : label.trim();
            if (label != null && label.length() > 60) throw bad("COLLAB_BAD_COLUMNS", "Column names are at most 60 characters");
            Map<String, Object> m = new LinkedHashMap<>();
            String[] b = key == null ? null : builtIn(key);
            if (b != null) {
                m.put("key", key);
                m.put("label", blank(label) ? b[1] : label);
            } else {
                if (key == null || key.isBlank()) key = newKey();
                else if (!existingCustom.contains(key)) throw bad("COLLAB_BAD_COLUMNS", "Unknown column " + key);
                if (blank(label)) throw bad("COLLAB_BAD_COLUMNS", "Every column needs a name");
                String type = str(c.get("type"));
                type = type == null ? "TEXT" : type.trim().toUpperCase();
                if (!TYPES.contains(type)) throw bad("COLLAB_BAD_COLUMNS", "Unknown column type " + type);
                if (++custom > MAX_CUSTOM) throw bad("COLLAB_BAD_COLUMNS", "At most " + MAX_CUSTOM + " columns of your own");
                m.put("key", key);
                m.put("label", label);
                m.put("type", type);
                if ("SELECT".equals(type)) {
                    List<String> opts = new ArrayList<>();
                    if (c.get("options") instanceof List<?> ol) {
                        for (Object x : ol) {
                            String v = str(x);
                            if (v != null && !v.isBlank() && !opts.contains(v.trim())) opts.add(v.trim().length() > 80 ? v.trim().substring(0, 80) : v.trim());
                        }
                    }
                    if (opts.isEmpty()) throw bad("COLLAB_BAD_COLUMNS", "A dropdown column needs at least one option");
                    if (opts.size() > 50) throw bad("COLLAB_BAD_COLUMNS", "At most 50 options per dropdown");
                    m.put("options", opts);
                }
            }
            if (!keys.add(key)) throw bad("COLLAB_BAD_COLUMNS", "A column appears twice");
            Object w = c.get("width");
            if (w instanceof Number n) m.put("width", Math.max(60, Math.min(600, n.intValue())));
            if (!"title".equals(key) && Boolean.TRUE.equals(c.get("hidden"))) m.put("hidden", true);
            clean.add(m);
        }
        CollabPlanLayout layout = layoutRepository.findByWorkspaceId(ws.getId()).orElseGet(() -> {
            CollabPlanLayout l = CollabPlanLayout.builder().workspaceId(ws.getId()).build();
            l.setTenantId(ws.getTenantId());
            return l;
        });
        layout.setColumnsJson(write(clean));
        layout.setUpdatedBy(userId);
        layoutRepository.save(layout);
        return columns(ws.getId());
    }

    // ══════════════════════ VALUES ═══════════════════════════════════════════

    /** The item's custom values, limited to columns that still exist. */
    Map<String, Object> values(CollabPlanItem i, Map<String, Map<String, Object>> cols) {
        Map<String, Object> all = readMap(i.getCustomJson());
        Map<String, Object> out = new LinkedHashMap<>();
        all.forEach((k, v) -> { if (cols.containsKey(k)) out.put(k, v); });
        return out;
    }

    /**
     * Merges {key: value} into the item (null or "" clears a cell). Returns the
     * changes as {label: [old, new]} for the history.
     */
    Map<String, String[]> apply(CollabWorkspace ws, CollabPlanItem i, Object raw) {
        Map<String, String[]> changes = new LinkedHashMap<>();
        if (raw == null) return changes;
        if (!(raw instanceof Map<?, ?> in)) throw bad("COLLAB_BAD_CUSTOM", "Expected {column: value}");
        Map<String, Map<String, Object>> cols = customColumns(ws.getId());
        Map<String, Object> cur = readMap(i.getCustomJson());
        Set<Long> members = null;
        for (Map.Entry<?, ?> e : in.entrySet()) {
            String key = String.valueOf(e.getKey());
            Map<String, Object> col = cols.get(key);
            if (col == null) throw bad("COLLAB_BAD_CUSTOM", "Unknown column " + key);
            String type = (String) col.get("type");
            if ("PERSON".equals(type) && members == null) {
                members = memberRepository.findByWorkspaceId(ws.getId()).stream()
                        .map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet());
            }
            Object v = coerce(col, e.getValue(), members);
            Object old = cur.get(key);
            if (v == null) cur.remove(key); else cur.put(key, v);
            if (!Objects.equals(str(old), str(v))) {
                changes.put(String.valueOf(col.get("label")), new String[]{str(old), str(v)});
            }
        }
        i.setCustomJson(cur.isEmpty() ? null : write(cur));
        return changes;
    }

    @SuppressWarnings("unchecked")
    static Object coerce(Map<String, Object> col, Object raw, Set<Long> members) {
        if (raw == null || (raw instanceof String s0 && s0.isBlank())) return null;
        String label = String.valueOf(col.get("label"));
        String s = raw.toString().trim();
        switch (String.valueOf(col.get("type"))) {
            case "NUMBER" -> {
                try { double d = Double.parseDouble(s.replace(",", "")); return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : d; }
                catch (NumberFormatException ex) { throw bad("COLLAB_BAD_CUSTOM", label + " takes a number"); }
            }
            case "DATE" -> {
                try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).toString(); }
                catch (RuntimeException ex) { throw bad("COLLAB_BAD_CUSTOM", label + " takes a date (YYYY-MM-DD)"); }
            }
            case "SELECT" -> {
                List<String> opts = (List<String>) col.getOrDefault("options", List.of());
                return opts.stream().filter(o -> o.equalsIgnoreCase(s)).findFirst()
                        .orElseThrow(() -> bad("COLLAB_BAD_CUSTOM", label + " must be one of: " + String.join(", ", opts)));
            }
            case "PERSON" -> {
                long id;
                try { id = Long.parseLong(s); } catch (NumberFormatException ex) { throw bad("COLLAB_BAD_CUSTOM", label + " takes a workspace member"); }
                if (members != null && !members.contains(id)) throw bad("COLLAB_BAD_CUSTOM", label + " takes a workspace member");
                return id;
            }
            case "CHECKBOX" -> {
                return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equals("1") || s.equalsIgnoreCase("y") || s.equals("✓");
            }
            default -> {
                if (s.length() > 2000) throw bad("COLLAB_BAD_CUSTOM", label + " is at most 2000 characters");
                return raw.toString();
            }
        }
    }

    // ══════════════════════ FORMATTING ═══════════════════════════════════════

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * Validates a row's formatting and returns it as JSON (null when empty).
     * Unknown keys, unknown columns and anything that is not a plain colour are
     * dropped rather than stored — the value ends up in a style attribute.
     */
    @SuppressWarnings("unchecked")
    String cleanFormat(Long workspaceId, Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> in)) throw bad("COLLAB_BAD_FORMAT", "Expected {row, cells}");
        Set<String> keys = columns(workspaceId).stream().map(c -> (String) c.get("key")).collect(Collectors.toSet());
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> row = style(in.get("row"));
        if (!row.isEmpty()) out.put("row", row);
        if (in.get("cells") instanceof Map<?, ?> cells) {
            Map<String, Object> cs = new LinkedHashMap<>();
            cells.forEach((k, v) -> {
                String key = String.valueOf(k);
                Map<String, Object> st = style(v);
                if (keys.contains(key) && !st.isEmpty()) cs.put(key, st);
            });
            if (!cs.isEmpty()) out.put("cells", cs);
        }
        return out.isEmpty() ? null : write(out);
    }

    Map<String, Object> readFormat(String json) {
        return readMap(json);
    }

    private static Map<String, Object> style(Object raw) {
        Map<String, Object> st = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) return st;
        for (String flag : List.of("b", "i", "u")) if (Boolean.TRUE.equals(m.get(flag))) st.put(flag, true);
        Object size = m.get("size");
        if ("s".equals(size) || "l".equals(size)) st.put("size", size);
        for (String c : List.of("color", "bg")) {
            Object v = m.get(c);
            if (v != null && HEX.matcher(v.toString()).matches()) st.put(c, v.toString().toLowerCase());
        }
        return st;
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    private static String[] builtIn(String key) {
        for (String[] b : BUILT_INS) if (b[0].equals(key)) return b;
        return null;
    }

    private static String newKey() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder("c_");
        for (int k = 0; k < 8; k++) sb.append(abc.charAt(RANDOM.nextInt(abc.length())));
        return sb.toString();
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private String write(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (Exception e) { throw bad("COLLAB_BAD_COLUMNS", "Could not save"); }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static BusinessException bad(String code, String msg) { return new BusinessException(code, msg); }
}
