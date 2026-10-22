package com.kashi.grc.collab.service;

import com.kashi.grc.collab.domain.CollabCallGuest;
import com.kashi.grc.collab.domain.CollabCallLink;
import com.kashi.grc.collab.domain.CollabMeeting;
import com.kashi.grc.collab.domain.CollabMeetingAttendee;
import com.kashi.grc.collab.domain.CollabRoom;
import com.kashi.grc.collab.repository.CollabCallGuestRepository;
import com.kashi.grc.collab.repository.CollabCallLinkRepository;
import com.kashi.grc.collab.repository.CollabMeetingAttendeeRepository;
import com.kashi.grc.collab.repository.CollabMeetingRepository;
import com.kashi.grc.collab.repository.CollabRoomMemberRepository;
import com.kashi.grc.collab.repository.CollabRoomRepository;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Call links and guest entry.
 *
 * ── THE LINK ─────────────────────────────────────────────────────────────────
 * Anyone who may be in a call can fetch its link ({origin}/join/{code}) to
 * paste into a chat or an email. A call in a standing room gets the ROOM's
 * link, so it is the same link every day; any other call gets its MEETING's.
 * Whoever runs the call (the organiser, a meeting manager, the room's creator
 * or workspace owner) can turn guests off, or replace the link — which kills
 * the old one at once.
 *
 * ── WHO GETS IN WITH IT ───────────────────────────────────────────────────────
 *   Signed in, same organisation, and able to see the call (a workspace
 *   member where the programme allows; the organisation's own staff for an
 *   internal meeting or room) → straight in, recorded as attending.
 *   Anyone else → the lobby, as a GUEST, only while guests are allowed:
 *   they give a name, wait, and are let in by a person already in the call.
 *
 * ── WHY A GUEST CANNOT FORCE THEIR WAY IN ────────────────────────────────────
 *   • a human admits every guest, by name, from inside the call;
 *   • the code is 80 random bits and the public endpoints are rate-limited
 *     per address and per link, so codes cannot be guessed;
 *   • the guest's page proves itself with a 256-bit secret (only its hash is
 *     stored) — knowing a request id is not enough;
 *   • a guest token names one room, cannot be room admin or record, and is
 *     only minted while the request is ADMITTED and the link still valid;
 *   • a waiting request lapses after 15 minutes; an admission after 12 hours;
 *     anyone in the call can remove a guest, who is then put out of the room
 *     and cannot get another token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabCallLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";   // no 0/o, 1/l/i
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,100}@[^\\s@]{1,100}\\.[^\\s@]{2,40}$");
    private static final Pattern CODE  = Pattern.compile("^[a-z2-9]{4}(-[a-z2-9]{4}){3}$");
    private static final Duration WAIT_LIMIT  = Duration.ofMinutes(15);
    private static final Duration ADMIT_LIMIT = Duration.ofHours(12);
    /** A waiting guest whose page has not polled for this long has closed it. */
    private static final Duration LIVE_WINDOW = Duration.ofSeconds(40);
    private static final int MAX_WAITING_PER_CALL = 25;

    private final CollabAccessService             access;
    private final CollabMeetingService            meetingService;
    private final CollabCallService               callService;
    private final CollabCallLinkRepository        linkRepository;
    private final CollabCallGuestRepository       guestRepository;
    private final CollabMeetingRepository         meetingRepository;
    private final CollabMeetingAttendeeRepository attendeeRepository;
    private final CollabRoomRepository            roomRepository;
    private final CollabRoomMemberRepository      roomMemberRepository;
    private final TenantRepository                tenantRepository;
    private final LiveKitService                  liveKit;
    private final CollabGuestRateLimiter          rateLimiter;

    // ══════════════════════ IN THE CALL (signed in) ══════════════════════════

    /** The call's link — created on first ask, renewed (expiry pushed out) after that. */
    @Transactional
    public Map<String, Object> link(Long meetingId) {
        requireEnabled();
        Caller c = access.caller();
        CollabMeeting m = requireInCall(c, meetingId);
        // Fetched from the meeting page too, ahead of time — so the meeting
        // itself has to be one a link can open.
        if (!CollabMeetingService.EMBEDDED.equals(m.getProvider())) {
            throw new BusinessException("COLLAB_NOT_IN_APP", "This meeting uses an outside link (Teams, Zoom, Meet), not an in-app call");
        }
        if (CollabMeeting.CANCELLED.equals(m.getStatus())) {
            throw new BusinessException("COLLAB_CANCELLED", "This meeting was cancelled");
        }
        Target t = target(m);
        CollabCallLink l = activeLink(c.tenantId(), t).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (l == null) {
            l = CollabCallLink.builder()
                    .targetType(t.type).targetId(t.id)
                    .code(newCode()).allowGuests(true)
                    .expiresAt(expiryFor(t, m, now))
                    .build();
            l.setTenantId(c.tenantId());
            l.setCreatedBy(c.userId());
            linkRepository.save(l);
            log.info("[COLLAB] Call link created | {}={} | by={}", t.type, t.id, c.userId());
        } else {
            LocalDateTime renewed = expiryFor(t, m, now);
            if (renewed.isAfter(l.getExpiresAt())) { l.setExpiresAt(renewed); linkRepository.save(l); }
        }
        return linkMap(c, m, l);
    }

    /** Turn guests on/off, or replace the link ({ allowGuests?, regenerate? }). Whoever runs the call. */
    @Transactional
    public Map<String, Object> updateLink(Long meetingId, Map<String, Object> body) {
        requireEnabled();
        Caller c = access.caller();
        CollabMeeting m = requireInCall(c, meetingId);
        if (!canRunCall(c, m)) {
            throw new BusinessException("COLLAB_LINK_DENIED", "Only whoever runs this call can change its link", HttpStatus.FORBIDDEN);
        }
        Target t = target(m);
        CollabCallLink l = activeLink(c.tenantId(), t).orElseGet(() -> {
            link(meetingId);
            return activeLink(c.tenantId(), t).orElseThrow();
        });
        if (body.containsKey("allowGuests")) {
            l.setAllowGuests(Boolean.parseBoolean(String.valueOf(body.get("allowGuests"))));
            if (!l.isAllowGuests()) turnAwayWaiting(c, t, "guests turned off");
        }
        if (Boolean.parseBoolean(String.valueOf(body.get("regenerate")))) {
            l.setRevoked(true);
            l.setUpdatedBy(c.userId());
            linkRepository.save(l);
            turnAwayWaiting(c, t, "link replaced");
            CollabCallLink fresh = CollabCallLink.builder()
                    .targetType(t.type).targetId(t.id).code(newCode()).allowGuests(l.isAllowGuests())
                    .expiresAt(expiryFor(t, m, LocalDateTime.now())).build();
            fresh.setTenantId(c.tenantId());
            fresh.setCreatedBy(c.userId());
            linkRepository.save(fresh);
            log.info("[COLLAB] Call link replaced | {}={} | by={}", t.type, t.id, c.userId());
            return linkMap(c, m, fresh);
        }
        l.setUpdatedBy(c.userId());
        linkRepository.save(l);
        return linkMap(c, m, l);
    }

    /** Who is waiting to be let in, and the guests already in. Polled by the call window. */
    @Transactional
    public Map<String, Object> lobby(Long meetingId) {
        Caller c = access.caller();
        CollabMeeting m = requireInCall(c, meetingId);
        Target t = target(m);
        boolean canAdmit = canAdmit(c, m);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> waiting = new ArrayList<>(), inCall = new ArrayList<>();
        for (CollabCallGuest g : guestRepository.findByTenantIdAndTargetTypeAndTargetIdAndStatusInOrderByRequestedAtAsc(
                c.tenantId(), t.type, t.id, List.of(CollabCallGuest.WAITING, CollabCallGuest.ADMITTED))) {
            if (lapse(g, now)) continue;
            if (CollabCallGuest.WAITING.equals(g.getStatus())) {
                // Only guests whose page is still open — a closed page stops polling.
                if (g.getLastSeenAt() != null && g.getLastSeenAt().isAfter(now.minus(LIVE_WINDOW))) waiting.add(guestMap(g));
            } else {
                inCall.add(guestMap(g));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canAdmit", canAdmit);
        out.put("waiting", canAdmit ? waiting : List.of());
        out.put("waitingCount", waiting.size());
        out.put("guests", inCall);
        return out;
    }

    /** admit | deny | remove one guest request. Anyone in the call who may admit. */
    @Transactional
    public Map<String, Object> decide(Long requestId, String action, Long callMeetingId) {
        Caller c = access.caller();
        CollabCallGuest g = guestRepository.findByIdAndTenantId(requestId, c.tenantId())
                .orElseThrow(() -> new BusinessException("COLLAB_GUEST_NOT_FOUND", "That request is no longer there", HttpStatus.NOT_FOUND));
        // Judged against the call the person is actually in (sent by the call
        // window). For a ROOM link the room's "current session" can be a
        // different meeting — a repeating stand-up joined after its slot, a
        // drop-in — and judging by that refused people who were in the call.
        CollabMeeting m = null;
        if (callMeetingId != null) {
            CollabMeeting mine = meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(callMeetingId, c.tenantId())
                    .filter(x -> meetingService.canSee(c, x)).orElse(null);
            if (mine != null) {
                Target t = target(mine);
                if (t.type.equals(g.getTargetType()) && t.id.equals(g.getTargetId()) && canAdmit(c, mine)) m = mine;
            }
        }
        if (m == null) {
            CollabMeeting session = meetingForTarget(c, g.getTargetType(), g.getTargetId());
            if (session != null && canAdmit(c, session)) m = session;
        }
        // A room's own people (its members, its managers) may always answer
        // its door, whichever session is on.
        boolean roomPerson = m == null && CollabCallLink.ROOM.equals(g.getTargetType()) && canAdmitRoom(c, g.getTargetId());
        if (m == null && !roomPerson) {
            throw new BusinessException("COLLAB_ADMIT_DENIED", "Only people in this call can let guests in", HttpStatus.FORBIDDEN);
        }
        LocalDateTime now = LocalDateTime.now();
        switch (action == null ? "" : action.toLowerCase()) {
            case "admit" -> {
                if (!CollabCallGuest.WAITING.equals(g.getStatus()) || lapse(g, now)) throw gone();
                g.setStatus(CollabCallGuest.ADMITTED);
                g.setAdmittedUntil(now.plus(ADMIT_LIMIT));
                if (m != null) g.setMeetingId(m.getId());
            }
            case "deny" -> {
                if (!CollabCallGuest.WAITING.equals(g.getStatus())) throw gone();
                g.setStatus(CollabCallGuest.DENIED);
            }
            case "remove" -> {
                if (!CollabCallGuest.ADMITTED.equals(g.getStatus())) throw gone();
                g.setStatus(CollabCallGuest.REMOVED);
                liveKit.removeParticipant(g.getRoomRef(), g.getIdentity());
            }
            default -> throw new BusinessException("COLLAB_BAD_ACTION", "Use admit, deny or remove");
        }
        g.setDecidedBy(c.userId());
        g.setDecidedAt(now);
        guestRepository.save(g);
        log.info("[COLLAB] Guest {} | request={} | name={} | by={}", action, g.getId(), g.getDisplayName(), c.userId());
        return guestMap(g);
    }

    // ══════════════════════ OPENING A LINK (signed in) ════════════════════════

    /**
     * Opened by a signed-in user. DIRECT (with a join token, as any other join)
     * when they may be in the call; otherwise GUEST — the page then asks to
     * join through the lobby, the same as someone without an account.
     */
    @Transactional
    public Map<String, Object> openSignedIn(String code) {
        requireEnabled();
        Caller c = access.caller();
        CollabCallLink l = requireLink(code);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!l.getTenantId().equals(c.tenantId())) {           // another organisation's call
            out.put("mode", "GUEST");
            return out;
        }
        Map<String, Object> grant = null;
        if (CollabCallLink.MEETING.equals(l.getTargetType())) {
            CollabMeeting m = meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(l.getTargetId(), c.tenantId())
                    .orElseThrow(this::ended);
            // Internal meetings are visible to whoever is on them: the link is
            // the invitation, so the organisation's own staff are added.
            boolean internalColleague = m.getWorkspaceId() == null && !c.guest();
            if (meetingService.canSee(c, m) || internalColleague) {
                if (attendeeRepository.findByMeetingIdAndUserId(m.getId(), c.userId()).isEmpty()
                        && !meetingService.isRoomMember(c, m) && !meetingService.canRun(c, m)) {
                    CollabMeetingAttendee a = CollabMeetingAttendee.builder().meetingId(m.getId()).userId(c.userId()).attended(false).build();
                    a.setTenantId(m.getTenantId());
                    attendeeRepository.save(a);
                }
                grant = callService.joinMeeting(m.getId());
            }
        } else {
            CollabRoom r = roomRepository.findByIdAndTenantIdAndIsDeletedFalse(l.getTargetId(), c.tenantId()).orElseThrow(this::ended);
            if (callService.canSee(c, r)) grant = callService.joinRoomSession(c, r);
        }
        if (grant == null) {
            out.put("mode", "GUEST");
            return out;
        }
        out.putAll(grant);
        out.put("mode", "DIRECT");
        return out;
    }

    /** What a chat card shows for a call link: the call's name and whether the link still works. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String code) {
        Caller c = access.caller();
        Map<String, Object> out = new LinkedHashMap<>();
        CollabCallLink l = CODE.matcher(code == null ? "" : code).matches()
                ? linkRepository.findByCodeAndIsDeletedFalse(code).orElse(null) : null;
        boolean mine = l != null && l.getTenantId().equals(c.tenantId());
        out.put("active", l != null && l.usable(LocalDateTime.now()) && targetOpen(l));
        out.put("title", mine ? titleOf(l) : null);
        out.put("kind", l == null ? null : l.getTargetType());
        return out;
    }

    // ══════════════════════ GUESTS (no account) ═══════════════════════════════

    /** The join page: what this call is, and whether guests can ask to join. */
    @Transactional(readOnly = true)
    public Map<String, Object> publicInfo(String code, String ip) {
        limit("info:" + ip, 60, Duration.ofMinutes(10));
        CollabCallLink l = requireLinkPublic(code);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean active = l.usable(now) && targetOpen(l);
        out.put("active", active);
        out.put("guestsAllowed", active && l.isAllowGuests());
        out.put("title", active ? titleOf(l) : null);
        out.put("host", active ? tenantRepository.findById(l.getTenantId()).map(t -> t.getName()).orElse(null) : null);
        out.put("callsEnabled", liveKit.enabled());
        return out;
    }

    /** Ask to join. Returns the request id and the secret the page polls with (shown once). */
    @Transactional
    public Map<String, Object> knock(String code, Map<String, Object> body, String ip) {
        requireEnabled();
        limit("knock:" + ip, 10, Duration.ofMinutes(10));
        CollabCallLink l = requireLinkPublic(code);
        limit("knock-link:" + l.getId(), 40, Duration.ofMinutes(10));
        // Honeypot: a field people never see. Bots fill it; they get a request that never opens.
        if (body.get("website") != null && !String.valueOf(body.get("website")).isBlank()) {
            log.info("[COLLAB] Guest honeypot hit | link={}", l.getId());
            return Map.of("requestId", 0, "secret", token(32), "status", CollabCallGuest.WAITING);
        }
        if (!l.usable(LocalDateTime.now()) || !targetOpen(l)) throw ended();
        if (!l.isAllowGuests()) {
            throw new BusinessException("COLLAB_GUESTS_OFF", "This call is not taking guests. Ask the person who sent the link.", HttpStatus.FORBIDDEN);
        }
        String name = cleanName(body.get("name"));
        String email = body.get("email") == null ? null : String.valueOf(body.get("email")).trim();
        if (email != null && email.isEmpty()) email = null;
        if (email != null && (email.length() > 200 || !EMAIL.matcher(email).matches())) {
            throw new BusinessException("COLLAB_BAD_EMAIL", "That email address does not look right");
        }
        if (guestRepository.countByTenantIdAndTargetTypeAndTargetIdAndStatus(l.getTenantId(), l.getTargetType(), l.getTargetId(),
                CollabCallGuest.WAITING) >= MAX_WAITING_PER_CALL) {
            throw new BusinessException("COLLAB_LOBBY_FULL", "Too many people are waiting to join. Try again in a few minutes.", HttpStatus.TOO_MANY_REQUESTS);
        }
        String secret = token(32);
        LocalDateTime now = LocalDateTime.now();
        CollabCallGuest g = CollabCallGuest.builder()
                .linkId(l.getId()).targetType(l.getTargetType()).targetId(l.getTargetId())
                .displayName(name).email(email).secretHash(sha256(secret))
                .status(CollabCallGuest.WAITING).requestedAt(now).lastSeenAt(now)
                .build();
        g.setTenantId(l.getTenantId());
        guestRepository.save(g);
        log.info("[COLLAB] Guest asked to join | link={} | request={}", l.getId(), g.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", g.getId());
        out.put("secret", secret);
        out.put("status", g.getStatus());
        return out;
    }

    /**
     * The guest page's poll: still waiting, turned away, or — once admitted —
     * a join token. Fetching a token again (a refresh) works until the
     * admission lapses or they are removed.
     */
    @Transactional
    public Map<String, Object> guestStatus(Long requestId, Map<String, Object> body) {
        limit("status:" + requestId, 400, Duration.ofMinutes(10));
        CollabCallGuest g = requireGuest(requestId, body);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        CollabCallLink l = linkRepository.findById(g.getLinkId()).orElse(null);
        // The link was replaced or switched off for guests: nobody new gets in on it.
        if (CollabCallGuest.WAITING.equals(g.getStatus()) && (l == null || !l.usable(now) || !l.isAllowGuests())) {
            g.setStatus(CollabCallGuest.EXPIRED);
        }
        if (!lapse(g, now) && CollabCallGuest.WAITING.equals(g.getStatus())) g.setLastSeenAt(now);
        out.put("status", g.getStatus());
        if (CollabCallGuest.ADMITTED.equals(g.getStatus())) {
            String room = roomRefFor(g);
            if (room == null || !liveKit.enabled()) {
                g.setStatus(CollabCallGuest.EXPIRED);
                out.put("status", g.getStatus());
            } else {
                String identity = LiveKitService.guestIdentity(g.getId());
                g.setIdentity(identity);
                g.setRoomRef(room);
                out.put("url", liveKit.url());
                out.put("token", liveKit.guestToken(identity, g.getDisplayName() + " (guest)", room));
                out.put("room", room);
                out.put("title", l == null ? "Call" : titleOf(l));
                out.put("name", g.getDisplayName());
                out.put("expiresIn", liveKit.ttlSeconds());
            }
        }
        guestRepository.save(g);
        return out;
    }

    /** The guest gave up waiting, or left the call. */
    @Transactional
    public Map<String, Object> guestLeave(Long requestId, Map<String, Object> body) {
        limit("status:" + requestId, 400, Duration.ofMinutes(10));
        CollabCallGuest g = requireGuest(requestId, body);
        if (CollabCallGuest.WAITING.equals(g.getStatus())) {
            g.setStatus(CollabCallGuest.LEFT);
            guestRepository.save(g);
        }
        return Map.of("status", g.getStatus());
    }

    // ══════════════════════ RULES ═════════════════════════════════════════════

    private record Target(String type, Long id) { }

    /** A room's call shares the ROOM's link; everything else, the meeting's. */
    private Target target(CollabMeeting m) {
        return m.getRoomId() != null ? new Target(CollabCallLink.ROOM, m.getRoomId()) : new Target(CollabCallLink.MEETING, m.getId());
    }

    /** May be in this call: invited, a member of its room, or running it. */
    private CollabMeeting requireInCall(Caller c, Long meetingId) {
        CollabMeeting m = meetingService.requireVisible(c, meetingId);
        boolean in = attendeeRepository.findByMeetingIdAndUserId(m.getId(), c.userId()).isPresent()
                || meetingService.isRoomMember(c, m) || meetingService.canRun(c, m);
        if (!in) throw new BusinessException("COLLAB_NOT_INVITED", "Only people in this call can do that", HttpStatus.FORBIDDEN);
        return m;
    }

    /** Runs the call: the meeting's runners, or for a room call the room's managers. */
    private boolean canRunCall(Caller c, CollabMeeting m) {
        if (meetingService.canRun(c, m)) return true;
        if (m.getRoomId() == null) return false;
        return roomRepository.findByIdAndTenantIdAndIsDeletedFalse(m.getRoomId(), c.tenantId())
                .map(r -> callService.canManage(c, r)).orElse(false);
    }

    /**
     * Who may let guests in: anyone in the call from the host organisation,
     * and the audit firm's people only where they run it.
     */
    private boolean canAdmit(Caller c, CollabMeeting m) {
        boolean in = attendeeRepository.findByMeetingIdAndUserId(m.getId(), c.userId()).isPresent()
                || meetingService.isRoomMember(c, m) || canRunCall(c, m);
        return in && (!c.guest() || canRunCall(c, m));
    }

    /** A member or manager of the room — staff of the host organisation, or the firm's people who manage it. */
    private boolean canAdmitRoom(Caller c, Long roomId) {
        CollabRoom r = roomRepository.findByIdAndTenantIdAndIsDeletedFalse(roomId, c.tenantId()).orElse(null);
        if (r == null || !callService.canSee(c, r)) return false;
        boolean manage = callService.canManage(c, r);
        boolean member = roomMemberRepository.findByRoomIdAndUserId(r.getId(), c.userId()).isPresent();
        return (member || manage) && (!c.guest() || manage);
    }

    /** The meeting a lobby decision is judged against: the meeting, or the room's current session. */
    private CollabMeeting meetingForTarget(Caller c, String type, Long id) {
        if (CollabCallLink.MEETING.equals(type)) {
            return meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId())
                    .filter(m -> meetingService.canSee(c, m)).orElse(null);
        }
        CollabRoom r = roomRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId()).orElse(null);
        if (r == null || !callService.canSee(c, r)) return null;
        CollabMeeting session = callService.currentSession(r);
        return session != null && meetingService.canSee(c, session) ? session : null;
    }

    private java.util.Optional<CollabCallLink> activeLink(Long tenantId, Target t) {
        LocalDateTime now = LocalDateTime.now();
        return linkRepository.findByTenantIdAndTargetTypeAndTargetIdAndRevokedFalseAndIsDeletedFalseOrderByIdDesc(tenantId, t.type, t.id)
                .stream().filter(l -> l.usable(now)).findFirst();
    }

    /** A meeting link lasts until two hours after the meeting ends; a room link 30 days. Both renew when fetched. */
    private LocalDateTime expiryFor(Target t, CollabMeeting m, LocalDateTime now) {
        if (CollabCallLink.ROOM.equals(t.type)) return now.plusDays(30);
        LocalDateTime end = m.getEndsAt() != null ? m.getEndsAt() : m.getStartsAt().plusHours(2);
        LocalDateTime base = end.isAfter(now) ? end : now;
        return base.plusHours(2);
    }

    /** The call behind the link still exists and is not cancelled or archived. */
    private boolean targetOpen(CollabCallLink l) {
        if (CollabCallLink.MEETING.equals(l.getTargetType())) {
            return meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(l.getTargetId(), l.getTenantId())
                    .filter(m -> CollabMeetingService.EMBEDDED.equals(m.getProvider()))
                    .filter(m -> !CollabMeeting.CANCELLED.equals(m.getStatus())).isPresent();
        }
        return roomRepository.findByIdAndTenantIdAndIsDeletedFalse(l.getTargetId(), l.getTenantId())
                .filter(r -> !r.isArchived()).isPresent();
    }

    private String titleOf(CollabCallLink l) {
        if (CollabCallLink.MEETING.equals(l.getTargetType())) {
            return meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(l.getTargetId(), l.getTenantId())
                    .map(CollabMeeting::getTitle).orElse("Call");
        }
        return roomRepository.findByIdAndTenantIdAndIsDeletedFalse(l.getTargetId(), l.getTenantId())
                .map(CollabRoom::getName).orElse("Call");
    }

    /** The LiveKit room an admitted guest goes into. */
    private String roomRefFor(CollabCallGuest g) {
        if (CollabCallLink.MEETING.equals(g.getTargetType())) {
            return meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(g.getTargetId(), g.getTenantId())
                    .filter(m -> !CollabMeeting.CANCELLED.equals(m.getStatus()))
                    .map(m -> m.getRoomRef() != null ? m.getRoomRef() : CollabMeetingService.roomRef(m)).orElse(null);
        }
        return roomRepository.findByIdAndTenantIdAndIsDeletedFalse(g.getTargetId(), g.getTenantId())
                .filter(r -> !r.isArchived()).map(CollabRoom::getRoomRef).orElse(null);
    }

    /** Lapses a request whose time is up. True when it is no longer live. */
    private boolean lapse(CollabCallGuest g, LocalDateTime now) {
        boolean waitingTooLong = CollabCallGuest.WAITING.equals(g.getStatus()) && g.getRequestedAt().plus(WAIT_LIMIT).isBefore(now);
        boolean admissionOver = CollabCallGuest.ADMITTED.equals(g.getStatus())
                && g.getAdmittedUntil() != null && g.getAdmittedUntil().isBefore(now);
        if (waitingTooLong || admissionOver) {
            g.setStatus(CollabCallGuest.EXPIRED);
            guestRepository.save(g);
            return true;
        }
        return false;
    }

    private void turnAwayWaiting(Caller c, Target t, String why) {
        LocalDateTime now = LocalDateTime.now();
        for (CollabCallGuest g : guestRepository.findByTenantIdAndTargetTypeAndTargetIdAndStatusInOrderByRequestedAtAsc(
                c.tenantId(), t.type, t.id, List.of(CollabCallGuest.WAITING))) {
            g.setStatus(CollabCallGuest.EXPIRED);
            g.setDecidedBy(c.userId());
            g.setDecidedAt(now);
            guestRepository.save(g);
        }
        log.info("[COLLAB] Waiting guests turned away | {}={} | {}", t.type, t.id, why);
    }

    private Map<String, Object> linkMap(Caller c, CollabMeeting m, CollabCallLink l) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", l.getCode());
        out.put("path", "/join/" + l.getCode());
        out.put("allowGuests", l.isAllowGuests());
        out.put("expiresAt", l.getExpiresAt());
        out.put("target", l.getTargetType());
        out.put("title", titleOf(l));
        out.put("canManage", canRunCall(c, m));
        return out;
    }

    private static Map<String, Object> guestMap(CollabCallGuest g) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", g.getId());
        x.put("name", g.getDisplayName());
        x.put("email", g.getEmail());
        x.put("status", g.getStatus());
        x.put("requestedAt", g.getRequestedAt());
        return x;
    }

    // ══════════════════════ HELPERS ═══════════════════════════════════════════

    private CollabCallLink requireLink(String code) {
        if (code == null || !CODE.matcher(code).matches()) throw ended();
        CollabCallLink l = linkRepository.findByCodeAndIsDeletedFalse(code).orElseThrow(this::ended);
        if (!l.usable(LocalDateTime.now()) || !targetOpen(l)) throw ended();
        return l;
    }

    /** By code, for the public page — every failure looks the same, so codes cannot be probed. */
    private CollabCallLink requireLinkPublic(String code) {
        if (code == null || !CODE.matcher(code).matches()) throw ended();
        return linkRepository.findByCodeAndIsDeletedFalse(code).orElseThrow(this::ended);
    }

    private CollabCallGuest requireGuest(Long requestId, Map<String, Object> body) {
        String secret = body == null || body.get("secret") == null ? "" : String.valueOf(body.get("secret"));
        CollabCallGuest g = requestId == null ? null : guestRepository.findById(requestId).orElse(null);
        if (g == null || !MessageDigest.isEqual(g.getSecretHash().getBytes(StandardCharsets.UTF_8),
                sha256(secret).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("COLLAB_GUEST_NOT_FOUND", "This request is not valid any more. Open the link again.", HttpStatus.NOT_FOUND);
        }
        return g;
    }

    private static String cleanName(Object raw) {
        String s = raw == null ? "" : String.valueOf(raw);
        s = s.replaceAll("[\\p{Cntrl}<>]", "").replaceAll("\\s+", " ").trim();
        if (s.length() < 2) throw new BusinessException("COLLAB_NAME_REQUIRED", "Enter your name so the people in the call know who you are");
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private String newCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder b = new StringBuilder(19);
            for (int i = 0; i < 16; i++) {
                if (i > 0 && i % 4 == 0) b.append('-');
                b.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = b.toString();
            if (!linkRepository.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not create a unique call link code");
    }

    private static String token(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void limit(String key, int max, Duration window) {
        if (!rateLimiter.allow(key, max, window)) {
            throw new BusinessException("COLLAB_RATE_LIMITED", "Too many attempts. Wait a few minutes and try again.", HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private void requireEnabled() {
        if (!liveKit.enabled()) {
            throw new BusinessException("COLLAB_CALLS_OFF", "In-app calls are not set up on this server", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private BusinessException ended() {
        return new BusinessException("COLLAB_LINK_INVALID", "This call link is not valid. It may have expired or been replaced — ask for a new one.", HttpStatus.NOT_FOUND);
    }

    private static BusinessException gone() {
        return new BusinessException("COLLAB_GUEST_GONE", "That person is no longer waiting", HttpStatus.CONFLICT);
    }
}