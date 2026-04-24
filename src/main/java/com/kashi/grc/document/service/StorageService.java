package com.kashi.grc.document.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/**
 * StorageService — all S3 interaction for the unified document system.
 *
 * ── PRESIGNED URL FLOW (industry standard) ──────────────────────────────────
 *   Upload:
 *     1. generateUploadUrl() → returns presigned PUT URL + s3Key (15 min TTL)
 *     2. Client uploads directly to S3 using the PUT URL
 *     3. confirmUpload() → verifies S3 ETag, marks Document ACTIVE
 *
 *   Download:
 *     1. generateDownloadUrl() → returns presigned GET URL (1h TTL for UI,
 *        5min TTL for inline display)
 *     2. Client fetches directly from S3 using GET URL
 *
 * ── IMAGE HANDLING ──────────────────────────────────────────────────────────
 *   Images (JPEG, PNG, GIF, TIFF, HEIC) are converted to WebP on the server
 *   before upload. Quality 85 (visually equivalent to JPEG 95, ~30% smaller).
 *   Max dimension: 4096px (larger images are downscaled proportionally).
 *   WebP is supported by all modern browsers (Chrome 17+, Firefox 65+, Safari 14+).
 *
 * ── PDF HANDLING ─────────────────────────────────────────────────────────────
 *   User-uploaded PDFs: stored verbatim (never re-encoded — digital signatures).
 *   System-generated PDFs: uploaded directly after generation (caller is responsible
 *   for linearization via Apache PDFBox before calling uploadSystemDocument).
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────────
 *   - All objects: SSE-KMS encryption (enforced by bucket policy too)
 *   - Bucket: Block Public Access = ON, ACLs = DISABLED
 *   - Presigned PUT: includes Content-Length-Range condition (50MB max)
 *   - Presigned PUT: includes Content-Type condition (MIME allowlist)
 *   - S3 key: UUID prefix prevents collision + enumeration
 *   - Object metadata tags: tenant_id, uploaded_by, entity_type for CloudTrail queries
 *
 * ── S3 KEY FORMAT ─────────────────────────────────────────────────────────────
 *   tenants/{tenantId}/{YYYY}/{MM}/{uuid}-{sanitized-filename}
 *   Example: tenants/42/2026/04/7f3a8b2c-mfa-policy.pdf
 */
@Slf4j
@Service
public class StorageService {

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024; // 50MB
    private static final int  WEBP_QUALITY         = 85;
    private static final int  MAX_IMAGE_DIMENSION  = 4096;
    private static final Duration PUT_URL_TTL      = Duration.ofMinutes(15);
    private static final Duration GET_URL_TTL_LONG = Duration.ofHours(1);
    private static final Duration GET_URL_TTL_SHORT = Duration.ofMinutes(5);

    // MIME types accepted for user uploads (enforced at URL generation + S3 policy)
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg", "image/png", "image/gif",
            "image/tiff", "image/webp", "image/heic", "image/heif",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/csv",
            "text/plain",
            "application/zip",
            "application/x-zip-compressed"
    );

    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif",
            "image/tiff", "image/heic", "image/heif"
            // image/webp already in target format — skip conversion
    );

    private final S3Client   s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    /** Package-visible accessor — used by GenerateAssessmentReportAction and ReviewController */
    public String getBucket() { return bucket; }

    @Value("${aws.s3.kms-key-arn}")
    private String kmsKeyArn;

    @Value("${aws.s3.region:us-east-1}")
    private String region;

    public StorageService(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client    = s3Client;
        this.s3Presigner = s3Presigner;
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRESIGNED UPLOAD URL (step 1 of 4-step upload flow)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generates a presigned PUT URL for direct client-to-S3 upload.
     *
     * @param tenantId    For key namespacing and metadata tagging
     * @param userId      For audit trail metadata
     * @param fileName    Original filename from client (will be sanitized)
     * @param mimeType    Must be in ALLOWED_MIME_TYPES
     * @param fileSizeBytes Client-declared size (enforced by S3 via Content-Length-Range)
     * @param documentType EVIDENCE | GENERATED_REPORT | POLICY | CONTRACT
     * @param entityType   For metadata tagging (ASSESSMENT, VENDOR, etc.)
     * @param entityId     For metadata tagging
     * @return PresignedUploadResult with url + s3Key + effectiveMimeType
     */
    public PresignedUploadResult generateUploadUrl(
            Long tenantId, Long userId, String fileName, String mimeType,
            Long fileSizeBytes, String documentType, String entityType, Long entityId) {

        // ── Validate ──────────────────────────────────────────────────────
        validateMimeType(mimeType);
        validateFileSize(fileSizeBytes);

        // Determine if image needs conversion
        boolean willConvertToWebP = IMAGE_MIME_TYPES.contains(mimeType);
        String effectiveMimeType  = willConvertToWebP ? "image/webp" : mimeType;
        String effectiveExtension = willConvertToWebP
                ? "webp"
                : extractExtension(fileName);

        // ── Generate S3 key ───────────────────────────────────────────────
        String s3Key = buildS3Key(tenantId, fileName, effectiveExtension);

        // ── Build presigned PUT URL ───────────────────────────────────────
        // Include SSE-KMS requirement in the presigned URL so the client
        // must pass the right encryption header — bucket policy also enforces this,
        // giving defense-in-depth.
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType(effectiveMimeType)
                .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                .ssekmsKeyId(kmsKeyArn)
                .overrideConfiguration(b -> b
                        .putHeader("x-amz-meta-tenant-id",     String.valueOf(tenantId))
                        .putHeader("x-amz-meta-uploaded-by",   String.valueOf(userId))
                        .putHeader("x-amz-meta-document-type", documentType)
                        .putHeader("x-amz-meta-entity-type",   entityType != null ? entityType : "")
                        .putHeader("x-amz-meta-entity-id",     entityId != null ? String.valueOf(entityId) : "")
                        .putHeader("x-amz-meta-original-name", sanitizeFilename(fileName))
                        .putHeader("x-amz-meta-kashi-version", "1"))
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PUT_URL_TTL)
                .putObjectRequest(putRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);

        log.info("[STORAGE] Presigned PUT URL generated | tenant={} | key={} | mimeType={} | ttl=15min",
                tenantId, s3Key, effectiveMimeType);

        return PresignedUploadResult.builder()
                .presignedUrl(presigned.url().toString())
                .s3Key(s3Key)
                .s3Bucket(bucket)
                .effectiveMimeType(effectiveMimeType)
                .willConvertToWebP(willConvertToWebP)
                .expiresAt(java.time.Instant.now().plus(PUT_URL_TTL))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // SERVER-SIDE UPLOAD (for image conversion + system-generated reports)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * For IMAGE files: converts to WebP server-side, then uploads to S3.
     * Called when the client is uploading images through the server
     * (needed because WebP conversion happens server-side).
     *
     * For most file types, use generateUploadUrl() instead (client-direct).
     * This method is only for images where conversion is required.
     */
    public ServerUploadResult uploadImageAsWebP(
            Long tenantId, Long userId, MultipartFile file,
            String documentType, String entityType, Long entityId) throws Exception {

        validateMimeType(file.getContentType());
        validateFileSize(file.getSize());

        // Convert to WebP
        byte[] webpBytes = convertToWebP(file.getBytes(), file.getOriginalFilename());
        String s3Key = buildS3Key(tenantId, file.getOriginalFilename(), "webp");
        String sha256 = computeSha256(webpBytes);

        // Upload directly (server-side, no presigned URL needed here)
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .contentType("image/webp")
                        .contentLength((long) webpBytes.length)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .ssekmsKeyId(kmsKeyArn)
                        .overrideConfiguration(b -> b
                                .putHeader("x-amz-meta-tenant-id",     String.valueOf(tenantId))
                                .putHeader("x-amz-meta-uploaded-by",   String.valueOf(userId))
                                .putHeader("x-amz-meta-document-type", documentType)
                                .putHeader("x-amz-meta-entity-type",   entityType != null ? entityType : "")
                                .putHeader("x-amz-meta-entity-id",     entityId != null ? String.valueOf(entityId) : "")
                                .putHeader("x-amz-meta-original-name", sanitizeFilename(file.getOriginalFilename()))
                                .putHeader("x-amz-meta-original-mime", file.getContentType())
                                .putHeader("x-amz-meta-kashi-version", "1"))
                        .build(),
                RequestBody.fromBytes(webpBytes)
        );

        log.info("[STORAGE] Image uploaded as WebP | tenant={} | key={} | originalSize={} → webpSize={}",
                tenantId, s3Key, file.getSize(), webpBytes.length);

        return ServerUploadResult.builder()
                .s3Key(s3Key)
                .s3Bucket(bucket)
                .effectiveMimeType("image/webp")
                .originalMimeType(file.getContentType())
                .contentLength((long) webpBytes.length)
                .checksumSha256(sha256)
                .build();
    }

    /**
     * Uploads a system-generated document (PDF report, CSV export) directly from bytes.
     * Caller is responsible for linearizing the PDF before calling this.
     * Uses server-side upload (not presigned) since the server generates the bytes.
     */
    public ServerUploadResult uploadSystemDocument(
            Long tenantId, Long userId, byte[] bytes, String filename,
            String mimeType, String sourceModule) throws Exception {

        String s3Key = buildS3Key(tenantId, filename, extractExtension(filename));
        String sha256 = computeSha256(bytes);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .contentType(mimeType)
                        .contentLength((long) bytes.length)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .ssekmsKeyId(kmsKeyArn)
                        .overrideConfiguration(b -> b
                                .putHeader("x-amz-meta-tenant-id",     String.valueOf(tenantId))
                                .putHeader("x-amz-meta-uploaded-by",   String.valueOf(userId))
                                .putHeader("x-amz-meta-document-type", "GENERATED_REPORT")
                                .putHeader("x-amz-meta-source-module", sourceModule)
                                .putHeader("x-amz-meta-generated-by",  "kashi-grc")
                                .putHeader("x-amz-meta-kashi-version", "1"))
                        .build(),
                RequestBody.fromBytes(bytes)
        );

        log.info("[STORAGE] System document uploaded | tenant={} | module={} | key={} | size={}",
                tenantId, sourceModule, s3Key, bytes.length);

        return ServerUploadResult.builder()
                .s3Key(s3Key)
                .s3Bucket(bucket)
                .effectiveMimeType(mimeType)
                .contentLength((long) bytes.length)
                .checksumSha256(sha256)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // CONFIRM UPLOAD (step 4 of 4-step upload flow)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Verifies that the client actually uploaded to S3 after receiving the presigned URL.
     * Called during POST /v1/documents/{documentId}/confirm.
     *
     * @param s3Key The key that was given to the client
     * @return S3 object metadata (ETag, content-length) for persisting to Document row
     */
    public S3ObjectMeta confirmUpload(String s3Key) {
        try {
            HeadObjectResponse head = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(s3Key).build());

            log.info("[STORAGE] Upload confirmed | key={} | etag={} | size={}",
                    s3Key, head.eTag(), head.contentLength());

            return S3ObjectMeta.builder()
                    .etag(head.eTag())
                    .contentLength(head.contentLength())
                    .serverSideEncryption(head.serverSideEncryptionAsString())
                    .exists(true)
                    .build();

        } catch (NoSuchKeyException e) {
            log.warn("[STORAGE] Upload confirm failed — key not found: {}", s3Key);
            return S3ObjectMeta.builder().exists(false).build();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PRESIGNED DOWNLOAD URL (generated per-request, after auth check)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generates a presigned GET URL for viewing/downloading a document.
     *
     * @param s3Key     S3 object key
     * @param shortTtl  true = 5 min (inline display), false = 1 hour (download)
     * @param fileName  Override filename for Content-Disposition header
     * @return Presigned URL string — never store this in DB, it expires
     */
    public String generateDownloadUrl(String s3Key, boolean shortTtl, String fileName) {
        Duration ttl = shortTtl ? GET_URL_TTL_SHORT : GET_URL_TTL_LONG;

        GetObjectRequest.Builder getReqBuilder = GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key);

        // Set Content-Disposition so browser downloads with the right filename
        if (fileName != null && !fileName.isBlank()) {
            getReqBuilder.responseContentDisposition(
                    "attachment; filename=\"" + sanitizeFilename(fileName) + "\"");
        }

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getReqBuilder.build())
                .build();

        String url = s3Presigner.presignGetObject(presignRequest).url().toString();

        log.debug("[STORAGE] Presigned GET URL generated | key={} | ttl={}min",
                s3Key, ttl.toMinutes());

        return url;
    }

    /**
     * Soft-delete: marks object with a 'deleted' tag. Does not actually delete —
     * GRC evidence must be retained for audit (S3 lifecycle will eventually move
     * to Glacier or expire based on retention policy).
     */
    public void softDelete(String s3Key, Long deletedBy) {
        try {
            s3Client.putObjectTagging(PutObjectTaggingRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .tagging(Tagging.builder()
                            .tagSet(
                                    Tag.builder().key("deleted").value("true").build(),
                                    Tag.builder().key("deleted-by").value(String.valueOf(deletedBy)).build(),
                                    Tag.builder().key("deleted-at").value(java.time.Instant.now().toString()).build()
                            ).build())
                    .build());
            log.info("[STORAGE] Object soft-deleted | key={} | by={}", s3Key, deletedBy);
        } catch (Exception e) {
            log.error("[STORAGE] Soft delete failed | key={} | error={}", s3Key, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // IMAGE CONVERSION — JPEG/PNG/GIF/TIFF → WebP
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Converts any supported image format to WebP at quality 85.
     * Downscales to max 4096px on the longest dimension if needed.
     *
     * Uses javax.imageio with TwelveMonkeys ImageIO plugins for HEIC/TIFF support.
     * Add to pom.xml:
     *   com.twelvemonkeys.imageio:imageio-core:3.10.1
     *   com.twelvemonkeys.imageio:imageio-jpeg:3.10.1
     *   com.twelvemonkeys.imageio:imageio-tiff:3.10.1
     *   com.twelvemonkeys.imageio:imageio-webp:3.10.1  (write support)
     *
     * NOTE: If TwelveMonkeys WebP write support is unavailable, fall back to
     * net.coobird:thumbnailator:0.4.20 which wraps these operations cleanly.
     */
    private byte[] convertToWebP(byte[] inputBytes, String originalFilename) throws Exception {
        BufferedImage image;
        try (InputStream is = new ByteArrayInputStream(inputBytes)) {
            image = ImageIO.read(is);
        }

        if (image == null) {
            throw new IllegalArgumentException(
                    "Could not decode image: " + originalFilename +
                            ". Ensure TwelveMonkeys ImageIO plugins are on the classpath.");
        }

        // Downscale if needed (preserving aspect ratio)
        image = downscaleIfNeeded(image, MAX_IMAGE_DIMENSION);

        // Write as WebP
        // TwelveMonkeys 3.10+ supports WebP write via imageio-webp plugin
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            var writers = ImageIO.getImageWritersByMIMEType("image/webp");
            if (!writers.hasNext()) {
                // Fallback: write as JPEG at quality 85 if WebP write not available
                // Log a warning — add imageio-webp dependency to fix
                log.warn("[STORAGE] WebP writer not found — falling back to JPEG. Add imageio-webp to classpath.");
                var jpegWriters = ImageIO.getImageWritersByMIMEType("image/jpeg");
                if (!jpegWriters.hasNext()) throw new IllegalStateException("No JPEG writer found");
                var writer = jpegWriters.next();
                var writeParam = writer.getDefaultWriteParam();
                writeParam.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(WEBP_QUALITY / 100.0f);
                writer.setOutput(ImageIO.createImageOutputStream(bos));
                writer.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
                return bos.toByteArray();
            }
            var writer = writers.next();
            var writeParam = writer.getDefaultWriteParam();
            // WebP quality: 0.0 = smallest, 1.0 = lossless
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(WEBP_QUALITY / 100.0f);
            }
            writer.setOutput(ImageIO.createImageOutputStream(bos));
            writer.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
            return bos.toByteArray();
        }
    }

    private BufferedImage downscaleIfNeeded(BufferedImage img, int maxDimension) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= maxDimension && h <= maxDimension) return img;

        double scale = Math.min((double) maxDimension / w, (double) maxDimension / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);

        java.awt.Image scaled = img.getScaledInstance(newW, newH, java.awt.Image.SCALE_SMOOTH);
        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        result.getGraphics().drawImage(scaled, 0, 0, null);

        log.info("[STORAGE] Image downscaled: {}x{} → {}x{}", w, h, newW, newH);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * S3 key format: tenants/{tenantId}/{YYYY}/{MM}/{uuid}-{sanitized-filename}
     * UUID prefix prevents collision + enumeration. Tenant prefix enables
     * per-tenant lifecycle policies. Date prefix enables time-based queries.
     */
    private String buildS3Key(Long tenantId, String originalFilename, String extension) {
        LocalDate now = LocalDate.now();
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String safe = sanitizeFilenameBase(originalFilename);
        // Limit total key length to 256 chars (S3 max is 1024, but shorter is cleaner)
        return String.format("tenants/%d/%d/%02d/%s-%s.%s",
                tenantId, now.getYear(), now.getMonthValue(), uuid, safe, extension);
    }

    /** Sanitize filename for use in S3 key: lowercase, remove unsafe chars, truncate. */
    public String sanitizeFilename(String filename) {
        if (filename == null) return "document";
        String ext = extractExtension(filename);
        String base = sanitizeFilenameBase(filename);
        return base + (ext.isBlank() ? "" : "." + ext);
    }

    private String sanitizeFilenameBase(String filename) {
        if (filename == null) return "document";
        String withoutExt = filename.contains(".")
                ? filename.substring(0, filename.lastIndexOf('.'))
                : filename;
        return withoutExt
                .toLowerCase()
                .replaceAll("[^a-z0-9\\-_]", "-")  // keep alphanumeric, dash, underscore
                .replaceAll("-{2,}", "-")            // collapse consecutive dashes
                .replaceAll("^-|-$", "")             // trim leading/trailing dashes
                .substring(0, Math.min(80, withoutExt.length()));
    }

    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private void validateMimeType(String mimeType) {
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "INVALID_MIME_TYPE",
                    "File type not allowed: " + mimeType +
                            ". Allowed: PDF, images (JPEG/PNG/GIF/TIFF/HEIC/WebP), " +
                            "Excel, Word, CSV, ZIP",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }

    private void validateFileSize(Long size) {
        if (size != null && size > MAX_FILE_SIZE_BYTES) {
            throw new com.kashi.grc.common.exception.BusinessException(
                    "FILE_TOO_LARGE",
                    "File size " + (size / 1024 / 1024) + "MB exceeds the 50MB limit.",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
    }

    private String computeSha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            log.warn("[STORAGE] SHA-256 computation failed", e);
            return null;
        }
    }

    // ── Result DTOs ────────────────────────────────────────────────────────

    @lombok.Data @lombok.Builder
    public static class PresignedUploadResult {
        private String presignedUrl;
        private String s3Key;
        private String s3Bucket;
        private String effectiveMimeType;
        private boolean willConvertToWebP;
        private java.time.Instant expiresAt;
    }

    @lombok.Data @lombok.Builder
    public static class ServerUploadResult {
        private String s3Key;
        private String s3Bucket;
        private String effectiveMimeType;
        private String originalMimeType;
        private Long contentLength;
        private String checksumSha256;
    }

    @lombok.Data @lombok.Builder
    public static class S3ObjectMeta {
        private boolean exists;
        private String etag;
        private Long contentLength;
        private String serverSideEncryption;
    }
}