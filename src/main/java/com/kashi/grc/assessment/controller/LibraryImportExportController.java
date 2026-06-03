//package com.kashi.grc.assessment.controller;
//
//import com.kashi.grc.common.dto.ApiResponse;
//import com.kashi.grc.common.dto.CsvImportResult;
//import com.kashi.grc.common.service.CsvExportService;
//import com.kashi.grc.common.service.CsvImportService;
//import com.kashi.grc.common.service.XlsxExportService;
//import com.kashi.grc.common.service.XlsxImportService;
//import com.kashi.grc.common.util.UtilityService;
//import io.swagger.v3.oas.annotations.Operation;
//import io.swagger.v3.oas.annotations.tags.Tag;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.IOException;
//import java.time.LocalDate;
//import java.util.Objects;
//
///**
// * Import/export endpoints for the global assessment library.
// *
// * IMPORT:
// *   POST /v1/library/import
// *   Accepts .csv or .xlsx — format is auto-detected from the file extension.
// *   - CSV  → flat single-sheet (CsvImportService)
// *   - XLSX → multi-sheet two-pass (XlsxImportService)
// *
// * EXPORT:
// *   GET /v1/library/export?format=csv   → flat CSV  (CsvExportService)
// *   GET /v1/library/export?format=xlsx  → multi-sheet XLSX (XlsxExportService)
// *   Default format is csv if the param is omitted.
// *
// * Both import and export operate on the GLOBAL library (tenant_id IS NULL).
// * Platform Admin access control should be enforced via SecurityConfig.
// */
//@Slf4j
//@RestController
//@RequestMapping("/v1/library")
//@Tag(name = "Library Import/Export", description = "Bulk import and export of the global assessment library")
//@RequiredArgsConstructor
//public class LibraryImportExportController {
//
//    private final CsvImportService  csvImportService;
//    private final XlsxImportService xlsxImportService;
//    private final CsvExportService  csvExportService;
//    private final XlsxExportService xlsxExportService;
//    private final UtilityService    utilityService;
//
//    // ─────────────────────────────────────────────────────────────────
//    // IMPORT
//    // ─────────────────────────────────────────────────────────────────
//
//    /**
//     * Imports a library from a .csv or .xlsx file.
//     * Format is auto-detected from the file extension — no query param needed.
//     *
//     * CSV format:  flat, hierarchical rows (TEMPLATE / SECTION / QUESTION / OPTION)
//     * XLSX format: 7-sheet workbook (templates, sections, questions, options,
//     *              template_sections, section_questions, question_options)
//     *
//     * Both formats are idempotent — re-importing the same file is safe.
//     */
//    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @Operation(summary = "Import library from CSV or XLSX",
//            description = "Auto-detects format from file extension. Both formats are idempotent.")
//    public ResponseEntity<ApiResponse<CsvImportResult>> importLibrary(
//            @RequestParam("file") MultipartFile file) {
//
//        Long tenantId = utilityService.getLoggedInDataContext().getTenantId();
//        // Global library imports have tenantId = null (Platform Admin)
//        // tenantId from context is passed through so the service can set it correctly
//
//        String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase();
//        log.info("[LIBRARY-IMPORT] Received file: {} | tenantId={}", filename, tenantId);
//
//        CsvImportResult result;
//
//        if (filename.endsWith(".xlsx")) {
//            result = xlsxImportService.importLibrary(file, tenantId);
//        } else if (filename.endsWith(".csv")) {
//            result = csvImportService.importLibrary(file, tenantId);
//        } else {
//            return ResponseEntity.badRequest().body(
//                    ApiResponse.error("Unsupported file type. Upload a .csv or .xlsx file."));
//        }
//
//        if (result.isFatalError()) {
//            return ResponseEntity.badRequest().body(ApiResponse.error(result.getSummary()));
//        }
//
//        HttpStatus status = result.hasErrors() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
//        return ResponseEntity.status(status).body(ApiResponse.success(result));
//    }
//
//    // ─────────────────────────────────────────────────────────────────
//    // EXPORT
//    // ─────────────────────────────────────────────────────────────────
//
//    /**
//     * Exports the full global library.
//     *
//     * ?format=csv  (default) → flat single-sheet CSV, good for data analysis
//     * ?format=xlsx           → multi-sheet XLSX, good for human review and re-import
//     *
//     * Both outputs are directly re-importable via the /import endpoint.
//     */
//    @GetMapping("/export")
//    @Operation(summary = "Export full library as CSV or XLSX",
//            description = "Default format is csv. Use ?format=xlsx for multi-sheet Excel export.")
//    public ResponseEntity<byte[]> exportLibrary(
//            @RequestParam(value = "format", defaultValue = "csv") String format) throws IOException {
//
//        log.info("[LIBRARY-EXPORT] Requested format: {}", format);
//        String today = LocalDate.now().toString(); // e.g. 2026-04-02
//
//        return switch (format.toLowerCase().trim()) {
//
//            case "xlsx" -> {
//                byte[] xlsx = xlsxExportService.exportLibrary();
//                yield ResponseEntity.ok()
//                        .header(HttpHeaders.CONTENT_DISPOSITION,
//                                "attachment; filename=\"library-export-" + today + ".xlsx\"")
//                        .contentType(MediaType.parseMediaType(
//                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
//                        .contentLength(xlsx.length)
//                        .body(xlsx);
//            }
//
//            case "csv" -> {
//                byte[] csv = csvExportService.exportLibrary();
//                yield ResponseEntity.ok()
//                        .header(HttpHeaders.CONTENT_DISPOSITION,
//                                "attachment; filename=\"library-export-" + today + ".csv\"")
//                        .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
//                        .contentLength(csv.length)
//                        .body(csv);
//            }
//
//            default -> ResponseEntity.badRequest()
//                    .body(("Unsupported format \"" + format + "\". Use csv or xlsx.").getBytes());
//        };
//    }
//}