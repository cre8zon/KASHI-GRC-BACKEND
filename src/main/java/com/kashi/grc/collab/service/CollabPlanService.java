package com.kashi.grc.collab.service;

import com.kashi.grc.collab.domain.CollabPlanChange;
import com.kashi.grc.collab.domain.CollabPlanItem;
import com.kashi.grc.collab.domain.CollabProgramme;
import com.kashi.grc.collab.domain.CollabProgrammeMember;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.domain.CollabWorkspaceMember;
import com.kashi.grc.collab.repository.CollabPlanChangeRepository;
import com.kashi.grc.collab.repository.CollabPlanItemRepository;
import com.kashi.grc.collab.repository.CollabProgrammeMemberRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceMemberRepository;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collaboration plan — phase 2: plan items, computed progress for items linked
 * to an engagement, change history, the engagement Timeline, My week, and
 * Excel export/import.
 *
 * ── WHO MAY DO WHAT ───────────────────────────────────────────────────────────
 *
 *   See an item        can see the workspace, and the item is workspace-level
 *                      (no programme) or in a programme the caller can see
 *                      (the firm's people: only programmes they are on).
 *   Edit fully         workspace managers (CollabAccessService.canManage), or
 *                      holders of collab:plan:edit: create, delete, dates,
 *                      owner, programme, parent, dependencies, link.
 *   Update own items   the item's owner: status, progress, actual end and
 *                      description of a MANUAL item.
 *
 * Every change to dates, owner, status, title, link or programme writes a
 * collab_plan_changes row. A date or owner change made by someone else
 * notifies the item's owner.
 *
 * ── LINKED PROGRESS ───────────────────────────────────────────────────────────
 *
 * An item linked to an audit engagement shows that engagement's real figures:
 * controls with evidence submitted and controls tested, progress =
 * (submitted + tested) / (2 × controls); done when the engagement is CLOSED.
 * A workspace member who is not on the engagement sees only these numbers and
 * the engagement's name — never its controls (decided in the design).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabPlanService {

    public static final String PERM_PLAN_EDIT = "collab:plan:edit";
    private static final Set<String> KINDS = Set.of(CollabPlanItem.KIND_PHASE, CollabPlanItem.KIND_TASK,
            CollabPlanItem.KIND_MILESTONE);
    private static final Set<String> STATUSES = Set.of(CollabPlanItem.NOT_STARTED, CollabPlanItem.IN_PROGRESS,
            CollabPlanItem.BLOCKED, CollabPlanItem.DONE);

    private final CollabAccessService             access;
    private final CollabWorkspaceService          workspaceService;
    private final CollabPlanItemRepository        itemRepository;
    private final CollabPlanChangeRepository      changeRepository;
    private final CollabWorkspaceMemberRepository memberRepository;
    private final CollabProgrammeMemberRepository programmeMemberRepository;
    private final UserRepository                  userRepository;
    private final NotificationService             notificationService;
    private final CollabPlanColumnsService        columnsService;

    @PersistenceContext
    private EntityManager em;

    // ══════════════════════ READ ═════════════════════════════════════════════

    /** The plan of a workspace (optionally one programme), as the caller may see it. */
    @Transactional(readOnly = true)
    public Map<String, Object> plan(Long workspaceId, Long programmeId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        List<CollabProgramme> programmes = workspaceService.visibleProgrammes(c, ws);
        Set<Long> progIds = programmes.stream().map(CollabProgramme::getId).collect(Collectors.toSet());

        List<CollabPlanItem> items = itemRepository.findByWorkspaceIdAndIsDeletedFalse(ws.getId()).stream()
                .filter(i -> i.getProgrammeId() == null || progIds.contains(i.getProgrammeId()))
                .filter(i -> programmeId == null || programmeId.equals(i.getProgrammeId()))
                .toList();

        boolean fullEdit = canEditPlan(c, ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", ws.getId());
        out.put("canEditPlan", fullEdit);
        out.put("today", LocalDate.now());
        out.put("programmes", programmes.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("plannedStart", p.getPlannedStart());
            m.put("plannedEnd", p.getPlannedEnd());
            m.put("status", p.getStatus());
            return m;
        }).toList());
        out.put("members", membersFor(ws));
        out.put("columns", columnsService.columns(ws.getId()));
        out.put("items", toMaps(c, items, fullEdit));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Long workspaceId, Long itemId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireVisibleItem(c, ws, itemId);
        List<CollabPlanChange> rows = changeRepository.findByPlanItemIdOrderByCreatedAtDesc(itemId);
        Map<Long, User> users = users(rows.stream().map(CollabPlanChange::getChangedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", r.getField());
            m.put("oldValue", r.getOldValue());
            m.put("newValue", r.getNewValue());
            m.put("reason", r.getReason());
            m.put("changedBy", r.getChangedBy());
            m.put("changedByName", name(users.get(r.getChangedBy())));
            m.put("changedAt", r.getCreatedAt());
            return m;
        }).toList();
    }

    /**
     * Engagements an item can be linked to: the organisation's engagements, or
     * for a firm's people only those their guest scope already shows them.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> linkableEngagements(Long workspaceId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        if (!canEditPlan(c, ws)) return List.of();
        List<Object[]> rows = em.createNativeQuery("""
                SELECT e.id, e.name, e.engagement_ref, e.status
                FROM   audit_engagements e
                WHERE  e.tenant_id = :tenantId AND e.status <> 'CANCELLED'
                ORDER  BY e.id DESC
                """).setParameter("tenantId", ws.getTenantId()).getResultList();
        Set<Long> guestVisible = c.guest() ? com.kashi.grc.common.config.multitenancy.AccessScope.engagementIds() : null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Long id = ((Number) r[0]).longValue();
            if (guestVisible != null && !guestVisible.contains(id)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", r[1]);
            m.put("ref", r[2]);
            m.put("status", r[3]);
            out.add(m);
        }
        return out;
    }

    /** Items linked to this engagement, and everything under them, across the caller's workspaces. */
    @Transactional(readOnly = true)
    public Map<String, Object> engagementTimeline(Long engagementId) {
        Caller c = access.caller();
        List<CollabWorkspace> workspaces = access.visibleWorkspaces(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", LocalDate.now());
        if (workspaces.isEmpty()) { out.put("workspaces", List.of()); return out; }

        List<CollabPlanItem> linked = itemRepository.findByTenantIdAndLinkedEntityTypeAndLinkedEntityIdAndIsDeletedFalse(
                c.tenantId(), CollabPlanItem.LINK_ENGAGEMENT, engagementId);
        Map<Long, CollabWorkspace> wsById = workspaces.stream()
                .collect(Collectors.toMap(CollabWorkspace::getId, w -> w));
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Long wsId : linked.stream().map(CollabPlanItem::getWorkspaceId).distinct().toList()) {
            CollabWorkspace ws = wsById.get(wsId);
            if (ws == null) continue;
            Set<Long> progIds = workspaceService.visibleProgrammes(c, ws).stream()
                    .map(CollabProgramme::getId).collect(Collectors.toSet());
            List<CollabPlanItem> all = itemRepository.findByWorkspaceIdAndIsDeletedFalse(wsId).stream()
                    .filter(i -> i.getProgrammeId() == null || progIds.contains(i.getProgrammeId())).toList();
            Set<Long> keep = new HashSet<>();
            all.stream().filter(i -> CollabPlanItem.LINK_ENGAGEMENT.equals(i.getLinkedEntityType())
                    && engagementId.equals(i.getLinkedEntityId())).forEach(i -> keep.add(i.getId()));
            if (keep.isEmpty()) continue;
            boolean grew = true;
            while (grew) {
                grew = false;
                for (CollabPlanItem i : all) {
                    if (i.getParentId() != null && keep.contains(i.getParentId()) && keep.add(i.getId())) grew = true;
                }
            }
            boolean fullEdit = canEditPlan(c, ws);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("workspaceId", ws.getId());
            b.put("workspaceName", ws.getName());
            b.put("items", toMaps(c, all.stream().filter(i -> keep.contains(i.getId())).toList(), fullEdit));
            blocks.add(b);
        }
        out.put("workspaces", blocks);
        return out;
    }

    /**
     * My week: my open items due in the next 14 days or overdue, and the
     * milestones due in that window, across the workspaces I can see in the
     * active organisation.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> myWeek() {
        Caller c = access.caller();
        LocalDate today = LocalDate.now(), horizon = today.plusDays(14);
        List<CollabWorkspace> workspaces = access.visibleWorkspaces(c).stream()
                .filter(w -> CollabWorkspace.ACTIVE.equals(w.getStatus())).toList();
        List<Map<String, Object>> mine = new ArrayList<>(), milestones = new ArrayList<>();
        for (CollabWorkspace ws : workspaces) {
            Set<Long> progIds = workspaceService.visibleProgrammes(c, ws).stream()
                    .map(CollabProgramme::getId).collect(Collectors.toSet());
            List<CollabPlanItem> items = itemRepository.findByWorkspaceIdAndIsDeletedFalse(ws.getId()).stream()
                    .filter(i -> i.getProgrammeId() == null || progIds.contains(i.getProgrammeId())).toList();
            boolean fullEdit = canEditPlan(c, ws);
            List<CollabPlanItem> myItems = items.stream()
                    .filter(i -> c.userId().equals(i.getOwnerUserId()))
                    .filter(i -> !CollabPlanItem.DONE.equals(i.getStatus()))
                    .filter(i -> i.getPlannedEnd() != null && !i.getPlannedEnd().isAfter(horizon)).toList();
            List<CollabPlanItem> ms = items.stream()
                    .filter(i -> CollabPlanItem.KIND_MILESTONE.equals(i.getKind()))
                    .filter(i -> !CollabPlanItem.DONE.equals(i.getStatus()))
                    .filter(i -> i.getPlannedEnd() != null && !i.getPlannedEnd().isAfter(horizon)).toList();
            for (Map<String, Object> m : toMaps(c, myItems, fullEdit)) {
                m.put("workspaceId", ws.getId()); m.put("workspaceName", ws.getName()); mine.add(m);
            }
            for (Map<String, Object> m : toMaps(c, ms, fullEdit)) {
                m.put("workspaceId", ws.getId()); m.put("workspaceName", ws.getName()); milestones.add(m);
            }
        }
        Comparator<Map<String, Object>> byEnd = Comparator.comparing(m -> String.valueOf(m.get("plannedEnd")));
        mine.sort(byEnd);
        milestones.sort(byEnd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today);
        out.put("horizon", horizon);
        out.put("myItems", mine);
        out.put("milestones", milestones);
        return out;
    }

    // ══════════════════════ WRITE ════════════════════════════════════════════

    @Transactional
    public Map<String, Object> createItem(Long workspaceId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireEditPlan(c, ws);
        String title = str(body.get("title"));
        if (title == null || title.isBlank()) throw bad("COLLAB_TITLE_REQUIRED", "An item needs a title");

        CollabPlanItem i = CollabPlanItem.builder()
                .workspaceId(ws.getId())
                .title(title.trim())
                .description(str(body.get("description")))
                .kind(kind(body.get("kind")))
                .status(CollabPlanItem.NOT_STARTED)
                .progress(0)
                .progressMode(CollabPlanItem.MODE_MANUAL)
                .sortOrder(intOr(body.get("sortOrder"), 0))
                .build();
        i.setTenantId(ws.getTenantId());
        i.setCreatedBy(c.userId());
        i.setProgrammeId(programmeFor(c, ws, longOrNull(body.get("programmeId"))));
        i.setParentId(parentFor(ws, null, longOrNull(body.get("parentId"))));
        i.setPlannedStart(date(body.get("plannedStart")));
        i.setPlannedEnd(date(body.get("plannedEnd")));
        if (CollabPlanItem.KIND_MILESTONE.equals(i.getKind()) && i.getPlannedEnd() == null) i.setPlannedEnd(i.getPlannedStart());
        checkDates(i);
        Long owner = longOrNull(body.get("ownerUserId"));
        if (owner != null) { checkOwner(ws, i, owner); i.setOwnerUserId(owner); }
        i.setDependsOn(dependsFor(ws, null, body.get("dependsOn")));
        applyLink(ws, c, i, body);
        if (body.containsKey("custom")) columnsService.apply(ws, i, body.get("custom"));
        itemRepository.save(i);
        record(i, "created", null, i.getTitle(), c.userId(), null);
        if (owner != null && !owner.equals(c.userId())) notifyOwner(ws, i, owner,
                "You own \"" + i.getTitle() + "\" in the " + ws.getName() + " plan");
        return toMaps(c, List.of(i), true).get(0);
    }

    @Transactional
    public Map<String, Object> updateItem(Long workspaceId, Long itemId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        CollabPlanItem i = requireVisibleItem(c, ws, itemId);
        boolean full = canEditPlan(c, ws);
        boolean owner = c.userId().equals(i.getOwnerUserId());
        if (!full && !owner) {
            throw new BusinessException("COLLAB_PLAN_EDIT_DENIED",
                    "Only the item's owner or someone who can edit the plan can change it", HttpStatus.FORBIDDEN);
        }
        String reason = str(body.get("reason"));
        Set<String> ownerFields = Set.of("status", "progress", "actualEnd", "description", "reason", "custom");
        if (!full) {
            for (String k : body.keySet()) {
                if (!ownerFields.contains(k)) {
                    throw new BusinessException("COLLAB_PLAN_EDIT_DENIED",
                            "As the item's owner you can update its status, progress and description; "
                                    + "dates and other fields need permission to edit the plan", HttpStatus.FORBIDDEN);
                }
            }
        }

        LocalDate oldStart = i.getPlannedStart(), oldEnd = i.getPlannedEnd();
        Long oldOwner = i.getOwnerUserId();

        if (full) {
            if (body.containsKey("title")) {
                String t = str(body.get("title"));
                if (t == null || t.isBlank()) throw bad("COLLAB_TITLE_REQUIRED", "An item needs a title");
                change(i, "title", i.getTitle(), t.trim(), c, reason);
                i.setTitle(t.trim());
            }
            if (body.containsKey("kind")) i.setKind(kind(body.get("kind")));
            if (body.containsKey("programmeId")) {
                Long p = programmeFor(c, ws, longOrNull(body.get("programmeId")));
                change(i, "programme", s(i.getProgrammeId()), s(p), c, reason);
                i.setProgrammeId(p);
            }
            if (body.containsKey("parentId")) i.setParentId(parentFor(ws, i.getId(), longOrNull(body.get("parentId"))));
            if (body.containsKey("plannedStart")) i.setPlannedStart(date(body.get("plannedStart")));
            if (body.containsKey("plannedEnd"))   i.setPlannedEnd(date(body.get("plannedEnd")));
            if (body.containsKey("ownerUserId")) {
                Long o = longOrNull(body.get("ownerUserId"));
                if (o != null) checkOwner(ws, i, o);
                i.setOwnerUserId(o);
            }
            if (body.containsKey("dependsOn")) i.setDependsOn(dependsFor(ws, i.getId(), body.get("dependsOn")));
            if (body.containsKey("sortOrder")) i.setSortOrder(intOr(body.get("sortOrder"), 0));
            // Formatting (colours, bold …) — plan editors only; not recorded in the history.
            if (body.containsKey("format")) i.setFormatJson(columnsService.cleanFormat(ws.getId(), body.get("format")));
            if (body.containsKey("linkedEngagementId")) {
                String before = i.getLinkedEntityId() == null ? null : i.getLinkedEntityType() + ":" + i.getLinkedEntityId();
                applyLink(ws, c, i, body);
                String after = i.getLinkedEntityId() == null ? null : i.getLinkedEntityType() + ":" + i.getLinkedEntityId();
                change(i, "link", before, after, c, reason);
            }
            if (i.getOwnerUserId() != null && i.getProgrammeId() != null) checkOwner(ws, i, i.getOwnerUserId());
        }

        // Owner and full editors: status / progress / actual end / description of a MANUAL item.
        if (body.containsKey("description")) i.setDescription(str(body.get("description")));
        // ...and the workspace's own columns.
        if (body.containsKey("custom")) {
            columnsService.apply(ws, i, body.get("custom")).forEach((label, ov) ->
                    change(i, label.length() > 40 ? label.substring(0, 40) : label, ov[0], ov[1], c, reason));
        }
        if (CollabPlanItem.MODE_MANUAL.equals(i.getProgressMode())) {
            if (body.containsKey("status")) {
                String st = status(body.get("status"));
                change(i, "status", i.getStatus(), st, c, reason);
                i.setStatus(st);
                if (CollabPlanItem.DONE.equals(st)) {
                    i.setProgress(100);
                    if (i.getActualEnd() == null) i.setActualEnd(LocalDate.now());
                } else if (i.getActualEnd() != null && !body.containsKey("actualEnd")) {
                    i.setActualEnd(null);
                }
            }
            if (body.containsKey("progress")) {
                int p = Math.max(0, Math.min(100, intOr(body.get("progress"), 0)));
                i.setProgress(p);
            }
            if (body.containsKey("actualEnd")) i.setActualEnd(date(body.get("actualEnd")));
        }

        if (CollabPlanItem.KIND_MILESTONE.equals(i.getKind()) && i.getPlannedEnd() == null) i.setPlannedEnd(i.getPlannedStart());
        checkDates(i);
        change(i, "plannedStart", s(oldStart), s(i.getPlannedStart()), c, reason);
        change(i, "plannedEnd", s(oldEnd), s(i.getPlannedEnd()), c, reason);
        change(i, "owner", s(oldOwner), s(i.getOwnerUserId()), c, reason);
        i.setUpdatedBy(c.userId());
        itemRepository.save(i);

        // Tell the owner when someone else moves their dates or hands the item over.
        boolean datesMoved = !Objects.equals(oldStart, i.getPlannedStart()) || !Objects.equals(oldEnd, i.getPlannedEnd());
        if (!Objects.equals(oldOwner, i.getOwnerUserId()) && i.getOwnerUserId() != null
                && !i.getOwnerUserId().equals(c.userId())) {
            notifyOwner(ws, i, i.getOwnerUserId(), "You own \"" + i.getTitle() + "\" in the " + ws.getName() + " plan");
        } else if (datesMoved && i.getOwnerUserId() != null && !i.getOwnerUserId().equals(c.userId())) {
            notifyOwner(ws, i, i.getOwnerUserId(), "Dates of \"" + i.getTitle() + "\" changed: "
                    + range(oldStart, oldEnd) + " → " + range(i.getPlannedStart(), i.getPlannedEnd())
                    + (reason != null && !reason.isBlank() ? " (" + reason + ")" : ""));
        }
        return toMaps(c, List.of(i), full).get(0);
    }

    /** Soft delete. Children move up to the deleted item's parent; dependencies on it are dropped. */
    @Transactional
    public void deleteItem(Long workspaceId, Long itemId, String reason) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireEditPlan(c, ws);
        CollabPlanItem i = requireVisibleItem(c, ws, itemId);
        List<CollabPlanItem> all = itemRepository.findByWorkspaceIdAndIsDeletedFalse(ws.getId());
        for (CollabPlanItem other : all) {
            boolean dirty = false;
            if (itemId.equals(other.getParentId())) { other.setParentId(i.getParentId()); dirty = true; }
            List<Long> deps = ids(other.getDependsOn());
            if (deps.remove(itemId)) { other.setDependsOn(join(deps)); dirty = true; }
            if (dirty) itemRepository.save(other);
        }
        i.setDeleted(true);
        i.setUpdatedBy(c.userId());
        itemRepository.save(i);
        record(i, "deleted", i.getTitle(), null, c.userId(), reason);
    }

    // ══════════════════════ SHEET LAYOUT AND ROW ORDER ═══════════════════════

    /** Replace the workspace's plan columns (plan editors). */
    @Transactional
    public List<Map<String, Object>> saveColumns(Long workspaceId, Object columns) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireEditPlan(c, ws);
        return columnsService.save(ws, c.userId(), columns);
    }

    /**
     * Put rows in this order (plan editors). Only the ids given are renumbered,
     * 10 apart, starting from the lowest sort order among them — so the sheet
     * can move one row, or a whole section, without touching the rest.
     */
    @Transactional
    public void reorder(Long workspaceId, Object rawIds) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireEditPlan(c, ws);
        if (!(rawIds instanceof Collection<?> list) || list.isEmpty()) throw bad("COLLAB_BAD_ORDER", "Expected a list of item ids");
        Map<Long, CollabPlanItem> all = itemRepository.findByWorkspaceIdAndIsDeletedFalse(ws.getId()).stream()
                .collect(Collectors.toMap(CollabPlanItem::getId, x -> x));
        List<CollabPlanItem> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Object o : list) {
            Long id = longOrNull(o);
            CollabPlanItem i = id == null ? null : all.get(id);
            if (i == null) throw new ResourceNotFoundException("CollabPlanItem", id);
            if (seen.add(id)) rows.add(i);
        }
        int order = rows.stream().mapToInt(i -> i.getSortOrder() == null ? 0 : i.getSortOrder()).min().orElse(0);
        for (CollabPlanItem i : rows) {
            if (!Objects.equals(i.getSortOrder(), order)) {
                i.setSortOrder(order);
                itemRepository.save(i);
            }
            order += 10;
        }
    }

    // ══════════════════════ EXCEL ════════════════════════════════════════════

    private static final String[] COLUMNS = {
            "ID", "Programme", "Kind", "Title", "Parent", "Owner", "Owner email",
            "Planned start", "Planned end", "Status", "Progress %", "Linked engagement", "Depends on (IDs)"
    };

    @Transactional(readOnly = true)
    public byte[] exportXlsx(Long workspaceId, Long programmeId) {
        Map<String, Object> plan = plan(workspaceId, programmeId);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get("items");
        @SuppressWarnings("unchecked") List<Map<String, Object>> progs = (List<Map<String, Object>>) plan.get("programmes");
        Map<Object, Object> progName = new HashMap<>();
        progs.forEach(p -> progName.put(p.get("id"), p.get("name")));
        Map<Object, Object> title = new HashMap<>();
        items.forEach(m -> title.put(m.get("id"), m.get("title")));
        // The workspace's own columns follow the standard ones; a person is written as their email.
        @SuppressWarnings("unchecked") List<Map<String, Object>> cols = (List<Map<String, Object>>) plan.get("columns");
        List<Map<String, Object>> custom = cols.stream().filter(x -> !Boolean.TRUE.equals(x.get("builtIn"))).toList();
        @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) plan.get("members");
        Map<String, Object> emailOf = new HashMap<>();
        members.forEach(mm -> emailOf.put(String.valueOf(mm.get("userId")), mm.get("email")));

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Plan");
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row h = sh.createRow(0);
            for (int k = 0; k < COLUMNS.length; k++) {
                Cell cell = h.createCell(k);
                cell.setCellValue(COLUMNS[k]);
                cell.setCellStyle(head);
            }
            for (int k = 0; k < custom.size(); k++) {
                Cell cell = h.createCell(COLUMNS.length + k);
                cell.setCellValue(String.valueOf(custom.get(k).get("label")));
                cell.setCellStyle(head);
            }
            int r = 1;
            Map<String, CellStyle> styleCache = new HashMap<>();
            for (Map<String, Object> m : items) {
                Row row = sh.createRow(r++);
                @SuppressWarnings("unchecked") Map<String, Object> link = (Map<String, Object>) m.get("linked");
                Object[] v = {
                        m.get("id"), progName.getOrDefault(m.get("programmeId"), ""), m.get("kind"), m.get("title"),
                        m.get("parentId") == null ? "" : title.getOrDefault(m.get("parentId"), ""),
                        m.get("ownerName"), m.get("ownerEmail"), m.get("plannedStart"), m.get("plannedEnd"),
                        m.get("status"), m.get("progress"),
                        link == null ? "" : (link.get("ref") != null ? link.get("ref") + " " : "") + link.get("name"),
                        m.get("dependsOn") == null ? "" : ((List<?>) m.get("dependsOn")).stream()
                                .map(String::valueOf).collect(Collectors.joining(","))
                };
                for (int k = 0; k < v.length; k++) {
                    Object val = v[k];
                    if (val instanceof Number n) row.createCell(k).setCellValue(n.doubleValue());
                    else row.createCell(k).setCellValue(val == null ? "" : val.toString());
                }
                @SuppressWarnings("unchecked") Map<String, Object> cv = (Map<String, Object>) m.getOrDefault("custom", Map.of());
                for (int k = 0; k < custom.size(); k++) {
                    Map<String, Object> col = custom.get(k);
                    Object val = cv.get(col.get("key"));
                    Cell cell = row.createCell(COLUMNS.length + k);
                    if (val == null) cell.setCellValue("");
                    else if ("PERSON".equals(col.get("type"))) cell.setCellValue(String.valueOf(emailOf.getOrDefault(String.valueOf(val), val)));
                    else if ("CHECKBOX".equals(col.get("type"))) cell.setCellValue(Boolean.TRUE.equals(val) ? "Yes" : "");
                    else if (val instanceof Number n) cell.setCellValue(n.doubleValue());
                    else cell.setCellValue(val.toString());
                }
                applyFormats((XSSFWorkbook) wb, row, m, custom, styleCache);   // after every cell of the row exists
            }
            for (int k = 0; k < COLUMNS.length + custom.size(); k++) sh.autoSizeColumn(k);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("COLLAB_EXPORT_FAILED", "Could not build the spreadsheet");
        }
    }

    /** Sheet column → export columns it fills (Owner also fills Owner email). */
    private static final Map<String, int[]> EXPORT_COLS = Map.of(
            "kind", new int[]{2}, "title", new int[]{3}, "parent", new int[]{4}, "owner", new int[]{5, 6},
            "start", new int[]{7}, "end", new int[]{8}, "status", new int[]{9}, "progress", new int[]{10});

    /**
     * Carries the sheet's formatting into the export: the row's style on every
     * cell of the row, a cell's own style over it. Values are written first, so
     * this only styles; formats come pre-validated (CollabPlanColumnsService).
     */
    @SuppressWarnings("unchecked")
    private void applyFormats(XSSFWorkbook wb, Row row, Map<String, Object> item, List<Map<String, Object>> custom,
                              Map<String, CellStyle> cache) {
        Map<String, Object> fmt = (Map<String, Object>) item.get("format");
        if (fmt == null || fmt.isEmpty()) return;
        Map<String, Object> rowStyle = fmt.get("row") instanceof Map<?, ?> rs ? (Map<String, Object>) rs : Map.of();
        Map<String, Object> cells = fmt.get("cells") instanceof Map<?, ?> cs ? (Map<String, Object>) cs : Map.of();
        int last = COLUMNS.length + custom.size();
        for (int k = 0; k < last; k++) {
            Map<String, Object> st = new HashMap<>(rowStyle);
            String key = null;
            if (k >= COLUMNS.length) key = (String) custom.get(k - COLUMNS.length).get("key");
            else for (Map.Entry<String, int[]> e : EXPORT_COLS.entrySet()) for (int idx : e.getValue()) if (idx == k) key = e.getKey();
            if (key != null && cells.get(key) instanceof Map<?, ?> cst) st.putAll((Map<String, Object>) cst);
            if (st.isEmpty()) continue;
            Cell cell = row.getCell(k) != null ? row.getCell(k) : row.createCell(k);
            String sig = new java.util.TreeMap<>(st).toString();
            CellStyle style = cache.computeIfAbsent(sig, x -> {
                XSSFCellStyle cs = wb.createCellStyle();
                XSSFFont font = wb.createFont();
                font.setBold(Boolean.TRUE.equals(st.get("b")));
                font.setItalic(Boolean.TRUE.equals(st.get("i")));
                if (Boolean.TRUE.equals(st.get("u"))) font.setUnderline(Font.U_SINGLE);
                font.setFontHeightInPoints((short) ("s".equals(st.get("size")) ? 9 : "l".equals(st.get("size")) ? 14 : 11));
                if (st.get("color") != null) font.setColor(hexColor((String) st.get("color")));
                cs.setFont(font);
                if (st.get("bg") != null) {
                    cs.setFillForegroundColor(hexColor((String) st.get("bg")));
                    cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return cs;
            });
            cell.setCellStyle(style);
        }
    }

    private static XSSFColor hexColor(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new XSSFColor(new byte[]{(byte) (v >> 16), (byte) (v >> 8), (byte) v}, null);
    }

    /**
     * Reads a spreadsheet into plan rows WITHOUT writing anything: each row with
     * what will be created and any problems. Columns are found by their header
     * (case-insensitive); only Kind, Title, Parent, Owner email, Planned start,
     * Planned end and Status are read — a sheet exported from here imports
     * back as is.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> importPreview(Long workspaceId, Long programmeId, MultipartFile file) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireEditPlan(c, ws);
        programmeFor(c, ws, programmeId);
        List<ImportRow> rows = parse(ws, programmeId, file);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows.stream().map(ImportRow::toMap).toList());
        out.put("errorCount", rows.stream().filter(r -> !r.errors.isEmpty()).count());
        out.put("rowCount", rows.size());
        return out;
    }

    /** Creates every row — all or nothing; refused while any row has a problem. */
    @Transactional
    public Map<String, Object> importCommit(Long workspaceId, Long programmeId, MultipartFile file) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        requireEditPlan(c, ws);
        Long prog = programmeFor(c, ws, programmeId);
        List<ImportRow> rows = parse(ws, prog, file);
        long errors = rows.stream().filter(r -> !r.errors.isEmpty()).count();
        if (errors > 0) {
            throw bad("COLLAB_IMPORT_ERRORS", errors + " row(s) have problems — fix them and preview again");
        }
        Map<String, Long> phaseIds = new HashMap<>();
        int order = 0;
        for (ImportRow r : rows) {
            CollabPlanItem i = CollabPlanItem.builder()
                    .workspaceId(ws.getId())
                    .programmeId(prog)
                    .title(r.title)
                    .kind(r.kind)
                    .ownerUserId(r.ownerUserId)
                    .plannedStart(r.start)
                    .plannedEnd(r.end)
                    .status(r.status)
                    .progress(CollabPlanItem.DONE.equals(r.status) ? 100 : 0)
                    .progressMode(CollabPlanItem.MODE_MANUAL)
                    .sortOrder(order++)
                    .build();
            i.setTenantId(ws.getTenantId());
            i.setCreatedBy(c.userId());
            if (!r.custom.isEmpty()) columnsService.apply(ws, i, r.custom);
            if (r.parent != null) i.setParentId(phaseIds.get(r.parent.toLowerCase()));
            itemRepository.save(i);
            if (CollabPlanItem.KIND_PHASE.equals(r.kind)) phaseIds.put(r.title.toLowerCase(), i.getId());
            record(i, "created", null, i.getTitle(), c.userId(), "Imported from " + file.getOriginalFilename());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", rows.size());
        return out;
    }

    private static final class ImportRow {
        int rowNo; String kind; String title; String parent; String ownerEmail; Long ownerUserId;
        LocalDate start; LocalDate end; String status; List<String> errors = new ArrayList<>();
        Map<String, Object> custom = new LinkedHashMap<>();
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", rowNo); m.put("kind", kind); m.put("title", title); m.put("parent", parent);
            m.put("ownerEmail", ownerEmail); m.put("ownerUserId", ownerUserId);
            m.put("plannedStart", start); m.put("plannedEnd", end); m.put("status", status);
            m.put("custom", custom);
            m.put("errors", errors);
            return m;
        }
    }

    private List<ImportRow> parse(CollabWorkspace ws, Long programmeId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("COLLAB_IMPORT_EMPTY", "Choose an .xlsx file");
        Map<String, Long> memberByEmail = new HashMap<>();
        Set<Long> memberIds = memberRepository.findByWorkspaceId(ws.getId()).stream()
                .map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet());
        users(memberIds).values().forEach(u -> { if (u.getEmail() != null) memberByEmail.put(u.getEmail().toLowerCase(), u.getId()); });

        List<ImportRow> rows = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            Row header = sh.getRow(sh.getFirstRowNum());
            if (header == null) throw bad("COLLAB_IMPORT_EMPTY", "The first sheet is empty");
            Map<String, Integer> col = new HashMap<>();
            for (Cell cell : header) col.put(text(cell).trim().toLowerCase(), cell.getColumnIndex());
            if (!col.containsKey("title")) throw bad("COLLAB_IMPORT_NO_TITLE", "The sheet needs a \"Title\" column");
            // The workspace's own columns, matched by their name.
            Map<String, Map<String, Object>> customByHeader = new LinkedHashMap<>();
            columnsService.customColumns(ws.getId()).values().forEach(cc -> {
                String hdr = String.valueOf(cc.get("label")).trim().toLowerCase();
                if (col.containsKey(hdr)) customByHeader.put(hdr, cc);
            });

            Set<String> phasesSoFar = new HashSet<>();
            for (int r = sh.getFirstRowNum() + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String title = cell(row, col.get("title"));
                if (title.isBlank()) continue;
                ImportRow ir = new ImportRow();
                ir.rowNo = r + 1;
                ir.title = title.trim();
                String k = cell(row, col.get("kind")).trim().toUpperCase();
                ir.kind = k.isEmpty() ? CollabPlanItem.KIND_TASK : k;
                if (!KINDS.contains(ir.kind)) ir.errors.add("Kind must be Phase, Task or Milestone");
                String st = cell(row, col.get("status")).trim().toUpperCase().replace(' ', '_');
                ir.status = st.isEmpty() ? CollabPlanItem.NOT_STARTED : st;
                if (!STATUSES.contains(ir.status)) ir.errors.add("Status must be Not started, In progress, Blocked or Done");
                ir.start = dateCell(row, col.get("planned start"), ir);
                ir.end = dateCell(row, col.get("planned end"), ir);
                if (CollabPlanItem.KIND_MILESTONE.equals(ir.kind) && ir.end == null) ir.end = ir.start;
                if (ir.start != null && ir.end != null && ir.end.isBefore(ir.start)) ir.errors.add("End date is before the start date");
                String email = cell(row, col.get("owner email")).trim();
                if (!email.isEmpty()) {
                    ir.ownerEmail = email;
                    ir.ownerUserId = memberByEmail.get(email.toLowerCase());
                    if (ir.ownerUserId == null) ir.errors.add("Owner " + email + " is not a member of this workspace");
                }
                String parent = cell(row, col.get("parent")).trim();
                if (!parent.isEmpty()) {
                    ir.parent = parent;
                    if (!phasesSoFar.contains(parent.toLowerCase())) ir.errors.add("Parent \"" + parent + "\" is not a Phase above this row");
                }
                for (Map.Entry<String, Map<String, Object>> ce : customByHeader.entrySet()) {
                    Map<String, Object> cc = ce.getValue();
                    String raw = "DATE".equals(cc.get("type"))
                            ? s(dateCell(row, col.get(ce.getKey()), ir)) : cell(row, col.get(ce.getKey())).trim();
                    if (raw == null || raw.isEmpty()) continue;
                    if ("PERSON".equals(cc.get("type"))) {
                        Long pid = memberByEmail.get(raw.toLowerCase());
                        if (pid == null) { ir.errors.add(cc.get("label") + ": " + raw + " is not a member of this workspace"); continue; }
                        raw = String.valueOf(pid);
                    }
                    try { ir.custom.put((String) cc.get("key"), CollabPlanColumnsService.coerce(cc, raw, null)); }
                    catch (BusinessException ex) { ir.errors.add(ex.getMessage()); }
                }
                if (CollabPlanItem.KIND_PHASE.equals(ir.kind)) phasesSoFar.add(ir.title.toLowerCase());
                rows.add(ir);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) throw be;
            throw bad("COLLAB_IMPORT_UNREADABLE", "Could not read the spreadsheet — save it as .xlsx and try again");
        }
        if (rows.size() > 2000) throw bad("COLLAB_IMPORT_TOO_BIG", "At most 2000 rows per import");
        return rows;
    }

    private static String cell(Row row, Integer idx) {
        if (idx == null) return "";
        Cell c = row.getCell(idx);
        return c == null ? "" : text(c);
    }

    private static String text(Cell c) {
        if (c == null) return "";
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (t) {
            case STRING  -> c.getStringCellValue();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                yield d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default      -> "";
        };
    }

    private static LocalDate dateCell(Row row, Integer idx, ImportRow ir) {
        if (idx == null) return null;
        Cell c = row.getCell(idx);
        if (c == null) return null;
        try {
            CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
            if (t == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                return c.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String s = text(c).trim();
            if (s.isEmpty()) return null;
            for (String p : List.of("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "dd MMM yyyy", "d MMM yyyy")) {
                try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(p, java.util.Locale.ENGLISH)); }
                catch (RuntimeException ignored) { /* next pattern */ }
            }
            ir.errors.add("Date \"" + s + "\" not understood (use YYYY-MM-DD)");
        } catch (RuntimeException e) {
            ir.errors.add("Unreadable date");
        }
        return null;
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    boolean canEditPlan(Caller c, CollabWorkspace ws) {
        return CollabWorkspace.ACTIVE.equals(ws.getStatus()) && (access.canManage(c, ws) || c.holds(PERM_PLAN_EDIT));
    }

    private void requireEditPlan(Caller c, CollabWorkspace ws) {
        if (!canEditPlan(c, ws)) {
            throw new BusinessException("COLLAB_PLAN_EDIT_DENIED",
                    "You cannot edit this plan", HttpStatus.FORBIDDEN);
        }
    }

    private CollabPlanItem requireVisibleItem(Caller c, CollabWorkspace ws, Long itemId) {
        CollabPlanItem i = itemRepository.findByIdAndWorkspaceIdAndIsDeletedFalse(itemId, ws.getId())
                .orElseThrow(() -> new ResourceNotFoundException("CollabPlanItem", itemId));
        if (i.getProgrammeId() != null && workspaceService.visibleProgrammes(c, ws).stream()
                .noneMatch(p -> p.getId().equals(i.getProgrammeId()))) {
            throw new ResourceNotFoundException("CollabPlanItem", itemId);
        }
        return i;
    }

    /** A programme the caller can see, or null for a workspace-level item. */
    private Long programmeFor(Caller c, CollabWorkspace ws, Long programmeId) {
        if (programmeId == null) return null;
        if (workspaceService.visibleProgrammes(c, ws).stream().noneMatch(p -> p.getId().equals(programmeId))) {
            throw new ResourceNotFoundException("CollabProgramme", programmeId);
        }
        return programmeId;
    }

    private Long parentFor(CollabWorkspace ws, Long selfId, Long parentId) {
        if (parentId == null) return null;
        if (parentId.equals(selfId)) throw bad("COLLAB_BAD_PARENT", "An item cannot be its own parent");
        Map<Long, CollabPlanItem> all = itemRepository.findByWorkspaceIdAndIsDeletedFalse(ws.getId()).stream()
                .collect(Collectors.toMap(CollabPlanItem::getId, x -> x));
        if (!all.containsKey(parentId)) throw new ResourceNotFoundException("CollabPlanItem", parentId);
        Long cur = parentId;
        for (int hops = 0; cur != null && hops < 64; hops++) {
            if (cur.equals(selfId)) throw bad("COLLAB_BAD_PARENT", "That would put the item inside itself");
            CollabPlanItem p = all.get(cur);
            cur = p == null ? null : p.getParentId();
        }
        return parentId;
    }

    private String dependsFor(CollabWorkspace ws, Long selfId, Object raw) {
        List<Long> wanted = new ArrayList<>();
        if (raw instanceof Collection<?> list) {
            for (Object o : list) { Long v = longOrNull(o); if (v != null) wanted.add(v); }
        } else if (raw != null) {
            wanted.addAll(ids(raw.toString()));
        }
        if (wanted.isEmpty()) return null;
        Set<Long> valid = itemRepository.findByWorkspaceIdAndIsDeletedFalse(ws.getId()).stream()
                .map(CollabPlanItem::getId).collect(Collectors.toSet());
        for (Long id : wanted) {
            if (id.equals(selfId)) throw bad("COLLAB_BAD_DEPENDENCY", "An item cannot depend on itself");
            if (!valid.contains(id)) throw bad("COLLAB_BAD_DEPENDENCY", "Unknown item " + id + " in dependencies");
        }
        return join(wanted.stream().distinct().toList());
    }

    /** The owner must be in the workspace, and a firm member must be on the item's programme. */
    private void checkOwner(CollabWorkspace ws, CollabPlanItem i, Long ownerId) {
        CollabWorkspaceMember m = memberRepository.findByWorkspaceIdAndUserId(ws.getId(), ownerId)
                .orElseThrow(() -> bad("COLLAB_OWNER_NOT_MEMBER", "The owner must be a member of the workspace"));
        if (CollabWorkspaceMember.SIDE_FIRM.equals(m.getSide()) && i.getProgrammeId() != null
                && programmeMemberRepository.findByProgrammeIdAndUserId(i.getProgrammeId(), ownerId).isEmpty()) {
            throw bad("COLLAB_OWNER_NOT_ON_PROGRAMME",
                    "This person is from the audit firm and is not on the item's programme, so they could not see it");
        }
    }

    private void applyLink(CollabWorkspace ws, Caller c, CollabPlanItem i, Map<String, Object> body) {
        if (!body.containsKey("linkedEngagementId")) return;
        Long eid = longOrNull(body.get("linkedEngagementId"));
        if (eid == null) {
            i.setLinkedEntityType(null);
            i.setLinkedEntityId(null);
            i.setProgressMode(CollabPlanItem.MODE_MANUAL);
            return;
        }
        boolean ok = linkableEngagements(ws.getId()).stream().anyMatch(m -> eid.equals(m.get("id")));
        if (!ok) throw new ResourceNotFoundException("AuditEngagement", eid);
        i.setLinkedEntityType(CollabPlanItem.LINK_ENGAGEMENT);
        i.setLinkedEntityId(eid);
        i.setProgressMode(CollabPlanItem.MODE_LINKED);
    }

    /** Batch figures for the engagements the given items are linked to. */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> engagementFigures(Set<Long> ids) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] r : (List<Object[]>) em.createNativeQuery("""
                SELECT e.id, e.name, e.engagement_ref, e.status,
                       COUNT(c.id),
                       COALESCE(SUM(CASE WHEN c.auditee_evidence_submitted = 1 THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN c.test_result IS NOT NULL AND c.test_result <> 'NOT_TESTED' THEN 1 ELSE 0 END), 0)
                FROM   audit_engagements e
                LEFT JOIN audit_control_instances c ON c.engagement_id = e.id
                WHERE  e.id IN (:ids)
                GROUP  BY e.id, e.name, e.engagement_ref, e.status
                """).setParameter("ids", ids).getResultList()) {
            long total = ((Number) r[4]).longValue();
            long evid = ((Number) r[5]).longValue();
            long tested = ((Number) r[6]).longValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", CollabPlanItem.LINK_ENGAGEMENT);
            m.put("id", ((Number) r[0]).longValue());
            m.put("name", r[1]);
            m.put("ref", r[2]);
            m.put("status", r[3]);
            m.put("controls", total);
            m.put("evidenceSubmitted", evid);
            m.put("tested", tested);
            m.put("progress", total == 0 ? 0 : (int) Math.round(100.0 * (evid + tested) / (2.0 * total)));
            m.put("done", "CLOSED".equals(String.valueOf(r[3])));
            out.put(((Number) r[0]).longValue(), m);
        }
        return out;
    }

    private List<Map<String, Object>> toMaps(Caller c, List<CollabPlanItem> items, boolean fullEdit) {
        Map<Long, Map<String, Map<String, Object>>> customCols = new HashMap<>();
        Map<Long, User> owners = users(items.stream().map(CollabPlanItem::getOwnerUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, Map<String, Object>> figures = engagementFigures(items.stream()
                .filter(i -> CollabPlanItem.LINK_ENGAGEMENT.equals(i.getLinkedEntityType()) && i.getLinkedEntityId() != null)
                .map(CollabPlanItem::getLinkedEntityId).collect(Collectors.toSet()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollabPlanItem i : items.stream()
                .sorted(Comparator.comparing((CollabPlanItem x) -> x.getSortOrder() == null ? 0 : x.getSortOrder())
                        .thenComparing(x -> x.getPlannedStart() == null ? LocalDate.MAX : x.getPlannedStart())
                        .thenComparing(CollabPlanItem::getId)).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("programmeId", i.getProgrammeId());
            m.put("parentId", i.getParentId());
            m.put("kind", i.getKind());
            m.put("title", i.getTitle());
            m.put("description", i.getDescription());
            m.put("ownerUserId", i.getOwnerUserId());
            User o = owners.get(i.getOwnerUserId());
            m.put("ownerName", i.getOwnerUserId() == null ? null : name(o));
            m.put("ownerEmail", o == null ? null : o.getEmail());
            m.put("plannedStart", i.getPlannedStart());
            m.put("plannedEnd", i.getPlannedEnd());
            m.put("actualEnd", i.getActualEnd());
            m.put("progressMode", i.getProgressMode());
            m.put("dependsOn", ids(i.getDependsOn()));
            m.put("sortOrder", i.getSortOrder());
            m.put("format", columnsService.readFormat(i.getFormatJson()));
            m.put("custom", columnsService.values(i,
                    customCols.computeIfAbsent(i.getWorkspaceId(), columnsService::customColumns)));
            Map<String, Object> link = i.getLinkedEntityId() == null ? null : figures.get(i.getLinkedEntityId());
            m.put("linked", link);
            if (CollabPlanItem.MODE_LINKED.equals(i.getProgressMode()) && link != null) {
                int p = (Integer) link.get("progress");
                boolean done = Boolean.TRUE.equals(link.get("done"));
                m.put("progress", done ? 100 : p);
                m.put("status", done ? CollabPlanItem.DONE : p > 0 ? CollabPlanItem.IN_PROGRESS : CollabPlanItem.NOT_STARTED);
            } else {
                m.put("progress", i.getProgress() == null ? 0 : i.getProgress());
                m.put("status", i.getStatus());
            }
            m.put("canEdit", fullEdit);
            m.put("canUpdateStatus", fullEdit || c.userId().equals(i.getOwnerUserId()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> membersFor(CollabWorkspace ws) {
        List<CollabWorkspaceMember> members = memberRepository.findByWorkspaceId(ws.getId());
        Map<Long, User> users = users(members.stream().map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet()));
        Map<Long, List<Long>> progs = programmeMemberRepository.findByWorkspaceId(ws.getId()).stream()
                .collect(Collectors.groupingBy(CollabProgrammeMember::getUserId,
                        Collectors.mapping(CollabProgrammeMember::getProgrammeId, Collectors.toList())));
        return members.stream().map(m -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("userId", m.getUserId());
            x.put("name", name(users.get(m.getUserId())));
            x.put("email", users.get(m.getUserId()) == null ? null : users.get(m.getUserId()).getEmail());
            x.put("side", m.getSide());
            x.put("programmeIds", progs.getOrDefault(m.getUserId(), List.of()));
            return x;
        }).sorted(Comparator.comparing(x -> String.valueOf(x.get("name")).toLowerCase())).toList();
    }

    private void change(CollabPlanItem i, String field, String oldV, String newV, Caller c, String reason) {
        if (Objects.equals(oldV, newV)) return;
        record(i, field, oldV, newV, c.userId(), reason);
    }

    private void record(CollabPlanItem i, String field, String oldV, String newV, Long by, String reason) {
        CollabPlanChange ch = CollabPlanChange.builder()
                .planItemId(i.getId())
                .workspaceId(i.getWorkspaceId())
                .field(field)
                .oldValue(trim(oldV))
                .newValue(trim(newV))
                .changedBy(by)
                .reason(trim(reason))
                .build();
        ch.setTenantId(i.getTenantId());
        changeRepository.save(ch);
    }

    private void notifyOwner(CollabWorkspace ws, CollabPlanItem i, Long userId, String message) {
        try {
            notificationService.send(userId, "COLLAB_PLAN_ITEM_CHANGED", message, "COLLAB_WORKSPACE", ws.getId());
        } catch (RuntimeException e) {
            log.warn("[COLLAB] Plan notification failed (non-fatal) | item={} | {}", i.getId(), e.getMessage());
        }
    }

    private Map<Long, User> users(Set<Long> ids) {
        Map<Long, User> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private static String name(User u) {
        if (u == null) return "Unknown user";
        String n = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static void checkDates(CollabPlanItem i) {
        if (i.getPlannedStart() != null && i.getPlannedEnd() != null && i.getPlannedEnd().isBefore(i.getPlannedStart())) {
            throw bad("COLLAB_BAD_DATES", "The end date is before the start date");
        }
    }

    private static String kind(Object o) {
        String k = o == null || o.toString().isBlank() ? CollabPlanItem.KIND_TASK : o.toString().trim().toUpperCase();
        if (!KINDS.contains(k)) throw bad("COLLAB_BAD_KIND", "Kind must be PHASE, TASK or MILESTONE");
        return k;
    }

    private static String status(Object o) {
        String s = o == null ? "" : o.toString().trim().toUpperCase();
        if (!STATUSES.contains(s)) throw bad("COLLAB_BAD_STATUS", "Unknown status: " + o);
        return s;
    }

    private static List<Long> ids(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String p : csv.split(",")) {
            try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { /* skip */ }
        }
        return out;
    }

    private static String join(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String range(LocalDate a, LocalDate b) {
        return (a == null ? "?" : a.toString()) + " – " + (b == null ? "?" : b.toString());
    }

    private static String s(Object o) { return o == null ? null : o.toString(); }

    private static String trim(String v) { return v == null ? null : v.length() > 1000 ? v.substring(0, 1000) : v; }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Long longOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return Long.parseLong(o.toString().trim()); }
        catch (NumberFormatException e) { throw bad("COLLAB_BAD_ID", "Not an id: " + o); }
    }

    private static int intOr(Object o, int dflt) {
        if (o == null || o.toString().isBlank()) return dflt;
        try { return (int) Math.round(Double.parseDouble(o.toString())); } catch (NumberFormatException e) { return dflt; }
    }

    private static LocalDate date(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return LocalDate.parse(o.toString().substring(0, Math.min(10, o.toString().length()))); }
        catch (RuntimeException ex) { throw bad("COLLAB_BAD_DATE", "Dates must be YYYY-MM-DD: " + o); }
    }

    private static BusinessException bad(String code, String msg) {
        return new BusinessException(code, msg);
    }
}
