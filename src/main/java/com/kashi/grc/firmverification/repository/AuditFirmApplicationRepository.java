package com.kashi.grc.firmverification.repository;

import com.kashi.grc.firmverification.domain.AuditFirmApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuditFirmApplicationRepository extends JpaRepository<AuditFirmApplication, Long> {
    List<AuditFirmApplication> findByTenantIdOrderByIdDesc(Long tenantId);
    List<AuditFirmApplication> findByStatusInOrderByIdAsc(Collection<String> statuses);
    List<AuditFirmApplication> findAllByOrderByIdDesc();
    List<AuditFirmApplication> findByStatusAndVerifiedUntilBefore(String status, LocalDate day);
    Optional<AuditFirmApplication> findByEmailTokenHash(String emailTokenHash);
    List<AuditFirmApplication> findByStatusAndVerifiedUntilBetween(String status, LocalDate from, LocalDate to);
}
