package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditControlInstanceTestMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditControlInstanceTestMappingRepository
        extends JpaRepository<AuditControlInstanceTestMapping, Long> {

    List<AuditControlInstanceTestMapping> findByControlInstanceIdOrderByOrderNoAsc(Long controlInstanceId);

    List<AuditControlInstanceTestMapping> findByTestInstanceId(Long testInstanceId);

    List<AuditControlInstanceTestMapping> findByEngagementId(Long engagementId);

    /** All required test instance IDs for a control — used for result derivation */
    @Query("SELECT m.testInstanceId FROM AuditControlInstanceTestMapping m " +
            "WHERE m.controlInstanceId = :controlId AND m.isRequired = true")
    List<Long> findRequiredTestInstanceIdsByControlInstanceId(@Param("controlId") Long controlId);

    /** All control instance IDs affected by a test — used for bulk re-evaluation */
    @Query("SELECT m.controlInstanceId FROM AuditControlInstanceTestMapping m " +
            "WHERE m.testInstanceId = :testId")
    List<Long> findControlInstanceIdsByTestInstanceId(@Param("testId") Long testId);

    void deleteByEngagementId(Long engagementId);
}
