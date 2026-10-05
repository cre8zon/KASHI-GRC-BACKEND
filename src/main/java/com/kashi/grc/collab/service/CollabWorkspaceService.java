package com.kashi.grc.collab.service;

import com.kashi.grc.collab.domain.CollabProgramme;
import com.kashi.grc.collab.domain.CollabProgrammeMember;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.domain.CollabWorkspaceMember;
import com.kashi.grc.collab.repository.CollabProgrammeMemberRepository;
import com.kashi.grc.collab.repository.CollabProgrammeRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceMemberRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceRepository;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.domain.FirmAccessGrant;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserTenantMembership;
import com.kashi.grc.usermanagement.repository.FirmAccessGrantRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
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
 * Collaboration workspaces — phase 1: the workspace, its members and its
 * programmes. Every read and write goes through CollabAccessService first.
 *
 * Programme visibility: the client's people see every programme of a workspace
 * they can see; the firm's people see only programmes they are members of.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabWorkspaceService {

    private final CollabAccessService             access;
    private final CollabWorkspaceRepository       workspaceRepository;
    private final CollabWorkspaceMemberRepository memberRepository;
    private final CollabProgrammeRepository       programmeRepository;
    private final CollabProgrammeMemberRepository programmeMemberRepository;
    private final FirmAccessGrantRepository       grantRepository;
    private final UserTenantMembershipRepository  membershipRepository;
    private final UserRepository                  userRepository;
    private final com.kashi.grc.tenant.repository.TenantRepository tenantRepository;
    private final NotificationService             notificationService;

    @PersistenceContext
    private EntityManager em;

    // ══════════════════════ WORKSPACES ═══════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Caller c = access.caller();
        List<CollabWorkspace> list = access.visibleWorkspaces(c);
        Map<Long, String> tenantNames = tenantNames(list.stream()
                .map(CollabWorkspace::getFirmTenantId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollabWorkspace ws : list) {
            Map<String, Object> m = summary(ws, tenantNames);
            List<CollabWorkspaceMember> members = memberRepository.findByWorkspaceId(ws.getId());
            m.put("memberCount", members.size());
            m.put("programmeCount", visibleProgrammes(c, ws).size());
            m.put("myRole", members.stream().filter(x -> x.getUserId().equals(c.userId()))
                    .map(CollabWorkspaceMember::getWorkspaceRole).findFirst().orElse(null));
            out.add(m);
        }
        return out;
    }

    /** Firms the caller can open a workspace with: admitted firms (client side) or their own firm. */
    @Transactional(readOnly = true)
    public Map<String, Object> creationOptions() {
        Caller c = access.caller();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canCreate", c.holds(CollabAccessService.PERM_CREATE));
        out.put("side", c.side());
        List<Long> firmIds = new ArrayList<>();
        if (c.guest()) {
            if (c.firmTenantId() != null && access.activeGrant(c.tenantId(), c.firmTenantId()).isPresent()) {
                firmIds.add(c.firmTenantId());
            }
        } else {
            grantRepository.findByClientTenantId(c.tenantId()).stream()
                    .filter(g -> access.activeGrant(g.getClientTenantId(), g.getFirmTenantId()).isPresent())
                    .forEach(g -> firmIds.add(g.getFirmTenantId()));
        }
        Map<Long, String> names = tenantNames(new HashSet<>(firmIds));
        List<Map<String, Object>> firms = new ArrayList<>();
        for (Long f : firmIds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("firmTenantId", f);
            m.put("name", names.getOrDefault(f, "Firm " + f));
            m.put("hasWorkspace", workspaceRepository
                    .existsByTenantIdAndFirmTenantIdAndStatusAndIsDeletedFalse(c.tenantId(), f, CollabWorkspace.ACTIVE));
            firms.add(m);
        }
        out.put("firms", firms);
        // Internal workspaces (no firm) are the client's own; a guest cannot open one.
        out.put("canCreateInternal", !c.guest() && c.holds(CollabAccessService.PERM_CREATE));
        return out;
    }

    @Transactional
    public Map<String, Object> create(String name, String description, Long firmTenantId) {
        Caller c = access.caller();
        access.requirePermission(c, CollabAccessService.PERM_CREATE);
        if (name == null || name.isBlank()) {
            throw new BusinessException("COLLAB_NAME_REQUIRED", "A workspace needs a name");
        }

        Long firm = c.guest() ? c.firmTenantId() : firmTenantId;
        if (c.guest() && firmTenantId != null && !firmTenantId.equals(c.firmTenantId())) {
            throw new BusinessException("COLLAB_WRONG_FIRM",
                    "You can only open a workspace for your own firm", HttpStatus.FORBIDDEN);
        }
        if (c.guest() && firm == null) {
            throw new BusinessException("COLLAB_NO_FIRM", "Your membership here names no audit firm",
                    HttpStatus.FORBIDDEN);
        }

        Long grantId = null;
        if (firm != null) {
            FirmAccessGrant grant = access.activeGrant(c.tenantId(), firm).orElseThrow(() ->
                    new BusinessException("COLLAB_FIRM_NOT_ADMITTED",
                            "This audit firm has not been granted access to the organisation",
                            HttpStatus.FORBIDDEN));
            grantId = grant.getId();
            if (workspaceRepository.existsByTenantIdAndFirmTenantIdAndStatusAndIsDeletedFalse(
                    c.tenantId(), firm, CollabWorkspace.ACTIVE)) {
                throw new BusinessException("COLLAB_WORKSPACE_EXISTS",
                        "A workspace with this firm already exists — add a programme to it instead",
                        HttpStatus.CONFLICT);
            }
        }

        CollabWorkspace ws = CollabWorkspace.builder()
                .name(name.trim())
                .description(description)
                .firmTenantId(firm)
                .firmGrantId(grantId)
                .status(CollabWorkspace.ACTIVE)
                .build();
        ws.setTenantId(c.tenantId());
        ws.setCreatedBy(c.userId());
        workspaceRepository.save(ws);

        CollabWorkspaceMember owner = CollabWorkspaceMember.builder()
                .workspaceId(ws.getId())
                .userId(c.userId())
                .side(c.side())
                .workspaceRole(CollabWorkspaceMember.ROLE_OWNER)
                .addedBy(c.userId())
                .build();
        owner.setTenantId(c.tenantId());
        memberRepository.save(owner);

        log.info("[COLLAB] Workspace created | id={} | tenantId={} | firmTenantId={} | by={}",
                ws.getId(), c.tenantId(), firm, c.userId());
        return overview(ws.getId());
    }

    @Transactional
    public Map<String, Object> update(Long workspaceId, Map<String, Object> fields) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        if (fields.containsKey("name")) {
            String n = str(fields.get("name"));
            if (n == null || n.isBlank()) throw new BusinessException("COLLAB_NAME_REQUIRED", "A workspace needs a name");
            ws.setName(n.trim());
        }
        if (fields.containsKey("description")) ws.setDescription(str(fields.get("description")));
        if (fields.containsKey("status")) {
            String s = str(fields.get("status"));
            if (!CollabWorkspace.ACTIVE.equals(s) && !CollabWorkspace.ARCHIVED.equals(s)) {
                throw new BusinessException("COLLAB_BAD_STATUS", "Status must be ACTIVE or ARCHIVED");
            }
            if (CollabWorkspace.ACTIVE.equals(s) && !CollabWorkspace.ACTIVE.equals(ws.getStatus())
                    && ws.getFirmTenantId() != null
                    && workspaceRepository.existsByTenantIdAndFirmTenantIdAndStatusAndIsDeletedFalse(
                    ws.getTenantId(), ws.getFirmTenantId(), CollabWorkspace.ACTIVE)) {
                throw new BusinessException("COLLAB_WORKSPACE_EXISTS",
                        "Another active workspace with this firm exists", HttpStatus.CONFLICT);
            }
            ws.setStatus(s);
        }
        ws.setUpdatedBy(c.userId());
        workspaceRepository.save(ws);
        return overview(workspaceId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Long workspaceId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        Map<Long, String> tenantNames = tenantNames(ws.getFirmTenantId() == null ? Set.of() : Set.of(ws.getFirmTenantId()));

        Map<String, Object> out = summary(ws, tenantNames);
        out.put("clientName", tenantNames(Set.of(ws.getTenantId())).get(ws.getTenantId()));
        boolean manage = access.canManage(c, ws);
        out.put("canManage", manage);
        out.put("mySide", c.side());

        List<CollabWorkspaceMember> members = memberRepository.findByWorkspaceId(ws.getId());
        Map<Long, User> users = users(members.stream().map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet()));
        out.put("myRole", members.stream().filter(x -> x.getUserId().equals(c.userId()))
                .map(CollabWorkspaceMember::getWorkspaceRole).findFirst().orElse(null));
        out.put("members", members.stream()
                .sorted(Comparator.comparing(CollabWorkspaceMember::getSide)
                        .thenComparing(m -> name(users.get(m.getUserId())).toLowerCase()))
                .map(m -> memberMap(m, users.get(m.getUserId()))).toList());

        List<CollabProgramme> programmes = visibleProgrammes(c, ws);
        Map<Long, List<Long>> progMembers = programmeMemberRepository.findByWorkspaceId(ws.getId()).stream()
                .collect(Collectors.groupingBy(CollabProgrammeMember::getProgrammeId,
                        Collectors.mapping(CollabProgrammeMember::getUserId, Collectors.toList())));
        out.put("programmes", programmes.stream().map(p -> {
            Map<String, Object> m = programmeMap(p);
            m.put("memberUserIds", progMembers.getOrDefault(p.getId(), List.of()));
            return m;
        }).toList());
        return out;
    }

    // ══════════════════════ MEMBERS ══════════════════════════════════════════

    /**
     * People who can be added: the client's own staff (usable HOME membership)
     * and, for a firm workspace, that firm's people (usable GUEST membership
     * under that firm). Never another firm's auditors. A guest caller sees only
     * the client staff their guest scope already lets them see.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> eligibleMembers(Long workspaceId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        List<Object[]> rows = em.createNativeQuery("""
                SELECT u.id, u.first_name, u.last_name, u.email, m.membership_type, m.firm_tenant_id
                FROM   user_tenant_memberships m
                JOIN   users u ON u.id = m.user_id
                WHERE  m.tenant_id = :tenantId
                  AND  m.status = 'ACTIVE'
                  AND  (m.access_expires_at IS NULL OR m.access_expires_at > NOW())
                  AND  u.is_deleted = 0
                """).setParameter("tenantId", ws.getTenantId()).getResultList();

        Set<Long> already = memberRepository.findByWorkspaceId(ws.getId()).stream()
                .map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet());
        Set<Long> guestVisible = c.guest() ? com.kashi.grc.common.config.multitenancy.AccessScope.userIds() : null;

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Long userId = r[0] instanceof Number n ? n.longValue() : null;
            if (userId == null || already.contains(userId)) continue;
            boolean guest = "GUEST".equalsIgnoreCase(String.valueOf(r[4]));
            Long firm = r[5] instanceof Number n ? n.longValue() : null;
            String side;
            if (!guest) {
                side = CollabWorkspaceMember.SIDE_CLIENT;
                if (guestVisible != null && !guestVisible.contains(userId)) continue;
            } else {
                if (ws.getFirmTenantId() == null || !ws.getFirmTenantId().equals(firm)) continue;
                side = CollabWorkspaceMember.SIDE_FIRM;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", userId);
            m.put("name", joinName((String) r[1], (String) r[2], (String) r[3]));
            m.put("email", r[3]);
            m.put("side", side);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("side")))
                .thenComparing(m -> String.valueOf(m.get("name")).toLowerCase()));
        return out;
    }

    @Transactional
    public Map<String, Object> addMember(Long workspaceId, Long userId, String role) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        if (userId == null) throw new BusinessException("COLLAB_USER_REQUIRED", "Choose a person to add");
        if (memberRepository.findByWorkspaceIdAndUserId(ws.getId(), userId).isPresent()) {
            throw new BusinessException("COLLAB_ALREADY_MEMBER", "This person is already in the workspace",
                    HttpStatus.CONFLICT);
        }
        String side = sideFor(ws, userId);
        if (c.guest() && CollabWorkspaceMember.SIDE_CLIENT.equals(side)) {
            Set<Long> visible = com.kashi.grc.common.config.multitenancy.AccessScope.userIds();
            if (visible != null && !visible.contains(userId)) {
                throw new ResourceNotFoundException("User", userId);
            }
        }
        CollabWorkspaceMember m = CollabWorkspaceMember.builder()
                .workspaceId(ws.getId())
                .userId(userId)
                .side(side)
                .workspaceRole(normaliseRole(role))
                .addedBy(c.userId())
                .build();
        m.setTenantId(ws.getTenantId());
        memberRepository.save(m);
        notifyAdded(c, userId, ws, "added you to the workspace \"" + ws.getName() + "\"");
        return memberMap(m, users(Set.of(userId)).get(userId));
    }

    @Transactional
    public Map<String, Object> changeRole(Long workspaceId, Long memberId, String role) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        CollabWorkspaceMember m = memberRepository.findById(memberId)
                .filter(x -> x.getWorkspaceId().equals(ws.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("CollabWorkspaceMember", memberId));
        String newRole = normaliseRole(role);
        if (CollabWorkspaceMember.ROLE_OWNER.equals(m.getWorkspaceRole())
                && !CollabWorkspaceMember.ROLE_OWNER.equals(newRole)) {
            requireAnotherOwner(ws.getId());
        }
        m.setWorkspaceRole(newRole);
        memberRepository.save(m);
        return memberMap(m, users(Set.of(m.getUserId())).get(m.getUserId()));
    }

    @Transactional
    public void removeMember(Long workspaceId, Long memberId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        CollabWorkspaceMember m = memberRepository.findById(memberId)
                .filter(x -> x.getWorkspaceId().equals(ws.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("CollabWorkspaceMember", memberId));
        if (CollabWorkspaceMember.ROLE_OWNER.equals(m.getWorkspaceRole())) requireAnotherOwner(ws.getId());
        programmeMemberRepository.deleteByWorkspaceIdAndUserId(ws.getId(), m.getUserId());
        memberRepository.delete(m);
    }

    // ══════════════════════ PROGRAMMES ═══════════════════════════════════════

    @Transactional
    public Map<String, Object> createProgramme(Long workspaceId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException("COLLAB_NAME_REQUIRED", "A programme needs a name");
        CollabProgramme p = CollabProgramme.builder()
                .workspaceId(ws.getId())
                .name(name.trim())
                .description(str(body.get("description")))
                .plannedStart(date(body.get("plannedStart")))
                .plannedEnd(date(body.get("plannedEnd")))
                .status(CollabProgramme.PLANNED)
                .build();
        p.setTenantId(ws.getTenantId());
        p.setCreatedBy(c.userId());
        checkDates(p);
        programmeRepository.save(p);
        // The creator is on it, so a firm-side creator can see what they made.
        addProgrammeMemberInternal(ws, p, c.userId(), c.userId());
        return programmeMap(p);
    }

    @Transactional
    public Map<String, Object> updateProgramme(Long workspaceId, Long programmeId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        CollabProgramme p = requireVisibleProgramme(c, ws, programmeId);
        if (body.containsKey("name")) {
            String n = str(body.get("name"));
            if (n == null || n.isBlank()) throw new BusinessException("COLLAB_NAME_REQUIRED", "A programme needs a name");
            p.setName(n.trim());
        }
        if (body.containsKey("description")) p.setDescription(str(body.get("description")));
        if (body.containsKey("plannedStart")) p.setPlannedStart(date(body.get("plannedStart")));
        if (body.containsKey("plannedEnd"))   p.setPlannedEnd(date(body.get("plannedEnd")));
        if (body.containsKey("status")) {
            String s = str(body.get("status"));
            if (!List.of(CollabProgramme.PLANNED, CollabProgramme.ACTIVE, CollabProgramme.COMPLETED,
                    CollabProgramme.ARCHIVED).contains(s)) {
                throw new BusinessException("COLLAB_BAD_STATUS", "Unknown programme status: " + s);
            }
            p.setStatus(s);
        }
        checkDates(p);
        p.setUpdatedBy(c.userId());
        programmeRepository.save(p);
        return programmeMap(p);
    }

    /**
     * Delete a programme (workspace owners). Nothing in it is lost: its plan
     * items, meetings and requests move to Workspace-wide — which means
     * everyone in the workspace, the firm's people included, can now see them.
     * Each moved plan item gets a history entry. The programme itself is
     * soft-deleted, and its member list removed.
     */
    @Transactional
    public Map<String, Object> deleteProgramme(Long workspaceId, Long programmeId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        CollabProgramme p = requireVisibleProgramme(c, ws, programmeId);

        List<com.kashi.grc.collab.domain.CollabPlanItem> items = em.createQuery(
                        "SELECT i FROM CollabPlanItem i WHERE i.workspaceId = :ws AND i.programmeId = :p AND i.isDeleted = false",
                        com.kashi.grc.collab.domain.CollabPlanItem.class)
                .setParameter("ws", ws.getId()).setParameter("p", p.getId()).getResultList();
        for (com.kashi.grc.collab.domain.CollabPlanItem i : items) {
            i.setProgrammeId(null);
            i.setUpdatedBy(c.userId());
            em.merge(i);
            com.kashi.grc.collab.domain.CollabPlanChange ch = com.kashi.grc.collab.domain.CollabPlanChange.builder()
                    .planItemId(i.getId()).workspaceId(ws.getId()).field("programme")
                    .oldValue(String.valueOf(p.getId())).newValue(null).changedBy(c.userId())
                    .reason("Programme \"" + p.getName() + "\" deleted — moved to Workspace-wide").build();
            ch.setTenantId(ws.getTenantId());
            em.persist(ch);
        }
        int meetings = em.createQuery("UPDATE CollabMeeting m SET m.programmeId = NULL WHERE m.workspaceId = :ws AND m.programmeId = :p")
                .setParameter("ws", ws.getId()).setParameter("p", p.getId()).executeUpdate();
        int requests = em.createQuery("UPDATE ActionItem a SET a.parentEntityType = NULL, a.parentEntityId = NULL "
                        + "WHERE a.tenantId = :t AND a.entityType = :wsType AND a.entityId = :ws "
                        + "AND a.parentEntityType = :progType AND a.parentEntityId = :p")
                .setParameter("t", ws.getTenantId())
                .setParameter("wsType", com.kashi.grc.actionitem.domain.ActionItem.EntityType.COLLAB_WORKSPACE)
                .setParameter("ws", ws.getId())
                .setParameter("progType", com.kashi.grc.actionitem.domain.ActionItem.EntityType.COLLAB_PROGRAMME)
                .setParameter("p", p.getId())
                .executeUpdate();
        programmeMemberRepository.findByProgrammeId(p.getId()).forEach(programmeMemberRepository::delete);
        p.setDeleted(true);
        p.setUpdatedBy(c.userId());
        programmeRepository.save(p);
        log.info("[COLLAB] Programme deleted | id={} | ws={} | items={} | meetings={} | requests={} | by={}",
                p.getId(), ws.getId(), items.size(), meetings, requests, c.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("movedPlanItems", items.size());
        out.put("movedMeetings", meetings);
        out.put("movedRequests", requests);
        return out;
    }

    @Transactional
    public void addProgrammeMember(Long workspaceId, Long programmeId, Long userId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        CollabProgramme p = requireVisibleProgramme(c, ws, programmeId);
        if (memberRepository.findByWorkspaceIdAndUserId(ws.getId(), userId).isEmpty()) {
            throw new BusinessException("COLLAB_NOT_WORKSPACE_MEMBER",
                    "Add this person to the workspace first");
        }
        boolean already = programmeMemberRepository.findByProgrammeIdAndUserId(p.getId(), userId).isPresent();
        addProgrammeMemberInternal(ws, p, userId, c.userId());
        if (!already) {
            notifyAdded(c, userId, ws, "added you to the programme \"" + p.getName() + "\" in " + ws.getName());
        }
    }

    /** "Priya added you to …" — opens the workspace. Never fails the change itself. */
    private void notifyAdded(Caller c, Long userId, CollabWorkspace ws, String what) {
        if (userId == null || userId.equals(c.userId())) return;
        try {
            String actor = name(userRepository.findById(c.userId()).orElse(null));
            notificationService.send(userId, "COLLAB_WORKSPACE_MEMBER", actor + " " + what,
                    "COLLAB_WORKSPACE", ws.getId());
        } catch (RuntimeException e) {
            log.warn("[COLLAB] Membership notification failed (non-fatal) | ws={} | {}", ws.getId(), e.getMessage());
        }
    }

    @Transactional
    public void removeProgrammeMember(Long workspaceId, Long programmeId, Long userId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireManage(c, workspaceId);
        CollabProgramme p = requireVisibleProgramme(c, ws, programmeId);
        programmeMemberRepository.findByProgrammeIdAndUserId(p.getId(), userId)
                .ifPresent(programmeMemberRepository::delete);
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    /** Client side: every programme. Firm side: only programmes they are on. */
    List<CollabProgramme> visibleProgrammes(Caller c, CollabWorkspace ws) {
        List<CollabProgramme> all = programmeRepository
                .findByWorkspaceIdAndIsDeletedFalseOrderByPlannedStartAscNameAsc(ws.getId());
        if (!c.guest()) return all;
        Set<Long> mine = programmeMemberRepository.findByWorkspaceId(ws.getId()).stream()
                .filter(pm -> pm.getUserId().equals(c.userId()))
                .map(CollabProgrammeMember::getProgrammeId).collect(Collectors.toSet());
        return all.stream().filter(p -> mine.contains(p.getId())).toList();
    }

    private CollabProgramme requireVisibleProgramme(Caller c, CollabWorkspace ws, Long programmeId) {
        CollabProgramme p = programmeRepository.findByIdAndWorkspaceIdAndIsDeletedFalse(programmeId, ws.getId())
                .orElseThrow(() -> new ResourceNotFoundException("CollabProgramme", programmeId));
        if (visibleProgrammes(c, ws).stream().noneMatch(x -> x.getId().equals(p.getId()))) {
            throw new ResourceNotFoundException("CollabProgramme", programmeId);
        }
        return p;
    }

    private void addProgrammeMemberInternal(CollabWorkspace ws, CollabProgramme p, Long userId, Long addedBy) {
        if (programmeMemberRepository.findByProgrammeIdAndUserId(p.getId(), userId).isPresent()) return;
        CollabProgrammeMember pm = CollabProgrammeMember.builder()
                .programmeId(p.getId())
                .workspaceId(ws.getId())
                .userId(userId)
                .addedBy(addedBy)
                .build();
        pm.setTenantId(ws.getTenantId());
        programmeMemberRepository.save(pm);
    }

    /** Which side a person joins on — from their membership in the client tenant. */
    private String sideFor(CollabWorkspace ws, Long userId) {
        UserTenantMembership m = membershipRepository.findByUserIdAndTenantId(userId, ws.getTenantId())
                .filter(UserTenantMembership::isUsable)
                .orElseThrow(() -> new BusinessException("COLLAB_NOT_IN_ORGANISATION",
                        "This person has no active access to the organisation"));
        if (!"GUEST".equalsIgnoreCase(m.getMembershipType())) return CollabWorkspaceMember.SIDE_CLIENT;
        if (ws.getFirmTenantId() == null || !ws.getFirmTenantId().equals(m.getFirmTenantId())) {
            throw new BusinessException("COLLAB_OTHER_FIRM",
                    "Only this workspace's audit firm can join it from outside the organisation");
        }
        return CollabWorkspaceMember.SIDE_FIRM;
    }

    private void requireAnotherOwner(Long workspaceId) {
        if (memberRepository.countByWorkspaceIdAndWorkspaceRole(workspaceId, CollabWorkspaceMember.ROLE_OWNER) <= 1) {
            throw new BusinessException("COLLAB_LAST_OWNER", "A workspace must keep at least one owner");
        }
    }

    private static String normaliseRole(String role) {
        String r = role == null || role.isBlank() ? CollabWorkspaceMember.ROLE_MEMBER : role.trim().toUpperCase();
        if (!List.of(CollabWorkspaceMember.ROLE_OWNER, CollabWorkspaceMember.ROLE_MEMBER,
                CollabWorkspaceMember.ROLE_VIEWER).contains(r)) {
            throw new BusinessException("COLLAB_BAD_ROLE", "Role must be OWNER, MEMBER or VIEWER");
        }
        return r;
    }

    private static void checkDates(CollabProgramme p) {
        if (p.getPlannedStart() != null && p.getPlannedEnd() != null && p.getPlannedEnd().isBefore(p.getPlannedStart())) {
            throw new BusinessException("COLLAB_BAD_DATES", "The end date is before the start date");
        }
    }

    private Map<String, Object> summary(CollabWorkspace ws, Map<Long, String> tenantNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ws.getId());
        m.put("name", ws.getName());
        m.put("description", ws.getDescription());
        m.put("status", ws.getStatus());
        m.put("firmTenantId", ws.getFirmTenantId());
        m.put("firmName", ws.getFirmTenantId() == null ? null : tenantNames.get(ws.getFirmTenantId()));
        m.put("internal", ws.getFirmTenantId() == null);
        m.put("firmAccessActive", ws.getFirmTenantId() == null
                || access.activeGrant(ws.getTenantId(), ws.getFirmTenantId()).isPresent());
        m.put("createdAt", ws.getCreatedAt());
        return m;
    }

    private static Map<String, Object> programmeMap(CollabProgramme p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("plannedStart", p.getPlannedStart());
        m.put("plannedEnd", p.getPlannedEnd());
        m.put("status", p.getStatus());
        return m;
    }

    private static Map<String, Object> memberMap(CollabWorkspaceMember m, User u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("userId", m.getUserId());
        out.put("name", name(u));
        out.put("email", u != null ? u.getEmail() : null);
        out.put("side", m.getSide());
        out.put("role", m.getWorkspaceRole());
        return out;
    }

    private Map<Long, User> users(Set<Long> ids) {
        Map<Long, User> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private Map<Long, String> tenantNames(Set<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        tenantRepository.findAllById(ids).forEach(t -> out.put(t.getId(), t.getName()));
        return out;
    }

    private static String name(User u) {
        if (u == null) return "Unknown user";
        return joinName(u.getFirstName(), u.getLastName(), u.getEmail());
    }

    private static String joinName(String first, String last, String email) {
        String n = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return n.isEmpty() ? (email == null ? "Unknown user" : email) : n;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDate date(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return LocalDate.parse(o.toString().substring(0, Math.min(10, o.toString().length())));
        } catch (RuntimeException ex) {
            throw new BusinessException("COLLAB_BAD_DATE", "Dates must be YYYY-MM-DD: " + o);
        }
    }
}