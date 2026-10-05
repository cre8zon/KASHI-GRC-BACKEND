package com.kashi.grc.exception_register.repository;

import com.kashi.grc.exception_register.domain.ExceptionLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExceptionLinkRepository extends JpaRepository<ExceptionLink, Long> {

    List<ExceptionLink> findByExceptionId(Long exceptionId);

    /** "Is this asset covered by an exception?" — the question every other
     *  module wants to ask, answered without joining through the register. */
    List<ExceptionLink> findByTenantIdAndEntityTypeAndEntityId(
            Long tenantId, String entityType, Long entityId);
}
