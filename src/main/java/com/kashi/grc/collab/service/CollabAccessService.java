package com.kashi.grc.collab.service;

import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.domain.CollabWorkspaceMember;
import com.kashi.grc.collab.repository.CollabWorkspaceMemberRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceRepository;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.usermanagement.domain.FirmAccessGrant;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.domain.UserTenantMembership;
import com.kashi.grc.usermanagement.repository.FirmAccessGrantRepository;
import com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository;
import com.kashi.grc.workflow.service.WorkflowAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may see and manage Collaboration workspaces — the ONE place the rules live.
 *
 * ── THE RULES ─────────────────────────────────────────────────────────────────
 *
 *   See a workspace (both must hold):
 *     • permission collab:workspace:view, and
 *     • CLIENT staff (HOME membership in the tenant): a member of the
 *       workspace, or holding collab:workspace:manage (oversight of every
 *       workspace in their organisation);
 *       FIRM people (GUEST membership): a member of the workspace, their
 *       membership's firm IS the workspace's firm, and the client's grant to
 *       that firm is ACTIVE and unexpired.
 *
 *   Manage a workspace (members, programmes, settings): a workspace OWNER, or
 *   CLIENT staff holding collab:workspace:manage.
 *
 *   Create a workspace: collab:workspace:create, plus — for a firm workspace —
 *   an ACTIVE grant between this client and that firm. Firm people can only
 *   create one for their own firm.
 *
 * Guest status is read from the caller's membership in the ACTIVE tenant on
 * every request, not cached on the workspace, so revoking a firm grant or one
 * auditor's membership ends their access to every workspace immediately.
 *
 * Gated on permissions and memberships — never on role names or role sides.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabAccessService {

    public static final String PERM_VIEW   = "collab:workspace:view";
    public static final String PERM_CREATE = "collab:workspace:create";
    public static final String PERM_MANAGE = "collab:workspace:manage";

    private final UtilityService                  utilityService;
    private final WorkflowAccessService           workflowAccessService;
    private final UserTenantMembershipRepository  membershipRepository;
    private final FirmAccessGrantRepository       grantRepository;
    private final CollabWorkspaceRepository       workspaceRepository;
    private final CollabWorkspaceMemberRepository memberRepository;

    /** The caller, as seen in the active tenant. */
    public record Caller(Long userId, Long tenantId, boolean guest, Long firmTenantId, Set<String> permissions) {
        public boolean holds(String code) { return permissions.contains(code); }
        /** CLIENT for the client's own staff, FIRM for an invited auditor. */
        public String side() { return guest ? CollabWorkspaceMember.SIDE_FIRM : CollabWorkspaceMember.SIDE_CLIENT; }
    }

    public Caller caller() {
        User user = utilityService.getLoggedInDataContext();
        Long tenantId = user.getTenantId();
        Optional<UserTenantMembership> membership = membershipRepository.findByUserIdAndTenantId(user.getId(), tenantId);
        boolean guest = membership.map(m -> "GUEST".equalsIgnoreCase(m.getMembershipType())).orElse(false);
        Long firm = guest ? membership.map(UserTenantMembership::getFirmTenantId).orElse(null) : null;
        Set<String> perms;
        try {
            List<String> p = workflowAccessService.resolvePermissions(
                    utilityService.getLoggedInUserWithRolesAndPermissions());
            perms = p == null ? Set.of() : Set.copyOf(p);
        } catch (RuntimeException ex) {
            log.warn("[COLLAB] Could not resolve permissions: {}", ex.getMessage());
            perms = Set.of();
        }
        return new Caller(user.getId(), tenantId, guest, firm, perms);
    }

    // ── grant ─────────────────────────────────────────────────────────────────

    /** The client's ACTIVE, unexpired grant to this firm, if any. */
    public Optional<FirmAccessGrant> activeGrant(Long clientTenantId, Long firmTenantId) {
        if (clientTenantId == null || firmTenantId == null) return Optional.empty();
        return grantRepository.findByClientTenantIdAndFirmTenantId(clientTenantId, firmTenantId)
                .filter(g -> "ACTIVE".equalsIgnoreCase(g.getStatus()))
                .filter(g -> g.getExpiresAt() == null || g.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    // ── visibility ────────────────────────────────────────────────────────────

    public boolean canSee(Caller c, CollabWorkspace ws) {
        if (ws == null || !ws.getTenantId().equals(c.tenantId()) || !c.holds(PERM_VIEW)) return false;
        boolean member = memberRepository.findByWorkspaceIdAndUserId(ws.getId(), c.userId()).isPresent();
        if (!c.guest()) return member || c.holds(PERM_MANAGE);
        return member
                && ws.getFirmTenantId() != null
                && ws.getFirmTenantId().equals(c.firmTenantId())
                && activeGrant(ws.getTenantId(), ws.getFirmTenantId()).isPresent();
    }

    public boolean canManage(Caller c, CollabWorkspace ws) {
        if (!canSee(c, ws)) return false;
        if (!c.guest() && c.holds(PERM_MANAGE)) return true;
        return memberRepository.findByWorkspaceIdAndUserId(ws.getId(), c.userId())
                .map(m -> CollabWorkspaceMember.ROLE_OWNER.equals(m.getWorkspaceRole()))
                .orElse(false);
    }

    /** Workspaces in the active tenant the caller may see. */
    public List<CollabWorkspace> visibleWorkspaces(Caller c) {
        if (!c.holds(PERM_VIEW)) return List.of();
        if (!c.guest() && c.holds(PERM_MANAGE)) {
            return workspaceRepository.findByTenantIdAndIsDeletedFalseOrderByNameAsc(c.tenantId());
        }
        Set<Long> mine = memberRepository.findByTenantIdAndUserId(c.tenantId(), c.userId()).stream()
                .map(CollabWorkspaceMember::getWorkspaceId).collect(Collectors.toSet());
        if (mine.isEmpty()) return List.of();
        return workspaceRepository.findByTenantIdAndIdInAndIsDeletedFalseOrderByNameAsc(c.tenantId(), mine)
                .stream().filter(ws -> canSee(c, ws)).toList();
    }

    /** The workspace, or 404 — never 403, so a firm cannot probe another firm's ids. */
    public CollabWorkspace requireVisible(Caller c, Long workspaceId) {
        CollabWorkspace ws = workspaceRepository.findByIdAndTenantIdAndIsDeletedFalse(workspaceId, c.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("CollabWorkspace", workspaceId));
        if (!canSee(c, ws)) throw new ResourceNotFoundException("CollabWorkspace", workspaceId);
        return ws;
    }

    public CollabWorkspace requireManage(Caller c, Long workspaceId) {
        CollabWorkspace ws = requireVisible(c, workspaceId);
        if (!canManage(c, ws)) {
            throw new BusinessException("COLLAB_NOT_OWNER",
                    "Only a workspace owner can change this workspace", HttpStatus.FORBIDDEN);
        }
        return ws;
    }

    public void requirePermission(Caller c, String code) {
        if (!c.holds(code)) {
            throw new BusinessException("COLLAB_PERMISSION_DENIED",
                    "You do not have permission to do this (" + code + ")", HttpStatus.FORBIDDEN);
        }
    }
}