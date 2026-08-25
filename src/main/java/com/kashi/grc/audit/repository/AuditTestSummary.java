package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditTest;

import java.time.LocalDateTime;

/**
 * Everything the test LIST needs, and nothing it does not.
 *
 * WHY THIS EXISTS
 *   GET /v1/audit/library/tests was loading full AuditTest entities for every
 *   row — test_procedure and evidence_guidance included, both TEXT — and taking
 *   30-36 seconds. The perf log showed 3 queries, so the cost was the payload,
 *   not the round trips.
 *
 * WHAT IS DELIBERATELY MISSING
 *   testProcedure and evidenceGuidance. toTestMap emits them, so this IS a
 *   response-shape change for the list endpoint — the two single-test endpoints
 *   (GET /tests/{id} and the create/update responses) still return them via the
 *   entity overload, and no frontend code reads either field off the list.
 *   Adding them back would reinstate exactly the cost this removes.
 */
public record AuditTestSummary(
        Long                     id,
        String                   name,
        String                   testRef,
        String                   description,
        String                   frameworkRef,
        String                   frameworkTestId,
        String                   controlTag,
        AuditTest.AutomationType automationType,
        String                   automationKey,
        AuditTest.Frequency      frequency,
        Long                     tenantId,
        LocalDateTime            createdAt
) {}