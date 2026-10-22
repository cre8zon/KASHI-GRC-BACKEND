package com.kashi.grc.collab.repository;

import com.kashi.grc.collab.domain.CollabCallLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollabCallLinkRepository extends JpaRepository<CollabCallLink, Long> {

    /** By code alone — the public endpoints have no tenant until the link names one. */
    Optional<CollabCallLink> findByCodeAndIsDeletedFalse(String code);

    boolean existsByCode(String code);

    List<CollabCallLink> findByTenantIdAndTargetTypeAndTargetIdAndRevokedFalseAndIsDeletedFalseOrderByIdDesc(
            Long tenantId, String targetType, Long targetId);
}
