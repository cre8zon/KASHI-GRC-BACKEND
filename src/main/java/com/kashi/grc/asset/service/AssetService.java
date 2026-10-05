package com.kashi.grc.asset.service;

import com.kashi.grc.asset.domain.Asset;
import com.kashi.grc.asset.domain.RiskAssetLink;
import com.kashi.grc.asset.dto.*;
import com.kashi.grc.asset.repository.AssetRepository;
import com.kashi.grc.asset.repository.RiskAssetLinkRepository;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.risk.domain.Risk;
import com.kashi.grc.risk.repository.RiskRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository;
import com.kashi.grc.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * AssetService — ISO 27001 A.5.9 inventory, A.8.10 / AST-03.3 disposal.
 *
 *   ACTIVE <-> IN_REPAIR
 *   ACTIVE <-> IN_STORAGE
 *   ACTIVE | IN_STORAGE | IN_REPAIR -> RETIRED
 *   RETIRED -> ACTIVE  (reinstate)
 *   RETIRED -> DISPOSED  (terminal)
 *
 * The transition table below MUST stay identical to
 * module_blueprints.status_flow_json for ASSET and to the
 * allowed_statuses_json on each ui_actions row.
 *
 * ── DISPOSED IS TERMINAL ──────────────────────────────────────────────────
 * Deliberately no transition out of it. A disposal record carries a method and
 * an evidence reference saying the thing was destroyed; an asset that comes
 * back into service afterwards makes that certificate a lie. Reinstating from
 * RETIRED is fine — retiring is a decision, disposal is a physical fact.
 *
 * ── CRITICALITY IS DERIVED ────────────────────────────────────────────────
 * From the highest of the three CIA ratings, on every valuation write. ISO
 * 27005 values an asset in three dimensions and the headline figure has to
 * follow them, or the register says one thing and its own inputs say another.
 *
 * ── PERMISSIONS ───────────────────────────────────────────────────────────
 * No @PreAuthorize, matching every other module controller here. asset:*
 * gate the buttons via ui_actions.required_permission. What IS enforced is
 * tenancy, transition legality, disposal evidence and parent-cycle safety.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetRepository                assetRepository;
    private final RiskAssetLinkRepository        linkRepository;
    private final RiskRepository                 riskRepository;
    private final VendorRepository               vendorRepository;
    private final UserRepository                 userRepository;
    private final UserTenantMembershipRepository membershipRepository;

    /** Mirrors status_flow_json — see class javadoc. */
    private static final Map<Asset.Status, Set<Asset.Status>> ALLOWED = Map.of(
            Asset.Status.ACTIVE,     EnumSet.of(Asset.Status.IN_REPAIR, Asset.Status.IN_STORAGE, Asset.Status.RETIRED),
            Asset.Status.IN_REPAIR,  EnumSet.of(Asset.Status.ACTIVE, Asset.Status.RETIRED),
            Asset.Status.IN_STORAGE, EnumSet.of(Asset.Status.ACTIVE, Asset.Status.RETIRED),
            Asset.Status.RETIRED,    EnumSet.of(Asset.Status.ACTIVE, Asset.Status.DISPOSED),
            Asset.Status.DISPOSED,   EnumSet.noneOf(Asset.Status.class)
    );

    /** Depth limit when walking the parent chain. Guards against a pre-existing cycle. */
    private static final int MAX_ANCESTOR_WALK = 64;

    // ═════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AssetResponse create(AssetRequest req, Long createdBy, Long tenantId) {

        if (req.getOwnerId()     != null) requireTenantUser(req.getOwnerId(), tenantId, "ownerId");
        if (req.getCustodianId() != null) requireTenantUser(req.getCustodianId(), tenantId, "custodianId");
        if (req.getVendorId()    != null) requireTenantVendor(req.getVendorId(), tenantId);

        Asset asset = Asset.builder()
                .tenantId(tenantId)
                .assetRef(resolveRef(req.getAssetRef(), tenantId))
                .name(req.getName())
                .description(req.getDescription())
                .assetType(trimToNull(req.getAssetType()))
                .assetClass(trimToNull(req.getAssetClass()))
                .status(Asset.Status.ACTIVE)
                .classification(trimToNull(req.getClassification()))
                .ownerId(req.getOwnerId())
                .ownerTeam(trimToNull(req.getOwnerTeam()))
                .custodianId(req.getCustodianId())
                .environment(trimToNull(req.getEnvironment()))
                .hostingModel(trimToNull(req.getHostingModel()))
                .location(trimToNull(req.getLocation()))
                .dataResidency(trimToNull(req.getDataResidency()))
                .assetIdentifier(trimToNull(req.getAssetIdentifier()))
                .serialNumber(trimToNull(req.getSerialNumber()))
                .vendorId(req.getVendorId())
                .containsPersonalData(Boolean.TRUE.equals(req.getContainsPersonalData()))
                .personalDataCategories(trimToNull(req.getPersonalDataCategories()))
                .controlTags(trimToNull(req.getControlTags()))
                .frameworkRefs(trimToNull(req.getFrameworkRefs()))
                .nextReviewDate(req.getNextReviewDate())
                .reviewFrequencyMonths(req.getReviewFrequencyMonths() != null
                        ? req.getReviewFrequencyMonths() : 12)
                .createdBy(createdBy)
                .build();

        // Parent is set after the entity exists, so the cycle check has an id
        // to exclude. On create there is no id yet, so only the parent's own
        // tenancy and existence can be checked — a new row cannot be its own
        // ancestor.
        if (req.getParentAssetId() != null) {
            requireOwnAsset(req.getParentAssetId(), tenantId);
            asset.setParentAssetId(req.getParentAssetId());
        }

        assetRepository.save(asset);
        log.info("[ASSET] Created | ref={} | type={} | tenantId={}",
                asset.getAssetRef(), asset.getAssetType(), tenantId);
        return toResponse(asset, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // READ
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public AssetResponse getById(Long id, Long tenantId) {
        return toResponse(requireOwnAsset(id, tenantId), tenantId);
    }

    @Transactional(readOnly = true)
    public List<AssetResponse.LinkedRisk> listLinkedRisks(Long assetId, Long tenantId) {
        requireOwnAsset(assetId, tenantId);
        return buildLinkedRisks(assetId, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE — header and overview tabs
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AssetResponse update(Long id, AssetRequest req, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);

        // The header form shows status and criticality. Echoing the current
        // value back is a no-op; changing either here is refused. Status has
        // lifecycle endpoints that record timestamps and enforce legality;
        // criticality is derived from the CIA ratings and would be overwritten
        // on the next valuation save anyway, which is worse than refusing.
        if (req.getStatus() != null && !req.getStatus().isBlank()
                && !req.getStatus().equalsIgnoreCase(asset.getStatus().name())) {
            throw new ValidationException(
                    "Status cannot be changed by editing the asset. Use the "
                            + "Send for repair / Move to storage / Retire / Record disposal actions, "
                            + "which record who changed it and when.");
        }
        if (req.getCriticality() != null && !req.getCriticality().isBlank()
                && !req.getCriticality().equalsIgnoreCase(nullToEmpty(asset.getCriticality()))) {
            throw new ValidationException(
                    "Criticality is calculated from the confidentiality, integrity and "
                            + "availability ratings. Set those on the Valuation tab instead.");
        }

        if (req.getOwnerId() != null && !req.getOwnerId().equals(asset.getOwnerId())) {
            requireTenantUser(req.getOwnerId(), tenantId, "ownerId");
        }
        if (req.getCustodianId() != null && !req.getCustodianId().equals(asset.getCustodianId())) {
            requireTenantUser(req.getCustodianId(), tenantId, "custodianId");
        }
        if (req.getVendorId() != null && !req.getVendorId().equals(asset.getVendorId())) {
            requireTenantVendor(req.getVendorId(), tenantId);
        }
        if (req.getParentAssetId() != null && !req.getParentAssetId().equals(asset.getParentAssetId())) {
            requireSafeParent(asset, req.getParentAssetId(), tenantId);
        }

        if (req.getName()            != null) asset.setName(req.getName());
        if (req.getDescription()     != null) asset.setDescription(req.getDescription());
        if (req.getAssetType()       != null) asset.setAssetType(trimToNull(req.getAssetType()));
        if (req.getAssetClass()      != null) asset.setAssetClass(trimToNull(req.getAssetClass()));
        if (req.getClassification()  != null) asset.setClassification(trimToNull(req.getClassification()));
        if (req.getOwnerId()         != null) asset.setOwnerId(req.getOwnerId());
        if (req.getOwnerTeam()       != null) asset.setOwnerTeam(trimToNull(req.getOwnerTeam()));
        if (req.getCustodianId()     != null) asset.setCustodianId(req.getCustodianId());
        if (req.getEnvironment()     != null) asset.setEnvironment(trimToNull(req.getEnvironment()));
        if (req.getHostingModel()    != null) asset.setHostingModel(trimToNull(req.getHostingModel()));
        if (req.getLocation()        != null) asset.setLocation(trimToNull(req.getLocation()));
        if (req.getDataResidency()   != null) asset.setDataResidency(trimToNull(req.getDataResidency()));
        if (req.getAssetIdentifier() != null) asset.setAssetIdentifier(trimToNull(req.getAssetIdentifier()));
        if (req.getSerialNumber()    != null) asset.setSerialNumber(trimToNull(req.getSerialNumber()));
        if (req.getVendorId()        != null) asset.setVendorId(req.getVendorId());
        if (req.getParentAssetId()   != null) asset.setParentAssetId(req.getParentAssetId());
        if (req.getControlTags()     != null) asset.setControlTags(trimToNull(req.getControlTags()));
        if (req.getFrameworkRefs()   != null) asset.setFrameworkRefs(trimToNull(req.getFrameworkRefs()));
        if (req.getNextReviewDate()  != null) asset.setNextReviewDate(req.getNextReviewDate());
        if (req.getReviewFrequencyMonths() != null)
            asset.setReviewFrequencyMonths(req.getReviewFrequencyMonths());
        if (req.getContainsPersonalData() != null)
            asset.setContainsPersonalData(req.getContainsPersonalData());
        if (req.getPersonalDataCategories() != null)
            asset.setPersonalDataCategories(trimToNull(req.getPersonalDataCategories()));

        String newRef = trimToNull(req.getAssetRef());
        if (newRef != null && !newRef.equals(asset.getAssetRef())) {
            if (assetRepository.existsByAssetRefAndTenantId(newRef, tenantId)) {
                throw new ValidationException("Asset reference " + newRef + " is already in use.");
            }
            asset.setAssetRef(newRef);
        }

        asset.setUpdatedBy(userId);
        assetRepository.save(asset);
        log.info("[ASSET] Updated | id={} | by={}", id, userId);
        return toResponse(asset, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VALUATION
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AssetResponse saveValuation(Long id, AssetValuationRequest req, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);

        Integer c = parseRating(req.getConfidentialityRating(), "confidentialityRating");
        Integer i = parseRating(req.getIntegrityRating(),       "integrityRating");
        Integer a = parseRating(req.getAvailabilityRating(),    "availabilityRating");

        if (c != null) asset.setConfidentialityRating(c);
        if (i != null) asset.setIntegrityRating(i);
        if (a != null) asset.setAvailabilityRating(a);

        if (req.getClassification() != null) asset.setClassification(trimToNull(req.getClassification()));
        if (req.getContainsPersonalData() != null)
            asset.setContainsPersonalData(req.getContainsPersonalData());

        // depends_on_json hides the categories field unless containsPersonalData
        // is true. That is a display rule; a hidden field is still submittable,
        // so the same rule is applied here rather than assumed.
        if (asset.isContainsPersonalData()) {
            if (req.getPersonalDataCategories() != null)
                asset.setPersonalDataCategories(trimToNull(req.getPersonalDataCategories()));
        } else {
            asset.setPersonalDataCategories(null);
        }

        if (req.getReviewFrequencyMonths() != null)
            asset.setReviewFrequencyMonths(req.getReviewFrequencyMonths());
        if (req.getNextReviewDate() != null) asset.setNextReviewDate(req.getNextReviewDate());

        // Always derived. The form posts criticality; it is discarded.
        asset.setCriticality(deriveCriticality(asset));

        asset.setUpdatedBy(userId);
        assetRepository.save(asset);
        log.info("[ASSET] Valuation saved | id={} | C/I/A={}/{}/{} -> {}",
                id, asset.getConfidentialityRating(), asset.getIntegrityRating(),
                asset.getAvailabilityRating(), asset.getCriticality());
        return toResponse(asset, tenantId);
    }

    /**
     * Highest of C, I and A wins. An asset is as critical as its worst
     * consequence — averaging would let a 5-for-availability database look
     * Medium because nobody minds if it leaks.
     */
    private String deriveCriticality(Asset asset) {
        Integer max = null;
        for (Integer v : new Integer[]{asset.getConfidentialityRating(),
                asset.getIntegrityRating(),
                asset.getAvailabilityRating()}) {
            if (v != null && (max == null || v > max)) max = v;
        }
        if (max == null) return null;
        if (max >= 5) return "CRITICAL";
        if (max == 4) return "HIGH";
        if (max == 3) return "MEDIUM";
        return "LOW";
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AssetResponse saveLifecycle(Long id, AssetLifecycleRequest req, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);

        if (req.getAcquiredAt()     != null) asset.setAcquiredAt(req.getAcquiredAt());
        if (req.getWarrantyExpiry() != null) asset.setWarrantyExpiry(req.getWarrantyExpiry());
        if (req.getEndOfLifeDate()  != null) asset.setEndOfLifeDate(req.getEndOfLifeDate());

        LocalDateTime reviewed = parseTemporal(req.getLastReviewedAt(), "lastReviewedAt");
        if (reviewed != null) {
            asset.setLastReviewedAt(reviewed);
            // Recording a review rolls the next one forward. Otherwise the
            // overdue list never clears and people stop believing it.
            int months = asset.getReviewFrequencyMonths() != null
                    && asset.getReviewFrequencyMonths() > 0 ? asset.getReviewFrequencyMonths() : 12;
            asset.setNextReviewDate(reviewed.toLocalDate().plusMonths(months));
        }

        if (req.getDisposalMethod()      != null) asset.setDisposalMethod(trimToNull(req.getDisposalMethod()));
        if (req.getDisposalEvidenceRef() != null) asset.setDisposalEvidenceRef(trimToNull(req.getDisposalEvidenceRef()));

        asset.setUpdatedBy(userId);
        assetRepository.save(asset);
        log.info("[ASSET] Lifecycle saved | id={} | eol={}", id, asset.getEndOfLifeDate());
        return toResponse(asset, tenantId);
    }

    @Transactional
    public AssetResponse sendForRepair(Long id, Long userId, Long tenantId) {
        return simpleTransition(id, Asset.Status.IN_REPAIR, userId, tenantId, "Sent for repair");
    }

    @Transactional
    public AssetResponse moveToStorage(Long id, Long userId, Long tenantId) {
        return simpleTransition(id, Asset.Status.IN_STORAGE, userId, tenantId, "Moved to storage");
    }

    @Transactional
    public AssetResponse returnToService(Long id, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);
        transition(asset, Asset.Status.ACTIVE, userId);
        assetRepository.save(asset);
        log.info("[ASSET] Returned to service | id={} | by={}", id, userId);
        return toResponse(asset, tenantId);
    }

    @Transactional
    public AssetResponse retire(Long id, String remarks, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);

        // Children first. Retiring a server while the applications on it are
        // still ACTIVE leaves an inventory that says those apps run on nothing,
        // which is exactly the inconsistency A.5.9 exists to prevent.
        List<Asset> liveChildren = assetRepository
                .findByTenantIdAndParentAssetIdAndIsDeletedFalse(tenantId, id).stream()
                .filter(c -> c.getStatus() == Asset.Status.ACTIVE || c.getStatus() == Asset.Status.IN_REPAIR)
                .toList();
        if (!liveChildren.isEmpty()) {
            throw new ValidationException(
                    "Retire or reassign the " + liveChildren.size() + " asset(s) that sit on this one first: "
                            + liveChildren.stream().limit(5)
                            .map(c -> c.getAssetRef() != null ? c.getAssetRef() : c.getName())
                            .reduce((x, y) -> x + ", " + y).orElse("")
                            + (liveChildren.size() > 5 ? ", …" : "") + ".");
        }

        transition(asset, Asset.Status.RETIRED, userId);
        asset.setRetiredAt(LocalDateTime.now());
        if (!isBlank(remarks)) {
            asset.setDescription(appendNote(asset.getDescription(), "Retired: " + remarks));
        }
        assetRepository.save(asset);
        log.info("[ASSET] Retired | id={} | by={}", id, userId);
        return toResponse(asset, tenantId);
    }

    @Transactional
    public AssetResponse reinstate(Long id, String remarks, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);
        transition(asset, Asset.Status.ACTIVE, userId);
        asset.setRetiredAt(null);
        if (!isBlank(remarks)) {
            asset.setDescription(appendNote(asset.getDescription(), "Reinstated: " + remarks));
        }
        assetRepository.save(asset);
        log.info("[ASSET] Reinstated | id={} | by={}", id, userId);
        return toResponse(asset, tenantId);
    }

    /**
     * Final disposal. Refuses without a method and an evidence reference,
     * because a disposal record with neither proves nothing and AST-03.3 is
     * tested on exactly that evidence.
     */
    @Transactional
    public AssetResponse dispose(Long id, AssetLifecycleRequest req, Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(id, tenantId);

        if (req != null) {
            if (req.getDisposalMethod()      != null) asset.setDisposalMethod(trimToNull(req.getDisposalMethod()));
            if (req.getDisposalEvidenceRef() != null) asset.setDisposalEvidenceRef(trimToNull(req.getDisposalEvidenceRef()));
        }
        if (isBlank(asset.getDisposalMethod())) {
            throw new ValidationException(
                    "Record a disposal method on the Lifecycle tab before disposing. "
                            + "Secure disposal has to be evidenced, not asserted.");
        }
        if (isBlank(asset.getDisposalEvidenceRef())) {
            throw new ValidationException(
                    "Record a disposal evidence reference (certificate number, ticket or document id) "
                            + "on the Lifecycle tab before disposing.");
        }

        transition(asset, Asset.Status.DISPOSED, userId);
        asset.setDisposedAt(LocalDateTime.now());
        assetRepository.save(asset);
        log.info("[ASSET] Disposed | id={} | method={} | by={}", id, asset.getDisposalMethod(), userId);
        return toResponse(asset, tenantId);
    }

    private AssetResponse simpleTransition(Long id, Asset.Status target, Long userId,
                                           Long tenantId, String logLabel) {
        Asset asset = requireEditableAsset(id, tenantId);
        transition(asset, target, userId);
        assetRepository.save(asset);
        log.info("[ASSET] {} | id={} | by={}", logLabel, id, userId);
        return toResponse(asset, tenantId);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RISK LINKAGE
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public AssetResponse.LinkedRisk linkRisk(Long assetId, AssetRiskLinkRequest req,
                                             Long userId, Long tenantId) {
        Asset asset = requireEditableAsset(assetId, tenantId);

        // The tenant's OWN risk. A platform library row has tenant_id NULL and
        // is not theirs to attach to anything — they adopt it first.
        Risk risk = riskRepository.findByIdAndTenantId(req.getRiskId(), tenantId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Risk", req.getRiskId()));

        if (linkRepository.existsByRiskIdAndAssetId(risk.getId(), asset.getId())) {
            throw new ValidationException("That risk is already linked to this asset.");
        }

        RiskAssetLink link = RiskAssetLink.builder()
                .tenantId(tenantId)
                .riskId(risk.getId())
                .assetId(asset.getId())
                .linkNote(trimToNull(req.getLinkNote()))
                .createdBy(userId)
                .build();
        linkRepository.save(link);
        log.info("[ASSET] Risk linked | assetId={} riskId={} by={}", assetId, risk.getId(), userId);

        return toLinkedRisk(link, risk);
    }

    @Transactional
    public void unlinkRisk(Long assetId, Long riskId, Long tenantId) {
        requireEditableAsset(assetId, tenantId);
        RiskAssetLink link = linkRepository.findByRiskIdAndAssetId(riskId, assetId)
                .filter(l -> tenantId.equals(l.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("RiskAssetLink", "riskId", riskId));
        linkRepository.delete(link);
        log.info("[ASSET] Risk unlinked | assetId={} riskId={}", assetId, riskId);
    }

    private List<AssetResponse.LinkedRisk> buildLinkedRisks(Long assetId, Long tenantId) {
        List<RiskAssetLink> links = linkRepository.findByAssetIdAndTenantId(assetId, tenantId);
        if (links.isEmpty()) return List.of();

        List<Long> riskIds = links.stream().map(RiskAssetLink::getRiskId).toList();
        Map<Long, Risk> risksById = new HashMap<>();
        riskRepository.findAllById(riskIds).forEach(r -> risksById.put(r.getId(), r));

        List<AssetResponse.LinkedRisk> out = new ArrayList<>(links.size());
        for (RiskAssetLink link : links) {
            Risk risk = risksById.get(link.getRiskId());
            if (risk == null) continue;              // risk deleted out from under the link
            out.add(toLinkedRisk(link, risk));
        }
        return out;
    }

    private AssetResponse.LinkedRisk toLinkedRisk(RiskAssetLink link, Risk risk) {
        Integer residual = risk.getResidualScore();
        Integer inherent = risk.getInherentScore();
        Integer score = residual != null ? residual : inherent;
        return AssetResponse.LinkedRisk.builder()
                .linkId(link.getId())
                .id(risk.getId())
                .ref(risk.getRiskRef())
                .title(risk.getTitle())
                .status(risk.getStatus() != null ? risk.getStatus().name() : null)
                // The residual score is the honest headline once treatment has
                // been assessed; inherent is the fallback before that.
                .badge(score != null ? "Score " + score : null)
                .linkNote(link.getLinkNote())
                .navEntityType("risk")
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STATS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        Map<String, Long> byStatus      = grouped(assetRepository.countByStatusForTenant(tenantId));
        Map<String, Long> byType        = grouped(assetRepository.countByTypeForTenant(tenantId));
        Map<String, Long> byCriticality = grouped(assetRepository.countByCriticalityForTenant(tenantId));

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long inService = byStatus.getOrDefault(Asset.Status.ACTIVE.name(), 0L)
                + byStatus.getOrDefault(Asset.Status.IN_REPAIR.name(), 0L);

        stats.put("total",          total);
        stats.put("inService",      inService);
        stats.put("byStatus",       byStatus);
        stats.put("byType",         byType);
        stats.put("byCriticality",  byCriticality);
        stats.put("pastEndOfLife",  assetRepository.findPastEndOfLife(tenantId, LocalDate.now()).size());
        stats.put("reviewOverdue",  assetRepository.findOverdueForReview(tenantId, LocalDate.now()).size());
        return stats;
    }

    private Map<String, Long> grouped(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put(row[0] == null ? "UNSET" : String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAPPING
    // ═════════════════════════════════════════════════════════════════════════

    public AssetResponse toResponse(Asset asset, Long tenantId) {
        List<AssetResponse.LinkedRisk> risks = buildLinkedRisks(asset.getId(), tenantId);
        boolean disposed = asset.getStatus() == Asset.Status.DISPOSED;

        int childCount = assetRepository
                .findByTenantIdAndParentAssetIdAndIsDeletedFalse(tenantId, asset.getId()).size();

        return AssetResponse.builder()
                .id(asset.getId())
                .assetRef(asset.getAssetRef())
                .name(asset.getName())
                .description(asset.getDescription())
                .assetType(asset.getAssetType())
                .assetClass(asset.getAssetClass())
                .status(asset.getStatus() != null ? asset.getStatus().name() : null)
                // Ratings go out as text — see AssetResponse javadoc.
                .confidentialityRating(str(asset.getConfidentialityRating()))
                .integrityRating(str(asset.getIntegrityRating()))
                .availabilityRating(str(asset.getAvailabilityRating()))
                .criticality(asset.getCriticality())
                .classification(asset.getClassification())
                .ownerId(asset.getOwnerId())
                .ownerName(resolveUserName(asset.getOwnerId()))
                .ownerTeam(asset.getOwnerTeam())
                .custodianId(asset.getCustodianId())
                .custodianName(resolveUserName(asset.getCustodianId()))
                .environment(asset.getEnvironment())
                .hostingModel(asset.getHostingModel())
                .location(asset.getLocation())
                .dataResidency(asset.getDataResidency())
                .assetIdentifier(asset.getAssetIdentifier())
                .serialNumber(asset.getSerialNumber())
                .vendorId(asset.getVendorId())
                .vendorName(resolveVendorName(asset.getVendorId(), tenantId))
                .parentAssetId(asset.getParentAssetId())
                .parentAssetName(resolveAssetName(asset.getParentAssetId(), tenantId))
                .parentId(asset.getParentAssetId())
                .childCount(childCount)
                .containsPersonalData(asset.isContainsPersonalData())
                .personalDataCategories(asset.getPersonalDataCategories())
                .controlTags(asset.getControlTags())
                .frameworkRefs(asset.getFrameworkRefs())
                .acquiredAt(asset.getAcquiredAt())
                .warrantyExpiry(asset.getWarrantyExpiry())
                .endOfLifeDate(asset.getEndOfLifeDate())
                .retiredAt(asset.getRetiredAt())
                .disposedAt(asset.getDisposedAt())
                .disposalMethod(asset.getDisposalMethod())
                .disposalEvidenceRef(asset.getDisposalEvidenceRef())
                .pastEndOfLife(isPastEndOfLife(asset))
                .lastReviewedAt(asset.getLastReviewedAt())
                .nextReviewDate(asset.getNextReviewDate())
                .reviewFrequencyMonths(asset.getReviewFrequencyMonths())
                .reviewOverdue(asset.getNextReviewDate() != null
                        && asset.getNextReviewDate().isBefore(LocalDate.now())
                        && !disposed && asset.getStatus() != Asset.Status.RETIRED)
                .createdAt(asset.getCreatedAt())
                .updatedAt(asset.getUpdatedAt())
                .createdBy(asset.getCreatedBy())
                .editable(!disposed)
                .linkedRisks(risks)
                .linkedRiskCount(risks.size())
                .build();
    }

    private boolean isPastEndOfLife(Asset asset) {
        return asset.getEndOfLifeDate() != null
                && asset.getEndOfLifeDate().isBefore(LocalDate.now())
                && (asset.getStatus() == Asset.Status.ACTIVE
                || asset.getStatus() == Asset.Status.IN_REPAIR);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HELPERS
    /**
     * Every asset in the tenant, for the composition hierarchy.
     *
     * Unpaginated on purpose — see AssetController.tree. Disposed assets are
     * excluded by default: they are evidence, not inventory, and a chart of
     * what exists should not be half full of things that no longer do.
     */
    @Transactional(readOnly = true)
    public List<Asset> listAllForTree(Long tenantId, boolean includeDisposed) {
        return assetRepository.findAll().stream()
                .filter(a -> tenantId.equals(a.getTenantId()) && !a.isDeleted())
                .filter(a -> includeDisposed || a.getStatus() != Asset.Status.DISPOSED)
                .toList();
    }

    // ═════════════════════════════════════════════════════════════════════════

    /** The tenant's own, undeleted asset — or 404. */
    public Asset requireOwnAsset(Long id, Long tenantId) {
        return assetRepository.findByIdAndTenantIdAndIsDeletedFalse(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Asset", id));
    }

    /**
     * As above, but also refuses a DISPOSED asset.
     *
     * Disposal is the one irreversible thing in the module: the record exists
     * to say "this was destroyed on that date with this certificate", and a
     * record that keeps changing afterwards is not evidence of anything.
     */
    private Asset requireEditableAsset(Long id, Long tenantId) {
        Asset asset = requireOwnAsset(id, tenantId);
        if (asset.getStatus() == Asset.Status.DISPOSED) {
            throw new ValidationException(
                    "This asset has been disposed of and its record is now read-only. "
                            + "Create a new asset if the item came back into service.");
        }
        return asset;
    }

    private void transition(Asset asset, Asset.Status target, Long userId) {
        Asset.Status current = asset.getStatus();
        Set<Asset.Status> legal = ALLOWED.getOrDefault(current, Set.of());
        if (!legal.contains(target)) {
            throw new ValidationException(
                    "Cannot move an asset from " + current + " to " + target + ". "
                            + (legal.isEmpty() ? current + " is a terminal state."
                            : "Allowed from " + current + ": " + legal + "."));
        }
        asset.setStatus(target);
        asset.setUpdatedBy(userId);
    }

    /**
     * Refuses a parent that would create a cycle.
     *
     * Without this, setting A's parent to B while B's parent is A makes
     * EntityTreeView recurse until the browser tab dies, and there is nothing
     * in the tree builder that would catch it. The walk is bounded so a cycle
     * that already exists in the data cannot hang this check either.
     */
    private void requireSafeParent(Asset asset, Long newParentId, Long tenantId) {
        if (newParentId.equals(asset.getId())) {
            throw new ValidationException("An asset cannot be part of itself.");
        }
        Asset parent = requireOwnAsset(newParentId, tenantId);

        Long cursor = parent.getParentAssetId();
        int hops = 0;
        while (cursor != null && hops++ < MAX_ANCESTOR_WALK) {
            if (cursor.equals(asset.getId())) {
                throw new ValidationException(
                        "That would create a loop: " + parent.getName()
                                + " already sits under this asset.");
            }
            Long next = assetRepository.findByIdAndTenantIdAndIsDeletedFalse(cursor, tenantId)
                    .map(Asset::getParentAssetId).orElse(null);
            cursor = next;
        }
        if (hops >= MAX_ANCESTOR_WALK) {
            throw new ValidationException(
                    "The asset hierarchy is nested more than " + MAX_ANCESTOR_WALK
                            + " levels deep, or already contains a loop. Fix that before reparenting.");
        }
    }

    private String resolveRef(String supplied, Long tenantId) {
        String trimmed = trimToNull(supplied);
        if (trimmed != null && !assetRepository.existsByAssetRefAndTenantId(trimmed, tenantId)) {
            return trimmed;
        }
        long seq = assetRepository.nextAssetRefSequence(tenantId);
        String candidate = String.format("AST-%d-%04d", LocalDate.now().getYear(), seq);
        int guard = 0;
        while (assetRepository.existsByAssetRefAndTenantId(candidate, tenantId) && guard++ < 1000) {
            candidate = String.format("AST-%d-%04d", LocalDate.now().getYear(), ++seq);
        }
        return candidate;
    }

    /** "1".."5" from the select, or a bare integer. Blank means "not supplied". */
    private Integer parseRating(String raw, String field) {
        if (isBlank(raw)) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 1 || v > 5) {
                throw new ValidationException(field + " must be between 1 and 5.");
            }
            return v;
        } catch (NumberFormatException ex) {
            throw new ValidationException(
                    "Could not read " + field + " = '" + raw + "'. Expected a number from 1 to 5.");
        }
    }

    private LocalDateTime parseTemporal(String raw, String field) {
        if (isBlank(raw)) return null;
        String value = raw.trim();
        try {
            if (value.length() == 10) return LocalDate.parse(value).atStartOfDay();
            String iso = value.replace(" ", "T");
            return LocalDateTime.parse(iso.substring(0, Math.min(19, iso.length())));
        } catch (DateTimeParseException ex) {
            throw new ValidationException(
                    "Could not read " + field + " = '" + raw + "'. Expected a date (yyyy-MM-dd).");
        }
    }

    /**
     * Membership, not users.tenant_id — an external auditor's home tenant is
     * their firm, and checking the column would reject exactly the people the
     * guest model exists to admit.
     */
    private void requireTenantUser(Long userId, Long tenantId, String field) {
        if (userId == null) return;
        boolean ok = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .map(u -> tenantId.equals(u.getTenantId())
                        || membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                        .map(m -> m.isUsable())
                        .orElse(false))
                .orElse(false);
        if (!ok) {
            throw new ValidationException(
                    field + "=" + userId + " is not an active user of this organization.");
        }
    }

    private void requireTenantVendor(Long vendorId, Long tenantId) {
        vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .orElseThrow(() -> new ValidationException(
                        "vendorId=" + vendorId + " is not a vendor of this organization."));
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> {
                    String full = u.getFullName();
                    return (full != null && !full.isBlank()) ? full : u.getEmail();
                })
                .orElse(null);
    }

    private String resolveVendorName(Long vendorId, Long tenantId) {
        if (vendorId == null) return null;
        return vendorRepository.findByIdAndTenantIdAndIsDeletedFalse(vendorId, tenantId)
                .map(v -> v.getName()).orElse(null);
    }

    private String resolveAssetName(Long assetId, Long tenantId) {
        if (assetId == null) return null;
        return assetRepository.findByIdAndTenantIdAndIsDeletedFalse(assetId, tenantId)
                .map(Asset::getName).orElse(null);
    }

    private String appendNote(String existing, String addition) {
        String stamp = "[" + LocalDateTime.now() + "] " + addition;
        return isBlank(existing) ? stamp : existing + "\n" + stamp;
    }

    private static String  str(Integer v)        { return v == null ? null : String.valueOf(v); }
    private static boolean isBlank(String s)     { return s == null || s.isBlank(); }
    private static String  nullToEmpty(String s) { return s == null ? "" : s; }
    private static String  trimToNull(String s)  { return isBlank(s) ? null : s.trim(); }
}