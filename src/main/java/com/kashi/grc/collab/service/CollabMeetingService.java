package com.kashi.grc.collab.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.actionitem.domain.ActionItem;
import com.kashi.grc.actionitem.dto.ActionItemRequest;
import com.kashi.grc.actionitem.service.ActionItemService;
import com.kashi.grc.collab.domain.CollabMeeting;
import com.kashi.grc.collab.domain.CollabMeetingAttendee;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.domain.CollabWorkspaceMember;
import com.kashi.grc.collab.repository.CollabMeetingAttendeeRepository;
import com.kashi.grc.collab.repository.CollabMeetingRepository;
import com.kashi.grc.collab.repository.CollabProgrammeMemberRepository;
import com.kashi.grc.collab.repository.CollabRoomMemberRepository;
import com.kashi.grc.collab.repository.CollabRoomRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceMemberRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Collaboration meetings — scheduling, attendees, the record (agenda, minutes,
 * decisions), follow-ups as action items. The call is either a link (Teams,
 * Zoom, Meet or any other) or, with provider EMBEDDED, an in-app call on the
 * self-hosted LiveKit server (CollabCallService issues the join tokens).
 * Kind CALL is a "call now": starts at once, invitees are told to join.
 *
 * ── WHO SEES A MEETING ────────────────────────────────────────────────────────
 *   In a workspace: whoever can see the workspace and the meeting's programme
 *   (every member when it has none) — the same rule as the plan.
 *   Internal (no workspace): its organiser and attendees only.
 *
 * ── WHO RUNS IT ───────────────────────────────────────────────────────────────
 *   Schedule: collab:meeting:manage (internal meetings: the organisation's own
 *   staff only). Edit, record minutes / decisions / attendance, cancel, add
 *   follow-ups: the organiser; in a workspace also its managers and holders
 *   of collab:meeting:manage who can see it; for an internal meeting also
 *   attendees who hold collab:meeting:manage.
 *
 * ── WHO CAN BE INVITED ────────────────────────────────────────────────────────
 *   In a workspace: its members — a firm member only when they are on the
 *   meeting's programme. Internal: the organisation's own staff (HOME
 *   membership), never an invited auditor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabMeetingService {

    public static final String PERM_MEETING = "collab:meeting:manage";
    private static final Set<String> KINDS = Set.of("OPENING", "WALKTHROUGH", "STATUS", "CLOSING", "INTERNAL", "OTHER", "CALL");
    private static final Set<String> PROVIDERS = Set.of("NONE", "TEAMS", "ZOOM", "MEET", "OTHER", "EMBEDDED");
    static final String EMBEDDED = "EMBEDDED";

    private final CollabAccessService             access;
    private final CollabWorkspaceService          workspaceService;
    private final CollabWorkspaceRepository       workspaceRepository;
    private final CollabWorkspaceMemberRepository memberRepository;
    private final CollabProgrammeMemberRepository programmeMemberRepository;
    private final CollabMeetingRepository         meetingRepository;
    private final CollabMeetingAttendeeRepository attendeeRepository;
    private final UserRepository                  userRepository;
    private final NotificationService             notificationService;
    private final ActionItemService               actionItemService;
    private final ObjectMapper                    objectMapper;
    private final LiveKitService                  liveKit;
    private final CollabRoomRepository            roomRepository;
    private final CollabRoomMemberRepository      roomMemberRepository;

    @PersistenceContext
    private EntityManager em;

    // ══════════════════════ VISIBILITY ═══════════════════════════════════════

    /** Null when the caller may not see it. */
    private CollabWorkspace workspaceIfVisible(Caller c, CollabMeeting m) {
        if (m.getWorkspaceId() == null) return null;
        return workspaceRepository.findByIdAndTenantIdAndIsDeletedFalse(m.getWorkspaceId(), c.tenantId())
                .filter(ws -> access.canSee(c, ws))
                .orElse(null);
    }

    boolean canSee(Caller c, CollabMeeting m) {
        if (!m.getTenantId().equals(c.tenantId())) return false;
        if (m.getWorkspaceId() == null) {
            return c.userId().equals(m.getOrganizerId())
                    || attendeeRepository.findByMeetingIdAndUserId(m.getId(), c.userId()).isPresent()
                    || isRoomMember(c, m);
        }
        CollabWorkspace ws = workspaceIfVisible(c, m);
        if (ws == null) return false;
        return m.getProgrammeId() == null || workspaceService.visibleProgrammes(c, ws).stream()
                .anyMatch(p -> p.getId().equals(m.getProgrammeId()));
    }

    /** Members of a standing room see every meeting held in it, even ones they were not invited to. */
    boolean isRoomMember(Caller c, CollabMeeting m) {
        return m.getRoomId() != null && roomMemberRepository.findByRoomIdAndUserId(m.getRoomId(), c.userId()).isPresent();
    }

    boolean canRun(Caller c, CollabMeeting m) {
        if (!canSee(c, m)) return false;
        if (c.userId().equals(m.getOrganizerId())) return true;
        if (m.getWorkspaceId() == null) return c.holds(PERM_MEETING);   // an attendee who manages meetings
        CollabWorkspace ws = workspaceIfVisible(c, m);
        return ws != null && (access.canManage(c, ws) || c.holds(PERM_MEETING));
    }

    CollabMeeting requireVisible(Caller c, Long meetingId) {
        CollabMeeting m = meetingRepository.findByIdAndTenantIdAndIsDeletedFalse(meetingId, c.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("CollabMeeting", meetingId));
        if (!canSee(c, m)) throw new ResourceNotFoundException("CollabMeeting", meetingId);
        return m;
    }

    private CollabMeeting requireRun(Caller c, Long meetingId) {
        CollabMeeting m = requireVisible(c, meetingId);
        if (!canRun(c, m)) {
            throw new BusinessException("COLLAB_MEETING_DENIED",
                    "Only the organiser or someone who manages meetings can change this meeting", HttpStatus.FORBIDDEN);
        }
        return m;
    }

    // ══════════════════════ READ ═════════════════════════════════════════════

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForWorkspace(Long workspaceId) {
        Caller c = access.caller();
        CollabWorkspace ws = access.requireVisible(c, workspaceId);
        List<CollabMeeting> list = meetingRepository.findByWorkspaceIdAndIsDeletedFalseOrderByStartsAtDesc(ws.getId())
                .stream().filter(m -> canSee(c, m)).toList();
        return summaries(c, list);
    }

    /** Meetings I organise or attend, in the active organisation (workspace and internal). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myMeetings(LocalDateTime from, LocalDateTime to) {
        Caller c = access.caller();
        Set<Long> ids = new LinkedHashSet<>();
        meetingRepository.findByTenantIdAndOrganizerIdAndIsDeletedFalse(c.tenantId(), c.userId())
                .forEach(m -> ids.add(m.getId()));
        attendeeRepository.findByTenantIdAndUserId(c.tenantId(), c.userId()).forEach(a -> ids.add(a.getMeetingId()));
        if (ids.isEmpty()) return List.of();
        List<CollabMeeting> list = meetingRepository.findByTenantIdAndIdInAndIsDeletedFalse(c.tenantId(), ids).stream()
                .filter(m -> from == null || !m.getStartsAt().isBefore(from))
                .filter(m -> to == null || m.getStartsAt().isBefore(to))
                .filter(m -> canSee(c, m))
                .sorted(Comparator.comparing(CollabMeeting::getStartsAt))
                .toList();
        return summaries(c, list);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long meetingId) {
        Caller c = access.caller();
        CollabMeeting m = requireVisible(c, meetingId);
        Map<String, Object> out = summaries(c, List.of(m)).get(0);
        out.put("agenda", readList(m.getAgendaJson()));
        out.put("minutes", m.getMinutes());
        out.put("decisions", readList(m.getDecisionsJson()));
        out.put("followUps", followUps(c, m));
        // Every date of a repeating meeting, so its page can move between them.
        if (m.getSeriesRef() != null) {
            out.put("series", meetingRepository.findByTenantIdAndSeriesRefAndIsDeletedFalse(c.tenantId(), m.getSeriesRef()).stream()
                    .sorted(Comparator.comparing(CollabMeeting::getStartsAt))
                    .map(x -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("id", x.getId());
                        e.put("startsAt", x.getStartsAt());
                        e.put("status", x.getStatus());
                        e.put("hasMinutes", x.getMinutes() != null && !x.getMinutes().isBlank());
                        return e;
                    }).toList());
        }
        return out;
    }

    /** What the caller may schedule — drives the "New meeting" buttons. */
    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        Caller c = access.caller();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canSchedule", c.holds(PERM_MEETING));
        out.put("canScheduleInternal", c.holds(PERM_MEETING) && !c.guest());
        out.put("workspaces", access.visibleWorkspaces(c).stream()
                .filter(ws -> CollabWorkspace.ACTIVE.equals(ws.getStatus()))
                .map(ws -> {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("id", ws.getId());
                    w.put("name", ws.getName());
                    w.put("programmes", workspaceService.visibleProgrammes(c, ws).stream().map(p -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("id", p.getId());
                        x.put("name", p.getName());
                        return x;
                    }).toList());
                    return w;
                }).toList());
        return out;
    }

    /** People who can be invited: see the class comment. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> eligibleAttendees(Long workspaceId, Long programmeId) {
        Caller c = access.caller();
        List<Map<String, Object>> out = new ArrayList<>();
        if (workspaceId != null) {
            CollabWorkspace ws = access.requireVisible(c, workspaceId);
            Set<Long> onProgramme = programmeId == null ? null : programmeMemberRepository.findByProgrammeId(programmeId)
                                                                 .stream().map(pm -> pm.getUserId()).collect(Collectors.toSet());
            List<CollabWorkspaceMember> members = memberRepository.findByWorkspaceId(ws.getId());
            Map<Long, User> users = users(members.stream().map(CollabWorkspaceMember::getUserId).collect(Collectors.toSet()));
            for (CollabWorkspaceMember wm : members) {
                if (CollabWorkspaceMember.SIDE_FIRM.equals(wm.getSide()) && onProgramme != null
                        && !onProgramme.contains(wm.getUserId())) continue;
                out.add(person(wm.getUserId(), users.get(wm.getUserId()), wm.getSide()));
            }
        } else {
            if (c.guest()) return List.of();
            List<Object> rows = em.createNativeQuery("""
                    SELECT u.id FROM user_tenant_memberships m JOIN users u ON u.id = m.user_id
                    WHERE m.tenant_id = :t AND m.membership_type = 'HOME' AND m.status = 'ACTIVE'
                      AND (m.access_expires_at IS NULL OR m.access_expires_at > NOW()) AND u.is_deleted = 0
                    """).setParameter("t", c.tenantId()).getResultList();
            Set<Long> ids = rows.stream().map(r -> ((Number) r).longValue()).collect(Collectors.toSet());
            Map<Long, User> users = users(ids);
            ids.forEach(id -> out.add(person(id, users.get(id), CollabWorkspaceMember.SIDE_CLIENT)));
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase()));
        return out;
    }

    // ══════════════════════ WRITE ════════════════════════════════════════════

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Caller c = access.caller();
        access.requirePermission(c, PERM_MEETING);
        return get(createCore(c, body, null, null, null, null, null).getId());
    }

    /**
     * Schedules one meeting. Used by create() and by rooms (CollabCallService):
     *   roomId / roomRef   run it in a standing room, on the room's call
     *   seriesRef          tie the occurrences of a repeating meeting together
     *   message            notification to invitees: null = the usual one, "" = none
     *   status             null = SCHEDULED (a drop-in session is created HELD)
     * Who may be invited is checked here either way; the caller decides who may
     * schedule (create() needs collab:meeting:manage, rooms have their own rule).
     */
    CollabMeeting createCore(Caller c, Map<String, Object> body, Long roomId, String roomRef,
                             String seriesRef, String message, String status) {
        Long wsId = longOrNull(body.get("workspaceId"));
        Long progId = longOrNull(body.get("programmeId"));
        CollabWorkspace ws = null;
        if (wsId != null) {
            ws = access.requireVisible(c, wsId);
            if (!CollabWorkspace.ACTIVE.equals(ws.getStatus())) throw bad("COLLAB_ARCHIVED", "This workspace is archived");
            if (progId != null && workspaceService.visibleProgrammes(c, ws).stream().noneMatch(p -> p.getId().equals(progId))) {
                throw new ResourceNotFoundException("CollabProgramme", progId);
            }
        } else {
            if (c.guest()) {
                throw new BusinessException("COLLAB_INTERNAL_ONLY",
                        "Meetings outside a workspace are for the organisation's own staff", HttpStatus.FORBIDDEN);
            }
            if (progId != null) throw bad("COLLAB_BAD_PROGRAMME", "A programme needs a workspace");
        }

        CollabMeeting m = CollabMeeting.builder()
                .workspaceId(wsId)
                .programmeId(progId)
                .engagementId(longOrNull(body.get("engagementId")))
                .title(required(body.get("title"), "A meeting needs a title"))
                .kind(kind(body.get("kind"), wsId == null))
                .startsAt(dateTime(body.get("startsAt"), true))
                .endsAt(dateTime(body.get("endsAt"), false))
                .provider(provider(body.get("provider")))
                .joinUrl(url(body.get("joinUrl")))
                .organizerId(c.userId())
                .agendaJson(writeList(body.get("agenda")))
                .status(status == null ? CollabMeeting.SCHEDULED : status)
                .roomId(roomId)
                .roomRef(roomRef)
                .seriesRef(seriesRef)
                .build();
        m.setTenantId(c.tenantId());
        m.setCreatedBy(c.userId());
        checkTimes(m);
        checkEmbedded(m);
        meetingRepository.save(m);
        if (EMBEDDED.equals(m.getProvider()) && m.getRoomRef() == null) { m.setRoomRef(roomRef(m)); meetingRepository.save(m); }

        Set<Long> invitees = new LinkedHashSet<>(ids(body.get("attendeeUserIds")));
        invitees.add(c.userId());
        setAttendees(c, ws, m, invitees);
        if (message == null || !message.isEmpty()) {
            boolean call = "CALL".equals(m.getKind()) && message == null;
            notifyAttendees(m, invitees, c.userId(), message != null ? message : call
                                                                                 ? name(userRepository.findById(c.userId()).orElse(null)) + " started a call: \"" + m.getTitle() + "\" — join now"
                                                                                 : "You're invited: \"" + m.getTitle() + "\" on " + when(m), call ? "COLLAB_CALL" : "COLLAB_MEETING");
        }
        log.info("[COLLAB] Meeting scheduled | id={} | workspaceId={} | roomId={} | by={}", m.getId(), wsId, roomId, c.userId());
        return m;
    }

    /** Cancel this occurrence of a repeating meeting and every later one. */
    @Transactional
    public Map<String, Object> cancelSeries(Long meetingId) {
        Caller c = access.caller();
        CollabMeeting m = requireRun(c, meetingId);
        if (m.getSeriesRef() == null) throw bad("COLLAB_NOT_SERIES", "This meeting does not repeat");
        int n = 0;
        for (CollabMeeting x : meetingRepository.findByTenantIdAndSeriesRefAndIsDeletedFalse(c.tenantId(), m.getSeriesRef())) {
            if (x.getStartsAt().isBefore(m.getStartsAt()) || !CollabMeeting.SCHEDULED.equals(x.getStatus())) continue;
            x.setStatus(CollabMeeting.CANCELLED);
            x.setUpdatedBy(c.userId());
            meetingRepository.save(x);
            n++;
        }
        Set<Long> att = attendeeRepository.findByMeetingId(m.getId()).stream().map(CollabMeetingAttendee::getUserId).collect(Collectors.toSet());
        notifyAttendees(m, att, c.userId(), "\"" + m.getTitle() + "\" no longer repeats — " + n + " upcoming occurrence(s) from " + when(m) + " cancelled");
        Map<String, Object> out = get(m.getId());
        out.put("cancelled", n);
        return out;
    }

    @Transactional
    public Map<String, Object> update(Long meetingId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabMeeting m = requireRun(c, meetingId);
        CollabWorkspace ws = workspaceIfVisible(c, m);
        LocalDateTime oldStart = m.getStartsAt();

        if (body.containsKey("title"))    m.setTitle(required(body.get("title"), "A meeting needs a title"));
        if (body.containsKey("kind"))     m.setKind(kind(body.get("kind"), m.getWorkspaceId() == null));
        if (body.containsKey("startsAt")) {
            java.time.LocalDateTime start = dateTime(body.get("startsAt"), true);
            if (!java.util.Objects.equals(start, m.getStartsAt())) m.setReminderSentAt(null);   // moved: remind again
            m.setStartsAt(start);
        }
        if (body.containsKey("endsAt"))   m.setEndsAt(dateTime(body.get("endsAt"), false));
        if (body.containsKey("provider")) m.setProvider(provider(body.get("provider")));
        if (body.containsKey("joinUrl"))  m.setJoinUrl(url(body.get("joinUrl")));
        if (body.containsKey("engagementId")) m.setEngagementId(longOrNull(body.get("engagementId")));
        if (body.containsKey("agenda"))    m.setAgendaJson(writeList(body.get("agenda")));
        boolean minutesNew = body.containsKey("minutes") && (m.getMinutes() == null || m.getMinutes().isBlank())
                && str(body.get("minutes")) != null && !str(body.get("minutes")).isBlank();
        if (body.containsKey("minutes"))   m.setMinutes(str(body.get("minutes")));
        if (body.containsKey("decisions")) m.setDecisionsJson(writeList(body.get("decisions")));
        if (body.containsKey("status")) {
            String s = str(body.get("status"));
            if (!Set.of(CollabMeeting.SCHEDULED, CollabMeeting.HELD, CollabMeeting.CANCELLED).contains(s)) {
                throw bad("COLLAB_BAD_STATUS", "Status must be SCHEDULED, HELD or CANCELLED");
            }
            m.setStatus(s);
        }
        checkTimes(m);
        checkEmbedded(m);
        if (EMBEDDED.equals(m.getProvider()) && m.getRoomRef() == null) m.setRoomRef(roomRef(m));
        m.setUpdatedBy(c.userId());
        meetingRepository.save(m);

        Set<Long> current = attendeeRepository.findByMeetingId(m.getId()).stream()
                .map(CollabMeetingAttendee::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (body.containsKey("attendeeUserIds")) {
            Set<Long> wanted = new LinkedHashSet<>(ids(body.get("attendeeUserIds")));
            wanted.add(m.getOrganizerId());
            Set<Long> added = new LinkedHashSet<>(wanted);
            added.removeAll(current);
            setAttendees(c, ws, m, wanted);
            notifyAttendees(m, added, c.userId(), "You're invited: \"" + m.getTitle() + "\" on " + when(m));
            current = wanted;
        }
        if (body.containsKey("attended") && body.get("attended") instanceof Map<?, ?> att) {
            for (CollabMeetingAttendee a : attendeeRepository.findByMeetingId(m.getId())) {
                Object v = att.get(String.valueOf(a.getUserId()));
                if (v != null) { a.setAttended(Boolean.parseBoolean(v.toString())); attendeeRepository.save(a); }
            }
        }
        if (!Objects.equals(oldStart, m.getStartsAt()) && !CollabMeeting.CANCELLED.equals(m.getStatus())) {
            notifyAttendees(m, current, c.userId(), "\"" + m.getTitle() + "\" moved to " + when(m));
        }
        if (minutesNew) {
            notifyAttendees(m, current, c.userId(), "Minutes are up for \"" + m.getTitle() + "\" (" + when(m) + ")", "COLLAB_MEETING_MINUTES");
        }
        if (body.containsKey("status") && CollabMeeting.CANCELLED.equals(m.getStatus())) {
            notifyAttendees(m, current, c.userId(), "\"" + m.getTitle() + "\" on " + when(m) + " was cancelled");
        }
        return get(m.getId());
    }

    /** A follow-up from the meeting: an action item on it, in the assignee's inbox. */
    @Transactional
    public Map<String, Object> addFollowUp(Long meetingId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabMeeting m = requireRun(c, meetingId);
        Long assignee = longOrNull(body.get("assigneeUserId"));
        if (assignee == null) throw bad("COLLAB_ASSIGNEE_REQUIRED", "Choose who the follow-up is for");
        boolean attendee = attendeeRepository.findByMeetingIdAndUserId(m.getId(), assignee).isPresent();
        boolean eligible = attendee || eligibleAttendees(m.getWorkspaceId(), m.getProgrammeId()).stream()
                .anyMatch(p -> assignee.equals(p.get("userId")));
        if (!eligible) throw bad("COLLAB_ASSIGNEE_NOT_ALLOWED", "This person cannot be given work from this meeting");

        ActionItemRequest req = new ActionItemRequest();
        req.setAssignedTo(assignee);
        req.setSourceType(ActionItem.SourceType.SYSTEM);
        req.setSourceId(m.getId());
        req.setEntityType(ActionItem.EntityType.COLLAB_MEETING);
        req.setEntityId(m.getId());
        if (m.getWorkspaceId() != null) {
            req.setParentEntityType(ActionItem.EntityType.COLLAB_WORKSPACE);
            req.setParentEntityId(m.getWorkspaceId());
        }
        req.setTitle(required(body.get("title"), "A follow-up needs a title"));
        req.setDescription(str(body.get("description")));
        String due = str(body.get("dueDate"));
        if (due != null && !due.isBlank()) req.setDueAt(due.substring(0, Math.min(10, due.length())) + "T23:59:00");
        req.setNavContext(navContext("/collaboration/meetings/" + m.getId()));
        actionItemService.create(req, c.userId(), c.tenantId());
        notifyAttendees(m, List.of(assignee), c.userId(), "Follow-up for you from \"" + m.getTitle() + "\": " + req.getTitle()
                + (req.getDueAt() != null ? " (due " + req.getDueAt().substring(0, 10) + ")" : ""), "COLLAB_MEETING_FOLLOWUP");
        return get(m.getId());
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    private void setAttendees(Caller c, CollabWorkspace ws, CollabMeeting m, Set<Long> userIds) {
        Set<Long> allowed = eligibleAttendees(m.getWorkspaceId(), m.getProgrammeId()).stream()
                .map(p -> (Long) p.get("userId")).collect(Collectors.toSet());
        allowed.add(m.getOrganizerId());
        for (Long id : userIds) {
            if (!allowed.contains(id)) {
                throw bad("COLLAB_ATTENDEE_NOT_ALLOWED", ws != null
                        ? "Attendees must be workspace members (the firm's people: on the meeting's programme)"
                        : "Internal meetings are for the organisation's own staff");
            }
        }
        List<CollabMeetingAttendee> existing = attendeeRepository.findByMeetingId(m.getId());
        for (CollabMeetingAttendee a : existing) if (!userIds.contains(a.getUserId())) attendeeRepository.delete(a);
        Set<Long> have = existing.stream().map(CollabMeetingAttendee::getUserId).collect(Collectors.toSet());
        for (Long id : userIds) {
            if (have.contains(id)) continue;
            CollabMeetingAttendee a = CollabMeetingAttendee.builder().meetingId(m.getId()).userId(id).build();
            a.setTenantId(m.getTenantId());
            attendeeRepository.save(a);
        }
    }

    List<Map<String, Object>> summaries(Caller c, List<CollabMeeting> list) {
        if (list.isEmpty()) return new ArrayList<>();
        Map<Long, List<CollabMeetingAttendee>> att = attendeeRepository
                .findByMeetingIdIn(list.stream().map(CollabMeeting::getId).toList()).stream()
                .collect(Collectors.groupingBy(CollabMeetingAttendee::getMeetingId));
        Set<Long> userIds = new HashSet<>();
        att.values().forEach(l -> l.forEach(a -> userIds.add(a.getUserId())));
        list.forEach(m -> userIds.add(m.getOrganizerId()));
        Map<Long, User> users = users(userIds);
        Map<Long, String> wsNames = new HashMap<>();
        workspaceRepository.findAllById(list.stream().map(CollabMeeting::getWorkspaceId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(w -> wsNames.put(w.getId(), w.getName()));
        Map<Long, String> roomNames = new HashMap<>();
        roomRepository.findAllById(list.stream().map(CollabMeeting::getRoomId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(r -> roomNames.put(r.getId(), r.getName()));
        Map<Long, String> sides = new HashMap<>();
        for (Long wsId : wsNames.keySet()) {
            memberRepository.findByWorkspaceId(wsId).forEach(wm -> sides.putIfAbsent(wm.getUserId(), wm.getSide()));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (CollabMeeting m : list) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m.getId());
            x.put("workspaceId", m.getWorkspaceId());
            x.put("workspaceName", m.getWorkspaceId() == null ? null : wsNames.get(m.getWorkspaceId()));
            x.put("programmeId", m.getProgrammeId());
            x.put("engagementId", m.getEngagementId());
            x.put("title", m.getTitle());
            x.put("kind", m.getKind());
            x.put("startsAt", m.getStartsAt());
            x.put("endsAt", m.getEndsAt());
            x.put("provider", m.getProvider());
            x.put("joinUrl", m.getJoinUrl());
            x.put("inApp", EMBEDDED.equals(m.getProvider()));
            x.put("roomId", m.getRoomId());
            x.put("roomName", m.getRoomId() == null ? null : roomNames.get(m.getRoomId()));
            x.put("repeats", m.getSeriesRef() != null);
            x.put("seriesRef", m.getSeriesRef());
            x.put("seriesRule", m.getSeriesRule());
            x.put("status", m.getStatus());
            x.put("organizerId", m.getOrganizerId());
            x.put("organizerName", name(users.get(m.getOrganizerId())));
            x.put("attendees", att.getOrDefault(m.getId(), List.of()).stream().map(a -> {
                Map<String, Object> p = person(a.getUserId(), users.get(a.getUserId()),
                        sides.getOrDefault(a.getUserId(), CollabWorkspaceMember.SIDE_CLIENT));
                p.put("attended", a.getAttended());
                return p;
            }).toList());
            x.put("canRun", canRun(c, m));
            x.put("hasMinutes", m.getMinutes() != null && !m.getMinutes().isBlank());
            out.add(x);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> followUps(Caller c, CollabMeeting m) {
        List<ActionItem> items = em.createQuery("""
                SELECT a FROM ActionItem a
                WHERE a.tenantId = :t AND a.entityType = :et AND a.entityId = :id
                ORDER BY a.id
                """, ActionItem.class)
                .setParameter("t", m.getTenantId())
                .setParameter("et", ActionItem.EntityType.COLLAB_MEETING)
                .setParameter("id", m.getId())
                .getResultList();
        Map<Long, User> users = users(items.stream().map(ActionItem::getAssignedTo).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return items.stream().map(a -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", a.getId());
            x.put("title", a.getTitle());
            x.put("description", a.getDescription());
            x.put("assigneeUserId", a.getAssignedTo());
            x.put("assigneeName", a.getAssignedTo() == null ? null : name(users.get(a.getAssignedTo())));
            x.put("status", a.getStatus() == null ? null : a.getStatus().name());
            x.put("dueAt", a.getDueAt());
            return x;
        }).toList();
    }

    private void notifyAttendees(CollabMeeting m, Collection<Long> userIds, Long actor, String message) {
        notifyAttendees(m, userIds, actor, message, "COLLAB_MEETING");
    }

    /**
     * Always about the MEETING, so the notification opens its page
     * (CollabNotificationRoutes); COLLAB_CALL / COLLAB_MEETING_STARTING open
     * it with the call joined.
     */
    void notifyAttendees(CollabMeeting m, Collection<Long> userIds, Long actor, String message, String type) {
        for (Long u : userIds) {
            if (u == null || u.equals(actor)) continue;
            try {
                notificationService.send(u, type, message, "COLLAB_MEETING", m.getId());
            } catch (RuntimeException e) {
                log.warn("[COLLAB] Meeting notification failed (non-fatal) | meeting={} | {}", m.getId(), e.getMessage());
            }
        }
    }

    private Map<String, Object> person(Long id, User u, String side) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", id);
        p.put("name", name(u));
        p.put("email", u == null ? null : u.getEmail());
        p.put("side", side);
        return p;
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

    private List<Object> readList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<Object>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String writeList(Object v) {
        if (v == null) return null;
        if (!(v instanceof Collection<?>)) throw bad("COLLAB_BAD_LIST", "Expected a list");
        try {
            String s = objectMapper.writeValueAsString(v);
            if (s.length() > 200_000) throw bad("COLLAB_TOO_LONG", "That is too long to save");
            return s;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw bad("COLLAB_BAD_LIST", "Could not save the list");
        }
    }

    private String navContext(String route) {
        try { return objectMapper.writeValueAsString(Map.of("route", route)); }
        catch (Exception e) { return null; }
    }

    /** An in-app call needs the call server; it has no external link. */
    private void checkEmbedded(CollabMeeting m) {
        if (!EMBEDDED.equals(m.getProvider())) return;
        if (!liveKit.enabled()) {
            throw new BusinessException("COLLAB_CALLS_OFF", "In-app calls are not set up on this server", HttpStatus.SERVICE_UNAVAILABLE);
        }
        m.setJoinUrl(null);
    }

    static String roomRef(CollabMeeting m) { return "kashi-t" + m.getTenantId() + "-m" + m.getId(); }

    private static void checkTimes(CollabMeeting m) {
        if (m.getEndsAt() != null && m.getEndsAt().isBefore(m.getStartsAt())) {
            throw bad("COLLAB_BAD_TIMES", "The meeting ends before it starts");
        }
    }

    private static String when(CollabMeeting m) {
        return m.getStartsAt().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"));
    }

    private static String kind(Object o, boolean internal) {
        String k = o == null || o.toString().isBlank() ? (internal ? "INTERNAL" : "OTHER") : o.toString().trim().toUpperCase();
        if (!KINDS.contains(k)) throw bad("COLLAB_BAD_KIND", "Unknown meeting kind: " + o);
        return k;
    }

    private static String provider(Object o) {
        String p = o == null || o.toString().isBlank() ? "NONE" : o.toString().trim().toUpperCase();
        if (!PROVIDERS.contains(p)) throw bad("COLLAB_BAD_PROVIDER", "Unknown provider: " + o);
        return p;
    }

    /** Only http(s) links — a join link is opened in a new tab. */
    private static String url(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        String u = o.toString().trim();
        if (!u.matches("(?i)^https?://\\S+$") || u.length() > 1000) {
            throw bad("COLLAB_BAD_URL", "The join link must start with https://");
        }
        return u;
    }

    private static LocalDateTime dateTime(Object o, boolean required) {
        if (o == null || o.toString().isBlank()) {
            if (required) throw bad("COLLAB_TIME_REQUIRED", "A meeting needs a start time");
            return null;
        }
        String s = o.toString().trim();
        try {
            if (s.length() == 16) s = s + ":00";
            return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s);
        } catch (RuntimeException e) {
            throw bad("COLLAB_BAD_TIME", "Times must be YYYY-MM-DDTHH:MM: " + o);
        }
    }

    private static List<Long> ids(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof Collection<?> list) {
            for (Object o : list) { Long v = longOrNull(o); if (v != null) out.add(v); }
        }
        return out;
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