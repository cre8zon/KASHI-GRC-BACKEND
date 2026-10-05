package com.kashi.grc.collab.service;

import com.kashi.grc.collab.domain.CollabMeeting;
import com.kashi.grc.collab.domain.CollabMeetingAttendee;
import com.kashi.grc.collab.domain.CollabRoom;
import com.kashi.grc.collab.domain.CollabRoomMember;
import com.kashi.grc.collab.domain.CollabWorkspace;
import com.kashi.grc.collab.repository.CollabMeetingAttendeeRepository;
import com.kashi.grc.collab.repository.CollabMeetingRepository;
import com.kashi.grc.collab.repository.CollabRoomMemberRepository;
import com.kashi.grc.collab.repository.CollabRoomRepository;
import com.kashi.grc.collab.repository.CollabWorkspaceRepository;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-app calls — phase 4. Three ways in, one rule: a join token is issued only
 * to someone the server has just checked may be in that call.
 *
 *   MEETING CALL   a meeting with provider EMBEDDED. Join: its attendees, and
 *                  whoever runs it. Joining marks the caller as attended.
 *   CALL NOW       an instant meeting (kind CALL, provider EMBEDDED, now → +1h)
 *                  with the chosen people, who are notified to join — it keeps
 *                  a record like any meeting (minutes, follow-ups, files).
 *                  Needs collab:meeting:manage.
 *   STANDING ROOM  always open (CollabRoom). Join: its members. Create:
 *                  collab:meeting:manage. Change members / archive: whoever
 *                  created it, or a manager of its workspace. Internal rooms
 *                  are for the organisation's own staff only.
 *
 * Who can be in a room or a call follows the meeting rules
 * (CollabMeetingService.eligibleAttendees): workspace members — the firm's
 * people only where they belong — or, internally, HOME staff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollabCallService {

    private final CollabAccessService             access;
    private final CollabMeetingService            meetingService;
    private final CollabMeetingAttendeeRepository attendeeRepository;
    private final CollabMeetingRepository         meetingRepository;
    private final CollabRoomRepository            roomRepository;
    private final CollabRoomMemberRepository      roomMemberRepository;
    private final CollabWorkspaceRepository       workspaceRepository;
    private final UserRepository                  userRepository;
    private final NotificationService             notificationService;
    private final LiveKitService                  liveKit;

    // ══════════════════════ OPTIONS ══════════════════════════════════════════

    public Map<String, Object> options() {
        Caller c = access.caller();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", liveKit.enabled());
        out.put("canStart", liveKit.enabled() && c.holds(CollabMeetingService.PERM_MEETING));
        return out;
    }

    // ══════════════════════ MEETING CALLS ════════════════════════════════════

    @Transactional
    public Map<String, Object> joinMeeting(Long meetingId) {
        requireEnabled();
        Caller c = access.caller();
        CollabMeeting m = meetingService.requireVisible(c, meetingId);
        if (!CollabMeetingService.EMBEDDED.equals(m.getProvider())) {
            throw bad("COLLAB_NOT_IN_APP", "This meeting uses an outside link, not an in-app call");
        }
        if (CollabMeeting.CANCELLED.equals(m.getStatus())) throw bad("COLLAB_CANCELLED", "This meeting was cancelled");
        CollabMeetingAttendee me = attendeeRepository.findByMeetingIdAndUserId(m.getId(), c.userId()).orElse(null);
        boolean roomMember = meetingService.isRoomMember(c, m);
        if (me == null && !roomMember && !meetingService.canRun(c, m)) {
            throw new BusinessException("COLLAB_NOT_INVITED", "Only people invited to this meeting can join the call", HttpStatus.FORBIDDEN);
        }
        markAttended(m, c.userId());
        String room = m.getRoomRef() != null ? m.getRoomRef() : CollabMeetingService.roomRef(m);
        log.info("[COLLAB] Call join | meeting={} | user={}", m.getId(), c.userId());
        Map<String, Object> out = grant(c, room, m.getTitle());
        out.put("meetingId", m.getId());
        return out;
    }

    /** Start a call with these people now. */
    @Transactional
    public Map<String, Object> callNow(Map<String, Object> body) {
        requireEnabled();
        Caller c = access.caller();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspaceId", body.get("workspaceId"));
        m.put("programmeId", body.get("programmeId"));
        String title = body.get("title") == null ? "" : body.get("title").toString().trim();
        m.put("title", title.isEmpty() ? "Call · " + name(userRepository.findById(c.userId()).orElse(null)) : title);
        m.put("kind", "CALL");
        m.put("provider", CollabMeetingService.EMBEDDED);
        m.put("startsAt", now.toString());
        m.put("endsAt", now.plusHours(1).toString());
        m.put("attendeeUserIds", body.get("attendeeUserIds"));
        Map<String, Object> meeting = meetingService.create(m);    // checks permission and who may be invited
        Long id = ((Number) meeting.get("id")).longValue();
        Map<String, Object> out = new LinkedHashMap<>(joinMeeting(id));
        out.put("meetingId", id);
        return out;
    }

    // ══════════════════════ STANDING ROOMS ═══════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> rooms() {
        Caller c = access.caller();
        Set<Long> mine = roomMemberRepository.findByTenantIdAndUserId(c.tenantId(), c.userId()).stream()
                .map(CollabRoomMember::getRoomId).collect(Collectors.toSet());
        List<CollabRoom> rooms = mine.isEmpty() ? List.of()
                : roomRepository.findByTenantIdAndIdInAndIsDeletedFalse(c.tenantId(), mine).stream()
                  .filter(r -> !r.isArchived())
                  .filter(r -> canSee(c, r))
                  .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                  .toList();
        Map<String, List<Long>> live = liveKit.participants(rooms.stream().map(CollabRoom::getRoomRef).filter(Objects::nonNull).toList());
        List<Map<String, Object>> list = new ArrayList<>();
        Map<Long, List<CollabRoomMember>> members = roomMemberRepository.findByRoomIdIn(rooms.stream().map(CollabRoom::getId).toList())
                .stream().collect(Collectors.groupingBy(CollabRoomMember::getRoomId));
        Set<Long> userIds = new java.util.HashSet<>();
        members.values().forEach(l -> l.forEach(x -> userIds.add(x.getUserId())));
        Map<Long, User> users = new HashMap<>();
        if (!userIds.isEmpty()) userRepository.findAllById(userIds).forEach(u -> users.put(u.getId(), u));
        Map<Long, String> wsNames = new HashMap<>();
        workspaceRepository.findAllById(rooms.stream().map(CollabRoom::getWorkspaceId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(w -> wsNames.put(w.getId(), w.getName()));
        for (CollabRoom r : rooms) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.getId());
            x.put("name", r.getName());
            x.put("description", r.getDescription());
            x.put("workspaceId", r.getWorkspaceId());
            x.put("workspaceName", r.getWorkspaceId() == null ? null : wsNames.get(r.getWorkspaceId()));
            x.put("members", members.getOrDefault(r.getId(), List.of()).stream().map(mm -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("userId", mm.getUserId());
                p.put("name", name(users.get(mm.getUserId())));
                return p;
            }).toList());
            List<Long> in = r.getRoomRef() == null ? null : live.get(r.getRoomRef());
            x.put("live", in == null ? null : in.stream().map(uid -> name(users.containsKey(uid) ? users.get(uid)
                                                                          : userRepository.findById(uid).orElse(null))).toList());
            x.put("canManage", canManage(c, r));
            list.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", liveKit.enabled());
        out.put("canCreate", liveKit.enabled() && c.holds(CollabMeetingService.PERM_MEETING));
        out.put("rooms", list);
        return out;
    }

    @Transactional
    public Map<String, Object> createRoom(Map<String, Object> body) {
        requireEnabled();
        Caller c = access.caller();
        access.requirePermission(c, CollabMeetingService.PERM_MEETING);
        Long wsId = longOrNull(body.get("workspaceId"));
        if (wsId != null) {
            CollabWorkspace ws = access.requireVisible(c, wsId);
            if (!CollabWorkspace.ACTIVE.equals(ws.getStatus())) throw bad("COLLAB_ARCHIVED", "This workspace is archived");
        } else if (c.guest()) {
            throw new BusinessException("COLLAB_INTERNAL_ONLY", "Rooms outside a workspace are for the organisation's own staff", HttpStatus.FORBIDDEN);
        }
        CollabRoom r = CollabRoom.builder()
                .workspaceId(wsId)
                .name(required(body.get("name"), "A room needs a name"))
                .description(trim(body.get("description"), 1000))
                .build();
        r.setTenantId(c.tenantId());
        r.setCreatedBy(c.userId());
        roomRepository.save(r);
        r.setRoomRef("kashi-t" + c.tenantId() + "-r" + r.getId());
        roomRepository.save(r);
        Set<Long> members = new LinkedHashSet<>(ids(body.get("memberUserIds")));
        members.add(c.userId());
        setMembers(c, r, members);
        return roomMap(c, r);
    }

    @Transactional
    public Map<String, Object> updateRoom(Long roomId, Map<String, Object> body) {
        Caller c = access.caller();
        CollabRoom r = requireRoom(c, roomId);
        if (!canManage(c, r)) {
            throw new BusinessException("COLLAB_ROOM_DENIED", "Only whoever created the room, or a workspace owner, can change it", HttpStatus.FORBIDDEN);
        }
        if (body.containsKey("name")) r.setName(required(body.get("name"), "A room needs a name"));
        if (body.containsKey("description")) r.setDescription(trim(body.get("description"), 1000));
        if (body.containsKey("archived")) r.setArchived(Boolean.parseBoolean(String.valueOf(body.get("archived"))));
        r.setUpdatedBy(c.userId());
        roomRepository.save(r);
        if (body.containsKey("memberUserIds")) {
            Set<Long> members = new LinkedHashSet<>(ids(body.get("memberUserIds")));
            members.add(r.getCreatedBy());
            setMembers(c, r, members);
        }
        return roomMap(c, r);
    }

    /**
     * Join a room. Every call in a room leaves a record:
     *   1. a meeting scheduled in the room that is on now (15 min before its
     *      start until its end) — you join that meeting;
     *   2. otherwise a drop-in session already running (people are in the room)
     *      — you join that session;
     *   3. otherwise a new drop-in session is created (status Held, titled
     *      "Room · date"), so minutes, decisions and follow-ups have a home.
     * Either way you are recorded as attending.
     */
    @Transactional
    public Map<String, Object> joinRoom(Long roomId) {
        requireEnabled();
        Caller c = access.caller();
        CollabRoom r = requireRoom(c, roomId);
        if (r.isArchived()) throw bad("COLLAB_ROOM_ARCHIVED", "This room is archived");
        if (roomMemberRepository.findByRoomIdAndUserId(r.getId(), c.userId()).isEmpty()) {
            throw new BusinessException("COLLAB_NOT_MEMBER", "Only the room's members can join it", HttpStatus.FORBIDDEN);
        }
        CollabMeeting session = currentSession(r);
        if (session == null) {
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workspaceId", r.getWorkspaceId());
            body.put("title", r.getName() + " · " + now.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")));
            body.put("kind", "CALL");
            body.put("provider", CollabMeetingService.EMBEDDED);
            body.put("startsAt", now.toString());
            body.put("attendeeUserIds", List.of(c.userId()));
            session = meetingService.createCore(c, body, r.getId(), r.getRoomRef(), null, "", CollabMeeting.HELD);
        }
        markAttended(session, c.userId());
        log.info("[COLLAB] Room join | room={} | session={} | user={}", r.getId(), session.getId(), c.userId());
        Map<String, Object> out = grant(c, r.getRoomRef(), r.getName());
        out.put("meetingId", session.getId());
        return out;
    }

    /** The meeting a join should attach to, or null to start a new drop-in session. */
    private CollabMeeting currentSession(CollabRoom r) {
        LocalDateTime now = LocalDateTime.now();
        List<CollabMeeting> ms = meetingRepository.findByRoomIdAndIsDeletedFalseOrderByStartsAtDesc(r.getId());
        for (CollabMeeting m : ms) {
            if (!CollabMeeting.SCHEDULED.equals(m.getStatus())) continue;
            LocalDateTime end = m.getEndsAt() != null ? m.getEndsAt() : m.getStartsAt().plusHours(2);
            if (!m.getStartsAt().isAfter(now.plusMinutes(15)) && !end.isBefore(now)) return m;
        }
        CollabMeeting lastDropIn = ms.stream()
                .filter(m -> "CALL".equals(m.getKind()) && CollabMeeting.HELD.equals(m.getStatus()) && m.getSeriesRef() == null)
                .filter(m -> m.getStartsAt().isAfter(now.minusHours(12)))
                .findFirst().orElse(null);
        if (lastDropIn == null) return null;
        List<Long> inRoom = liveKit.participants(List.of(r.getRoomRef())).get(r.getRoomRef());
        if (inRoom != null) return inRoom.isEmpty() ? null : lastDropIn;        // people still in it → same session
        return lastDropIn.getStartsAt().isAfter(now.minusHours(2)) ? lastDropIn : null;   // server not asked: assume a recent one is still on
    }

    private void markAttended(CollabMeeting m, Long userId) {
        CollabMeetingAttendee a = attendeeRepository.findByMeetingIdAndUserId(m.getId(), userId).orElse(null);
        if (a == null) {
            a = CollabMeetingAttendee.builder().meetingId(m.getId()).userId(userId).attended(true).build();
            a.setTenantId(m.getTenantId());
            attendeeRepository.save(a);
        } else if (!Boolean.TRUE.equals(a.getAttended())) {
            a.setAttended(true);
            attendeeRepository.save(a);
        }
    }

    /** The room page: the room, what is scheduled in it, and its past sessions. */
    @Transactional(readOnly = true)
    public Map<String, Object> room(Long roomId) {
        Caller c = access.caller();
        CollabRoom r = requireRoom(c, roomId);
        Map<String, Object> out = new LinkedHashMap<>(roomMap(c, r));
        out.put("description", r.getDescription());
        out.put("archived", r.isArchived());
        out.put("enabled", liveKit.enabled());
        out.put("canSchedule", canSchedule(c, r));
        LocalDateTime now = LocalDateTime.now();
        List<CollabMeeting> ms = meetingRepository.findByRoomIdAndIsDeletedFalseOrderByStartsAtDesc(r.getId()).stream()
                .filter(m -> meetingService.canSee(c, m)).toList();
        List<CollabMeeting> upcomingAll = ms.stream()
                .filter(m -> CollabMeeting.SCHEDULED.equals(m.getStatus())
                        && !(m.getEndsAt() != null ? m.getEndsAt() : m.getStartsAt().plusHours(2)).isBefore(now))
                .sorted(java.util.Comparator.comparing(CollabMeeting::getStartsAt)).toList();
        // A repeating meeting is ONE row: its next occurrence, with how many are left.
        Map<String, Long> left = upcomingAll.stream().filter(m -> m.getSeriesRef() != null)
                .collect(Collectors.groupingBy(CollabMeeting::getSeriesRef, Collectors.counting()));
        Set<String> seen = new java.util.HashSet<>();
        List<CollabMeeting> upcoming = upcomingAll.stream()
                .filter(m -> m.getSeriesRef() == null || seen.add(m.getSeriesRef()))
                .limit(30).toList();
        List<CollabMeeting> past = ms.stream().filter(m -> !upcomingAll.contains(m))
                .filter(m -> !CollabMeeting.CANCELLED.equals(m.getStatus()) || m.getStartsAt().isBefore(now))
                .limit(30).toList();
        List<Map<String, Object>> up = meetingService.summaries(c, upcoming);
        up.forEach(x -> { if (x.get("seriesRef") != null) x.put("seriesLeft", left.get((String) x.get("seriesRef"))); });
        out.put("upcoming", up);
        out.put("past", meetingService.summaries(c, past));
        return out;
    }

    /**
     * Schedule a meeting in the room — once, or repeating (DAILY, WEEKDAYS,
     * WEEKLY until a date; at most 120 occurrences). Every occurrence is its
     * own meeting (agenda, minutes, attendance, follow-ups) on the room's call,
     * with the room's members invited. They are told once, not per occurrence.
     */
    @Transactional
    public Map<String, Object> scheduleInRoom(Long roomId, Map<String, Object> body) {
        requireEnabled();
        Caller c = access.caller();
        CollabRoom r = requireRoom(c, roomId);
        if (r.isArchived()) throw bad("COLLAB_ROOM_ARCHIVED", "This room is archived");
        if (!canSchedule(c, r)) {
            throw new BusinessException("COLLAB_ROOM_DENIED", "You cannot schedule meetings in this room", HttpStatus.FORBIDDEN);
        }
        LocalDateTime start = parseTime(body.get("startsAt"), "a start time");
        LocalDateTime end = body.get("endsAt") == null || body.get("endsAt").toString().isBlank() ? null : parseTime(body.get("endsAt"), "an end time");
        if (end != null && end.isBefore(start)) throw bad("COLLAB_BAD_TIMES", "The meeting ends before it starts");
        java.time.Duration length = end == null ? null : java.time.Duration.between(start, end);
        String repeat = body.get("repeat") == null ? "NONE" : body.get("repeat").toString().toUpperCase();
        List<LocalDateTime> starts = new ArrayList<>();
        if ("NONE".equals(repeat)) {
            starts.add(start);
        } else {
            if (!Set.of("DAILY", "WEEKDAYS", "WEEKLY").contains(repeat)) throw bad("COLLAB_BAD_REPEAT", "Repeat must be NONE, DAILY, WEEKDAYS or WEEKLY");
            java.time.LocalDate until;
            try { until = java.time.LocalDate.parse(String.valueOf(body.get("repeatUntil")).substring(0, 10)); }
            catch (RuntimeException e) { throw bad("COLLAB_BAD_DATE", "Choose the date the meeting repeats until"); }
            if (until.isBefore(start.toLocalDate())) throw bad("COLLAB_BAD_DATE", "The repeat end date is before the first meeting");
            for (LocalDateTime t = start; !t.toLocalDate().isAfter(until); t = "WEEKLY".equals(repeat) ? t.plusWeeks(1) : t.plusDays(1)) {
                java.time.DayOfWeek d = t.getDayOfWeek();
                if ("WEEKDAYS".equals(repeat) && (d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY)) continue;
                starts.add(t);
                if (starts.size() > 120) throw bad("COLLAB_TOO_MANY", "At most 120 occurrences — choose an earlier end date");
            }
            if (starts.isEmpty()) throw bad("COLLAB_BAD_DATE", "No meeting falls in that range");
        }
        List<Long> members = roomMemberRepository.findByRoomId(r.getId()).stream().map(CollabRoomMember::getUserId).toList();
        String series = starts.size() > 1 ? java.util.UUID.randomUUID().toString() : null;
        String title = body.get("title") == null || body.get("title").toString().isBlank() ? r.getName() : body.get("title").toString().trim();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");
        String first = name(userRepository.findById(c.userId()).orElse(null)) + " scheduled \"" + title + "\" in the room \"" + r.getName()
                + "\": " + start.format(fmt) + (series == null ? "" : ", repeating " + repeat.toLowerCase() + " (" + starts.size() + " times)");
        Long firstId = null;
        for (int k = 0; k < starts.size(); k++) {
            LocalDateTime t = starts.get(k);
            Map<String, Object> mb = new LinkedHashMap<>();
            mb.put("workspaceId", r.getWorkspaceId());
            mb.put("title", title);
            mb.put("kind", body.getOrDefault("kind", "STATUS"));
            mb.put("provider", CollabMeetingService.EMBEDDED);
            mb.put("startsAt", t.toString());
            if (length != null) mb.put("endsAt", t.plus(length).toString());
            mb.put("agenda", body.get("agenda"));
            mb.put("attendeeUserIds", members);
            CollabMeeting m = meetingService.createCore(c, mb, r.getId(), r.getRoomRef(), series, k == 0 ? first : "", null);
            if (series != null) {
                m.setSeriesRule(repeat + "|" + String.valueOf(body.get("repeatUntil")).substring(0, 10));
                meetingRepository.save(m);
            }
            if (firstId == null) firstId = m.getId();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", starts.size());
        out.put("firstMeetingId", firstId);
        return out;
    }

    private boolean canSchedule(Caller c, CollabRoom r) {
        if (canManage(c, r)) return true;
        return c.holds(CollabMeetingService.PERM_MEETING)
                && roomMemberRepository.findByRoomIdAndUserId(r.getId(), c.userId()).isPresent();
    }

    private static LocalDateTime parseTime(Object o, String what) {
        if (o == null || o.toString().isBlank()) throw bad("COLLAB_TIME_REQUIRED", "Choose " + what);
        String s = o.toString().trim();
        try { return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s.substring(0, Math.min(19, s.length()))); }
        catch (RuntimeException e) { throw bad("COLLAB_BAD_TIME", "Times must be YYYY-MM-DDTHH:MM: " + o); }
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    private Map<String, Object> grant(Caller c, String room, String title) {
        User me = userRepository.findById(c.userId()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", liveKit.url());
        out.put("token", liveKit.joinToken(c.userId(), name(me), room, true));
        out.put("room", room);
        out.put("title", title);
        out.put("expiresIn", liveKit.ttlSeconds());
        return out;
    }

    private boolean canSee(Caller c, CollabRoom r) {
        if (!r.getTenantId().equals(c.tenantId())) return false;
        if (r.getWorkspaceId() == null) return !c.guest();
        return workspaceRepository.findByIdAndTenantIdAndIsDeletedFalse(r.getWorkspaceId(), c.tenantId())
                .filter(ws -> access.canSee(c, ws)).isPresent();
    }

    private boolean canManage(Caller c, CollabRoom r) {
        if (!canSee(c, r)) return false;
        if (c.userId().equals(r.getCreatedBy())) return true;
        return r.getWorkspaceId() != null && workspaceRepository.findByIdAndTenantIdAndIsDeletedFalse(r.getWorkspaceId(), c.tenantId())
                .filter(ws -> access.canManage(c, ws)).isPresent();
    }

    private CollabRoom requireRoom(Caller c, Long roomId) {
        CollabRoom r = roomRepository.findByIdAndTenantIdAndIsDeletedFalse(roomId, c.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("CollabRoom", roomId));
        if (!canSee(c, r) || (roomMemberRepository.findByRoomIdAndUserId(r.getId(), c.userId()).isEmpty() && !canManage(c, r))) {
            throw new ResourceNotFoundException("CollabRoom", roomId);
        }
        return r;
    }

    private void setMembers(Caller c, CollabRoom r, Set<Long> userIds) {
        Set<Long> allowed = meetingService.eligibleAttendees(r.getWorkspaceId(), null).stream()
                .map(p -> (Long) p.get("userId")).collect(Collectors.toSet());
        allowed.add(r.getCreatedBy());
        for (Long id : userIds) {
            if (!allowed.contains(id)) {
                throw bad("COLLAB_ROOM_MEMBER_NOT_ALLOWED", r.getWorkspaceId() != null
                        ? "Room members must be members of the workspace" : "Internal rooms are for the organisation's own staff");
            }
        }
        List<CollabRoomMember> existing = roomMemberRepository.findByRoomId(r.getId());
        for (CollabRoomMember m : existing) if (!userIds.contains(m.getUserId())) roomMemberRepository.delete(m);
        Set<Long> have = existing.stream().map(CollabRoomMember::getUserId).collect(Collectors.toSet());
        for (Long id : userIds) {
            if (have.contains(id)) continue;
            CollabRoomMember m = CollabRoomMember.builder().roomId(r.getId()).userId(id).build();
            m.setTenantId(r.getTenantId());
            roomMemberRepository.save(m);
            if (!id.equals(c.userId())) {
                try {
                    notificationService.send(id, "COLLAB_ROOM", "You were added to the call room \"" + r.getName() + "\"",
                            "COLLAB_ROOM", r.getId());
                } catch (RuntimeException e) {
                    log.warn("[COLLAB] Room notification failed (non-fatal) | {}", e.getMessage());
                }
            }
        }
    }

    /** The room as the list shows it (archived or no longer a member: just its id and name). */
    private Map<String, Object> roomMap(Caller c, CollabRoom r) {
        Object list = rooms().get("rooms");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> x = cast(o);
                if (r.getId().equals(x.get("id"))) return x;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("name", r.getName());
        out.put("archived", r.isArchived());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    private void requireEnabled() {
        if (!liveKit.enabled()) {
            throw new BusinessException("COLLAB_CALLS_OFF", "In-app calls are not set up on this server", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static String name(User u) {
        if (u == null) return "Unknown user";
        String n = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static List<Long> ids(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof java.util.Collection<?> list) for (Object o : list) { Long v = longOrNull(o); if (v != null) out.add(v); }
        return out;
    }

    private static Long longOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return Long.parseLong(o.toString().trim()); }
        catch (NumberFormatException e) { throw bad("COLLAB_BAD_ID", "Not an id: " + o); }
    }

    private static String required(Object o, String msg) {
        String s = o == null ? null : o.toString().trim();
        if (s == null || s.isEmpty()) throw bad("COLLAB_REQUIRED", msg);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String trim(Object o, int max) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static BusinessException bad(String code, String msg) { return new BusinessException(code, msg); }
}