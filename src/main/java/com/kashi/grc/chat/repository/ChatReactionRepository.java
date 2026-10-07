package com.kashi.grc.chat.repository;

import com.kashi.grc.chat.domain.ChatReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatReactionRepository extends JpaRepository<ChatReaction, Long> {

    List<ChatReaction> findByMessageIdIn(Collection<Long> messageIds);

    Optional<ChatReaction> findByMessageIdAndUserIdAndEmoji(Long messageId, Long userId, String emoji);

    long countByMessageIdAndUserId(Long messageId, Long userId);
}
