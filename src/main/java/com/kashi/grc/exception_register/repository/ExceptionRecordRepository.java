package com.kashi.grc.exception_register.repository;

import com.kashi.grc.exception_register.domain.ExceptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ExceptionRecordRepository extends JpaRepository<ExceptionRecord, Long> {

    boolean existsByExceptionRefAndTenantId(String exceptionRef, Long tenantId);

    @Query("SELECT COALESCE(MAX(e.id), 0) + 1 FROM ExceptionRecord e WHERE e.tenantId = :tenantId")
    long nextRefSequence(@Param("tenantId") Long tenantId);

    List<ExceptionRecord> findByTenantIdAndIsDeletedFalse(Long tenantId);

    /**
     * Approved exceptions whose date has passed. The sweep's input.
     *
     * Bounded by status so it never scans the whole register — an expired or
     * revoked row is already settled and can never lapse again.
     */
    @Query("""
        SELECT e FROM ExceptionRecord e
         WHERE e.isDeleted = false
           AND e.status = com.kashi.grc.exception_register.domain.ExceptionRecord$Status.APPROVED
           AND e.expiresAt < :now
        """)
    List<ExceptionRecord> findLapsed(@Param("now") LocalDateTime now);
}
