package com.kashi.grc.onboarding.service;

import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.exception.ValidationException;
import com.kashi.grc.onboarding.domain.OnboardingTemplate;
import com.kashi.grc.onboarding.domain.OnboardingTemplateItem;
import com.kashi.grc.onboarding.domain.PersonnelOnboardingItem;
import com.kashi.grc.onboarding.repository.OnboardingTemplateItemRepository;
import com.kashi.grc.onboarding.repository.OnboardingTemplateRepository;
import com.kashi.grc.onboarding.repository.PersonnelOnboardingItemRepository;
import com.kashi.grc.personnel.domain.Personnel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Onboarding checklists: choosing a template, instantiating it, and settling
 * the items.
 *
 * ── THE ONE DECISION EVERYTHING ELSE FOLLOWS FROM ─────────────────────────
 * An incomplete checklist does NOT block a person becoming ACTIVE.
 *
 * People start work before the paperwork finishes. A system that refuses gets
 * worked around within a week — somebody ticks every box on day one to unblock
 * the hire — and then the checklist records nothing true. So items go overdue
 * and feed the compliance rollup instead. Visibility, not obstruction.
 *
 * Offboarding is deliberately the opposite and refuses outright, because there
 * the person is leaving and there is no next chance to collect the evidence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final OnboardingTemplateRepository       templateRepository;
    private final OnboardingTemplateItemRepository   templateItemRepository;
    private final PersonnelOnboardingItemRepository  itemRepository;

    // ═════════════════════════════════════════════════════════════════════════
    // INSTANTIATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates this person's checklist from whichever template fits.
     *
     * Called when a person is created, not when they activate. A background
     * check due at -7 days has to exist before they start, or the negative
     * offset means nothing.
     *
     * REQUIRES_NEW and swallows its own failures, the same as training
     * auto-assign: adding somebody to the roster is an HR fact and must not
     * roll back because a checklist template is misconfigured.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int instantiateFor(Personnel person, Long actorId) {
        try {
            // Idempotent. A re-activation, a retried request or a second call
            // from a different hook must not produce a second checklist.
            if (itemRepository.countByPersonnelId(person.getId()) > 0) {
                return 0;
            }

            OnboardingTemplate tpl = pickTemplate(person);
            if (tpl == null) {
                log.debug("[ONBOARDING] No template for {} ({}) — nothing created",
                        person.getPersonRef(), person.getEmploymentType());
                return 0;
            }

            LocalDate anchor = person.getStartDate() != null
                    ? person.getStartDate() : LocalDate.now();

            int created = 0;
            for (OnboardingTemplateItem src : templateItemRepository
                    .findByTemplateIdAndIsActiveTrueOrderBySortOrderAsc(tpl.getId())) {

                itemRepository.save(PersonnelOnboardingItem.builder()
                        .tenantId(person.getTenantId())
                        .personnelId(person.getId())
                        .templateItemId(src.getId())
                        // Snapshotted, not joined. Editing the template later
                        // must not rewrite what somebody already completed.
                        .title(src.getTitle())
                        .description(src.getDescription())
                        .category(src.getCategory())
                        .ownerRole(src.getOwnerRole())
                        .isRequired(src.isRequired())
                        .requiresEvidence(src.isRequiresEvidence())
                        .status(PersonnelOnboardingItem.Status.PENDING)
                        .dueAt(anchor.plusDays(src.getDueDaysOffset()).atStartOfDay())
                        .sortOrder(src.getSortOrder())
                        .build());
                created++;
            }

            log.info("[ONBOARDING] {} | template '{}' | {} item(s) created | by={}",
                    person.getPersonRef(), tpl.getName(), created, actorId);
            return created;

        } catch (Exception e) {
            log.error("[ONBOARDING] Checklist creation failed for personnelId={} | {}",
                    person.getId(), e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Most specific template wins: one matching this employment type, else the
     * tenant default, else nothing.
     *
     * Returning null rather than falling back to "any template" is deliberate.
     * Giving a vendor's staff the full employee induction because it was the
     * only list available is how a checklist stops being read.
     */
    private OnboardingTemplate pickTemplate(Personnel person) {
        List<OnboardingTemplate> all =
                templateRepository.findByTenantIdAndIsActiveTrueOrderByNameAsc(person.getTenantId());

        return all.stream()
                .filter(t -> t.getAppliesToEmploymentType() != null
                        && t.getAppliesToEmploymentType().equalsIgnoreCase(person.getEmploymentType()))
                .findFirst()
                .orElseGet(() -> all.stream()
                        .filter(OnboardingTemplate::isDefault)
                        .findFirst()
                        .orElse(null));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SETTLING ITEMS
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional
    public PersonnelOnboardingItem complete(Long itemId, Long evidenceDocumentId,
                                            Long userId, Long tenantId) {
        PersonnelOnboardingItem item = require(itemId, tenantId);

        // An item that asked for evidence and was ticked without any is the
        // commonest way a checklist ends up attesting to nothing.
        if (item.isRequiresEvidence() && evidenceDocumentId == null) {
            throw new ValidationException(
                    "\"" + item.getTitle() + "\" needs a document attached before it can be "
                            + "marked done.");
        }

        item.setStatus(PersonnelOnboardingItem.Status.DONE);
        item.setCompletedAt(LocalDateTime.now());
        item.setCompletedBy(userId);
        if (evidenceDocumentId != null) item.setEvidenceDocumentId(evidenceDocumentId);
        return itemRepository.save(item);
    }

    /**
     * Waives an item, with a reason.
     *
     * The reason is mandatory and the message says why. A waived item still
     * counts as settled for the rollup — the organisation made a decision and
     * recorded it, which is a different and perfectly legitimate thing from
     * leaving it outstanding.
     */
    @Transactional
    public PersonnelOnboardingItem waive(Long itemId, String reason, Long userId, Long tenantId) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException(
                    "A waiver needs a reason — it is the row an auditor asks about.");
        }
        PersonnelOnboardingItem item = require(itemId, tenantId);
        item.setStatus(PersonnelOnboardingItem.Status.WAIVED);
        item.setWaiverReason(reason.trim());
        item.setCompletedAt(LocalDateTime.now());
        item.setCompletedBy(userId);
        return itemRepository.save(item);
    }

    @Transactional
    public PersonnelOnboardingItem markNotApplicable(Long itemId, Long userId, Long tenantId) {
        PersonnelOnboardingItem item = require(itemId, tenantId);
        item.setStatus(PersonnelOnboardingItem.Status.NOT_APPLICABLE);
        item.setCompletedAt(LocalDateTime.now());
        item.setCompletedBy(userId);
        return itemRepository.save(item);
    }

    /** Undo. Clears the evidence and reason too, so a reopened item is genuinely open. */
    @Transactional
    public PersonnelOnboardingItem reopen(Long itemId, Long tenantId) {
        PersonnelOnboardingItem item = require(itemId, tenantId);
        item.setStatus(PersonnelOnboardingItem.Status.PENDING);
        item.setCompletedAt(null);
        item.setCompletedBy(null);
        item.setWaiverReason(null);
        item.setEvidenceDocumentId(null);
        return itemRepository.save(item);
    }

    /**
     * Adds a one-off item for this person, outside any template.
     *
     * Onboarding always has exceptions — a specific clearance, a customer
     * background check. Forcing those into the shared template so they can be
     * tracked would then impose them on everybody.
     */
    @Transactional
    public PersonnelOnboardingItem addAdHoc(Long personnelId, String title, String category,
                                            Integer dueDays, boolean requiresEvidence,
                                            Long tenantId) {
        if (title == null || title.isBlank()) throw new ValidationException("The item needs a title.");

        int maxSort = itemRepository.findByPersonnelIdOrderBySortOrderAsc(personnelId).stream()
                .map(PersonnelOnboardingItem::getSortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0);

        return itemRepository.save(PersonnelOnboardingItem.builder()
                .tenantId(tenantId)
                .personnelId(personnelId)
                .title(title.trim())
                .category(category == null ? "ADMIN" : category)
                .isRequired(true)
                .requiresEvidence(requiresEvidence)
                .status(PersonnelOnboardingItem.Status.PENDING)
                .dueAt(LocalDate.now().plusDays(dueDays == null ? 7 : dueDays).atStartOfDay())
                .sortOrder(maxSort + 10)
                .build());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ROLLUP
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * One person's progress, for the detail tab and the compliance rollup.
     *
     * Only REQUIRED items count towards complete. An optional item left
     * outstanding should not hold somebody at 90% forever — if it mattered it
     * would be required, and if it does not, it must not affect the number a
     * manager is chased about.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> progressFor(Long personnelId) {
        List<PersonnelOnboardingItem> items =
                itemRepository.findByPersonnelIdOrderBySortOrderAsc(personnelId);

        long required = items.stream().filter(PersonnelOnboardingItem::isRequired).count();
        long settled  = items.stream()
                .filter(PersonnelOnboardingItem::isRequired)
                .filter(PersonnelOnboardingItem::isSettled).count();
        long overdue  = items.stream().filter(PersonnelOnboardingItem::isOverdue).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total",       items.size());
        out.put("required",    required);
        out.put("settled",     settled);
        out.put("outstanding", required - settled);
        out.put("overdue",     overdue);
        out.put("percent",     required == 0 ? 100 : (int) Math.round(settled * 100.0 / required));
        out.put("complete",    required > 0 && settled == required);
        return out;
    }

    /** Tenant-level counts for the dashboard, in the shape the widgets read. */
    @Transactional(readOnly = true)
    public Map<String, Object> getStats(Long tenantId) {
        List<PersonnelOnboardingItem> all = itemRepository.findByTenantId(tenantId);

        Map<String, Long> byCategory = new LinkedHashMap<>();
        Map<String, Long> byStatus   = new LinkedHashMap<>();
        long overdue = 0;
        for (PersonnelOnboardingItem i : all) {
            byCategory.merge(i.getCategory(), 1L, Long::sum);
            byStatus.merge(i.getStatus().name(), 1L, Long::sum);
            if (i.isOverdue()) overdue++;
        }

        long settled = all.stream().filter(PersonnelOnboardingItem::isSettled).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalItems",  all.size());
        out.put("outstanding", all.size() - settled);
        out.put("overdue",     overdue);
        out.put("percentComplete", all.isEmpty() ? 100
                : (int) Math.round(settled * 100.0 / all.size()));
        out.put("byStatus",    series(byStatus));
        out.put("byCategory",  series(byCategory));
        return out;
    }

    /** [{key, value}] — a list, because a bare map has no stable chart order. */
    private List<Map<String, Object>> series(Map<String, Long> counts) {
        List<Map<String, Object>> out = new ArrayList<>(counts.size());
        counts.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", k);
            row.put("value", v);
            out.add(row);
        });
        return out;
    }

    private PersonnelOnboardingItem require(Long id, Long tenantId) {
        PersonnelOnboardingItem item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OnboardingItem", id));
        if (!tenantId.equals(item.getTenantId())) {
            throw new ResourceNotFoundException("OnboardingItem", id);
        }
        return item;
    }
}