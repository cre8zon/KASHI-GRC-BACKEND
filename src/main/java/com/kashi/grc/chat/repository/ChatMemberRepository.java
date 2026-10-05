package com.kashi.grc.chat.repository;

import com.kashi.grc.chat.domain.ChatMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {

    List<ChatMember> findByTenantIdAndUserId(Long tenantId, Long userId);

    List<ChatMember> findByConversationId(Long conversationId);

    List<ChatMember> findByConversationIdIn(Collection<Long> conversationIds);

    Optional<ChatMember> findByConversationIdAndUserId(Long conversationId, Long userId);
}
