package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.ResponseDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ResponseDocumentRepository extends JpaRepository<ResponseDocument, Long> {
    List<ResponseDocument> findByResponseId(Long responseId);
    List<ResponseDocument> findByResponseIdIn(List<Long> responseIds);
}
