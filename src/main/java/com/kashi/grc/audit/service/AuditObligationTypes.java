package com.kashi.grc.audit.service;

import java.util.List;

/**
 * The action-item remediation types that act as per-instance obligations on
 * an audit engagement, and which side of the audit each one belongs to.
 *
 * ── WHY THE SIDE IS IN THE TYPE ───────────────────────────────────────────
 * action_items has no "side" column, and a live item on a control is an
 * access grant (see ControlAccessGuard). An evidence delegation must not let
 * its holder record a test result, and a test delegation must not let its
 * holder submit the auditee's evidence. Encoding the side in remediation_type
 * keeps that separation without a schema change, and the guard matches on the
 * exact list for the side it is checking.
 *
 * Mirrors the vendor module's CONTRIBUTOR_ASSIGNMENT / REVIEWER_ASSIGNMENT /
 * CONTRIBUTOR_REOPEN, which split by side for the same reason.
 *
 * All values fit action_items.remediation_type VARCHAR(30).
 */
public final class AuditObligationTypes {

    private AuditObligationTypes() {}

    // ── Control instance ────────────────────────────────────────────────────
    /** Auditee side — "please provide evidence for this control". */
    public static final String CONTROL_EVIDENCE_ASSIGNMENT = "CONTROL_EVIDENCE_ASSIGNMENT";
    /** Auditor side — "please test this control". */
    public static final String CONTROL_TEST_ASSIGNMENT     = "CONTROL_TEST_ASSIGNMENT";
    /** Auditee side — raised by a send-back; the evidence must be provided again. */
    public static final String CONTROL_REOPEN              = "CONTROL_REOPEN";

    // ── Test instance ───────────────────────────────────────────────────────
    /** Auditor side — "please run / conclude this test". */
    public static final String TEST_ASSIGNMENT             = "TEST_ASSIGNMENT";

    // ── Policy instance ─────────────────────────────────────────────────────
    /** Auditor side — "please review this policy". */
    public static final String POLICY_REVIEW_ASSIGNMENT    = "POLICY_REVIEW_ASSIGNMENT";

    /** Every type that grants auditee-side access to a control. */
    public static final List<String> CONTROL_AUDITEE_TYPES =
            List.of(CONTROL_EVIDENCE_ASSIGNMENT, CONTROL_REOPEN);

    /** Every type that grants auditor-side access to a control. */
    public static final List<String> CONTROL_AUDITOR_TYPES =
            List.of(CONTROL_TEST_ASSIGNMENT);

    public static final List<String> TEST_TYPES   = List.of(TEST_ASSIGNMENT);
    public static final List<String> POLICY_TYPES = List.of(POLICY_REVIEW_ASSIGNMENT);

    // ── Override permissions ────────────────────────────────────────────────
    /**
     * May act on an AUDITOR-side control, test or policy assigned to someone
     * else. Seeded in sql/91 for the roles that cover for absent auditors.
     *
     * Two codes rather than one, deliberately: a lead auditor covering for a
     * colleague must not thereby be able to submit the AUDITEE's evidence, and
     * the reverse. One code would make every override holder both sides.
     */
    public static final String OVERRIDE_AUDITOR_PERMISSION = "audit:control:override-auditor-assignment";
    /** May act on an AUDITEE-side control assigned to someone else. */
    public static final String OVERRIDE_AUDITEE_PERMISSION = "audit:control:override-auditee-assignment";
}