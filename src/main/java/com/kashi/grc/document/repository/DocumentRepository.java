package com.kashi.grc.document.repository;

import com.kashi.grc.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByIdAndTenantId(Long id, Long tenantId);

    // ── Versioning queries ─────────────────────────────────────────────────

    /** Find the document this one supersedes (previous version in chain). */
    Optional<Document> findBySupersedesId(Long previousDocumentId);

    // ── Status queries ─────────────────────────────────────────────────────

    /** Pending uploads older than 1 hour — candidates for cleanup. */
    @Query("SELECT d FROM Document d WHERE d.status = 'PENDING' AND d.createdAt < :cutoff")
    List<Document> findAbandonedUploads(@Param("cutoff") LocalDateTime cutoff);

    /** Mark old pending document as DELETED (cleanup after presigned URL expiry). */
    @Modifying
    @Query("UPDATE Document d SET d.status = 'DELETED' WHERE d.id = :id AND d.status = 'PENDING'")
    int markDeleted(@Param("id") Long id);

    /** Mark document as SUPERSEDED when a new version is uploaded. */
    @Modifying
    @Query("UPDATE Document d SET d.status = 'SUPERSEDED' WHERE d.id = :id AND d.status = 'ACTIVE'")
    int markSuperseded(@Param("id") Long id);
}