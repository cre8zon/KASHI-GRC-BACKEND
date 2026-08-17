package com.kashi.grc.usermanagement.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.kashi.grc.usermanagement.domain.*;
import com.kashi.grc.usermanagement.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * External auditor access — grants and staffing.
 *
 * THE SPLIT, AND WHY
 *   Email is globally unique in this system. If a client admin could invite an
 *   auditor by typing an address, they would create the identity for another
 *   company's employee: they would hold that person's temporary password, and
 *   they could squat an address before the firm onboarded them. They could also
 *   invite any address at all and label it as the firm, putting a stranger into
 *   the audit trail as that firm's auditor.
 *
 *   So the client decides WHICH FIRM may work here (a grant), the firm decides
 *   WHICH OF ITS PEOPLE staff it (GUEST memberships), and the client keeps a
 *   veto over both. An auditor identity always originates in the firm's own
 *   tenant, which is enforced below rather than left as a convention.
 *
 * CLIENT SIDE  (caller is in the client tenant)
 *   POST   /v1/auditor-access/grants                  — admit a firm
 *   GET    /v1/auditor-access/grants                  — firms admitted here
 *   DELETE /v1/auditor-access/grants/{id}             — revoke firm + its people
 *   GET    /v1/auditor-access/guests                  — who from firms is inside
 *   DELETE /v1/auditor-access/guests/{membershipId}   — revoke one auditor
 *
 * FIRM SIDE    (caller is in the firm tenant)
 *   GET    /v1/auditor-access/clients                          — clients we may staff
 *   POST   /v1/auditor-access/clients/{clientTenantId}/auditors — assign one of ours
 */
@RestController
@RequestMapping("/v1/auditor-access")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Auditor Access", description = "External audit firm grants and staffing")
public class AuditorAccessController {

    private final FirmAccessGrantRepository        grantRepo;
    private final AuditorAccessRequestRepository   requestRepo;
    private final UserTenantMembershipRepository   membershipRepo;
    private final TenantRepository                 tenantRepo;
    private final UserRepository                   userRepository;
    private final RoleRepository                   roleRepository;
    private final UtilityService                   utilityService;
    private final com.kashi.grc.notification.service.NotificationService notificationService;

    @PersistenceContext
    private EntityManager em;

    // ══════════════════════ CLIENT SIDE ══════════════════════════════════════

    @PostMapping("/grants")
    @Operation(summary = "Admit an audit firm to this tenant")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> grantFirm(
            @RequestBody Map<String, Object> body) {

        User actor = utilityService.getLoggedInDataContext();
        Long clientTenantId = actor.getTenantId();
        Long firmTenantId   = asLong(body.get("firmTenantId"));
        LocalDateTime expiresAt = asDateTime(body.get("expiresAt"));

        Tenant firm = tenantRepo.findById(firmTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", firmTenantId));

        if (!firm.isAuditFirm()) {
            throw new BusinessException("NOT_AN_AUDIT_FIRM",
                    firm.getName() + " is not registered as an audit firm", HttpStatus.BAD_REQUEST);
        }
        if (firmTenantId.equals(clientTenantId)) {
            throw new BusinessException("SELF_GRANT",
                    "A tenant cannot grant audit access to itself", HttpStatus.BAD_REQUEST);
        }

        // Re-granting a previously revoked firm reactivates the same row, so the
        // client/firm pair stays unique and the history is not duplicated.
        FirmAccessGrant grant = grantRepo
                .findByClientTenantIdAndFirmTenantId(clientTenantId, firmTenantId)
                .orElseGet(() -> FirmAccessGrant.builder()
                        .clientTenantId(clientTenantId)
                        .firmTenantId(firmTenantId)
                        .build());

        grant.setStatus("ACTIVE");
        grant.setExpiresAt(expiresAt);
        grant.setGrantedBy(actor.getId());
        grant.setRevokedAt(null);
        grant.setRevokedBy(null);
        grant.setNote(body.get("note") != null ? body.get("note").toString() : null);
        grantRepo.save(grant);

        // Notify the FIRM, not the client.
        //
        // I originally aimed this at client admins, which is backwards: the
        // client admin just performed the action, and the firm is the party that
        // has to do something — assign auditors — and currently learns about the
        // grant only by chance.
        String clientName = tenantRepo.findById(clientTenantId)
                .map(Tenant::getName).orElse("a client");
        String until = expiresAt != null
                ? " Access runs until " + expiresAt.toLocalDate() + "."
                : "";
        for (Long adminId : firmAdminIds(firmTenantId)) {
            notificationService.send(adminId, "AUDITOR_FIRM_GRANTED",
                    clientName + " has admitted " + firm.getName()
                            + " as an external audit firm. Assign the auditors who will work there."
                            + until,
                    "FIRM_ACCESS_GRANT", grant.getId(), actor.getId(),
                    Map.of("clientName", clientName,
                            "firmName",   firm.getName(),
                            "accessUntilPhrase", until.trim()));
        }

        log.info("[AUDITOR-ACCESS] Firm granted | clientTenantId={} firmTenantId={} expiresAt={} by={}",
                clientTenantId, firmTenantId, expiresAt, actor.getId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(toGrantMap(grant)));
    }

    @GetMapping("/firms")
    @Operation(summary = "Audit firms available to admit — the picker source")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAuditFirms() {

        Long myTenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Deliberately narrow: id, name and code only. A client choosing an
        // auditor has no business seeing another tenant's plan, user counts or
        // status, and the general tenant list endpoint is System-side anyway.
        List<Map<String, Object>> firms = tenantRepo.findAll().stream()
                .filter(Tenant::isAuditFirm)
                .filter(t -> !t.getId().equals(myTenantId))
                .filter(t -> "ACTIVE".equalsIgnoreCase(t.getStatus()))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tenantId", t.getId());
                    m.put("name",     t.getName());
                    m.put("code",     t.getCode());
                    return m;
                })
                .toList();

        return ResponseEntity.ok(ApiResponse.success(firms));
    }

    @GetMapping("/grants")
    @Operation(summary = "Audit firms admitted to this tenant")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listGrants() {
        Long clientTenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                grantRepo.findByClientTenantId(clientTenantId).stream()
                        .map(this::toGrantMap).toList()));
    }

    @DeleteMapping("/grants/{id}")
    @Operation(summary = "Revoke a firm's access — and every auditor it placed here")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeGrant(@PathVariable Long id) {

        User actor = utilityService.getLoggedInDataContext();
        FirmAccessGrant grant = grantRepo.findById(id)
                .filter(g -> g.getClientTenantId().equals(actor.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("FirmAccessGrant", id));

        grant.setStatus("REVOKED");
        grant.setRevokedAt(LocalDateTime.now());
        grant.setRevokedBy(actor.getId());
        grantRepo.save(grant);

        // Collect who is affected BEFORE revoking, or the list is empty.
        List<Long> affected = membershipRepo
                .findByTenantIdAndFirmTenantId(actor.getTenantId(), grant.getFirmTenantId())
                .stream()
                .filter(m -> !"REVOKED".equalsIgnoreCase(m.getStatus()))
                .map(UserTenantMembership::getUserId)
                .toList();

        // Without this the firm would be removed while its auditors kept working.
        int revoked = grantRepo.revokeMembershipsForGrant(id);

        String clientName = tenantRepo.findById(actor.getTenantId())
                .map(Tenant::getName).orElse("a client");
        for (Long uid : affected) {
            notificationService.send(uid, "AUDITOR_ACCESS_REVOKED",
                    "Your firm's access to " + clientName
                            + " has been withdrawn, so your access there has ended.",
                    "TENANT", actor.getTenantId());
        }
        for (Long adminId : firmAdminIds(grant.getFirmTenantId())) {
            notificationService.send(adminId, "AUDITOR_ACCESS_REVOKED",
                    clientName + " has withdrawn your firm's access. "
                            + revoked + " auditor(s) affected.",
                    "FIRM_ACCESS_GRANT", id);
        }

        log.info("[AUDITOR-ACCESS] Grant revoked | grantId={} clientTenantId={} memberships={}",
                id, actor.getTenantId(), revoked);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "grantId", id, "membershipsRevoked", revoked)));
    }

    @GetMapping("/guests")
    @Operation(summary = "External auditors currently inside this tenant")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listGuests() {
        Long clientTenantId = utilityService.getLoggedInDataContext().getTenantId();
        return ResponseEntity.ok(ApiResponse.success(
                membershipRepo.findByTenantIdAndMembershipType(clientTenantId, "GUEST").stream()
                        .map(this::toGuestMap).toList()));
    }

    @DeleteMapping("/guests/{membershipId}")
    @Operation(summary = "Revoke one external auditor's access to this tenant")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> revokeGuest(@PathVariable Long membershipId) {

        Long clientTenantId = utilityService.getLoggedInDataContext().getTenantId();
        UserTenantMembership m = membershipRepo.findById(membershipId)
                .filter(x -> x.getTenantId().equals(clientTenantId))
                .filter(x -> "GUEST".equalsIgnoreCase(x.getMembershipType()))
                .orElseThrow(() -> new ResourceNotFoundException("UserTenantMembership", membershipId));

        m.setStatus("REVOKED");
        membershipRepo.save(m);

        notificationService.send(m.getUserId(), "AUDITOR_ACCESS_REVOKED",
                "Your auditor access to "
                        + tenantRepo.findById(clientTenantId).map(Tenant::getName).orElse("a client")
                        + " has been withdrawn. Work you already recorded is retained.",
                "TENANT", clientTenantId);

        log.info("[AUDITOR-ACCESS] Guest revoked | membershipId={} userId={} tenantId={}",
                membershipId, m.getUserId(), clientTenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ══════════════════════ FIRM SIDE ════════════════════════════════════════

    @GetMapping("/clients")
    @Operation(summary = "Clients this firm may staff, with who is already assigned")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClients() {

        Long firmTenantId = utilityService.getLoggedInDataContext().getTenantId();
        List<Map<String, Object>> out = new ArrayList<>();

        for (FirmAccessGrant g : grantRepo.findByFirmTenantIdAndStatus(firmTenantId, "ACTIVE")) {
            Map<String, Object> m = toGrantMap(g);
            m.put("clientName", tenantRepo.findById(g.getClientTenantId())
                    .map(Tenant::getName).orElse("Unknown"));
            m.put("assigned", membershipRepo
                    .findByTenantIdAndFirmTenantId(g.getClientTenantId(), firmTenantId).stream()
                    .filter(x -> !"REVOKED".equalsIgnoreCase(x.getStatus()))
                    .map(this::toGuestMap).toList());
            out.add(m);
        }
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PostMapping("/clients/{clientTenantId}/auditors")
    @Operation(summary = "Assign one of this firm's auditors to a client")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignAuditor(
            @PathVariable Long clientTenantId,
            @RequestBody Map<String, Object> body) {

        User actor = utilityService.getLoggedInDataContext();
        Long firmTenantId = actor.getTenantId();
        Long userId = asLong(body.get("userId"));
        Long roleId = asLong(body.get("roleId"));
        LocalDateTime requestedExpiry = asDateTime(body.get("expiresAt"));

        FirmAccessGrant grant = grantRepo
                .findByClientTenantIdAndFirmTenantId(clientTenantId, firmTenantId)
                .orElseThrow(() -> new BusinessException("NO_GRANT",
                        "This client has not granted your firm access", HttpStatus.FORBIDDEN));

        if (!grant.isUsable()) {
            throw new BusinessException("GRANT_INACTIVE",
                    "The client's grant to your firm has been revoked or has expired",
                    HttpStatus.FORBIDDEN);
        }

        // ── Identity origin. This is the guard that makes the whole split real:
        // a firm may only place its OWN people. Without it, a firm admin could
        // assign any user id in the system into a client tenant.
        UserTenantMembership home = membershipRepo.findByUserIdAndTenantId(userId, firmTenantId)
                .filter(h -> "HOME".equalsIgnoreCase(h.getMembershipType()))
                .orElseThrow(() -> new BusinessException("NOT_FIRM_STAFF",
                        "That user is not a member of your firm", HttpStatus.FORBIDDEN));

        User auditor = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", roleId));

        // An external auditor is AUDITOR-side by definition. Allowing any other
        // side would hand a firm's employee, say, ORGANIZATION admin rights in
        // the client's tenant.
        if (role.getSide() != RoleSide.AUDITOR) {
            throw new BusinessException("ROLE_SIDE_INVALID",
                    "External auditors can only be given AUDITOR-side roles; '"
                            + role.getName() + "' is " + role.getSide(),
                    HttpStatus.BAD_REQUEST);
        }

        // A membership may end sooner than the grant, never later — otherwise a
        // firm could out-stay the window the client actually agreed to.
        LocalDateTime effectiveExpiry = requestedExpiry;
        if (grant.getExpiresAt() != null
                && (effectiveExpiry == null || effectiveExpiry.isAfter(grant.getExpiresAt()))) {
            effectiveExpiry = grant.getExpiresAt();
        }

        UserTenantMembership membership = membershipRepo
                .findByUserIdAndTenantId(userId, clientTenantId)
                .orElseGet(() -> UserTenantMembership.builder()
                        .userId(userId)
                        .tenantId(clientTenantId)
                        .membershipType("GUEST")
                        .build());

        if (!"GUEST".equalsIgnoreCase(membership.getMembershipType())) {
            // They already work for the client directly — nothing to grant, and
            // overwriting their HOME membership would be destructive.
            throw new BusinessException("ALREADY_HOME_MEMBER",
                    auditor.getEmail() + " is already a member of that tenant",
                    HttpStatus.CONFLICT);
        }

        membership.setFirmTenantId(firmTenantId);
        membership.setStatus("ACTIVE");
        membership.setAccessExpiresAt(effectiveExpiry);
        membership.setInvitedBy(actor.getId());
        membership.setPrimary(false);
        membershipRepo.save(membership);

        // Written natively: User.roles is a @ManyToMany whose join table mapping
        // does not include membership_id, so JPA cannot express "this role, in
        // that tenant".
        em.createNativeQuery("""
                        INSERT INTO user_roles (user_id, role_id, membership_id, assigned_at)
                        SELECT :userId, :roleId, :membershipId, NOW()
                        FROM DUAL
                        WHERE NOT EXISTS (
                            SELECT 1 FROM (SELECT * FROM user_roles) ur
                             WHERE ur.user_id = :userId
                               AND ur.role_id = :roleId
                               AND ur.membership_id = :membershipId)
                        """)
                .setParameter("userId", userId)
                .setParameter("roleId", roleId)
                .setParameter("membershipId", membership.getId())
                .executeUpdate();

        // Tell the auditor. Deliberately no password: their account already
        // exists in their firm's tenant, and issuing a temporary one here would
        // reset the credential they use at their own employer.
        String clientName2 = tenantRepo.findById(clientTenantId)
                .map(Tenant::getName).orElse("a client");
        String until2 = effectiveExpiry != null
                ? " until " + effectiveExpiry.toLocalDate() : "";
        notificationService.send(userId, "AUDITOR_ASSIGNED_TO_CLIENT",
                "You now have auditor access to " + clientName2 + " as "
                        + role.getName().replace('_', ' ').toLowerCase() + until2
                        + ". Use the organization switcher in the top bar to open it. "
                        + "Sign in with your existing account — no new password is issued.",
                "TENANT", clientTenantId, actor.getId(),
                Map.of("clientName", clientName2,
                        "roleName",   role.getName().replace('_', ' '),
                        "accessUntilPhrase", until2.trim()));

        log.info("[AUDITOR-ACCESS] Auditor assigned | userId={} firmTenantId={} clientTenantId={} "
                        + "roleId={} expires={} membershipId={}",
                userId, firmTenantId, clientTenantId, roleId, effectiveExpiry, membership.getId());

        Map<String, Object> out = toGuestMap(membership);
        out.put("clientTenantId", clientTenantId);
        out.put("roleId", roleId);
        out.put("roleName", role.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(out));
    }

    @DeleteMapping("/clients/{clientTenantId}/auditors/{userId}")
    @Operation(summary = "Withdraw one of this firm's auditors from a client")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> withdrawAuditor(
            @PathVariable Long clientTenantId, @PathVariable Long userId) {

        Long firmTenantId = utilityService.getLoggedInDataContext().getTenantId();

        UserTenantMembership m = membershipRepo.findByUserIdAndTenantId(userId, clientTenantId)
                .filter(x -> firmTenantId.equals(x.getFirmTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("UserTenantMembership", userId));

        m.setStatus("REVOKED");
        membershipRepo.save(m);

        log.info("[AUDITOR-ACCESS] Auditor withdrawn | userId={} clientTenantId={} by firm={}",
                userId, clientTenantId, firmTenantId);
        return ResponseEntity.ok(ApiResponse.success());
    }


    // ══════════════════════ ACCESS REQUESTS ══════════════════════════════════
    //
    // The firm asks; the client decides. Approval creates the same
    // FirmAccessGrant the client-initiated path creates, so nothing about who
    // controls access changes — only who starts the conversation.

    @PostMapping("/requests")
    @Operation(summary = "Firm asks a client for access, identified by the client's tenant code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestAccess(
            @RequestBody Map<String, Object> body) {

        User actor = utilityService.getLoggedInDataContext();
        Long firmTenantId = actor.getTenantId();

        Tenant firm = tenantRepo.findById(firmTenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", firmTenantId));
        if (!firm.isAuditFirm()) {
            throw new BusinessException("NOT_AN_AUDIT_FIRM",
                    "Only audit firms can request client access", HttpStatus.FORBIDDEN);
        }

        // Identified by tenant CODE, supplied by the client, rather than by
        // picking from a list. A firm must not be able to browse every
        // organisation on the platform looking for someone to ask — that would
        // turn this into a directory of who uses the product.
        String code = String.valueOf(body.getOrDefault("clientCode", "")).trim();
        if (code.isEmpty()) {
            throw new BusinessException("MISSING_CODE",
                    "Enter the client's organization code. Ask your client for it.",
                    HttpStatus.BAD_REQUEST);
        }

        // ── Deliberately indistinguishable outcomes ───────────────────────
        // Every path below returns the same response. Reporting "no such
        // organization" would turn this endpoint into an oracle: a firm could
        // try codes and learn which companies are customers of the platform,
        // which is information about OTHER people's clients and not ours to
        // give away. The cost is that a typo looks like success — worth it, and
        // softened by the wording.
        Optional<Tenant> clientOpt = tenantRepo.findAll().stream()
                .filter(x -> code.equalsIgnoreCase(x.getCode()))
                .filter(x -> "ACTIVE".equalsIgnoreCase(x.getStatus()))
                .filter(x -> !x.getId().equals(firmTenantId))
                .filter(x -> !x.isAuditFirm())
                .findFirst();

        Map<String, Object> sent = Map.of(
                "sent", true,
                "message", "If an organization with that code exists, its administrators "
                        + "have been notified. You will be told when they decide.");

        if (clientOpt.isEmpty()) {
            // Logged so a real typo is diagnosable from the server side, where
            // the person guessing codes cannot see it.
            log.info("[AUDITOR-ACCESS] Request for unknown/ineligible code '{}' | firmTenantId={}",
                    code, firmTenantId);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(sent));
        }

        Tenant client = clientOpt.get();

        // Already admitted, or already asked — silently no-op for the same
        // reason. Both states would otherwise reveal a real relationship.
        boolean alreadyGranted = grantRepo
                .findByClientTenantIdAndFirmTenantId(client.getId(), firmTenantId)
                .filter(FirmAccessGrant::isUsable).isPresent();
        boolean alreadyPending = requestRepo
                .findByFirmTenantIdAndClientTenantIdAndStatus(firmTenantId, client.getId(), "PENDING")
                .isPresent();

        if (alreadyGranted || alreadyPending) {
            log.info("[AUDITOR-ACCESS] Duplicate request suppressed | firmTenantId={} clientTenantId={} "
                            + "granted={} pending={}",
                    firmTenantId, client.getId(), alreadyGranted, alreadyPending);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(sent));
        }

        AuditorAccessRequest req = AuditorAccessRequest.builder()
                .firmTenantId(firmTenantId)
                .clientTenantId(client.getId())
                .status("PENDING")
                .requestedUntil(asDateTime(body.get("requestedUntil")))
                .message(body.get("message") != null ? body.get("message").toString() : null)
                .requestedBy(actor.getId())
                .build();
        requestRepo.save(req);

        for (Long adminId : clientAdminIds(client.getId())) {
            notificationService.send(adminId, "AUDITOR_ACCESS_REQUESTED",
                    firm.getName() + " has requested access to work as an external audit firm"
                            + (req.getMessage() != null ? ": " + req.getMessage() : "")
                            + ". Review it on External Auditors.",
                    "AUDITOR_ACCESS_REQUEST", req.getId(), actor.getId(),
                    Map.of("firmName", firm.getName()));
        }

        log.info("[AUDITOR-ACCESS] Request raised | firmTenantId={} clientTenantId={} requestId={}",
                firmTenantId, client.getId(), req.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(sent));
    }

    @GetMapping("/requests")
    @Operation(summary = "Access requests — incoming for a client, outgoing for a firm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listRequests() {
        Long myTenantId = utilityService.getLoggedInDataContext().getTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("incoming", requestRepo.findByClientTenantIdOrderByCreatedAtDesc(myTenantId)
                .stream().map(this::toRequestMap).toList());
        out.put("outgoing", requestRepo.findByFirmTenantIdOrderByCreatedAtDesc(myTenantId)
                .stream().map(this::toRequestMap).toList());
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @PostMapping("/requests/{id}/approve")
    @Operation(summary = "Client approves a request, creating the grant")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveRequest(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {

        User actor = utilityService.getLoggedInDataContext();
        AuditorAccessRequest req = requestRepo.findById(id)
                .filter(r -> r.getClientTenantId().equals(actor.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditorAccessRequest", id));

        if (!req.isPending()) {
            throw new BusinessException("NOT_PENDING",
                    "This request has already been " + req.getStatus().toLowerCase() + ".",
                    HttpStatus.CONFLICT);
        }

        // The CLIENT's date wins. requestedUntil is the firm's proposal and is
        // only a default — a firm setting its own access window would invert the
        // control this whole flow exists to preserve.
        LocalDateTime expiresAt = body != null && body.get("expiresAt") != null
                ? asDateTime(body.get("expiresAt"))
                : req.getRequestedUntil();
        if (expiresAt == null) {
            throw new BusinessException("EXPIRY_REQUIRED",
                    "Set an end date. Open-ended access to an audit firm is the one nobody revisits.",
                    HttpStatus.BAD_REQUEST);
        }

        FirmAccessGrant grant = grantRepo
                .findByClientTenantIdAndFirmTenantId(req.getClientTenantId(), req.getFirmTenantId())
                .orElseGet(() -> FirmAccessGrant.builder()
                        .clientTenantId(req.getClientTenantId())
                        .firmTenantId(req.getFirmTenantId())
                        .build());
        grant.setStatus("ACTIVE");
        grant.setExpiresAt(expiresAt);
        grant.setGrantedBy(actor.getId());
        grant.setRevokedAt(null);
        grant.setRevokedBy(null);
        grant.setNote(req.getMessage());
        grantRepo.save(grant);

        req.setStatus("APPROVED");
        req.setDecidedBy(actor.getId());
        req.setDecidedAt(LocalDateTime.now());
        req.setGrantId(grant.getId());
        requestRepo.save(req);

        notifyFirmOfDecision(req, "approved", null);

        log.info("[AUDITOR-ACCESS] Request approved | requestId={} grantId={} by={}",
                id, grant.getId(), actor.getId());
        return ResponseEntity.ok(ApiResponse.success(toGrantMap(grant)));
    }

    @PostMapping("/requests/{id}/decline")
    @Operation(summary = "Client declines a request")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> declineRequest(
            @PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {

        User actor = utilityService.getLoggedInDataContext();
        AuditorAccessRequest req = requestRepo.findById(id)
                .filter(r -> r.getClientTenantId().equals(actor.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditorAccessRequest", id));

        if (!req.isPending()) {
            throw new BusinessException("NOT_PENDING",
                    "This request has already been " + req.getStatus().toLowerCase() + ".",
                    HttpStatus.CONFLICT);
        }

        String note = body != null && body.get("note") != null ? body.get("note").toString() : null;
        req.setStatus("DECLINED");
        req.setDecidedBy(actor.getId());
        req.setDecidedAt(LocalDateTime.now());
        req.setDecisionNote(note);
        requestRepo.save(req);

        // Told, not left waiting. A silent decline means the firm chases the
        // client by email, which is the situation this flow removes.
        notifyFirmOfDecision(req, "declined", note);

        log.info("[AUDITOR-ACCESS] Request declined | requestId={} by={}", id, actor.getId());
        return ResponseEntity.ok(ApiResponse.success(toRequestMap(req)));
    }

    @DeleteMapping("/requests/{id}")
    @Operation(summary = "Firm withdraws its own pending request")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> withdrawRequest(@PathVariable Long id) {
        User actor = utilityService.getLoggedInDataContext();
        AuditorAccessRequest req = requestRepo.findById(id)
                .filter(r -> r.getFirmTenantId().equals(actor.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("AuditorAccessRequest", id));
        if (!req.isPending()) {
            throw new BusinessException("NOT_PENDING", "Only a pending request can be withdrawn.",
                    HttpStatus.CONFLICT);
        }
        req.setStatus("WITHDRAWN");
        req.setDecidedAt(LocalDateTime.now());
        requestRepo.save(req);
        return ResponseEntity.ok(ApiResponse.success());
    }

    private void notifyFirmOfDecision(AuditorAccessRequest req, String decision, String note) {
        String clientName = tenantRepo.findById(req.getClientTenantId())
                .map(Tenant::getName).orElse("A client");
        String body = clientName + " has " + decision + " your firm's access request."
                + (note != null && !note.isBlank() ? " Reason: " + note : "");
        for (Long adminId : firmAdminIds(req.getFirmTenantId())) {
            notificationService.send(adminId, "AUDITOR_ACCESS_REQUEST_DECIDED", body,
                    "AUDITOR_ACCESS_REQUEST", req.getId());
        }
        // The person who raised it hears directly, even if they are not an admin.
        if (req.getRequestedBy() != null && !firmAdminIds(req.getFirmTenantId()).contains(req.getRequestedBy())) {
            notificationService.send(req.getRequestedBy(), "AUDITOR_ACCESS_REQUEST_DECIDED", body,
                    "AUDITOR_ACCESS_REQUEST", req.getId());
        }
    }

    /** Admins of a client tenant, as recipients for an incoming request. */
    private List<Long> clientAdminIds(Long clientTenantId) {
        return userRepository.findAll().stream()
                .filter(u -> !u.isDeleted())
                .filter(u -> clientTenantId.equals(u.getTenantId()))
                .filter(u -> u.getRoles().stream().anyMatch(r ->
                        "ORG_OWNER".equalsIgnoreCase(r.getName())
                                || "ADMIN".equalsIgnoreCase(r.getName())
                                || "GRC_MANAGER".equalsIgnoreCase(r.getName())))
                .map(User::getId)
                .toList();
    }

    private Map<String, Object> toRequestMap(AuditorAccessRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             r.getId());
        m.put("firmTenantId",   r.getFirmTenantId());
        m.put("firmName",       tenantRepo.findById(r.getFirmTenantId())
                .map(Tenant::getName).orElse("Unknown"));
        m.put("clientTenantId", r.getClientTenantId());
        m.put("clientName",     tenantRepo.findById(r.getClientTenantId())
                .map(Tenant::getName).orElse("Unknown"));
        m.put("status",         r.getStatus());
        m.put("requestedUntil", r.getRequestedUntil());
        m.put("message",        r.getMessage());
        m.put("decisionNote",   r.getDecisionNote());
        m.put("decidedAt",      r.getDecidedAt());
        m.put("grantId",        r.getGrantId());
        m.put("createdAt",      r.getCreatedAt());
        return m;
    }

    // ══════════════════════ helpers ══════════════════════════════════════════

    /**
     * Admins of a firm, as notification recipients.
     *
     * Notification rows are keyed by user id only (Notification extends
     * BaseEntity, and the list query is findByUserIdOrderBySentAtDesc), so a
     * notification raised while acting in the client's tenant still reaches a
     * user who belongs to the firm. That is what makes cross-tenant notification
     * possible here without a separate delivery path.
     */
    private List<Long> firmAdminIds(Long firmTenantId) {
        return userRepository.findAll().stream()
                .filter(u -> !u.isDeleted())
                .filter(u -> firmTenantId.equals(u.getTenantId()))
                .filter(u -> u.getRoles().stream().anyMatch(r ->
                        "ORG_OWNER".equalsIgnoreCase(r.getName())
                                || "ADMIN".equalsIgnoreCase(r.getName())))
                .map(User::getId)
                .toList();
    }

    private Map<String, Object> toGrantMap(FirmAccessGrant g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             g.getId());
        m.put("clientTenantId", g.getClientTenantId());
        m.put("firmTenantId",   g.getFirmTenantId());
        m.put("firmName",       tenantRepo.findById(g.getFirmTenantId())
                .map(Tenant::getName).orElse("Unknown"));
        m.put("status",         g.getStatus());
        m.put("expiresAt",      g.getExpiresAt());
        m.put("usable",         g.isUsable());
        m.put("note",           g.getNote());
        return m;
    }

    private Map<String, Object> toGuestMap(UserTenantMembership m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("membershipId",    m.getId());
        out.put("userId",          m.getUserId());
        out.put("firmTenantId",    m.getFirmTenantId());
        out.put("status",          m.getStatus());
        out.put("accessExpiresAt", m.getAccessExpiresAt());
        out.put("usable",          m.isUsable());
        userRepository.findById(m.getUserId()).ifPresent(u -> {
            out.put("email",    u.getEmail());
            out.put("fullName", u.getFullName());
        });
        if (m.getFirmTenantId() != null) {
            out.put("firmName", tenantRepo.findById(m.getFirmTenantId())
                    .map(Tenant::getName).orElse("Unknown"));
        }

        // The role they hold IN THIS TENANT, read from the membership rather
        // than from the user. An auditor's grade at their own firm and their
        // role on a given client are different things -- that distinction is the
        // whole reason membership_id exists on user_roles -- so listing the
        // user's roles here would report the wrong one.
        @SuppressWarnings("unchecked")
        List<String> roleNames = em.createNativeQuery(
                        """
                        SELECT r.name FROM user_roles ur
                        JOIN   roles r ON r.id = ur.role_id
                        WHERE  ur.membership_id = :membershipId
                        ORDER  BY r.level
                        """)
                .setParameter("membershipId", m.getId())
                .getResultList();
        out.put("roleName",  roleNames.isEmpty() ? null : roleNames.get(0));
        out.put("roleNames", roleNames);
        return out;
    }

    private Long asLong(Object v) {
        if (v == null) throw new BusinessException("MISSING_FIELD",
                "Required id was not supplied", HttpStatus.BAD_REQUEST);
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private LocalDateTime asDateTime(Object v) {
        if (v == null || v.toString().isBlank()) return null;
        String s = v.toString();
        return s.length() == 10
                ? LocalDateTime.parse(s + "T23:59:59")   // date-only from a date picker
                : LocalDateTime.parse(s.replace(" ", "T"));
    }
}