package com.kashi.grc.personnel.repository;

import com.kashi.grc.personnel.domain.PersonnelExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PersonnelExclusionRepository extends JpaRepository<PersonnelExclusion, Long> {

    List<PersonnelExclusion> findByPersonnelIdAndTenantId(Long personnelId, Long tenantId);

    /** Batch: one query for a whole page of people, for the compliance rollup. */
    List<PersonnelExclusion> findByPersonnelIdInAndTenantId(Collection<Long> personnelIds, Long tenantId);

    Optional<PersonnelExclusion> findByPersonnelIdAndRequirementKey(Long personnelId, String requirementKey);

    List<PersonnelExclusion> findByTenantIdAndIsActiveTrue(Long tenantId);
}
