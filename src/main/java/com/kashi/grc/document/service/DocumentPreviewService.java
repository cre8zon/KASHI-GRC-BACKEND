package com.kashi.grc.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DocumentPreviewService — converts office documents to HTML for in-app preview.
 *
 * Supported conversions:
 *   DOCX  → HTML  (via Apache POI XWPFDocument)
 *   XLS/X → HTML  (via Apache POI WorkbookFactory — tabular view with sheet tabs)
 *   CSV   → HTML  (plain Java — first 500 rows shown)
 *   TXT   → HTML  (wrap in pre block)
 *   PDF   → raw bytes passed through as-is (rendered by PDF.js in browser)
 *   IMG   → raw bytes passed through (rendered by browser)
 *
 * For DOCX conversion we extract paragraphs and tables into clean semantic HTML.
 * We deliberately avoid LibreOffice headless to keep the deployment footprint small.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentPreviewService {

    private static final int CSV_MAX_ROWS    = 500;
    private static final int CSV_MAX_COLS    = 50;
    private static final int XLSX_MAX_ROWS   = 1000;
    private static final String HTML_HEAD    = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <style>
          body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                 font-size: 13px; color: #e2e8f0; background: #0f172a; margin: 0; padding: 16px; }
          table { border-collapse: collapse; width: 100%; margin-bottom: 16px; }
          th, td { border: 1px solid #334155; padding: 6px 10px; text-align: left;
                   white-space: pre-wrap; word-break: break-word; max-width: 400px; }
          th { background: #1e293b; color: #94a3b8; font-weight: 600; position: sticky; top:0; }
          tr:nth-child(even) { background: #111827; }
          tr:hover { background: #1e293b; }
          .sheet-tab { display:inline-block; padding:4px 12px; margin:0 2px 8px 0;
                       border-radius:4px; background:#1e293b; color:#94a3b8;
                       font-size:11px; font-weight:600; cursor:pointer; border:1px solid #334155; }
          .sheet-tab.active { background:#3b82f620; border-color:#3b82f6; color:#60a5fa; }
          p { margin: 0 0 8px; line-height: 1.6; }
          h1,h2,h3 { color:#f1f5f9; margin:12px 0 6px; }
          .truncated { color:#f59e0b; font-size:11px; margin-top:8px; font-style:italic; }
          pre { background:#1e293b; padding:12px; border-radius:6px; overflow-x:auto;
                white-space:pre-wrap; word-break:break-all; color:#94a3b8; }
        </style></head><body>
        """;
    private static final String HTML_FOOT = "</body></html>";

    // ── DOCX → HTML ──────────────────────────────────────────────────────────

    public byte[] docxToHtml(byte[] docxBytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            StringBuilder sb = new StringBuilder(HTML_HEAD);
            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph p) {
                    appendParagraph(sb, p);
                } else if (elem instanceof XWPFTable t) {
                    appendTable(sb, t);
                }
            }
            sb.append(HTML_FOOT);
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph p) {
        String text = p.getText();
        if (text == null || text.isBlank()) { sb.append("<br>"); return; }
        String style = p.getStyle();
        if (style != null && style.matches("(?i)Heading[123]")) {
            int level = Character.getNumericValue(style.charAt(style.length() - 1));
            sb.append("<h").append(level).append(">")
                    .append(escHtml(text))
                    .append("</h").append(level).append(">\n");
        } else {
            sb.append("<p>").append(escHtml(text)).append("</p>\n");
        }
    }

    private void appendTable(StringBuilder sb, XWPFTable t) {
        sb.append("<table>\n");
        boolean firstRow = true;
        for (XWPFTableRow row : t.getRows()) {
            sb.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                String tag = firstRow ? "th" : "td";
                sb.append("<").append(tag).append(">")
                        .append(escHtml(cell.getText()))
                        .append("</").append(tag).append(">");
            }
            sb.append("</tr>\n");
            firstRow = false;
        }
        sb.append("</table>\n");
    }

    // ── XLSX/XLS → HTML ──────────────────────────────────────────────────────

    public byte[] xlsxToHtml(byte[] xlsxBytes, String mimeType) throws IOException {
        try (InputStream is = new ByteArrayInputStream(xlsxBytes);
             Workbook wb = "application/vnd.ms-excel".equals(mimeType)
                     ? new HSSFWorkbook(is)
                     : new XSSFWorkbook(is)) {

            StringBuilder sb = new StringBuilder(HTML_HEAD);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter fmt = new DataFormatter();

            // Sheet tabs
            if (wb.getNumberOfSheets() > 1) {
                sb.append("<div style='margin-bottom:12px'>");
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    sb.append("<span class='sheet-tab").append(i == 0 ? " active" : "")
                            .append("'>").append(escHtml(wb.getSheetName(i))).append("</span>");
                }
                sb.append("</div>");
            }

            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                if (wb.getNumberOfSheets() > 1) {
                    sb.append("<div class='sheet' id='sheet-").append(si)
                            .append("' style='").append(si > 0 ? "display:none" : "").append("'>");
                    sb.append("<div class='sheet-tab active' style='margin-bottom:8px'>")
                            .append(escHtml(sheet.getSheetName())).append("</div>");
                }
                sb.append("<table>\n");
                int rowCount = 0;
                boolean truncated = false;
                for (Row row : sheet) {
                    if (rowCount++ > XLSX_MAX_ROWS) { truncated = true; break; }
                    sb.append("<tr>");
                    int colCount = 0;
                    for (Cell cell : row) {
                        if (colCount++ > CSV_MAX_COLS) break;
                        String tag = rowCount == 1 ? "th" : "td";
                        String val = "";
                        try {
                            CellValue cv = eval.evaluate(cell);
                            if (cv != null) {
                                val = switch (cv.getCellType()) {
                                    case BOOLEAN -> String.valueOf(cv.getBooleanValue());
                                    case NUMERIC -> fmt.formatCellValue(cell, eval);
                                    case STRING  -> cv.getStringValue();
                                    default      -> "";
                                };
                            }
                        } catch (Exception ignored) {
                            try { val = fmt.formatCellValue(cell); } catch (Exception ignored2) {}
                        }
                        sb.append("<").append(tag).append(">").append(escHtml(val))
                                .append("</").append(tag).append(">");
                    }
                    sb.append("</tr>\n");
                }
                sb.append("</table>\n");
                if (truncated) sb.append("<p class='truncated'>Showing first ").append(XLSX_MAX_ROWS).append(" rows only.</p>");
                if (wb.getNumberOfSheets() > 1) sb.append("</div>");
            }

            // Sheet tab JS switcher (only when multiple sheets)
            if (wb.getNumberOfSheets() > 1) {
                sb.append("""
                    <script>
                    document.querySelectorAll('.sheet-tab').forEach((tab,i) => {
                      tab.addEventListener('click', () => {
                        document.querySelectorAll('.sheet').forEach(s=>s.style.display='none');
                        document.querySelectorAll('.sheet-tab').forEach(t=>t.classList.remove('active'));
                        document.getElementById('sheet-'+i).style.display='';
                        tab.classList.add('active');
                      });
                    });
                    </script>""");
            }

            sb.append(HTML_FOOT);
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    // ── CSV → HTML ───────────────────────────────────────────────────────────

    public byte[] csvToHtml(byte[] csvBytes) {
        String raw = new String(csvBytes, StandardCharsets.UTF_8);
        String[] lines = raw.split("\\r?\\n");
        StringBuilder sb = new StringBuilder(HTML_HEAD);
        sb.append("<table>\n");
        boolean truncated = false;
        for (int i = 0; i < lines.length; i++) {
            if (i >= CSV_MAX_ROWS) { truncated = true; break; }
            String[] cols = parseCsvLine(lines[i]);
            sb.append("<tr>");
            for (int c = 0; c < Math.min(cols.length, CSV_MAX_COLS); c++) {
                String tag = i == 0 ? "th" : "td";
                sb.append("<").append(tag).append(">").append(escHtml(cols[c]))
                        .append("</").append(tag).append(">");
            }
            sb.append("</tr>\n");
        }
        sb.append("</table>\n");
        if (truncated) sb.append("<p class='truncated'>Showing first ").append(CSV_MAX_ROWS).append(" rows only.</p>");
        sb.append(HTML_FOOT);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── TXT → HTML ───────────────────────────────────────────────────────────

    public byte[] txtToHtml(byte[] txtBytes) {
        String text = new String(txtBytes, StandardCharsets.UTF_8);
        return (HTML_HEAD + "<pre>" + escHtml(text) + "</pre>" + HTML_FOOT)
                .getBytes(StandardCharsets.UTF_8);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * Naive but correct CSV line parser that handles quoted fields with commas.
     */
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"') { inQuotes = !inQuotes; }
            else if (c == ',' && !inQuotes) { fields.add(current.toString()); current = new StringBuilder(); }
            else { current.append(c); }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}