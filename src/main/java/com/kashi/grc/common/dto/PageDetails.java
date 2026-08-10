package com.kashi.grc.common.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import java.util.ArrayList;

/**
 * Pagination, filter, search and sort parameters.
 * All list fields are initialised to empty lists — callers never need null-checks.
 *
 * Query-string format (passed as URL params):
 *   skip=0 &amp; take=20
 *   search=name=cloud;email=@acme    (LIKE match, OR-combined across fields)
 *   filterBy=status=ACTIVE           (exact match, AND-combined)
 *   sortBy=name                      (field name)
 *   sortDirection=asc                (asc | desc)
 */
@Getter
@Setter
@EqualsAndHashCode
@ToString
/*
 * EqualsAndHashCode + ToString are REQUIRED, not cosmetic.
 *
 * PageDetails is a parameter of @Cacheable methods (AuditReferenceListCacheService
 * .listWorkflowBlueprints and .listTemplates). Spring builds the cache key with
 * SimpleKeyGenerator, and Spring Data Redis serialises that key via
 * SimpleKey.toString(), which calls toString() on every parameter.
 *
 * With only @Getter/@Setter this class inherited Object.toString(), so the key
 * contained a fresh identity hash on every request:
 *
 *     com.kashi.grc.common.dto.PageDetails@1a2b3c4d
 *
 * The key was therefore never equal twice: a 100% cache MISS rate on endpoints
 * that looked cached, plus one orphaned Redis key written per request that
 * nothing would ever read.
 *
 * Value-based equals/hashCode/toString make identical page requests produce an
 * identical key. Do not remove these without also giving those @Cacheable methods
 * an explicit SpEL key.
 */
public class PageDetails {
    private Long  skip          = 0L;
    private Integer take        = 10;
    private ArrayList<NameValue> filterBy      = new ArrayList<>();
    private ArrayList<NameValue> search        = new ArrayList<>();
    private ArrayList<NameValue> sortBy        = new ArrayList<>();
    private ArrayList<NameValue> sortDirection = new ArrayList<>();
}