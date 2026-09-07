package com.kashi.grc.ai.domain;

import com.kashi.grc.common.domain.TenantAwareEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * AiOrgProfile — the facts that make a generated policy belong to THIS company.
 *
 * ── WHY THIS IS THE MOST IMPORTANT ENTITY IN THE MODULE ──────────────────────
 * The difference between an AI policy generator that impresses a buyer and one
 * that embarrasses you is not the model. It is whether the output says
 *
 *     "Kashi Technologies Pvt Ltd encrypts customer data at rest in AWS KMS
 *      across ap-south-1 and eu-west-1, in line with our DPDP Act obligations"
 *
 * or
 *
 *     "The Company shall implement appropriate encryption controls."
 *
 * Both are valid English from the same model. The first requires that the model
 * was TOLD the company name, cloud provider, regions and jurisdictions. This
 * table is where that grounding lives, and populating it well is worth more to
 * output quality than any amount of prompt engineering.
 *
 * ── WHY NOT EXTEND Tenant ────────────────────────────────────────────────────
 * Tenant is deliberately thin — name, code, plan, limits — and is read on
 * essentially every authenticated request. Hanging twenty compliance-narrative
 * columns off it would widen the hottest row in the system for the benefit of
 * one subsystem. A 1:1 satellite keeps that cost where it belongs.
 *
 * ── AUTO-POPULATION ──────────────────────────────────────────────────────────
 * Do not make the customer type all of this. Much of it is derivable from data
 * you already hold, and PolicyContextAssembler backfills what it can:
 *   - cloudProviders     <- the integration module's connected providers
 *   - identityProvider   <- an Okta/Azure AD integration if present
 *   - frameworksInScope  <- audit engagements and their frameworks
 *   - securityContact    <- the tenant user holding the security-owner role
 * The remainder is a short onboarding form. Ten fields well filled beat a
 * hundred left null.
 *
 * ── TENANT-SCOPED, ALWAYS ────────────────────────────────────────────────────
 * TenantAwareEntity, not GlobalOrTenantEntity. There is no such thing as a
 * global org profile — a null tenant here would mean "some company", which is
 * precisely the generic output this entity exists to prevent.
 */
@Entity
@Table(name = "ai_org_profiles",
        uniqueConstraints = @UniqueConstraint(name = "uk_aop_tenant", columnNames = "tenant_id")
)
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class AiOrgProfile extends TenantAwareEntity {

    // ── Identity: what the policy calls the company ───────────────────────────

    /** Full legal entity name — what belongs in a policy's opening paragraph. */
    @Column(name = "legal_name", length = 300)
    private String legalName;

    /** Short name for body text, so a policy is not 40 repetitions of "Pvt Ltd". */
    @Column(name = "short_name", length = 120)
    private String shortName;

    @Column(name = "industry", length = 120)
    private String industry;

    /** Drives register requirements — a 30-person startup and a 5,000-person bank differ. */
    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(name = "headquarters_country", length = 100)
    private String headquartersCountry;

    /** Comma-separated ISO country codes where the company operates. */
    @Column(name = "operating_countries", length = 500)
    private String operatingCountries;

    // ── Regulatory posture ────────────────────────────────────────────────────

    /**
     * Comma-separated: "SOC2,ISO27001,DPDP,GDPR,HIPAA,PCI_DSS".
     * The single biggest driver of what a policy must contain.
     */
    @Column(name = "frameworks_in_scope", length = 500)
    private String frameworksInScope;

    @Column(name = "processes_personal_data",     nullable = false) @Builder.Default private Boolean processesPersonalData    = false;
    @Column(name = "processes_health_data",       nullable = false) @Builder.Default private Boolean processesHealthData      = false;
    @Column(name = "processes_cardholder_data",   nullable = false) @Builder.Default private Boolean processesCardholderData  = false;
    @Column(name = "processes_children_data",     nullable = false) @Builder.Default private Boolean processesChildrenData    = false;

    /** Where customer data is contractually required to live: "ap-south-1,eu-west-1". */
    @Column(name = "data_residency_regions", length = 300)
    private String dataResidencyRegions;

    // ── Technical estate: turns generic clauses into specific ones ────────────

    /** "AWS,GCP,Azure" — usually derivable from the integration module. */
    @Column(name = "cloud_providers", length = 300)
    private String cloudProviders;

    @Column(name = "identity_provider", length = 120)
    private String identityProvider;

    @Column(name = "mdm_solution", length = 120)
    private String mdmSolution;

    @Column(name = "endpoint_protection", length = 120)
    private String endpointProtection;

    @Column(name = "code_repository", length = 120)
    private String codeRepository;

    @Column(name = "ticketing_system", length = 120)
    private String ticketingSystem;

    @Column(name = "has_on_premise", nullable = false) @Builder.Default private Boolean hasOnPremise = false;

    /** Changes the whole shape of an acceptable-use or device policy. */
    @Column(name = "remote_work_model", length = 40)
    private String remoteWorkModel;   // REMOTE_FIRST | HYBRID | OFFICE_FIRST

    // ── Named roles: policies must name accountable humans ────────────────────

    @Column(name = "security_owner_name",  length = 200) private String securityOwnerName;
    @Column(name = "security_owner_title", length = 200) private String securityOwnerTitle;
    @Column(name = "security_contact_email", length = 200) private String securityContactEmail;
    @Column(name = "privacy_officer_name", length = 200)  private String privacyOfficerName;
    @Column(name = "incident_contact_email", length = 200) private String incidentContactEmail;

    // ── Drafting conventions ──────────────────────────────────────────────────

    /** FORMAL | PLAIN_ENGLISH | CONCISE — house tone for generated text. */
    @Column(name = "tone_of_voice", length = 40)
    @Builder.Default
    private String toneOfVoice = "FORMAL";

    /** BRITISH | AMERICAN. Small detail; conspicuous when wrong throughout a document. */
    @Column(name = "spelling_variant", length = 20)
    @Builder.Default
    private String spellingVariant = "BRITISH";

    @Column(name = "default_review_frequency_months")
    @Builder.Default
    private Integer defaultReviewFrequencyMonths = 12;

    /** Prefix for generated refs, e.g. "KSH" -> KSH-POL-014. */
    @Column(name = "policy_ref_prefix", length = 20)
    private String policyRefPrefix;

    // ── Escape hatch ──────────────────────────────────────────────────────────

    /**
     * JSON array of {label, value} facts that do not deserve a column:
     * "We use a four-eyes rule for production deploys", "Our data centre is
     * Equinix Mumbai". Injected verbatim into the grounding block. This is the
     * field customers use most once they realise it works.
     */
    @Column(name = "custom_facts_json", columnDefinition = "LONGTEXT")
    private String customFactsJson;

    /**
     * Statements the model must never make about this company — the negative
     * constraints. "We do not hold cardholder data", "Do not reference ISO 27017".
     * A single false claim in a compliance document is worse than a missing one.
     */
    @Column(name = "prohibited_claims", columnDefinition = "TEXT")
    private String prohibitedClaims;

    // ── Per-tenant AI configuration ───────────────────────────────────────────

    /**
     * Pin a provider for this tenant: "openai" | "anthropic" | "bedrock".
     * Null = platform default. Enterprise buyers will ask for this by name, and
     * being able to answer yes in the security review is worth the column.
     */
    @Column(name = "preferred_provider", length = 40)
    private String preferredProvider;

    @Column(name = "preferred_model", length = 120)
    private String preferredModel;

    /**
     * Master opt-out. When false every AI endpoint refuses for this tenant.
     * Some regulated customers will require it contractually — offer it before
     * they have to ask.
     */
    @Column(name = "ai_enabled", nullable = false)
    @Builder.Default
    private Boolean aiEnabled = true;

    /** Consent for this tenant's accepted suggestions to seed few-shot examples. Default OFF. */
    @Column(name = "allow_example_mining", nullable = false)
    @Builder.Default
    private Boolean allowExampleMining = false;

    @Column(name = "updated_by")
    private Long updatedBy;
}
