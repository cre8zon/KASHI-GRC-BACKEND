package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditControlTestMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditControlTestMappingRepository extends JpaRepository<AuditControlTestMapping, Long> {

    List<AuditControlTestMapping> findByControlIdOrderByOrderNoAsc(Long controlId);

    List<AuditControlTestMapping> findByTestIdOrderByOrderNoAsc(Long testId);

    Optional<AuditControlTestMapping> findByControlIdAndTestId(Long controlId, Long testId);

    void deleteByControlId(Long controlId);

    void deleteByTestId(Long testId);
}
