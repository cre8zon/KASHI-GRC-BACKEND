package com.kashi.grc.common.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic result returned by CsvImportService after processing any CSV file.
 *
 * Designed to be reusable across all modules — assessment templates, vendors,
 * users, or anything else that needs bulk CSV import.
 *
 * Usage:
 * <pre>
 *   CsvImportResult result = csvImportService.importAssessmentTemplate(file, tenantId);
 *   if (result.hasErrors()) { ... }
 * </pre>
 */
@Data
@Builder
public class CsvImportResult {

    /** Whether the entire import was rejected before any processing (parse failure) */
    private boolean fatalError;

    /** Human-readable summary line */
    private String summary;

    /** Total rows attempted */
    private int totalRows;

    /** Rows successfully processed */
    private int successCount;

    /** Rows that failed */
    private int failureCount;

    /** Per-row log entries for the UI to display */
    @Builder.Default
    private List<ImportLogEntry> log = new ArrayList<>();

    /** ID of the primary entity created (e.g. templateId) — null on fatal error */
    private Long createdEntityId;

    /** Type label for createdEntityId (e.g. "templateId") */
    private String createdEntityType;

    public boolean hasErrors() {
        return fatalError || failureCount > 0;
    }

    public void addLog(String message, String status) {
        log.add(ImportLogEntry.builder().message(message).status(status).build());
    }

    public void addInfo(String message)    { addLog(message, "INFO");    }
    public void addSuccess(String message) { addLog(message, "SUCCESS"); }
    public void addWarning(String message) { addLog(message, "WARNING"); }
    public void addError(String message)   { addLog(message, "ERROR");   failureCount++; }

    @Data
    @Builder
    public static class ImportLogEntry {
        private String message;
        /** INFO | SUCCESS | WARNING | ERROR */
        private String status;
    }
}