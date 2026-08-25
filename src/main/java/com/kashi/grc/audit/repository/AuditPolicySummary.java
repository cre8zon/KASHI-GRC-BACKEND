package com.kashi.grc.audit.repository;

import com.kashi.grc.audit.domain.AuditPolicy;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Everything the policy LIST needs, and nothing it does not.
 *
 * WHY THIS EXISTS
 *   GET /v1/audit/library/policies was loading full AuditPolicy entities —
 *   including content_body, which holds the entire policy document as HTML —
 *   for every row, then mapping them down to a summary that throws the content
 *   away. With 39 policies that was 8-10 seconds for a screen showing eight
 *   short columns. It is the payload, not the query count: the perf log shows
 *   3 queries taking 9 seconds.
 *
 *   Selecting the columns explicitly means content_body never leaves MySQL.
 *
 * Kept as a record next to the repository rather than in dto/response because
 * it is a persistence-layer shape, not part of the API contract — the
 * controller still decides what JSON the client sees.
 */
public record AuditPolicySummary(
        Long                     id,
        String                   title,
        String                   policyRef,
        String                   description,
        Integer                  version,
        AuditPolicy.PolicyStatus status,
        AuditPolicy.ContentType  contentType,
        Long                     ownerId,
        String                   ownerTeam,
        LocalDateTime            approvedAt,
        LocalDate                effectiveDate,
        LocalDate                nextReviewDate,
        Integer                  reviewFrequencyMonths,
        String                   controlTags,
        String                   frameworkRefs,
        Long                     tenantId,
        LocalDateTime            createdAt
) {}