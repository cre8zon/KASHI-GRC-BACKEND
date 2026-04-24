package com.kashi.grc.common.criteria;

import com.kashi.grc.common.dto.NameValue;
import com.kashi.grc.common.dto.PageDetails;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/**
 * Parses a {@code Map<String, String>} of HTTP query parameters into
 * a {@link PageDetails} object, which is then consumed by
 * {@link CriteriaQueryHelper}.
 *
 * <p>This is the project-wide replacement for {@code UtilityService.getpageDetails()}.
 * The parsing logic mirrors the original {@code parseSemiColonSeparatedList} approach
 * but fixes the bug where the whole string was split on "=" instead of each segment.</p>
 *
 * <h3>Query param format:</h3>
 * <pre>
 *   GET /v1/vendors?skip=0&take=20
 *     &search=name=cloud;country=IN
 *     &filterBy=status=ACTIVE
 *     &sortBy=name
 *     &sortDirection=asc
 * </pre>
 */
@Component
public class PageDetailsParser {

    public PageDetails parse(Map<String, String> params) {
        PageDetails pd = new PageDetails();
        if (params == null || params.isEmpty()) return pd;

        params.forEach((key, value) -> {
            if (value == null) return;
            switch (key.toLowerCase()) {
                case "take"          -> pd.setTake(parseInt(value, 10));
                case "skip"          -> pd.setSkip(parseLong(value, 0L));
                case "filterby"      -> pd.setFilterBy(parsePairs(value));
                case "search"        -> pd.setSearch(parsePairs(value));
                case "sortby"        -> pd.setSortBy(singlePair(value));
                case "sortdirection" -> pd.setSortDirection(singlePair(value));
            }
        });
        return pd;
    }

    /**
     * Parses "key1=value1;key2=value2" into a list of NameValue pairs.
     * Each semicolon-delimited segment is split on the FIRST '=' only,
     * so values containing '=' are handled correctly.
     */
    private ArrayList<NameValue> parsePairs(String raw) {
        ArrayList<NameValue> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) return list;

        String cleaned = raw.replace("\"", "").trim();
        Arrays.stream(cleaned.split(";")).forEach(segment -> {
            int eq = segment.indexOf('=');
            if (eq > 0) {
                String name  = segment.substring(0, eq).trim().toLowerCase();
                String value = segment.substring(eq + 1).trim();
                if (!name.isEmpty()) list.add(new NameValue(name, value));
            }
        });
        return list;
    }

    /** Wraps a plain sort field name into a single-element list. */
    private ArrayList<NameValue> singlePair(String value) {
        ArrayList<NameValue> list = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            list.add(new NameValue("value", value.trim().toLowerCase()));
        }
        return list;
    }

    private int parseInt(String v, int def) {
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private long parseLong(String v, long def) {
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
