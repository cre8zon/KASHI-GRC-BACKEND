package com.kashi.grc.audit.service;

import com.kashi.grc.audit.domain.AuditEngagement;
import com.kashi.grc.audit.domain.AuditTemplate;
import com.kashi.grc.audit.repository.AuditEngagementRepository;
import com.kashi.grc.usermanagement.domain.Role;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.RoleRepository;
import com.kashi.grc.usermanagement.repository.UserRepository;
import com.kashi.grc.usermanagement.repository.UserTenantMembershipRepository;
import com.kashi.grc.usermanagement.service.role.PermissionHolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may be ASSIGNED work on an engagement — chosen by permission, not by role
 * name or role side.
 *
 *   auditors  — hold audit:control:record-test-result (they test and conclude)
 *   auditees  — hold audit:control:submit-evidence    (they supply evidence)
 *
 * These are the permissions the work itself is checked against
 * (ControlAccessGuard.requireCanRecordResult / requireCanSubmitEvidence), so a
 * picker can no longer offer someone who would then be refused, or hide
 * someone who could do the job because their role has a different name.
 *
 * Membership scope — WHERE the person comes from, not what their role is called:
 *
 *   auditees           — the client's own people (HOME membership). An invited
 *                        external auditor is never an evidence owner.
 *   auditors, INTERNAL — HOME members. "Internal audit — org-side actors only"
 *                        (AuditTemplate.AuditType).
 *   auditors, EXTERNAL — invited auditors (GUEST membership). When the lead
 *                        auditor is a guest, only their firm's guests — a
 *                        client can have several firms invited, and offering
 *                        Firm B's people on Firm A's engagement would hand
 *                        them its work.
 *
 * Holders come from PermissionHolderService (role permissions in this tenant,
 * role grants/denies, per-user overrides). No USER_VIEW is needed, as before:
 * section owners sub-assigning controls use these lists.
 */
@Service
@RequiredArgsConstructor
public class AuditAssignableUsersService {

    public static final String AUDITOR_PERMISSION = "audit:control:record-test-result";
    public static final String AUDITEE_PERMISSION = "audit:control:submit-evidence";

    private final PermissionHolderService         permissionHolderService;
    private final AuditEngagementRepository       engagementRepository;
    private final UserTenantMembershipRepository  membershipRepository;
    private final UserRepository                  userRepository;
    private final RoleRepository                  roleRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignableAuditees(AuditEngagement engagement) {
        var holders = permissionHolderService.holders(engagement.getTenantId(), AUDITEE_PERMISSION);
        return toUsers(holders.values().stream()
                .filter(h -> "HOME".equalsIgnoreCase(h.membershipType()))
                .toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignableAuditors(AuditEngagement engagement) {
        var holders = permissionHolderService.holders(engagement.getTenantId(), AUDITOR_PERMISSION);

        boolean external = engagement.getAuditType() == AuditTemplate.AuditType.EXTERNAL;
        if (!external) {
            return toUsers(holders.values().stream()
                    .filter(h -> "HOME".equalsIgnoreCase(h.membershipType()))
                    .toList());
        }

        // External: the lead auditor's firm, when the lead auditor is a guest.
        Long firm = null;
        if (engagement.getLeadAuditorId() != null) {
            firm = membershipRepository
                    .findByUserIdAndTenantId(engagement.getLeadAuditorId(), engagement.getTenantId())
                    .filter(m -> "GUEST".equalsIgnoreCase(m.getMembershipType()))
                    .map(m -> m.getFirmTenantId())
                    .orElse(null);
        }
        final Long leadFirm = firm;
        return toUsers(holders.values().stream()
                .filter(h -> "GUEST".equalsIgnoreCase(h.membershipType()))
                .filter(h -> leadFirm == null || leadFirm.equals(h.firmTenantId()))
                .toList());
    }

    /**
     * Same shape the old endpoint returned (WorkflowEngineService.getUsersByRoles:
     * id, firstName, lastName, email, fullName, roleName) plus userId, the
     * person's roles in this tenant for the pickers' role filter, and their
     * membership.
     */
    private List<Map<String, Object>> toUsers(List<PermissionHolderService.Holder> holders) {
        if (holders.isEmpty()) return List.of();
        Map<Long, PermissionHolderService.Holder> byUser = new LinkedHashMap<>();
        holders.forEach(h -> byUser.put(h.userId(), h));

        Set<Long> roleIds = holders.stream().flatMap(h -> h.roleIds().stream()).collect(Collectors.toSet());
        Map<Long, Role> roles = new HashMap<>();
        if (!roleIds.isEmpty()) roleRepository.findAllById(roleIds).forEach(r -> roles.put(r.getId(), r));

        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userRepository.findAllById(byUser.keySet())) {
            if (u.isDeleted()) continue;
            var h = byUser.get(u.getId());
            List<Map<String, Object>> userRoles = h.roleIds().stream()
                    .map(roles::get).filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(Role::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(r -> {
                        Map<String, Object> rm = new LinkedHashMap<>();
                        rm.put("id", r.getId());
                        rm.put("name", r.getName());
                        rm.put("roleName", r.getName());
                        return rm;
                    }).toList();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",             u.getId());
            m.put("userId",         u.getId());
            m.put("firstName",      u.getFirstName());
            m.put("lastName",       u.getLastName());
            m.put("email",          u.getEmail());
            m.put("fullName",       ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                    + (u.getLastName() == null ? "" : u.getLastName())).trim());
            m.put("roleName",       userRoles.isEmpty() ? null : userRoles.get(0).get("name"));
            m.put("roles",          userRoles);
            m.put("membershipType", h.membershipType());
            m.put("firmTenantId",   h.firmTenantId());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("fullName")).toLowerCase()));
        return out;
    }
}