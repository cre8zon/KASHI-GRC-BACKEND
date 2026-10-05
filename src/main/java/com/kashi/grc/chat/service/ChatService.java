package com.kashi.grc.chat.service;

import com.kashi.grc.chat.domain.ChatConversation;
import com.kashi.grc.chat.domain.ChatMember;
import com.kashi.grc.chat.domain.ChatMessage;
import com.kashi.grc.chat.repository.ChatConversationRepository;
import com.kashi.grc.chat.repository.ChatMemberRepository;
import com.kashi.grc.chat.repository.ChatMessageRepository;
import com.kashi.grc.collab.service.CollabAccessService;
import com.kashi.grc.collab.service.CollabAccessService.Caller;
import com.kashi.grc.collab.service.CollabMeetingService;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.notification.service.NotificationService;
import com.kashi.grc.usermanagement.domain.User;
import com.kashi.grc.usermanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal chat — direct messages, groups and channels for the organisation's
 * own staff.
 *
 * ── WHO ───────────────────────────────────────────────────────────────────────
 *   Use chat: permission chat:use AND a HOME membership in the active
 *   organisation. Invited auditors (GUEST) never see it — collaboration with
 *   them happens in workspaces.
 *   Read / post in a conversation: its members. A PUBLIC channel can be read
 *   and joined by any staff member; a PRIVATE one only by invitation.
 *   Manage a channel (rename, describe, add / remove people, archive): its
 *   owners. In a group anyone in it may add people or rename it. A direct
 *   conversation has no settings.
 *   Edit a message: whoever sent it. Delete: the sender, or a channel owner.
 *
 * ── LIVE UPDATES WITHOUT LEAKING CONTENT ──────────────────────────────────────
 * The STOMP broker does not check who subscribes to a topic, so nothing
 * readable is ever pushed. Each person gets a topic whose name is an HMAC of
 * their user id under a secret made at start-up (/topic/chat/{48 hex}); the
 * push says only "conversation N changed", and the browser fetches through
 * the authorised API. The secret is per process, so topics change on
 * restart — the client asks /v1/chat/me again when it reconnects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final String PERM_USE = "chat:use";
    private static final int MAX_BODY = 8000;
    private static final byte[] PUSH_SECRET = new byte[32];
    static { new SecureRandom().nextBytes(PUSH_SECRET); }

    private final CollabAccessService        access;
    private final CollabMeetingService       meetingService;
    private final ChatConversationRepository conversationRepository;
    private final ChatMemberRepository       memberRepository;
    private final ChatMessageRepository      messageRepository;
    private final UserRepository             userRepository;
    private final NotificationService        notificationService;
    private final SimpMessagingTemplate      messaging;

    // ══════════════════════ ACCESS ═══════════════════════════════════════════

    private Caller requireChat() {
        Caller c = access.caller();
        if (c.guest()) {
            throw new BusinessException("CHAT_INTERNAL_ONLY", "Chat is for the organisation's own staff", HttpStatus.FORBIDDEN);
        }
        if (!c.holds(PERM_USE)) {
            throw new BusinessException("CHAT_PERMISSION_DENIED", "You do not have permission to use chat (" + PERM_USE + ")", HttpStatus.FORBIDDEN);
        }
        return c;
    }

    private ChatConversation requireConversation(Caller c, Long id) {
        return conversationRepository.findByIdAndTenantIdAndIsDeletedFalse(id, c.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("ChatConversation", id));
    }

    /** Member, or — for reading only — anyone on a public channel. 404 otherwise. */
    private ChatMember requireReader(Caller c, ChatConversation conv) {
        ChatMember me = memberRepository.findByConversationIdAndUserId(conv.getId(), c.userId()).orElse(null);
        if (me == null && !isPublicChannel(conv)) throw new ResourceNotFoundException("ChatConversation", conv.getId());
        return me;
    }

    private ChatMember requireMember(Caller c, ChatConversation conv) {
        return memberRepository.findByConversationIdAndUserId(conv.getId(), c.userId())
                .orElseThrow(() -> isPublicChannel(conv)
                        ? new BusinessException("CHAT_JOIN_FIRST", "Join the channel to post in it", HttpStatus.FORBIDDEN)
                        : new ResourceNotFoundException("ChatConversation", conv.getId()));
    }

    private boolean canManage(ChatConversation conv, ChatMember me) {
        if (me == null || ChatConversation.DIRECT.equals(conv.getKind())) return false;
        return ChatConversation.GROUP.equals(conv.getKind()) || ChatMember.OWNER.equals(me.getRole());
    }

    private static boolean isPublicChannel(ChatConversation conv) {
        return ChatConversation.CHANNEL.equals(conv.getKind()) && ChatConversation.PUBLIC.equals(conv.getVisibility());
    }

    /** Staff who can be in a conversation: HOME members of the organisation. */
    private Map<Long, Map<String, Object>> staff() {
        return meetingService.eligibleAttendees(null, null).stream()
                .collect(Collectors.toMap(p -> (Long) p.get("userId"), p -> p, (a, b) -> a, LinkedHashMap::new));
    }

    // ══════════════════════ READ ═════════════════════════════════════════════

    public Map<String, Object> me() {
        Caller c = access.caller();
        boolean can = !c.guest() && c.holds(PERM_USE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", c.userId());
        out.put("canUse", can);
        out.put("pushTopic", can ? topic(c.userId()) : null);
        return out;
    }

    public List<Map<String, Object>> people() {
        requireChat();
        return new ArrayList<>(staff().values());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> conversations() {
        Caller c = requireChat();
        Map<Long, ChatMember> mine = memberRepository.findByTenantIdAndUserId(c.tenantId(), c.userId()).stream()
                .collect(Collectors.toMap(ChatMember::getConversationId, m -> m, (a, b) -> a));
        if (mine.isEmpty()) return List.of();
        List<ChatConversation> convs = conversationRepository.findByTenantIdAndIdInAndIsDeletedFalse(c.tenantId(), mine.keySet());
        List<Long> ids = convs.stream().map(ChatConversation::getId).toList();
        Map<Long, Long> unread = new HashMap<>();
        for (Object[] r : messageRepository.unreadCounts(c.userId(), ids)) unread.put((Long) r[0], ((Number) r[1]).longValue());
        Map<Long, ChatMessage> last = messageRepository.latest(ids).stream()
                .collect(Collectors.toMap(ChatMessage::getConversationId, m -> m, (a, b) -> a));
        Map<Long, List<ChatMember>> members = memberRepository.findByConversationIdIn(ids).stream()
                .collect(Collectors.groupingBy(ChatMember::getConversationId));
        Set<Long> userIds = new HashSet<>();
        members.values().forEach(l -> l.stream().limit(60).forEach(m -> userIds.add(m.getUserId())));
        last.values().forEach(m -> userIds.add(m.getSenderId()));
        Map<Long, User> users = users(userIds);

        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatConversation conv : convs) {
            Map<String, Object> x = summary(c, conv, mine.get(conv.getId()), members.getOrDefault(conv.getId(), List.of()), users);
            x.put("unread", mine.get(conv.getId()).isMuted() ? 0 : unread.getOrDefault(conv.getId(), 0L));
            ChatMessage lm = last.get(conv.getId());
            if (lm != null) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("senderName", lm.getSenderId().equals(c.userId()) ? "You" : firstName(users.get(lm.getSenderId())));
                l.put("text", lm.isDeleted() ? "Message deleted" : preview(lm.getBody()));
                l.put("at", lm.getCreatedAt());
                x.put("lastMessage", l);
            }
            out.add(x);
        }
        out.sort(Comparator.comparing((Map<String, Object> x) -> String.valueOf(x.get("sortAt"))).reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conversation(Long id) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, id);
        ChatMember me = requireReader(c, conv);
        List<ChatMember> members = memberRepository.findByConversationId(conv.getId());
        return summary(c, conv, me, members, users(members.stream().map(ChatMember::getUserId).collect(Collectors.toSet())));
    }

    /** Public channels I am not in yet. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> browse() {
        Caller c = requireChat();
        Set<Long> mine = memberRepository.findByTenantIdAndUserId(c.tenantId(), c.userId()).stream()
                .map(ChatMember::getConversationId).collect(Collectors.toSet());
        List<ChatConversation> chans = conversationRepository
                .findByTenantIdAndKindAndVisibilityAndArchivedFalseAndIsDeletedFalseOrderByNameAsc(c.tenantId(), ChatConversation.CHANNEL, ChatConversation.PUBLIC)
                .stream().filter(ch -> !mine.contains(ch.getId())).toList();
        Map<Long, Long> counts = memberRepository.findByConversationIdIn(chans.stream().map(ChatConversation::getId).toList()).stream()
                .collect(Collectors.groupingBy(ChatMember::getConversationId, Collectors.counting()));
        return chans.stream().map(ch -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", ch.getId());
            x.put("name", ch.getName());
            x.put("description", ch.getDescription());
            x.put("memberCount", counts.getOrDefault(ch.getId(), 0L));
            return x;
        }).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> messages(Long conversationId, Long before, int limit) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, conversationId);
        ChatMember me = requireReader(c, conv);
        int n = Math.max(1, Math.min(limit, 100));
        List<ChatMessage> page = new ArrayList<>(messageRepository.page(conv.getId(), before == null ? Long.MAX_VALUE : before, PageRequest.of(0, n + 1)));
        boolean more = page.size() > n;
        if (more) page = page.subList(0, n);
        java.util.Collections.reverse(page);
        Map<Long, User> users = users(page.stream().map(ChatMessage::getSenderId).collect(Collectors.toSet()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", page.stream().map(m -> messageMap(c, m, users)).toList());
        out.put("hasMore", more);
        out.put("lastReadMessageId", me == null ? null : me.getLastReadMessageId());
        return out;
    }

    /** Unread messages across my conversations (not muted) — the sidebar badge. */
    @Transactional(readOnly = true)
    public Map<String, Object> unread() {
        Caller c = access.caller();
        long total = 0;
        if (!c.guest() && c.holds(PERM_USE)) {
            List<ChatMember> mine = memberRepository.findByTenantIdAndUserId(c.tenantId(), c.userId());
            Set<Long> muted = mine.stream().filter(ChatMember::isMuted).map(ChatMember::getConversationId).collect(Collectors.toSet());
            List<Long> ids = mine.stream().map(ChatMember::getConversationId).toList();
            if (!ids.isEmpty()) {
                for (Object[] r : messageRepository.unreadCounts(c.userId(), ids)) {
                    if (!muted.contains((Long) r[0])) total += ((Number) r[1]).longValue();
                }
            }
        }
        return Map.of("total", total);
    }

    // ══════════════════════ CONVERSATIONS ════════════════════════════════════

    /**
     * { kind: DIRECT, userId }                       get or open a direct conversation
     * { kind: GROUP, memberUserIds, name? }
     * { kind: CHANNEL, name, description?, visibility: PUBLIC|PRIVATE, memberUserIds? }
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Caller c = requireChat();
        String kind = str(body.get("kind")) == null ? "" : str(body.get("kind")).toUpperCase();
        Map<Long, Map<String, Object>> staff = staff();
        switch (kind) {
            case ChatConversation.DIRECT -> {
                Long other = longOrNull(body.get("userId"));
                if (other == null || (!other.equals(c.userId()) && !staff.containsKey(other))) {
                    throw bad("CHAT_NOT_STAFF", "Direct messages are with the organisation's own staff");
                }
                String key = Math.min(other, c.userId()) + ":" + Math.max(other, c.userId());
                ChatConversation existing = conversationRepository.findFirstByTenantIdAndDirectKeyAndIsDeletedFalse(c.tenantId(), key).orElse(null);
                if (existing != null) return conversation(existing.getId());
                ChatConversation conv = newConversation(c, ChatConversation.DIRECT, null, null, null);
                conv.setDirectKey(key);
                conversationRepository.save(conv);
                addMember(conv, c.userId(), ChatMember.MEMBER);
                if (!other.equals(c.userId())) addMember(conv, other, ChatMember.MEMBER);
                return conversation(conv.getId());
            }
            case ChatConversation.GROUP -> {
                Set<Long> ids = checkStaff(staff, ids(body.get("memberUserIds")));
                ids.remove(c.userId());
                if (ids.isEmpty()) throw bad("CHAT_NO_MEMBERS", "Add at least one person");
                if (ids.size() > 49) throw bad("CHAT_TOO_MANY", "A group has at most 50 people — make a channel instead");
                ChatConversation conv = newConversation(c, ChatConversation.GROUP, trimOrNull(body.get("name"), 120), null, null);
                conversationRepository.save(conv);
                addMember(conv, c.userId(), ChatMember.OWNER);
                ids.forEach(id -> addMember(conv, id, ChatMember.MEMBER));
                ping(conv.getId(), ids);
                notifyAdded(c, conv, ids);
                return conversation(conv.getId());
            }
            case ChatConversation.CHANNEL -> {
                String name = channelName(c, body.get("name"), null);
                String vis = ChatConversation.PRIVATE.equalsIgnoreCase(str(body.get("visibility"))) ? ChatConversation.PRIVATE : ChatConversation.PUBLIC;
                Set<Long> ids = checkStaff(staff, ids(body.get("memberUserIds")));
                ids.remove(c.userId());
                ChatConversation conv = newConversation(c, ChatConversation.CHANNEL, name, trimOrNull(body.get("description"), 500), vis);
                conversationRepository.save(conv);
                addMember(conv, c.userId(), ChatMember.OWNER);
                ids.forEach(id -> addMember(conv, id, ChatMember.MEMBER));
                ping(conv.getId(), ids);
                notifyAdded(c, conv, ids);
                return conversation(conv.getId());
            }
            default -> throw bad("CHAT_BAD_KIND", "kind must be DIRECT, GROUP or CHANNEL");
        }
    }

    /** { name?, description?, visibility?, archived?, muted? } — muted is personal, the rest need manage rights. */
    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, id);
        ChatMember me = requireMember(c, conv);
        if (body.containsKey("muted")) {
            me.setMuted(Boolean.parseBoolean(String.valueOf(body.get("muted"))));
            memberRepository.save(me);
        }
        boolean settings = body.keySet().stream().anyMatch(k -> !"muted".equals(k));
        if (settings) {
            if (!canManage(conv, me)) throw denied("Only the channel's owners can change it");
            if (body.containsKey("name")) {
                conv.setName(ChatConversation.CHANNEL.equals(conv.getKind()) ? channelName(c, body.get("name"), conv.getId()) : trimOrNull(body.get("name"), 120));
            }
            if (body.containsKey("description")) conv.setDescription(trimOrNull(body.get("description"), 500));
            if (body.containsKey("visibility") && ChatConversation.CHANNEL.equals(conv.getKind())) {
                conv.setVisibility(ChatConversation.PRIVATE.equalsIgnoreCase(str(body.get("visibility"))) ? ChatConversation.PRIVATE : ChatConversation.PUBLIC);
            }
            if (body.containsKey("archived")) conv.setArchived(Boolean.parseBoolean(String.valueOf(body.get("archived"))));
            conv.setUpdatedBy(c.userId());
            conversationRepository.save(conv);
            ping(conv.getId(), memberIds(conv.getId()));
        }
        return conversation(conv.getId());
    }

    @Transactional
    public Map<String, Object> addMembers(Long id, Object rawIds) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, id);
        ChatMember me = requireMember(c, conv);
        if (!canManage(conv, me)) throw denied(ChatConversation.DIRECT.equals(conv.getKind())
                ? "Start a group to talk with more people" : "Only the channel's owners can add people");
        Set<Long> ids = checkStaff(staff(), ids(rawIds));
        Set<Long> have = memberIds(conv.getId());
        ids.removeAll(have);
        if (have.size() + ids.size() > 50 && ChatConversation.GROUP.equals(conv.getKind())) {
            throw bad("CHAT_TOO_MANY", "A group has at most 50 people — make a channel instead");
        }
        ids.forEach(uid -> addMember(conv, uid, ChatMember.MEMBER));
        ping(conv.getId(), memberIds(conv.getId()));
        notifyAdded(c, conv, ids);
        return conversation(conv.getId());
    }

    /** Remove someone (owners), or leave (yourself). */
    @Transactional
    public void removeMember(Long id, Long userId) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, id);
        ChatMember me = requireMember(c, conv);
        if (ChatConversation.DIRECT.equals(conv.getKind())) throw bad("CHAT_DIRECT", "You cannot leave a direct conversation — mute it instead");
        boolean self = c.userId().equals(userId);
        if (!self && !(ChatConversation.CHANNEL.equals(conv.getKind()) && ChatMember.OWNER.equals(me.getRole()))) {
            throw denied("Only the channel's owners can remove people");
        }
        ChatMember target = memberRepository.findByConversationIdAndUserId(conv.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("ChatMember", userId));
        Set<Long> before = memberIds(conv.getId());
        memberRepository.delete(target);
        // Never leave a channel without an owner: hand it to the longest-standing member.
        if (ChatMember.OWNER.equals(target.getRole())) {
            List<ChatMember> rest = memberRepository.findByConversationId(conv.getId());
            if (rest.stream().noneMatch(m -> ChatMember.OWNER.equals(m.getRole())) && !rest.isEmpty()) {
                ChatMember heir = rest.stream().min(Comparator.comparing(ChatMember::getId)).get();
                heir.setRole(ChatMember.OWNER);
                memberRepository.save(heir);
            }
        }
        ping(conv.getId(), before);
    }

    /** Join a public channel. */
    @Transactional
    public Map<String, Object> join(Long id) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, id);
        if (!isPublicChannel(conv) || conv.isArchived()) throw new ResourceNotFoundException("ChatConversation", id);
        if (memberRepository.findByConversationIdAndUserId(conv.getId(), c.userId()).isEmpty()) {
            ChatMember m = addMember(conv, c.userId(), ChatMember.MEMBER);
            m.setLastReadMessageId(messageRepository.maxId(conv.getId()));
            memberRepository.save(m);
        }
        return conversation(conv.getId());
    }

    // ══════════════════════ MESSAGES ═════════════════════════════════════════

    @Transactional
    public Map<String, Object> send(Long conversationId, Map<String, Object> body) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, conversationId);
        ChatMember me = requireMember(c, conv);
        if (conv.isArchived()) throw bad("CHAT_ARCHIVED", "This conversation is archived");
        String text = cleanBody(body.get("body"));
        List<ChatMember> memberRows = memberRepository.findByConversationId(conv.getId());
        Set<Long> members = memberRows.stream().map(ChatMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Long previousLatest = messageRepository.maxId(conv.getId());
        Set<Long> mentions = ids(body.get("mentions")).stream().filter(members::contains)
                .filter(id -> !id.equals(c.userId())).collect(Collectors.toCollection(LinkedHashSet::new));

        ChatMessage m = ChatMessage.builder()
                .conversationId(conv.getId())
                .senderId(c.userId())
                .body(text)
                .mentions(mentions.isEmpty() ? null : mentions.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .build();
        m.setTenantId(c.tenantId());
        messageRepository.save(m);
        conv.setLastMessageAt(LocalDateTime.now());
        conversationRepository.save(conv);
        me.setLastReadMessageId(m.getId());
        memberRepository.save(me);
        ping(conv.getId(), members);

        if (!mentions.isEmpty()) {
            String who = name(userRepository.findById(c.userId()).orElse(null));
            String where = ChatConversation.CHANNEL.equals(conv.getKind()) ? "#" + conv.getName() : "a conversation";
            for (Long uid : mentions) {
                try {
                    notificationService.send(uid, "CHAT_MENTION", who + " mentioned you in " + where + ": " + preview(text),
                            "CHAT_CONVERSATION", conv.getId());
                } catch (RuntimeException e) {
                    log.warn("[CHAT] Mention notification failed (non-fatal) | {}", e.getMessage());
                }
            }
        }
        notifyMessage(c, conv, memberRows, mentions, previousLatest, text);
        return messageMap(c, m, users(Set.of(c.userId())));
    }

    @Transactional
    public Map<String, Object> edit(Long messageId, Map<String, Object> body) {
        Caller c = requireChat();
        ChatMessage m = requireMessage(c, messageId);
        if (!m.getSenderId().equals(c.userId())) throw denied("You can only edit your own messages");
        if (m.isDeleted()) throw bad("CHAT_DELETED", "This message was deleted");
        m.setBody(cleanBody(body.get("body")));
        m.setEditedAt(LocalDateTime.now());
        messageRepository.save(m);
        ping(m.getConversationId(), memberIds(m.getConversationId()));
        return messageMap(c, m, users(Set.of(c.userId())));
    }

    @Transactional
    public void delete(Long messageId) {
        Caller c = requireChat();
        ChatMessage m = requireMessage(c, messageId);
        ChatConversation conv = requireConversation(c, m.getConversationId());
        ChatMember me = requireMember(c, conv);
        boolean owner = ChatConversation.CHANNEL.equals(conv.getKind()) && ChatMember.OWNER.equals(me.getRole());
        if (!m.getSenderId().equals(c.userId()) && !owner) throw denied("You can only delete your own messages");
        m.setDeleted(true);
        m.setBody(null);
        m.setMentions(null);
        messageRepository.save(m);
        ping(conv.getId(), memberIds(conv.getId()));
    }

    @Transactional
    public void read(Long conversationId, Long messageId) {
        Caller c = requireChat();
        ChatConversation conv = requireConversation(c, conversationId);
        ChatMember me = memberRepository.findByConversationIdAndUserId(conv.getId(), c.userId()).orElse(null);
        if (me == null) return;
        Long upTo = messageId != null ? messageId : messageRepository.maxId(conv.getId());
        if (upTo != null && (me.getLastReadMessageId() == null || upTo > me.getLastReadMessageId())) {
            me.setLastReadMessageId(upTo);
            memberRepository.save(me);
            ping(conv.getId(), Set.of(c.userId()));    // my other tabs clear the badge too
        }
    }

    // ══════════════════════ HELPERS ══════════════════════════════════════════

    private ChatMessage requireMessage(Caller c, Long id) {
        ChatMessage m = messageRepository.findById(id).filter(x -> x.getTenantId().equals(c.tenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("ChatMessage", id));
        requireMember(c, requireConversation(c, m.getConversationId()));
        return m;
    }

    private ChatConversation newConversation(Caller c, String kind, String name, String description, String visibility) {
        ChatConversation conv = ChatConversation.builder().kind(kind).name(name).description(description).visibility(visibility).build();
        conv.setTenantId(c.tenantId());
        conv.setCreatedBy(c.userId());
        return conv;
    }

    private ChatMember addMember(ChatConversation conv, Long userId, String role) {
        ChatMember m = ChatMember.builder().conversationId(conv.getId()).userId(userId).role(role).build();
        m.setTenantId(conv.getTenantId());
        return memberRepository.save(m);
    }

    private Set<Long> memberIds(Long conversationId) {
        return memberRepository.findByConversationId(conversationId).stream().map(ChatMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> checkStaff(Map<Long, Map<String, Object>> staff, Collection<Long> ids) {
        Set<Long> out = new LinkedHashSet<>(ids);
        for (Long id : out) if (!staff.containsKey(id)) throw bad("CHAT_NOT_STAFF", "Chat is with the organisation's own staff only");
        return out;
    }

    private String channelName(Caller c, Object raw, Long selfId) {
        String n = trimOrNull(raw, 80);
        if (n == null) throw bad("CHAT_NAME_REQUIRED", "A channel needs a name");
        n = n.replaceFirst("^#", "").trim();
        boolean taken = conversationRepository.findByTenantIdAndKindAndNameIgnoreCaseAndIsDeletedFalse(c.tenantId(), ChatConversation.CHANNEL, n)
                .stream().anyMatch(x -> !Objects.equals(x.getId(), selfId));
        if (taken) throw bad("CHAT_NAME_TAKEN", "There is already a channel called #" + n);
        return n;
    }

    private Map<String, Object> summary(Caller c, ChatConversation conv, ChatMember me, List<ChatMember> members, Map<Long, User> users) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", conv.getId());
        x.put("kind", conv.getKind());
        x.put("visibility", conv.getVisibility());
        x.put("description", conv.getDescription());
        x.put("archived", conv.isArchived());
        x.put("memberCount", members.size());
        x.put("members", members.stream().limit(60).map(m -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("userId", m.getUserId());
            p.put("name", name(users.containsKey(m.getUserId()) ? users.get(m.getUserId()) : userRepository.findById(m.getUserId()).orElse(null)));
            p.put("role", m.getRole());
            return p;
        }).toList());
        String display = conv.getName();
        if (ChatConversation.DIRECT.equals(conv.getKind())) {
            Long other = members.stream().map(ChatMember::getUserId).filter(id -> !id.equals(c.userId())).findFirst().orElse(c.userId());
            display = name(users.containsKey(other) ? users.get(other) : userRepository.findById(other).orElse(null))
                    + (other.equals(c.userId()) ? " (you)" : "");
            x.put("otherUserId", other);
        } else if (ChatConversation.GROUP.equals(conv.getKind()) && (display == null || display.isBlank())) {
            display = members.stream().filter(m -> !m.getUserId().equals(c.userId())).limit(4)
                    .map(m -> firstName(users.get(m.getUserId()))).collect(Collectors.joining(", "))
                    + (members.size() > 5 ? " +" + (members.size() - 5) : "");
        }
        x.put("name", display);
        x.put("member", me != null);
        x.put("myRole", me == null ? null : me.getRole());
        x.put("muted", me != null && me.isMuted());
        x.put("canManage", canManage(conv, me));
        x.put("lastReadMessageId", me == null ? null : me.getLastReadMessageId());
        LocalDateTime sortAt = conv.getLastMessageAt() != null ? conv.getLastMessageAt() : conv.getCreatedAt();
        x.put("sortAt", sortAt == null ? "" : sortAt.toString());
        return x;
    }

    private Map<String, Object> messageMap(Caller c, ChatMessage m, Map<Long, User> users) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", m.getId());
        x.put("conversationId", m.getConversationId());
        x.put("senderId", m.getSenderId());
        x.put("senderName", name(users.get(m.getSenderId())));
        x.put("body", m.isDeleted() ? null : m.getBody());
        x.put("deleted", m.isDeleted());
        x.put("mentions", m.getMentions() == null ? List.of() : List.of(m.getMentions().split(",")).stream().map(Long::valueOf).toList());
        x.put("editedAt", m.getEditedAt());
        x.put("createdAt", m.getCreatedAt());
        x.put("mine", m.getSenderId().equals(c.userId()));
        return x;
    }

    /**
     * Direct messages and groups: a notification for the first new message
     * since the person last caught up — not one per message, so a burst of
     * messages is a single bell entry. Skipped for people who muted the
     * conversation and for people already notified by a mention. Channels
     * notify only on mentions (they are too busy for anything else).
     */
    private void notifyMessage(Caller c, ChatConversation conv, List<ChatMember> memberRows, Set<Long> mentions,
                               Long previousLatest, String text) {
        if (ChatConversation.CHANNEL.equals(conv.getKind())) return;
        String who = null;
        for (ChatMember cm : memberRows) {
            Long uid = cm.getUserId();
            if (uid.equals(c.userId()) || cm.isMuted() || mentions.contains(uid)) continue;
            boolean caughtUp = previousLatest == null
                    || (cm.getLastReadMessageId() != null && cm.getLastReadMessageId() >= previousLatest);
            if (!caughtUp) continue;
            if (who == null) who = name(userRepository.findById(c.userId()).orElse(null));
            String head = ChatConversation.GROUP.equals(conv.getKind())
                    ? who + " in " + (conv.getName() == null || conv.getName().isBlank() ? "your group" : conv.getName())
                    : who;
            try {
                notificationService.send(uid, "CHAT_MESSAGE", head + ": " + preview(text), "CHAT_CONVERSATION", conv.getId());
            } catch (RuntimeException e) {
                log.warn("[CHAT] Message notification failed (non-fatal) | {}", e.getMessage());
            }
        }
    }

    /** "Priya added you to #design" / "… to a group chat". */
    private void notifyAdded(Caller c, ChatConversation conv, Collection<Long> userIds) {
        if (userIds.isEmpty()) return;
        String who = name(userRepository.findById(c.userId()).orElse(null));
        String what = ChatConversation.CHANNEL.equals(conv.getKind()) ? "the channel #" + conv.getName()
                : conv.getName() == null || conv.getName().isBlank() ? "a group chat" : "the group \"" + conv.getName() + "\"";
        for (Long uid : userIds) {
            if (uid == null || uid.equals(c.userId())) continue;
            try {
                notificationService.send(uid, "CHAT_ADDED", who + " added you to " + what, "CHAT_CONVERSATION", conv.getId());
            } catch (RuntimeException e) {
                log.warn("[CHAT] Added notification failed (non-fatal) | {}", e.getMessage());
            }
        }
    }

    /** After the transaction commits, tell these people's browsers that the conversation changed. */
    private void ping(Long conversationId, Collection<Long> userIds) {
        Set<Long> to = new LinkedHashSet<>(userIds);
        Runnable send = () -> to.forEach(uid -> {
            try { messaging.convertAndSend(topic(uid), Map.of("type", "chat", "conversationId", conversationId)); }
            catch (RuntimeException e) { log.debug("[CHAT] Push failed (clients poll as fallback) | {}", e.getMessage()); }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    static String topic(Long userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PUSH_SECRET, "HmacSHA256"));
            byte[] h = mac.doFinal(("chat:" + userId).getBytes(StandardCharsets.UTF_8));
            return "/topic/chat/" + HexFormat.of().formatHex(h).substring(0, 48);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<Long, User> users(Set<Long> ids) {
        Map<Long, User> out = new HashMap<>();
        if (!ids.isEmpty()) userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private static String cleanBody(Object raw) {
        String s = raw == null ? "" : raw.toString().replace("\r\n", "\n").strip();
        if (s.isEmpty()) throw bad("CHAT_EMPTY", "Write something first");
        if (s.length() > MAX_BODY) throw bad("CHAT_TOO_LONG", "A message is at most " + MAX_BODY + " characters");
        return s;
    }

    private static String preview(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 120 ? one.substring(0, 117) + "…" : one;
    }

    private static String name(User u) {
        if (u == null) return "Unknown user";
        String n = ((u.getFirstName() == null ? "" : u.getFirstName()) + " " + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return n.isEmpty() ? u.getEmail() : n;
    }

    private static String firstName(User u) {
        if (u == null) return "Someone";
        return u.getFirstName() != null && !u.getFirstName().isBlank() ? u.getFirstName() : name(u);
    }

    private static List<Long> ids(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof Collection<?> list) for (Object o : list) { Long v = longOrNull(o); if (v != null && !out.contains(v)) out.add(v); }
        return out;
    }

    private static Long longOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return Long.parseLong(o.toString().trim()); }
        catch (NumberFormatException e) { throw bad("CHAT_BAD_ID", "Not an id: " + o); }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String trimOrNull(Object o, int max) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static BusinessException bad(String code, String msg) { return new BusinessException(code, msg); }

    private static BusinessException denied(String msg) { return new BusinessException("CHAT_DENIED", msg, HttpStatus.FORBIDDEN); }
}