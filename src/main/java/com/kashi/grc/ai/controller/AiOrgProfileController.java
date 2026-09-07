package com.kashi.grc.ai.controller;

import com.kashi.grc.ai.domain.AiOrgProfile;
import com.kashi.grc.ai.repository.AiOrgProfileRepository;
import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The org profile CRUD.
 *
 * ── PUT THIS SCREEN IN ONBOARDING ────────────────────────────────────────────
 * Output quality is bounded by what this table contains. A tenant with a filled
 * profile gets policies naming their real cloud provider, their real security
 * owner and their real jurisdictions; a tenant with an empty one gets "The
 * Company shall implement appropriate controls."
 *
 * The completeness score exists to make that visible in the UI. Customers fill
 * in progress bars.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiOrgProfileController {

    private final AiOrgProfileRepository repository;
    private final UtilityService         utilityService;

    @GetMapping("/v1/ai/org-profile")
    @Transactional(readOnly = true)
    public ApiResponse<AiOrgProfile> get() {
        User user = utilityService.getLoggedInDataContext();
        return ApiResponse.success(repository.findByTenantId(user.getTenantId())
                .orElseGet(() -> AiOrgProfile.builder().tenantId(user.getTenantId()).build()));
    }

    @PutMapping("/v1/ai/org-profile")
    @Transactional
    public ApiResponse<AiOrgProfile> save(@RequestBody AiOrgProfile incoming) {
        User user = utilityService.getLoggedInDataContext();

        AiOrgProfile profile = repository.findByTenantId(user.getTenantId())
                .orElseGet(() -> AiOrgProfile.builder().tenantId(user.getTenantId()).build());

        // Identity
        profile.setLegalName(incoming.getLegalName());
        profile.setShortName(incoming.getShortName());
        profile.setIndustry(incoming.getIndustry());
        profile.setEmployeeCount(incoming.getEmployeeCount());
        profile.setHeadquartersCountry(incoming.getHeadquartersCountry());
        profile.setOperatingCountries(incoming.getOperatingCountries());
        // Regulatory
        profile.setFrameworksInScope(incoming.getFrameworksInScope());
        profile.setProcessesPersonalData(incoming.getProcessesPersonalData());
        profile.setProcessesHealthData(incoming.getProcessesHealthData());
        profile.setProcessesCardholderData(incoming.getProcessesCardholderData());
        profile.setProcessesChildrenData(incoming.getProcessesChildrenData());
        profile.setDataResidencyRegions(incoming.getDataResidencyRegions());
        // Estate
        profile.setCloudProviders(incoming.getCloudProviders());
        profile.setIdentityProvider(incoming.getIdentityProvider());
        profile.setMdmSolution(incoming.getMdmSolution());
        profile.setEndpointProtection(incoming.getEndpointProtection());
        profile.setCodeRepository(incoming.getCodeRepository());
        profile.setTicketingSystem(incoming.getTicketingSystem());
        profile.setHasOnPremise(incoming.getHasOnPremise());
        profile.setRemoteWorkModel(incoming.getRemoteWorkModel());
        // Roles
        profile.setSecurityOwnerName(incoming.getSecurityOwnerName());
        profile.setSecurityOwnerTitle(incoming.getSecurityOwnerTitle());
        profile.setSecurityContactEmail(incoming.getSecurityContactEmail());
        profile.setPrivacyOfficerName(incoming.getPrivacyOfficerName());
        profile.setIncidentContactEmail(incoming.getIncidentContactEmail());
        // Conventions
        profile.setToneOfVoice(incoming.getToneOfVoice());
        profile.setSpellingVariant(incoming.getSpellingVariant());
        profile.setDefaultReviewFrequencyMonths(incoming.getDefaultReviewFrequencyMonths());
        profile.setPolicyRefPrefix(incoming.getPolicyRefPrefix());
        profile.setCustomFactsJson(incoming.getCustomFactsJson());
        profile.setProhibitedClaims(incoming.getProhibitedClaims());
        // AI configuration
        profile.setPreferredProvider(incoming.getPreferredProvider());
        profile.setPreferredModel(incoming.getPreferredModel());
        profile.setAiEnabled(incoming.getAiEnabled());
        profile.setAllowExampleMining(incoming.getAllowExampleMining());
        profile.setUpdatedBy(user.getId());

        return ApiResponse.success(repository.save(profile));
    }

    /**
     * Completeness, weighted by impact on output rather than by field count.
     * Naming the company matters far more than recording the ticketing system,
     * and the score should tell the customer that.
     */
    @GetMapping("/v1/ai/org-profile/completeness")
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, Object>> completeness() {
        User user = utilityService.getLoggedInDataContext();
        AiOrgProfile p = repository.findByTenantId(user.getTenantId()).orElse(null);

        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("legalName", 15);
        weights.put("industry", 10);
        weights.put("frameworksInScope", 15);
        weights.put("cloudProviders", 10);
        weights.put("securityOwnerName", 10);
        weights.put("operatingCountries", 10);
        weights.put("identityProvider", 5);
        weights.put("headquartersCountry", 5);
        weights.put("remoteWorkModel", 5);
        weights.put("securityContactEmail", 5);
        weights.put("customFactsJson", 5);
        weights.put("prohibitedClaims", 5);

        int score = 0;
        java.util.List<String> missing = new java.util.ArrayList<>();

        if (p != null) {
            for (var e : weights.entrySet()) {
                String v = switch (e.getKey()) {
                    case "legalName"            -> p.getLegalName();
                    case "industry"             -> p.getIndustry();
                    case "frameworksInScope"    -> p.getFrameworksInScope();
                    case "cloudProviders"       -> p.getCloudProviders();
                    case "securityOwnerName"    -> p.getSecurityOwnerName();
                    case "operatingCountries"   -> p.getOperatingCountries();
                    case "identityProvider"     -> p.getIdentityProvider();
                    case "headquartersCountry"  -> p.getHeadquartersCountry();
                    case "remoteWorkModel"      -> p.getRemoteWorkModel();
                    case "securityContactEmail" -> p.getSecurityContactEmail();
                    case "customFactsJson"      -> p.getCustomFactsJson();
                    case "prohibitedClaims"     -> p.getProhibitedClaims();
                    default -> null;
                };
                if (v != null && !v.isBlank()) score += e.getValue(); else missing.add(e.getKey());
            }
        } else {
            missing.addAll(weights.keySet());
        }

        return ApiResponse.success(Map.of(
                "score", score,
                "missing", missing,
                "message", score >= 80 ? "Generation is well grounded"
                        : score >= 50 ? "Add the missing fields for noticeably more specific output"
                        : "Output will be generic until this profile is completed"));
    }
}
