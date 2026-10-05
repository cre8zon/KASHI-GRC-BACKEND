package com.kashi.grc.personnel.repository;

import com.kashi.grc.personnel.domain.Personnel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PersonnelRepository extends JpaRepository<Personnel, Long>,
        JpaSpecificationExecutor<Personnel>,
        PersonnelRepositoryCustom {

    Optional<Personnel> findByIdAndTenantIdAndIsDeletedFalse(Long id, Long tenantId);

    boolean existsByPersonRefAndTenantId(String personRef, Long tenantId);

    /** The idempotency key for HRIS and IdP syncs. */
    Optional<Personnel> findByTenantIdAndSourceSystemAndExternalId(
            Long tenantId, String sourceSystem, String externalId);

    Optional<Personnel> findByTenantIdAndUserIdAndIsDeletedFalse(Long tenantId, Long userId);

    List<Personnel> findByTenantIdAndManagerPersonnelIdAndIsDeletedFalse(Long tenantId, Long managerId);

    /** Batch direct-report counts for the org chart — one query per page. */
    List<Personnel> findByTenantIdAndManagerPersonnelIdInAndIsDeletedFalse(
            Long tenantId, Collection<Long> managerIds);
}
