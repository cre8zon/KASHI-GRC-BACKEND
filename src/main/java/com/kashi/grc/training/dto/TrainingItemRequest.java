package com.kashi.grc.training.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/training/courses/{id}/items — the training_item_form.
 *
 * documentId, not a file and not an S3 key. TrainingContentTab uploads through
 * useDocumentUpload with documentType TRAINING_VIDEO — which is what unlocks
 * the video MIME types and the 500MB ceiling in StorageService — and submits
 * the resulting document id here.
 *
 * durationSeconds is read off the video element in the browser rather than
 * typed. Asking a person for it guaranteed wrong numbers, and every completion
 * calculation divides by it.
 */
@Getter @Setter
public class TrainingItemRequest {

    @NotBlank(message = "Content type is required")
    private String itemType;

    @NotBlank(message = "Title is required")
    @Size(max = 500)
    private String title;

    /** documents.id from the upload. Required for VIDEO and DOCUMENT. */
    private Long documentId;

    @Size(max = 100)
    private String mimeType;

    private Long fileSizeBytes;

    /** Required for VIDEO. Every completion calculation divides by it. */
    @Min(1)
    private Integer durationSeconds;

    @Size(max = 1000)
    private String externalUrl;

    private String body;

    private Integer sortOrder;

    private Boolean isRequired;
}