package com.kashi.grc.chat.repository;

import com.kashi.grc.chat.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** Newest first; "before" pages back through history (Long.MAX_VALUE for the latest page). */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = :c AND m.id < :before ORDER BY m.id DESC")
    List<ChatMessage> page(@Param("c") Long conversationId, @Param("before") Long before, Pageable pageable);

    /** Unread per conversation for one reader: messages by others after their last read. */
    @Query("""
            SELECT m.conversationId, COUNT(m) FROM ChatMessage m, ChatMember cm
            WHERE cm.userId = :u AND cm.conversationId IN :convs AND m.conversationId = cm.conversationId
              AND m.senderId <> :u AND m.deleted = false
              AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
            GROUP BY m.conversationId
            """)
    List<Object[]> unreadCounts(@Param("u") Long userId, @Param("convs") Collection<Long> conversationIds);

    /** The latest message of each conversation. */
    @Query("SELECT m FROM ChatMessage m WHERE m.id IN (SELECT MAX(x.id) FROM ChatMessage x WHERE x.conversationId IN :convs GROUP BY x.conversationId)")
    List<ChatMessage> latest(@Param("convs") Collection<Long> conversationIds);

    @Query("SELECT MAX(m.id) FROM ChatMessage m WHERE m.conversationId = :c")
    Long maxId(@Param("c") Long conversationId);

    /** Pinned messages of a conversation, most recently pinned first. */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = :c AND m.pinnedAt IS NOT NULL AND m.deleted = false ORDER BY m.pinnedAt DESC")
    List<ChatMessage> pinned(@Param("c") Long conversationId);

    /** Search one conversation's text, newest first. :q is already lower-cased and wrapped in %…%. */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = :c AND m.deleted = false AND LOWER(m.body) LIKE :q ESCAPE '!' ORDER BY m.id DESC")
    List<ChatMessage> search(@Param("c") Long conversationId, @Param("q") String q, Pageable pageable);

    /** Messages with files, newest first — the details panel's Media and Files. */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = :c AND m.deleted = false AND m.attachmentsJson IS NOT NULL AND m.id < :before ORDER BY m.id DESC")
    List<ChatMessage> withFiles(@Param("c") Long conversationId, @Param("before") Long before, Pageable pageable);

    /** Messages that may hold a link (web or a KashiGuard page), newest first — the details panel's Links. */
    @Query("""
            SELECT m FROM ChatMessage m WHERE m.conversationId = :c AND m.deleted = false AND m.id < :before
              AND (m.body LIKE '%http://%' OR m.body LIKE '%https://%' OR m.body LIKE '%/module/%' OR m.body LIKE '%/collaboration/meetings/%')
            ORDER BY m.id DESC
            """)
    List<ChatMessage> withLinks(@Param("c") Long conversationId, @Param("before") Long before, Pageable pageable);
}
