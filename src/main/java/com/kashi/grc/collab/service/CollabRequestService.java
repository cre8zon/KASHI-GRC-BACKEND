package com.kashi.grc.collab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.repository.ActionItemRepository;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.collab.domain.CollabProgramme;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.domain.CollabWorkspaceMember;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collaboration requests — phase 3. Something one side asks the other for in a
 * workspace: a document, an answer, a sign-off. Each request IS an action item
 * (entity_type COLLAB_WORKSPACE, entity_id = the workspace; parent
 * COLLAB_PROGRAMME when it belongs to a programme), so it reaches the
 * assignee's inbox, counts, notifies and can be commented on like any other.
 * Files answering a request are document links on COLLAB_REQUEST + its id.
 *
 * Lifecycle (ActionItem.Status):
 *   OPEN        raised, waiting for the assignee
 *   SUBMITTED   the assignee answered (note and/or files) — the requester reviews
 *   RESOLVED    the requester (or a workspace manager) accepted it
 *   OPEN again  sent back with a note
 *   DISMISSED   withdrawn by the requester or a manager
 *
 * Who:
 *   see       workspace visible + the programme visible (firm: programmes they are on)
 *   raise     collab:request:raise; the assignee must be able to see it
 *   answer    the assignee
 *   accept / send back / withdraw / edit   the requester, or a workspace manager
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabRequestService {

    public static final String PERM_RAISE = "collab:request:raise";

    private final CollabAccessService             access;
    private final CollabWorkspaceService          workspaceService;
    private final CollabWorkspaceMemberRepository memberRepository;
    private final CollabProgrammeMemberRepository programmeMemberRepository;
    private final ActionItemService               actionItemService;
    private final ActionItemRepository            actionItemRepository;
    private final UserRepository                  userRepository;
    private final NotificationService             notificationService;
    private final ObjectMapper                    objectMapper;

    @PersistenceContext
    private EntityManager em;

    // ══════════════════════ READ ═════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> list(Long workspaceId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        Set<Long> progIds = workspaceService.visibleProgrammes(c, ws).stream()
                .map(CollabProgramme::getId).collect(Collectors.toSet());
        List<ActionItem> items = requestsOf(ws).stream()
                .filter(a -> a.getParentEntityId() == null || progIds.contains(a.getParentEntityId()))
                .toList();
        boolean manage = access.canManage(c, ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canRaise", c.holds(PERM_RAISE) && CollabWorkspace.ACTIVE.equals(ws.getStatus()));
        out.put("requests", toMaps(c, items, manage));
        return out;
    }

    /** Open requests assigned to me, and answered ones waiting for my review — for My week. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine() {
        Caller c = access.caller();
        List<CollabWorkspace> workspaces = access.visibleWorkspaces(c);
        List<Map<String, Object>> assigned = new ArrayList<>(), toReview = new ArrayList<>();
        for (CollabWorkspace ws : workspaces) {
            Set<Long> progIds = workspaceService.visibleProgrammes(c, ws).stream()
                    .map(CollabProgramme::getId).collect(Collectors.toSet());
            List<ActionItem> items = requestsOf(ws).stream()
                    .filter(a -> a.getParentEntityId() == null || progIds.contains(a.getParentEntityId())).toList();
            boolean manage = access.canManage(c, ws);
            for (Map<String, Object> m : toMaps(c, items.stream()
                    .filter(a -> c.userId().equals(a.getAssignedTo()))
                    .filter(a -> a.getStatus() == ActionItem.Status.OPEN || a.getStatus() == ActionItem.Status.IN_PROGRESS)
                    .toList(), manage)) {
                m.put("workspaceName", ws.getName()); assigned.add(m);
            }
            for (Map<String, Object> m : toMaps(c, items.stream()
                    .filter(a -> c.userId().equals(a.getCreatedBy()))
                    .filter(a -> a.getStatus() == ActionItem.Status.SUBMITTED).toList(), manage)) {
                m.put("workspaceName", ws.getName()); toReview.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assigned", assigned);
        out.put("toReview", toReview);
        return out;
    }

    // ══════════════════════ WRITE ════════════════════════════════════════════

    @Transactional
    public Map<String, Object> raise(Long workspaceId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabWorkspace ws = requireRaise(c, workspaceId);
        ActionItem a = create(c, ws, body);
        if (!c.userId().equals(a.getAssignedTo())) {
            notify(a.getAssignedTo(), ws, me(c) + " asked you: \"" + a.getTitle() + "\""
                    + (a.getDueAt() != null ? " — due " + a.getDueAt().toLocalDate() : "") + " (" + ws.getName() + ")");
        }
        return toMaps(c, List.of(a), access.canManage(c, ws)).get(0);
    }

    /**
     * Many requests at once — the information request list an auditor sends
     * today as a spreadsheet. Each row: title, description?, assigneeUserId or
     * assigneeEmail, dueDate?, programmeId?. All rows or none.
     */
    @Transactional
    public Map<String, Object> raiseMany(Long workspaceId, List<Map<String, Object>> rows) {
        Caller c = access.caller();
        CollabWorkspace ws = requireRaise(c, workspaceId);
        if (rows == null || rows.isEmpty()) throw bad("COLLAB_NO_ROWS", "Nothing to raise");
        if (rows.size() > 500) throw bad("COLLAB_TOO_MANY", "At most 500 requests at once");
        Map<String, Long> byEmail = new HashMap<>();
        Set<Long> memberIds = memberRepository.findByWorkspaceId(ws.getId()).stream()
                .map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet());
        userRepository.findAllById(memberIds).forEach(u -> { if (u.getEmail() != null) byEmail.put(u.getEmail().toLowerCase(), u.getId()); });

        List<String> problems = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> r : rows) {
            n++;
            if (r.get("assigneeUserId") == null && r.get("assigneeEmail") != null) {
                Long id = byEmail.get(r.get("assigneeEmail").toString().trim().toLowerCase());
                if (id == null) problems.add("Row " + n + ": " + r.get("assigneeEmail") + " is not in this workspace");
                else r.put("assigneeUserId", id);
            }
            if (str(r.get("title")) == null || str(r.get("title")).isBlank()) problems.add("Row " + n + ": no title");
        }
        if (!problems.isEmpty()) throw bad("COLLAB_BULK_PROBLEMS", String.join("; ", problems.subList(0, Math.min(10, problems.size()))));
        List<ActionItem> created = new ArrayList<>();
        for (Map<String, Object> r : rows) created.add(create(c, ws, r));
        // One notification per person, not one per row.
        created.stream().filter(a -> a.getAssignedTo() != null && !a.getAssignedTo().equals(c.userId()))
                .collect(Collectors.groupingBy(ActionItem::getAssignedTo, Collectors.counting()))
                .forEach((uid, cnt) -> notify(uid, ws, me(c) + " sent you " + cnt + (cnt == 1 ? " request" : " requests") + " in " + ws.getName()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created.size());
        return out;
    }

    @Transactional
    public Map<String, Object> update(Long workspaceId, Long requestId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        ActionItem a = requireVisibleRequest(c, ws, requestId);
        requireRequesterOrManager(c, ws, a);
        if (body.containsKey("title")) a.setTitle(required(body.get("title"), "A request needs a title"));
        if (body.containsKey("description")) a.setDescription(str(body.get("description")));
        if (body.containsKey("dueDate")) a.setDueAt(due(body.get("dueDate")));
        if (body.containsKey("assigneeUserId")) {
            Long to = longOrNull(body.get("assigneeUserId"));
            checkAssignee(ws, to, a.getParentEntityId());
            if (!Objects.equals(to, a.getAssignedTo())) {
                a.setAssignedTo(to);
                notify(to, ws, "Request for you: \"" + a.getTitle() + "\"");
            }
        }
        actionItemRepository.save(a);
        return toMaps(c, List.of(a), access.canManage(c, ws)).get(0);
    }

    /** The assignee answers: a note (files go on COLLAB_REQUEST). */
    @Transactional
    public Map<String, Object> answer(Long workspaceId, Long requestId, String note) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        ActionItem a = requireVisibleRequest(c, ws, requestId);
        if (!c.userId().equals(a.getAssignedTo())) {
            throw new BusinessException("COLLAB_NOT_ASSIGNEE", "Only the person the request is for can answer it", HttpStatus.FORBIDDEN);
        }
        if (a.getStatus() == ActionItem.Status.RESOLVED || a.getStatus() == ActionItem.Status.DISMISSED) {
            throw bad("COLLAB_REQUEST_CLOSED", "This request is closed");
        }
        a.setStatus(ActionItem.Status.SUBMITTED);
        a.setResolutionNote(note);
        actionItemRepository.save(a);
        notify(a.getCreatedBy(), ws, "Answered: \"" + a.getTitle() + "\" — review it");
        return toMaps(c, List.of(a), access.canManage(c, ws)).get(0);
    }

    /** accept | reopen | withdraw — by the requester or a workspace manager. */
    @Transactional
    public Map<String, Object> decide(Long workspaceId, Long requestId, String decision, String note) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        ActionItem a = requireVisibleRequest(c, ws, requestId);
        requireRequesterOrManager(c, ws, a);
        switch (decision == null ? "" : decision) {
            case "accept" -> {
                a.setStatus(ActionItem.Status.RESOLVED);
                a.setResolvedAt(LocalDateTime.now());
                a.setResolvedBy(c.userId());
                notify(a.getAssignedTo(), ws, "Accepted: \"" + a.getTitle() + "\"");
            }
            case "reopen" -> {
                a.setStatus(ActionItem.Status.OPEN);
                a.setResolvedAt(null);
                a.setResolvedBy(null);
                if (note != null && !note.isBlank()) {
                    a.setDescription((a.getDescription() == null ? "" : a.getDescription() + "\n\n")
                            + "Sent back: " + note);
                }
                notify(a.getAssignedTo(), ws, "Sent back: \"" + a.getTitle() + "\"" + (note != null && !note.isBlank() ? " — " + note : ""));
            }
            case "withdraw" -> {
                a.setStatus(ActionItem.Status.DISMISSED);
                a.setResolvedAt(LocalDateTime.now());
                a.setResolvedBy(c.userId());
                a.setResolutionNote(note != null && !note.isBlank() ? note : "Withdrawn");
            }
            default -> throw bad("COLLAB_BAD_DECISION", "Decision must be accept, reopen or withdraw");
        }
        actionItemRepository.save(a);
        return toMaps(c, List.of(a), access.canManage(c, ws)).get(0);
    }

    // ══════════════════════ HELPERS (also used by the visibility SPIs) ═══════

    /** The workspace a request belongs to, or null. */
    Long workspaceOf(Long requestId) {
        return actionItemRepository.findById(requestId)
                .filter(a -> a.getEntityType() == ActionItem.EntityType.COLLAB_WORKSPACE)
                .map(ActionItem::getEntityId).orElse(null);
    }

    boolean canSeeRequest(Caller c, Long requestId) {
        ActionItem a = actionItemRepository.findById(requestId).orElse(null);
        if (a == null || a.getEntityType() != ActionItem.EntityType.COLLAB_WORKSPACE) return false;
        try {
            CollabWorkspace ws = access.requireVisible(c, a.getEntityId());
            return a.getParentEntityId() == null || workspaceService.visibleProgrammes(c, ws).stream()
                    .anyMatch(p -> p.getId().equals(a.getParentEntityId()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean canWorkRequest(Caller c, Long requestId) {
        ActionItem a = actionItemRepository.findById(requestId).orElse(null);
        if (a == null || !canSeeRequest(c, requestId)) return false;
        if (c.userId().equals(a.getAssignedTo()) || c.userId().equals(a.getCreatedBy())) return true;
        return access.canManage(c, access.requireVisible(c, a.getEntityId()));
    }

    private ActionItem create(Caller c, CollabWorkspace ws, Map<String, Object> body) {
        Long progId = longOrNull(body.get("programmeId"));
        if (progId != null && workspaceService.visibleProgrammes(c, ws).stream().noneMatch(p -> p.getId().equals(progId))) {
            throw new ResourceNotFoundException("CollabProgramme", progId);
        }
        Long to = longOrNull(body.get("assigneeUserId"));
        if (to == null) throw bad("COLLAB_ASSIGNEE_REQUIRED", "Choose who the request is for");
        checkAssignee(ws, to, progId);

        ActionItemRequest req = new ActionItemRequest();
        req.setAssignedTo(to);
        req.setSourceType(ActionItem.SourceType.SYSTEM);
        req.setSourceId(ws.getId());
        req.setEntityType(ActionItem.EntityType.COLLAB_WORKSPACE);
        req.setEntityId(ws.getId());
        if (progId != null) {
            req.setParentEntityType(ActionItem.EntityType.COLLAB_PROGRAMME);
            req.setParentEntityId(progId);
        }
        req.setTitle(required(body.get("title"), "A request needs a title"));
        req.setDescription(str(body.get("description")));
        LocalDateTime d = due(body.get("dueDate"));
        if (d != null) req.setDueAt(d.toString());
        req.setNavContext(navContext("/collaboration/workspaces/" + ws.getId() + "?tab=requests"));
        Long id = actionItemService.create(req, c.userId(), c.tenantId()).getId();
        return actionItemRepository.findById(id).orElseThrow();
    }

    private CollabWorkspace requireRaise(Caller c, Long workspaceId) {
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        access.requirePermission(c, PERM_RAISE);
        if (!CollabWorkspace.ACTIVE.equals(ws.getStatus())) throw bad("COLLAB_ARCHIVED", "This workspace is archived");
        return ws;
    }

    /** In the workspace, and — for a firm member on a programme request — on that programme. */
    private void checkAssignee(CollabWorkspace ws, Long userId, Long programmeId) {
        if (userId == null) throw bad("COLLAB_ASSIGNEE_REQUIRED", "Choose who the request is for");
        CollabWorkspaceMember m = memberRepository.findByWorkspaceIdAndUserId(ws.getId(), userId)
                .orElseThrow(() -> bad("COLLAB_ASSIGNEE_NOT_MEMBER", "Requests go to members of the workspace"));
        if (CollabWorkspaceMember.SIDE_FIRM.equals(m.getSide()) && programmeId != null
                && programmeMemberRepository.findByProgrammeIdAndUserId(programmeId, userId).isEmpty()) {
            throw bad("COLLAB_ASSIGNEE_NOT_ON_PROGRAMME", "This person is not on the request's programme");
        }
    }

    private void requireRequesterOrManager(Caller c, CollabWorkspace ws, ActionItem a) {
        if (!c.userId().equals(a.getCreatedBy()) && !access.canManage(c, ws)) {
            throw new BusinessException("COLLAB_NOT_REQUESTER",
                    "Only whoever raised the request, or a workspace owner, can do this", HttpStatus.FORBIDDEN);
        }
    }

    private ActionItem requireVisibleRequest(Caller c, CollabWorkspace ws, Long requestId) {
        ActionItem a = actionItemRepository.findById(requestId)
                .filter(x -> x.getEntityType() == ActionItem.EntityType.COLLAB_WORKSPACE
                        && ws.getId().equals(x.getEntityId()) && ws.getTenantId().equals(x.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("CollabRequest", requestId));
        if (a.getParentEntityId() != null && workspaceService.visibleProgrammes(c, ws).stream()
                .noneMatch(p -> p.getId().equals(a.getParentEntityId()))) {
            throw new ResourceNotFoundException("CollabRequest", requestId);
        }
        return a;
    }

    private List<ActionItem> requestsOf(CollabWorkspace ws) {
        return em.createQuery("""
                SELECT a FROM ActionItem a
                WHERE a.tenantId = :t AND a.entityType = :et AND a.entityId = :id
                ORDER BY a.id DESC
                """, ActionItem.class)
                .setParameter("t", ws.getTenantId())
                .setParameter("et", ActionItem.EntityType.COLLAB_WORKSPACE)
                .setParameter("id", ws.getId())
                .getResultList();
    }

    private List<Map<String, Object>> toMaps(Caller c, List<ActionItem> items, boolean manage) {
        Set<Long> ids = new java.util.HashSet<>();
        items.forEach(a -> { if (a.getAssignedTo() != null) ids.add(a.getAssignedTo()); if (a.getCreatedBy() != null) ids.add(a.getCreatedBy()); });
        Map<Long, User> users = new HashMap<>();
        if (!ids.isEmpty()) userRepository.findAllById(ids).forEach(u -> users.put(u.getId(), u));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ActionItem a : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("workspaceId", a.getEntityId());
            m.put("programmeId", a.getParentEntityId());
            m.put("title", a.getTitle());
            m.put("description", a.getDescription());
            m.put("status", a.getStatus() == null ? null : a.getStatus().name());
            m.put("dueAt", a.getDueAt());
            m.put("overdue", a.getDueAt() != null && a.getDueAt().isBefore(LocalDateTime.now())
                    && a.getStatus() != ActionItem.Status.RESOLVED && a.getStatus() != ActionItem.Status.DISMISSED);
            m.put("assigneeUserId", a.getAssignedTo());
            m.put("assigneeName", a.getAssignedTo() == null ? null : name(users.get(a.getAssignedTo())));
            m.put("requestedBy", a.getCreatedBy());
            m.put("requestedByName", name(users.get(a.getCreatedBy())));
            m.put("answer", a.getResolutionNote());
            m.put("createdAt", a.getCreatedAt());
            boolean requester = c.userId().equals(a.getCreatedBy());
            m.put("canAnswer", c.userId().equals(a.getAssignedTo())
                    && a.getStatus() != ActionItem.Status.RESOLVED && a.getStatus() != ActionItem.Status.DISMISSED);
            m.put("canDecide", requester || manage);
            out.add(m);
        }
        return out;
    }

    private String me(Caller c) {
        return name(userRepository.findById(c.userId()).orElse(null));
    }

    private void notify(Long userId, CollabWorkspace ws, String message) {
        if (userId == null) return;
        try {
            notificationService.send(userId, "COLLAB_REQUEST", message, "COLLAB_WORKSPACE", ws.getId());
        } catch (RuntimeException e) {
            log.warn("[COLLAB] Request notification failed (non-fatal) | {}", e.getMessage());
        }
    }

    private String navContext(String route) {
        try { return objectMapper.writeValueAsString(Map.of("route", route)); }
        catch (Exception e) { return null; }
    }

    private static String name(User u) {
        if (u == null) return "Unknown user";
        String n = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static LocalDateTime due(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return java.time.LocalDate.parse(o.toString().substring(0, Math.min(10, o.toString().length()))).atTime(23, 59); }
        catch (RuntimeException e) { throw bad("COLLAB_BAD_DATE", "Due dates must be YYYY-MM-DD: " + o); }
    }

    private static String required(Object o, String msg) {
        String s = str(o);
        if (s == null || s.isBlank()) throw bad("COLLAB_REQUIRED", msg);
        return s.trim();
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Long longOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return Long.parseLong(o.toString().trim()); }
        catch (NumberFormatException e) { throw bad("COLLAB_BAD_ID", "Not an id: " + o); }
    }

    private static BusinessException bad(String code, String msg) { return new BusinessException(code, msg); }
}