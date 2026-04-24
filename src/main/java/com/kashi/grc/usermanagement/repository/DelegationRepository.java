package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Delegation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    @Query("""
        SELECT d FROM Delegation d
        WHERE d.tenantId = :tenantId
        AND d.status = 'ACTIVE'
        AND d.endDate > :now
        AND (:userId IS NULL OR d.delegatorUserId = :userId OR d.delegateeUserId = :userId)
        AND (:scopeType IS NULL OR d.scopeType = :scopeType)
        """)
    List<Delegation> findActive(
        @Param("tenantId")  Long tenantId,
        @Param("userId")    Long userId,
        @Param("scopeType") String scopeType,
        @Param("now")       LocalDateTime now);

    @Query("SELECT COUNT(d) FROM Delegation d WHERE d.delegateeUserId = :userId AND d.status = 'ACTIVE' AND d.endDate > CURRENT_TIMESTAMP")
    long countActiveDelegationsToMe(@Param("userId") Long userId);

    @Query("SELECT COUNT(d) FROM Delegation d WHERE d.delegatorUserId = :userId AND d.status = 'ACTIVE' AND d.endDate > CURRENT_TIMESTAMP")
    long countActiveDelegationsByMe(@Param("userId") Long userId);
}
