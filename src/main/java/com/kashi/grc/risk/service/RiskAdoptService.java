package com.kashi.grc.risk.service;

import com.kashi.grc.audit.domain.AuditControl;
import com.kashi.grc.audit.repository.AuditControlRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.risk.domain.Risk;
import com.kashi.grc.risk.domain.RiskControlLink;
import com.kashi.grc.risk.repository.RiskControlLinkRepository;
import com.kashi.grc.risk.repository.RiskRepository;
import com.kashi.grc.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bulk adoption of the platform risk library into one tenant.
 *
 * Modelled on AuditPolicyBulkAdoptService, including the part that matters
 * most: adoptOne is REQUIRES_NEW and is invoked across a bean boundary, so
 * each risk commits or rolls back on its own. The policy version of this was
 * originally a loop in the controller calling itself, which bypassed the
 * Spring proxy — the inner @Transactional was ignored, one failure marked the
 * whole transaction rollback-only, and all 39 adoptions vanished at commit
 * having already reported success. One bad row must cost one row.
 *
 * ── ADOPTION COPIES, IT NEVER SHARES ──────────────────────────────────────
 * The copy carries sourceRiskId pointing at the library row. That is both the
 * provenance record and the idempotency key: re-running adoption skips
 * anything this tenant already holds and picks up library rows added since.
 *
 * ── WHY SCORES ARE NOT COPIED ─────────────────────────────────────────────
 * The library ships every scenario at 3/3 (score 9) as a neutral placeholder.
 * Carrying that across would hand the customer 28 risks that look assessed and
 * are not, and the whole point of the module is that the organisation decides
 * its own likelihood and impact. So copies land at IDENTIFIED with no scores
 * and an empty assessment — an honest empty register rather than a
 * confident-looking fiction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAdoptService {

    private final RiskRepository            riskRepository;
    private final RiskControlLinkRepository linkRepository;
    private final AuditControlRepository    controlRepository;
    private final TenantRepository          tenantRepository;

    /** Result of one run, mirroring AuditPolicyBulkAdoptService.AdoptResult. */
    public record AdoptResult(int created, int skipped, int failed, List<String> problems) {}

    /**
     * @param ownerId   optional; stamped on every copy. Defaults to the caller,
     *                  matching what the adopt form's helper text promises.
     * @param ownerTeam optional; stamped on every copy.
     */
    public AdoptResult adoptAll(Long tenantId, Long actorUserId, Long ownerId, String ownerTeam) {

        List<Risk> library = riskRepository.findByTenantIdIsNullAndIsDeletedFalse();

        // UCF index built ONCE for the whole run, not per risk. The library is
        // 130 rows and the control catalogue 2,881; resolving inside adoptOne
        // would load every control 130 times. One query, one grouping.
        Map<String, List<AuditControl>> controlsByCommonCode =
                controlRepository.findByTenantIdIsNullOrTenantId(tenantId).stream()
                        .filter(c -> c.getCommonControlCode() != null
                                  && !c.getCommonControlCode().isBlank())
                        .collect(Collectors.groupingBy(AuditControl::getCommonControlCode));

        int created = 0, skipped = 0, failed = 0;
        List<String> problems = new ArrayList<>();

        for (Risk source : library) {
            if (riskRepository.existsBySourceRiskIdAndTenantId(source.getId(), tenantId)) {
                skipped++;                  // already adopted — re-runs pick up what is new
                continue;
            }
            try {
                adoptOne(source, tenantId, actorUserId, ownerId, ownerTeam, controlsByCommonCode);
                created++;
            } catch (Exception ex) {
                failed++;
                problems.add(source.getRiskRef() + ": " + ex.getMessage());
                log.warn("[RISK-BULK] Adopt failed | riskId={} ref={} | {}",
                        source.getId(), source.getRiskRef(), ex.getMessage());
            }
        }

        log.info("[RISK-BULK] Done | tenantId={} created={} skipped={} failed={}",
                tenantId, created, skipped, failed);
        return new AdoptResult(created, skipped, failed, problems);
    }

    /**
     * REQUIRES_NEW: this risk's copy commits or rolls back on its own,
     * independently of every other risk in the run. That is the entire reason
     * this method exists separately from the loop above.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void adoptOne(Risk source, Long tenantId, Long actorUserId,
                         Long ownerId, String ownerTeam,
                         Map<String, List<AuditControl>> controlsByCommonCode) {

        Risk copy = buildCopy(source, tenantId, actorUserId, ownerId, ownerTeam);
        riskRepository.save(copy);
        expandControlTags(copy, tenantId, actorUserId, controlsByCommonCode);
    }

    /** Shared by bulk and single adoption — one definition of what a copy is. */
    private Risk buildCopy(Risk source, Long tenantId, Long actorUserId,
                           Long ownerId, String ownerTeam) {
        return Risk.builder()
                .tenantId(tenantId)
                .sourceRiskId(source.getId())
                .riskRef(uniqueRef(source.getRiskRef(), tenantId))
                .title(source.getTitle())
                .description(source.getDescription())
                .category(source.getCategory())
                .riskSource(source.getRiskSource())
                .status(Risk.Status.IDENTIFIED)
                // Deliberately NOT copied: inherent/residual likelihood, impact
                // and score. See class javadoc.
                .controlTags(source.getControlTags())
                .frameworkRefs(source.getFrameworkRefs())
                .reviewFrequencyMonths(source.getReviewFrequencyMonths() != null
                        ? source.getReviewFrequencyMonths() : 12)
                .nextReviewDate(nextReview(source.getReviewFrequencyMonths()))
                // The chosen owner, not whoever ran the adoption. Owning is a
                // standing assignment that outlives this run; pressing the
                // button is an event.
                .ownerId(ownerId != null ? ownerId : actorUserId)
                .ownerTeam(ownerTeam != null && !ownerTeam.isBlank()
                        ? ownerTeam.trim() : source.getOwnerTeam())
                .createdBy(actorUserId)
                .build();
    }

    /**
     * Turns the library risk's UCF anchor into real control links.
     *
     * risks.control_tags on a library row holds common_controls codes
     * ("IAM-02.3,IAM-01.4") — the framework-agnostic semantic layer. This
     * resolves each code to the tenant's actual audit_controls through
     * audit_controls.common_control_code and writes one risk_control_links row
     * per match.
     *
     * ── WHY THE EXPANSION HAPPENS AT ADOPTION AND NOT IN THE SEED ──────────
     * A library risk cannot know which frameworks a tenant runs. Linking it to
     * audit_controls in the seed would mean picking one, and an ISO 27001
     * customer would adopt risks pre-wired to SOC 2 controls. Resolving at
     * adoption means each tenant gets links to the controls they actually
     * test, and a tenant who later licenses another framework re-runs adoption
     * to pick up the new coverage.
     *
     * ── SCOPE ─────────────────────────────────────────────────────────────
     * Global controls (tenant_id IS NULL) plus this tenant's own private ones.
     * Never another tenant's.
     *
     * ── FAILURE IS NOT FATAL ──────────────────────────────────────────────
     * A code that resolves to nothing leaves the risk adopted with no links
     * rather than failing the adoption. The risk is still worth having; the
     * missing linkage is visible on the Linked controls tab and in the count,
     * which is a better signal than a row that never arrived.
     */
    private void expandControlTags(Risk copy, Long tenantId, Long actorUserId,
                                   Map<String, List<AuditControl>> controlsByCommonCode) {
        String tags = copy.getControlTags();
        if (tags == null || tags.isBlank()) return;

        List<String> codes = Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .distinct()
                .toList();
        if (codes.isEmpty()) return;

        List<AuditControl> matches = codes.stream()
                .map(code -> controlsByCommonCode.getOrDefault(code, List.of()))
                .flatMap(List::stream)
                .toList();

        if (matches.isEmpty()) {
            log.debug("[RISK-BULK] No audit controls matched UCF codes {} for riskRef={}",
                    codes, copy.getRiskRef());
            return;
        }

        Set<Long> seen = new HashSet<>();
        for (AuditControl control : matches) {
            if (!seen.add(control.getId())) continue;
            if (linkRepository.existsByRiskIdAndControlId(copy.getId(), control.getId())) continue;

            linkRepository.save(RiskControlLink.builder()
                    .tenantId(tenantId)
                    .riskId(copy.getId())
                    .controlId(control.getId())
                    .linkNote("Suggested by the platform library via "
                            + control.getCommonControlCode())
                    .createdBy(actorUserId)
                    .build());
        }
        log.debug("[RISK-BULK] Linked {} control(s) to {} from codes {}",
                seen.size(), copy.getRiskRef(), codes);
    }

    /**
     * Adopt ONE library scenario — backs the per-row Adopt button on the
     * library list.
     *
     * Returns the new copy so the caller can send back its id: runRowAction in
     * ModuleListView reads `id` off the response and navigates straight to the
     * new record. Returning void would leave the user on the library list with
     * a toast and no idea where the copy went.
     *
     * Not REQUIRES_NEW — a single adoption is already its own transaction, and
     * the caller wants the failure, not a silent skip.
     */
    @Transactional
    public Risk adoptOneById(Long libraryRiskId, Long tenantId, Long actorUserId,
                             Long ownerId, String ownerTeam) {

        Risk source = riskRepository.findById(libraryRiskId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Risk", libraryRiskId));

        if (source.getTenantId() != null) {
            throw new ValidationException(
                    "Only platform library risks can be adopted. That risk already belongs to an organisation.");
        }
        if (riskRepository.existsBySourceRiskIdAndTenantId(source.getId(), tenantId)) {
            throw new ValidationException(
                    source.getRiskRef() + " is already in your register.");
        }

        Map<String, List<AuditControl>> controlsByCommonCode =
                controlRepository.findByTenantIdIsNullOrTenantId(tenantId).stream()
                        .filter(c -> c.getCommonControlCode() != null
                                  && !c.getCommonControlCode().isBlank())
                        .collect(Collectors.groupingBy(AuditControl::getCommonControlCode));

        Risk copy = buildCopy(source, tenantId, actorUserId, ownerId, ownerTeam);
        riskRepository.save(copy);
        expandControlTags(copy, tenantId, actorUserId, controlsByCommonCode);
        log.info("[RISK] Adopted | source={} -> id={} | tenantId={}",
                source.getRiskRef(), copy.getId(), tenantId);
        return copy;
    }

    /** RSK-L-001 -> RSK-L-001-META, with a numeric suffix if that is taken. */
    private String uniqueRef(String sourceRef, Long tenantId) {
        if (sourceRef == null || sourceRef.isBlank()) return null;
        String suffix = tenantRepository.findById(tenantId)
                .map(t -> t.getCode() != null && !t.getCode().isBlank()
                        ? t.getCode().toUpperCase().trim() : String.valueOf(tenantId))
                .orElse(String.valueOf(tenantId));

        String candidate = sourceRef + "-" + suffix;
        int n = 2;
        while (riskRepository.existsByRiskRefAndTenantId(candidate, tenantId)) {
            candidate = sourceRef + "-" + suffix + "-" + n++;
        }
        return candidate;
    }

    private LocalDate nextReview(Integer frequencyMonths) {
        int months = frequencyMonths != null && frequencyMonths > 0 ? frequencyMonths : 12;
        return LocalDate.now().plusMonths(months);
    }
}
