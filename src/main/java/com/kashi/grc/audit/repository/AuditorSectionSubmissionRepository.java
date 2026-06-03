package com.kashi.grc.audit.repository;
import com.kashi.grc.audit.domain.AuditorSectionSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List; import java.util.Optional;

@Repository
public interface AuditorSectionSubmissionRepository extends JpaRepository<AuditorSectionSubmission, Long> {
    Optional<AuditorSectionSubmission> findBySectionInstanceIdAndAuditorUserId(Long sectionInstanceId, Long auditorUserId);
    List<AuditorSectionSubmission> findBySectionInstanceId(Long sectionInstanceId);
    boolean existsBySectionInstanceIdAndAuditorUserId(Long sectionInstanceId, Long auditorUserId);
}
