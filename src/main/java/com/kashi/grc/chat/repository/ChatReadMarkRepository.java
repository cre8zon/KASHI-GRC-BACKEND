package com.kashi.grc.chat.repository;

import com.kashi.grc.chat.domain.ChatReadMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatReadMarkRepository extends JpaRepository<ChatReadMark, Long> {

    /**
     * For one message: the first moment each member's pointer reached it.
     *
     * A pointer that lands at 140 covers every message from wherever it was up
     * to 140, so "did this advance reach message M?" is up_to_message_id >= M.
     * The EARLIEST such advance is when they got there — later ones are them
     * reading further on, which says nothing new about M.
     *
     * Both kinds in one pass, grouped by member and kind, because the panel
     * wants delivered and read side by side and two queries would be two
     * round trips for one screen.
     *
     * Returns rows of [userId, kind, firstReachedAt].
     */
    @Query("""
            SELECT k.userId, k.kind, MIN(k.markedAt)
            FROM ChatReadMark k
            WHERE k.conversationId = :conversationId
              AND k.upToMessageId >= :messageId
            GROUP BY k.userId, k.kind
            """)
    List<Object[]> firstReachedBy(@Param("conversationId") Long conversationId,
                                  @Param("messageId") Long messageId);

    /**
     * Housekeeping, for whenever this is worth running.
     *
     * Advances accumulate — one per reading session per member per
     * conversation. That is small, but it is not bounded, and the only rows
     * that ever answer a question are the ones at or above the oldest message
     * still being looked at. Deleting marks that sit entirely below a cutoff
     * loses the times for messages below it and nothing else.
     *
     * Nothing calls this yet; it exists so the table has an obvious exit.
     */
    long deleteByConversationIdAndUpToMessageIdLessThan(Long conversationId, Long messageId);
}