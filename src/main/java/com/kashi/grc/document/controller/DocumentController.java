package com.kashi.grc.document.controller;

import com.kashi.grc.common.dto.ApiResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.document.domain.Document;
import com.kashi.grc.document.domain.DocumentLink;
import com.kashi.grc.document.repository.DocumentLinkRepository;
import com.kashi.grc.document.repository.DocumentRepository;
import com.kashi.grc.document.service.StorageService;
import com.kashi.grc.document.service.StorageService.PresignedUploadResult;
import com.kashi.grc.document.service.StorageService.S3ObjectMeta;
import com.kashi.grc.document.service.StorageService.ServerUploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

/**
 * DocumentController — unified document management for all KashiGRC modules.
 *
 * ── UPLOAD FLOW (presigned URL — industry standard) ─────────────────────────
 *   For non-image files (PDF, DOCX, XLSX, CSV, ZIP):
 *     1. POST /v1/documents/request-upload  → get presignedUrl + documentId
 *     2. PUT  {presignedUrl}                → client uploads directly to S3
 *     3. POST /v1/documents/{id}/confirm    → server verifies, activates document
 *
 *   For image files (JPEG, PNG, GIF, TIFF, HEIC):
 *     POST /v1/documents/upload-image       → server converts to WebP, uploads, confirms
 *     (single call — conversion must happen server-side, client can't do WebP encoding)
 *
 * ── LINK FLOW ────────────────────────────────────────────────────────────────
 *   POST /v1/documents/{id}/link           → attach document to any entity
 *   DELETE /v1/documents/links/{linkId}    → remove attachment (soft)
 *   GET  /v1/documents/by-entity           → list all documents for an entity
 *
 * ── DOWNLOAD ─────────────────────────────────────────────────────────────────
 *   GET /v1/documents/{id}/download-url    → get presigned GET URL (expires in 1h)
 *   GET /v1/documents/{id}/preview-url     → get presigned GET URL (expires in 5min, inline)
 *
 * ── VERSIONING ───────────────────────────────────────────────────────────────
 *   POST /v1/documents/{id}/new-version    → upload new version (marks old as SUPERSEDED)
 */
@Slf4j
@RestController
@Tag(name = "Documents", description = "Unified document management — evidence, reports, contracts")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentRepository     documentRepository;
    private final DocumentLinkRepository documentLinkRepository;
    private final StorageService         storageService;
    private final UtilityService         utilityService;

    // ── Request/Response DTOs ─────────────────────────────────────────────

    @Data
    public static class RequestUploadBody {
        @NotBlank public String fileName;
        @NotBlank public String mimeType;
        @NotNull  public Long   fileSizeBytes;
        @NotBlank public String documentType;   // EVIDENCE | POLICY | CONTRACT
        public String entityType;               // optional at request time, set at confirm
        public Long   entityId;
        public String linkType;                 // ATTACHMENT | REFERENCE
        public String title;                    // optional human-readable name
    }

    @Data
    public static class ConfirmUploadBody {
        public String checksumSha256;  // optional: client-computed SHA-256 for verification
        public String entityType;      // optional: link to entity at confirm time
        public Long   entityId;
        public String linkType;
        public String notes;
    }

    @Data
    public static class LinkDocumentBody {
        @NotBlank public String entityType;
        @NotNull  public Long   entityId;
        @NotBlank public String linkType;   // ATTACHMENT | REFERENCE
        public String notes;
        public Integer displayOrder;
    }

    @org.springframework.beans.factory.annotation.Value("${aws.s3.kms-key-arn}")
    private String kmsKeyArn;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 1 of upload: Request presigned PUT URL
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/v1/documents/request-upload")
    @Transactional
    @Operation(summary = "Step 1: Get a presigned S3 PUT URL for direct client-to-S3 upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestUpload(
            @RequestBody RequestUploadBody req) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        // Image files must go through /upload-image (server-side WebP conversion)
        if (isImageMime(req.getMimeType())) {
            throw new BusinessException("USE_IMAGE_ENDPOINT",
                    "Images must be uploaded via POST /v1/documents/upload-image " +
                            "so they can be converted to WebP server-side.",
                    HttpStatus.BAD_REQUEST);
        }

        // Generate presigned PUT URL
        PresignedUploadResult result = storageService.generateUploadUrl(
                tenantId, userId, req.getFileName(), req.getMimeType(),
                req.getFileSizeBytes(), req.getDocumentType(),
                req.getEntityType(), req.getEntityId());

        // Create PENDING document row so we have a record even if upload is abandoned
        Document doc = Document.builder()
                .tenantId(tenantId)
                .uploadedBy(userId)
                .fileName(storageService.sanitizeFilename(req.getFileName()))
                .title(req.getTitle() != null ? req.getTitle()
                        : storageService.sanitizeFilename(req.getFileName()))
                .mimeType(result.getEffectiveMimeType())
                .originalMime(req.getMimeType())
                .documentType(req.getDocumentType())
                .s3Key(result.getS3Key())
                .s3Bucket(result.getS3Bucket())
                .storagePath(result.getS3Key())
                .status("PENDING")
                .version(1)
                .fileSize(req.getFileSizeBytes())
                .build();
        documentRepository.save(doc);

        log.info("[DOC] Upload requested | tenant={} | docId={} | key={}", tenantId, doc.getId(), result.getS3Key());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentId",   doc.getId());
        response.put("presignedUrl", result.getPresignedUrl());
        response.put("s3Key",        result.getS3Key());
        response.put("expiresAt",    result.getExpiresAt().toString());
        response.put("mimeType",     result.getEffectiveMimeType());
        // Tell client which headers to include in the PUT request
        response.put("requiredHeaders", Map.of(
                "Content-Type",                                  result.getEffectiveMimeType()
//                "x-amz-server-side-encryption",                  "aws:kms",
//                "x-amz-server-side-encryption-aws-kms-key-id",  kmsKeyArn
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // ══════════════════════════════════════════════════════════════════════
    // STEP 3 of upload: Confirm (client finished uploading to S3)
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/v1/documents/{documentId}/confirm")
    @Transactional
    @Operation(summary = "Step 3: Confirm upload complete — verifies S3 object exists, activates document")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmUpload(
            @PathVariable Long documentId,
            @RequestBody(required = false) ConfirmUploadBody body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        Document doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));

        if ("ACTIVE".equals(doc.getStatus())) {
            // Idempotent — already confirmed
            return ResponseEntity.ok(ApiResponse.success(Map.of("documentId", documentId, "status", "ACTIVE")));
        }
        if (!"PENDING".equals(doc.getStatus())) {
            throw new BusinessException("INVALID_STATUS",
                    "Document is in status " + doc.getStatus() + " — cannot confirm.", HttpStatus.CONFLICT);
        }

        // Verify S3 object exists
        S3ObjectMeta meta = storageService.confirmUpload(doc.getS3Key());
        if (!meta.isExists()) {
            throw new BusinessException("S3_OBJECT_NOT_FOUND",
                    "File was not found in S3. Please re-upload.", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Update document with confirmed metadata
        doc.setStatus("ACTIVE");
        doc.setS3Etag(meta.getEtag());
        doc.setContentLength(meta.getContentLength());
        if (body != null && body.getChecksumSha256() != null) {
            doc.setChecksumSha256(body.getChecksumSha256());
        }
        documentRepository.save(doc);

        // Create link if entity info provided at confirm time
        if (body != null && body.getEntityType() != null && body.getEntityId() != null) {
            linkDocument(doc, body.getEntityType(), body.getEntityId(),
                    body.getLinkType() != null ? body.getLinkType() : "ATTACHMENT",
                    body.getNotes(), userId, tenantId);
        }

        log.info("[DOC] Confirmed | docId={} | key={} | size={} | etag={}",
                documentId, doc.getS3Key(), meta.getContentLength(), meta.getEtag());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "documentId",     documentId,
                "status",         "ACTIVE",
                "contentLength",  meta.getContentLength(),
                "s3Etag",         meta.getEtag() != null ? meta.getEtag() : "")));
    }

    // ══════════════════════════════════════════════════════════════════════
    // IMAGE UPLOAD (server-side WebP conversion)
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/v1/documents/upload-image")
    @Transactional
    @Operation(summary = "Upload an image — converted to WebP server-side (quality 85, max 4096px)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "entityType", required = false) String entityType,
            @RequestParam(value = "entityId",   required = false) Long entityId,
            @RequestParam(value = "linkType",   defaultValue = "ATTACHMENT") String linkType,
            @RequestParam(value = "title",      required = false) String title) throws Exception {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        if (!isImageMime(file.getContentType())) {
            throw new BusinessException("NOT_AN_IMAGE",
                    "This endpoint only accepts images. Use /request-upload for other file types.",
                    HttpStatus.BAD_REQUEST);
        }

        ServerUploadResult result = storageService.uploadImageAsWebP(
                tenantId, userId, file, "EVIDENCE", entityType, entityId);

        Document doc = Document.builder()
                .tenantId(tenantId)
                .uploadedBy(userId)
                .fileName(storageService.sanitizeFilename(file.getOriginalFilename()))
                .title(title != null ? title : storageService.sanitizeFilename(file.getOriginalFilename()))
                .mimeType("image/webp")
                .originalMime(file.getContentType())
                .documentType("EVIDENCE")
                .s3Key(result.getS3Key())
                .s3Bucket(result.getS3Bucket())
                .storagePath(result.getS3Key())
                .status("ACTIVE")
                .version(1)
                .contentLength(result.getContentLength())
                .fileSize(file.getSize())  // original size for reference
                .checksumSha256(result.getChecksumSha256())
                .build();
        documentRepository.save(doc);

        if (entityType != null && entityId != null) {
            linkDocument(doc, entityType, entityId, linkType, null, userId, tenantId);
        }

        log.info("[DOC] Image uploaded as WebP | docId={} | originalSize={} → webpSize={}",
                doc.getId(), file.getSize(), result.getContentLength());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "documentId",     doc.getId(),
                "mimeType",       "image/webp",
                "originalMime",   file.getContentType(),
                "contentLength",  result.getContentLength(),
                "status",         "ACTIVE")));
    }

    // ══════════════════════════════════════════════════════════════════════
    // LINK DOCUMENT TO ENTITY (polymorphic)
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/v1/documents/{documentId}/link")
    @Transactional
    @Operation(summary = "Link a document to any entity (VENDOR, ASSESSMENT, QUESTION_RESPONSE, etc.)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> linkToEntity(
            @PathVariable Long documentId,
            @RequestBody LinkDocumentBody body) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        Document doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));

        if (!"ACTIVE".equals(doc.getStatus())) {
            throw new BusinessException("DOCUMENT_NOT_ACTIVE",
                    "Document must be ACTIVE before linking. Confirm the upload first.", HttpStatus.CONFLICT);
        }

        DocumentLink link = linkDocument(doc, body.getEntityType(), body.getEntityId(),
                body.getLinkType(), body.getNotes(), userId, tenantId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "linkId",      link.getId(),
                "documentId",  documentId,
                "entityType",  body.getEntityType(),
                "entityId",    body.getEntityId(),
                "linkType",    body.getLinkType())));
    }

    @DeleteMapping("/v1/documents/links/{linkId}")
    @Transactional
    @Operation(summary = "Remove a document link (does not delete the document or S3 object)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> removeLink(@PathVariable Long linkId) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        DocumentLink link = documentLinkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentLink", linkId));
        if (!tenantId.equals(link.getTenantId()))
            throw new BusinessException("ACCESS_DENIED", "Not your tenant.", HttpStatus.FORBIDDEN);
        documentLinkRepository.delete(link);
        return ResponseEntity.ok(ApiResponse.success(Map.of("linkId", linkId, "removed", true)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIST DOCUMENTS FOR AN ENTITY (universal query)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/documents/by-entity")
    @Transactional(readOnly = true)
    @Operation(summary = "List all active documents linked to any entity — works for every module")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listByEntity(
            @RequestParam String entityType,
            @RequestParam Long entityId,
            @RequestParam(required = false) String linkType) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        List<DocumentLink> links = linkType != null
                ? documentLinkRepository.findActiveByEntity(entityType, entityId, linkType)
                : documentLinkRepository.findAllActiveByEntity(entityType, entityId);

        List<Map<String, Object>> result = links.stream().map(lnk -> {
            Document doc = documentRepository.findById(lnk.getDocumentId()).orElse(null);
            if (doc == null || !tenantId.equals(doc.getTenantId())) return null;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linkId",        lnk.getId());
            m.put("documentId",    doc.getId());
            m.put("title",         doc.getTitle() != null ? doc.getTitle() : doc.getFileName());
            m.put("fileName",      doc.getFileName());
            m.put("mimeType",      doc.getMimeType());
            m.put("documentType",  doc.getDocumentType());
            m.put("linkType",      lnk.getLinkType());
            m.put("version",       doc.getVersion());
            m.put("contentLength", doc.getContentLength());
            m.put("status",        doc.getStatus());
            m.put("createdAt",     doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
            m.put("notes",         lnk.getNotes());
            // Omit s3Key — never expose S3 keys to frontend (security)
            // Use /download-url endpoint to get a time-limited presigned GET URL

            // For generated reports: include report metadata from generatedData
            if ("GENERATED_REPORT".equals(doc.getDocumentType()) && doc.getGeneratedData() != null) {
                m.put("reportData", doc.getGeneratedData());
            }
            return m;
        }).filter(Objects::nonNull).toList();

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRESIGNED DOWNLOAD URLs (generated per request, after auth check)
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/documents/{documentId}/download-url")
    @Operation(summary = "Get a presigned S3 GET URL for downloading (1-hour TTL)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDownloadUrl(
            @PathVariable Long documentId) {
        return generatePresignedGetUrl(documentId, false);
    }

    @GetMapping("/v1/documents/{documentId}/preview-url")
    @Operation(summary = "Get a presigned S3 GET URL for inline preview (5-minute TTL)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPreviewUrl(
            @PathVariable Long documentId) {
        return generatePresignedGetUrl(documentId, true);
    }

    // ══════════════════════════════════════════════════════════════════════
    // VERSIONING — upload new version of an existing document
    // ══════════════════════════════════════════════════════════════════════

    @PostMapping("/v1/documents/{documentId}/new-version")
    @Transactional
    @Operation(summary = "Start a new version of a document — marks current as SUPERSEDED, returns presigned URL for v-next")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestNewVersion(
            @PathVariable Long documentId,
            @RequestBody RequestUploadBody req) {

        Long userId   = utilityService.getLoggedInDataContext().getId();
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();

        Document old = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        if (!"ACTIVE".equals(old.getStatus()))
            throw new BusinessException("DOCUMENT_NOT_ACTIVE", "Can only version an ACTIVE document.", HttpStatus.CONFLICT);

        // Generate presigned URL for new version
        PresignedUploadResult result = storageService.generateUploadUrl(
                tenantId, userId, req.getFileName(), req.getMimeType(),
                req.getFileSizeBytes(), old.getDocumentType(),
                null, null);

        // Create new PENDING document pointing to old as superseded
        Document newDoc = Document.builder()
                .tenantId(tenantId)
                .uploadedBy(userId)
                .fileName(storageService.sanitizeFilename(req.getFileName()))
                .title(req.getTitle() != null ? req.getTitle() : old.getTitle())
                .mimeType(result.getEffectiveMimeType())
                .originalMime(req.getMimeType())
                .documentType(old.getDocumentType())
                .sourceModule(old.getSourceModule())
                .s3Key(result.getS3Key())
                .s3Bucket(result.getS3Bucket())
                .storagePath(result.getS3Key())
                .status("PENDING")
                .version(old.getVersion() + 1)
                .supersedesId(old.getId())
                .fileSize(req.getFileSizeBytes())
                .build();
        documentRepository.save(newDoc);

        // Note: old document is marked SUPERSEDED during confirm, not now
        // (in case the upload is abandoned — we don't want to lose the active version)

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(Map.of(
                "newDocumentId",    newDoc.getId(),
                "documentId",       newDoc.getId(),
                "version",          newDoc.getVersion(),
                "supersedesId",     old.getId(),
                "presignedUrl",     result.getPresignedUrl(),
                "expiresAt",        result.getExpiresAt().toString(),
                "requiredHeaders",  Map.of(                // ← ADD THIS
                        "Content-Type",                                 result.getEffectiveMimeType()
//                        "x-amz-server-side-encryption",                 "aws:kms",
//                        "x-amz-server-side-encryption-aws-kms-key-id", kmsKeyArn
                ))));

    }

    // ══════════════════════════════════════════════════════════════════════
    // METADATA
    // ══════════════════════════════════════════════════════════════════════

    @GetMapping("/v1/documents/{documentId}")
    @Operation(summary = "Get document metadata (no file content — use download-url for that)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDocument(
            @PathVariable Long documentId) {

        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Document doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId",     doc.getId());
        m.put("title",          doc.getTitle() != null ? doc.getTitle() : doc.getFileName());
        m.put("fileName",       doc.getFileName());
        m.put("mimeType",       doc.getMimeType());
        m.put("originalMime",   doc.getOriginalMime());
        m.put("documentType",   doc.getDocumentType());
        m.put("sourceModule",   doc.getSourceModule());
        m.put("version",        doc.getVersion());
        m.put("status",         doc.getStatus());
        m.put("contentLength",  doc.getContentLength());
        m.put("checksumSha256", doc.getChecksumSha256());
        m.put("supersedesId",   doc.getSupersedesId());
        m.put("uploadedBy",     doc.getUploadedBy());
        m.put("createdAt",      doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
        m.put("expiresAt",      doc.getExpiresAt() != null ? doc.getExpiresAt().toString() : null);
        if ("GENERATED_REPORT".equals(doc.getDocumentType()) && doc.getGeneratedData() != null) {
            m.put("reportData", doc.getGeneratedData());
        }
        return ResponseEntity.ok(ApiResponse.success(m));
    }

    // ══════════════════════════════════════════════════════════════════════
    // SCHEDULED CLEANUP — abandoned PENDING uploads
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Cleans up Document rows stuck in PENDING status after 2 hours.
     * These are uploads where the client got a presigned URL but never confirmed.
     * Runs every 30 minutes.
     *
     * S3 lifecycle rule handles the actual S3 objects — they expire after 1 day
     * if never confirmed (set via lifecycle rule in Terraform/CDK).
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    @Transactional
    public void cleanupAbandonedUploads() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(2);
        List<Document> abandoned = documentRepository.findAbandonedUploads(cutoff);
        if (abandoned.isEmpty()) return;

        abandoned.forEach(doc -> {
            storageService.softDelete(doc.getS3Key(), 0L); // system cleanup
            documentRepository.markDeleted(doc.getId());
        });
        log.info("[DOC-CLEANUP] Cleaned {} abandoned PENDING uploads", abandoned.size());
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private DocumentLink linkDocument(Document doc, String entityType, Long entityId,
                                      String linkType, String notes, Long userId, Long tenantId) {
        // Idempotent — don't create duplicate links
        var existing = documentLinkRepository
                .findByDocumentIdAndEntityTypeAndEntityIdAndLinkType(
                        doc.getId(), entityType, entityId, linkType);
        if (existing.isPresent()) return existing.get();

        DocumentLink link = DocumentLink.builder()
                .tenantId(tenantId)
                .documentId(doc.getId())
                .entityType(entityType)
                .entityId(entityId)
                .linkType(linkType)
                .notes(notes)
                .createdBy(userId)
                .build();
        documentLinkRepository.save(link);

        log.info("[DOC-LINK] Created | docId={} | entity={}/{} | type={}",
                doc.getId(), entityType, entityId, linkType);
        return link;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> generatePresignedGetUrl(
            Long documentId, boolean shortTtl) {
        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
        Document doc = documentRepository.findByIdAndTenantId(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));

        if (!"ACTIVE".equals(doc.getStatus()))
            throw new BusinessException("DOCUMENT_NOT_ACTIVE",
                    "Document is not active — no S3 object available.", HttpStatus.CONFLICT);

        String url = storageService.generateDownloadUrl(doc.getS3Key(), shortTtl, doc.getFileName());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "documentId",  documentId,
                "downloadUrl", url,
                "fileName",    doc.getFileName(),
                "mimeType",    doc.getMimeType(),
                "ttlMinutes",  shortTtl ? 5 : 60)));
    }

    private boolean isImageMime(String mimeType) {
        return mimeType != null && (
                mimeType.startsWith("image/") &&
                        !mimeType.equals("image/webp") // WebP already in target format
        );
    }
}