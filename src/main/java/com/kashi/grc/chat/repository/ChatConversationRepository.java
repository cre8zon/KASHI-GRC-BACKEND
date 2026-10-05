package com.kashi.grc.chat.repository;

import com.kashi.grc.chat.domain.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    Optional<ChatConversation> findFirstByTenantIdAndDirectKeyAndIsDeletedFalse(Long tenantId, String directKey);

    List<ChatConversation> findByTenantIdAndIdInAndIsDeletedFalse(Long tenantId, Collection<Long> ids);

    List<ChatConversation> findByTenantIdAndKindAndNameIgnoreCaseAndIsDeletedFalse(Long tenantId, String kind, String name);

    List<ChatConversation> findByTenantIdAndKindAndVisibilityAndArchivedFalseAndIsDeletedFalseOrderByNameAsc(
            Long tenantId, String kind, String visibility);
}
