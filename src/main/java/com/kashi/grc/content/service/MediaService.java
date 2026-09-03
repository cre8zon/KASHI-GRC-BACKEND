package com.kashi.grc.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kashi.grc.common.dto.PageDetails;
import com.kashi.grc.common.dto.PaginatedResponse;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.common.exception.ResourceNotFoundException;
import com.kashi.grc.common.util.UtilityService;
import com.kashi.grc.content.domain.ContentMedia;
import com.kashi.grc.content.repository.ContentMediaRepository;
import com.kashi.grc.document.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The content image library.
 *
 * ── WHAT IS REUSED AND WHAT IS NOT ───────────────────────────────────────────
 * StorageService is reused wholesale: S3, SSE-KMS, WebP conversion, filename
 * sanitisation, presigned URLs. That is where the real work lives and none of
 * it is content-specific.
 *
 * The Document ENTITY is not reused. It extends TenantAwareEntity and carries
 * evidence machinery — retention, supersession, expiry, document links — none
 * of which applies to a marketing image. Sharing the table would mean either
 * inventing a synthetic tenant for platform assets or making tenant nullable on
 * evidence, and the second is how evidence leaks between customers.
 *
 * ── WIDTH AND HEIGHT ARE NOT OPTIONAL ────────────────────────────────────────
 * They are read here and written into the img tag so the browser reserves space
 * before the image loads. Without them, every article reflows as it renders.
 * CLS is one of the three Core Web Vitals this whole exercise is measured on,
 * and it is the one that is trivially avoidable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    private final ContentMediaRepository repository;
    private final StorageService storageService;
    private final UtilityService utilityService;
    private final ObjectMapper mapper;

    /** CDN in front of the bucket. Falls back to the S3 URL if unset. */
    @Value("${content.media.cdn-base-url:}")
    private String cdnBaseUrl;

    /** Platform-owned assets carry no real tenant; StorageService still wants one for the key prefix. */
    private static final Long PLATFORM_TENANT = 0L;

    @Transactional
    public ContentMedia upload(MultipartFile file, String altText, String caption) {
        if (altText == null || altText.isBlank()) {
            throw new BusinessException("ALT_TEXT_REQUIRED",
                    "Alt text is required. Describe what the image shows, for a reader who cannot see it.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_REQUIRED", "No file was uploaded");
        }

        Long userId = currentUserId();
        try {
            // Read dimensions from the ORIGINAL bytes, before conversion —
            // WebP is not decodable by stock ImageIO on every JDK, and a
            // missing dimension here silently reintroduces the layout shift.
            int[] dims = readDimensions(file.getBytes());

            StorageService.ServerUploadResult result = storageService.uploadImageAsWebP(
                    PLATFORM_TENANT, userId, file, "CONTENT_IMAGE", "CONTENT_MEDIA", null);

            Map<String, String> variants = new LinkedHashMap<>();
            variants.put("webp", publicUrl(result.getS3Key()));

            ContentMedia media = ContentMedia.builder()
                    .url(publicUrl(result.getS3Key()))
                    .s3Key(result.getS3Key())
                    .altText(altText.trim())
                    .caption(caption)
                    .mimeType(result.getEffectiveMimeType())
                    .sizeBytes(result.getContentLength())
                    .width(dims[0] > 0 ? dims[0] : null)
                    .height(dims[1] > 0 ? dims[1] : null)
                    .variantsJson(mapper.writeValueAsString(variants))
                    .uploadedById(userId)
                    .build();

            ContentMedia saved = repository.save(media);
            log.info("[CONTENT-MEDIA] uploaded | id={} key={} {}x{}",
                    saved.getId(), saved.getS3Key(), saved.getWidth(), saved.getHeight());
            return saved;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CONTENT-MEDIA] upload failed: {}", e.getMessage(), e);
            throw new BusinessException("MEDIA_UPLOAD_FAILED",
                    "The image could not be uploaded: " + e.getMessage());
        }
    }

    @Transactional
    public ContentMedia updateText(Long id, String altText, String caption) {
        ContentMedia media = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ContentMedia", id));
        if (altText != null) {
            if (altText.isBlank()) {
                throw new BusinessException("ALT_TEXT_REQUIRED", "Alt text cannot be blanked");
            }
            media.setAltText(altText.trim());
        }
        if (caption != null) media.setCaption(caption);
        return repository.save(media);
    }

    public PaginatedResponse<ContentMedia> list(Map<String, String> params) {
        PageDetails pd = utilityService.getpageDetails(params);
        int size = pd.getTake() == null || pd.getTake() <= 0 ? 24 : pd.getTake();
        int page = pd.getSkip() == null ? 0 : (int) (pd.getSkip() / size);
        return new PaginatedResponse<>(
                repository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)));
    }

    private String publicUrl(String s3Key) {
        if (cdnBaseUrl == null || cdnBaseUrl.isBlank()) {
            return storageService.generateDownloadUrl(s3Key, false, null);
        }
        return cdnBaseUrl.replaceAll("/$", "") + "/" + s3Key;
    }

    /** @return {width, height}, or zeros when the format cannot be read. */
    private int[] readDimensions(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return img == null ? new int[]{0, 0} : new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            log.warn("[CONTENT-MEDIA] could not read image dimensions: {}", e.getMessage());
            return new int[]{0, 0};
        }
    }

    private Long currentUserId() {
        try {
            var user = utilityService.getLoggedInDataContext();
            return user == null ? null : user.getId();
        } catch (Exception e) {
            return null;
        }
    }
}
