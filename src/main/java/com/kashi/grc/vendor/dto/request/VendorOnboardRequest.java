package com.kashi.grc.vendor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VendorOnboardRequest {
    @NotBlank public String name;
    public String legalName;
    public String registrationNumber;
    public String country;
    public String industry;
    public String riskClassification;
    public String criticality;
    public String dataAccessLevel;
    public String servicesProvided;
    public String website;
    @Data
    public static class PrimaryContact {
        public String firstName;
        public String lastName;
        public String email;
        public String jobTitle;
    }
    public PrimaryContact primaryContact;
    public String primaryContactEmail;

    /**
     * Which TPRM workflow to start. OPTIONAL — this was @NotNull.
     *
     * ── ISSUE 7: THE FORM ASKED FOR THIS TOO EARLY ────────────────────────
     * Onboarding asked the user to pick a workflow, and then the flow asked
     * again — for the assessment template — once the vendor existed.
     *
     * The ordering was the real problem, not the duplication. When this form is
     * submitted the vendor's risk score does not exist yet: it is calculated in
     * step 2 of onboard(), from the very fields being filled in. And the risk
     * score is what decides which templates are candidates
     * (RiskTemplateMapping.findByScore). So the first ask came before the
     * information that would make it an informed choice; the second ask —
     * after the score is known, with the candidate list in hand — is the one
     * that can actually be answered.
     *
     * Kept on the DTO rather than deleted. An existing caller that still sends
     * a workflowId behaves exactly as before, and an org running more than one
     * VENDOR workflow can still choose explicitly through the API. Only the
     * requirement is gone. When it is absent, VendorServiceImpl resolves the
     * active VENDOR workflow — see resolveWorkflowId there.
     */
    public Long workflowId;
}
