package com.kashi.grc.personnel.repository;

import com.kashi.grc.personnel.domain.PersonnelAssetLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonnelAssetLinkRepository extends JpaRepository<PersonnelAssetLink, Long> {

    List<PersonnelAssetLink> findByPersonnelIdAndTenantId(Long personnelId, Long tenantId);

    Optional<PersonnelAssetLink> findByPersonnelIdAndAssetId(Long personnelId, Long assetId);

    boolean existsByPersonnelIdAndAssetId(Long personnelId, Long assetId);

    /** Drives the offboarding gate. */
    List<PersonnelAssetLink> findByPersonnelIdAndTenantIdAndReturnedAtIsNull(Long personnelId, Long tenantId);

    /** Roster-wide: assets still out with people who have left. */
    long countByTenantIdAndReturnedAtIsNull(Long tenantId);
}
