package com.kashi.grc.ucf.service;

import com.kashi.grc.ucf.domain.CommonControl;
import com.kashi.grc.ucf.repository.CommonControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 3 — expands a control tag into the frozen set that instantiation writes
 * into matched_tags_snapshot.
 *
 * "IAM-02.3"  ->  "IAM-02.3,IAM-02,IAM"
 *
 * Ancestors only, never descendants. A control sitting on a coarse node must
 * not be satisfied by evidence for one narrow child of it. This is the whole
 * point of the hierarchy: coarse evidence reaches fine controls, not the reverse.
 *
 * ── WHY A CACHED SNAPSHOT ───────────────────────────────────────────────────
 * Instantiation resolves a chain per control instance, and an engagement can
 * have hundreds. Hitting the DB per control would be a query storm. The
 * catalogue is a few hundred effectively-static rows, so it is loaded once into
 * an in-memory parent map and refreshed lazily. If the catalogue is edited, the
 * next instantiation after the TTL picks it up — and since snapshots freeze at
 * instantiation anyway, a slightly stale map only ever affects brand-new
 * expansions, never existing ones.
 *
 * ── LEGACY FALLBACK ─────────────────────────────────────────────────────────
 * If the tag is not a catalogue code (an old free-text tag on an un-migrated
 * control), expand() returns just the tag itself. The upgraded matcher treats a
 * single-element set exactly as the old exact-match did, so nothing regresses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagExpansionService {

    private final CommonControlRepository controlRepository;

    private static final long TTL_MS = 5 * 60_000;

    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    private record Snapshot(Map<String, String> parentByCode, long loadedAt) {}

    /**
     * Expand one tag to its comma-joined ancestry chain, uppercased.
     * Returns the tag alone if it is not a known catalogue code.
     */
    public String expand(String tag) {
        if (tag == null || tag.isBlank()) return null;
        String code = tag.toUpperCase().trim();

        Map<String, String> parents = parentMap();
        if (!parents.containsKey(code)) {
            return code;   // legacy tag — single-element set, matches as before
        }

        List<String> chain = new ArrayList<>();
        String cursor = code;
        int guard = 0;
        while (cursor != null && guard++ < 10) {
            chain.add(cursor);
            cursor = parents.get(cursor);
        }
        return String.join(",", chain);
    }

    /**
     * Expand a CSV of tags (policy control_tags) into the DISTINCT union of all
     * their chains, order-preserved.
     *
     * "GOV-01.1,AST-02.1"  ->  "GOV-01.1,GOV-01,GOV,AST-02.1,AST-02,AST"
     */
    public String expandCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> all = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String expanded = expand(raw);
            if (expanded != null) {
                for (String c : expanded.split(",")) all.add(c);
            }
        }
        return all.isEmpty() ? null : String.join(",", all);
    }

    // ── cache ───────────────────────────────────────────────────────────────

    private Map<String, String> parentMap() {
        Snapshot s = cache.get();
        if (s == null || System.currentTimeMillis() - s.loadedAt() > TTL_MS) {
            s = load();
            cache.set(s);
        }
        return s.parentByCode();
    }

    private Snapshot load() {
        Map<String, String> map = new java.util.HashMap<>();
        for (CommonControl c : controlRepository.findAll()) {
            map.put(c.getCode(), c.getParentCode());
        }
        log.debug("[UCF] Loaded {} catalogue codes into expansion cache", map.size());
        return new Snapshot(map, System.currentTimeMillis());
    }

    /** Force a reload — call after a catalogue edit if immediacy is needed. */
    public void invalidate() {
        cache.set(null);
    }
}