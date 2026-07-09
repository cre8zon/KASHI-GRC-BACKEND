package com.kashi.grc.assessment.repository;
import com.kashi.grc.assessment.domain.QuestionComment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** Latest-response comment lookup lives in the Custom fragment (Criteria API). */
public interface QuestionCommentRepository
        extends JpaRepository<QuestionComment, Long>, QuestionCommentRepositoryCustom {

    List<QuestionComment> findByResponseIdOrderByCreatedAt(Long responseId);

    List<QuestionComment> findByResponseIdIn(List<Long> responseIds);
}
