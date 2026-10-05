package com.kashi.grc.personnel.repository;

import com.kashi.grc.personnel.domain.Personnel;

import java.util.List;

public interface PersonnelRepositoryCustom {

    long nextPersonRefSequence(Long tenantId);

    List<Object[]> countByStatusForTenant(Long tenantId);

    List<Object[]> countByEmploymentTypeForTenant(Long tenantId);

    List<Object[]> countByScreeningStatusForTenant(Long tenantId);

    /**
     * In-scope, non-terminal people who have not cleared screening.
     * The A.6.1 question, answerable without loading the roster.
     */
    List<Personnel> findUnscreenedInScope(Long tenantId);

    /**
     * People marked OFFBOARDED with no recorded access revocation.
     *
     * This is the "Former Personnel Offboarding" test an auditor runs: a leaver
     * whose access was never evidenced as disabled. It is the single most
     * useful query in the module and the reason access_revoked_at is a column
     * rather than a checklist item somewhere else.
     */
    List<Personnel> findOffboardedWithoutRevocationEvidence(Long tenantId);
}
