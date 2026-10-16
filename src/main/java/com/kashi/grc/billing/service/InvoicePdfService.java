package com.kashi.grc.billing.service;

import com.kashi.grc.billing.domain.BillingInvoice;
import com.kashi.grc.billing.domain.BillingInvoiceLine;
import com.kashi.grc.common.exception.BusinessException;
import com.kashi.grc.tenant.domain.Tenant;
import com.kashi.grc.tenant.repository.TenantRepository;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders a billing invoice as a PDF on the server, with the company logo.
 *
 * ── LIBRARY ──────────────────────────────────────────────────────────────────
 * OpenPDF (com.github.librepdf:openpdf, LGPL/MPL) — the Java counterpart of
 * PDFKit: the page is drawn programmatically (tables, text, images), with no
 * browser or HTML engine involved, so the output is identical on every run.
 *
 * ── ALWAYS FROM THE DATABASE ─────────────────────────────────────────────────
 * The PDF is generated on request from the invoice row, never stored. An
 * ISSUED invoice cannot change (InvoiceService refuses edits after issue), so
 * the same PDF comes out every time; a DRAFT carries a DRAFT watermark and a
 * VOID one a VOID watermark, so neither can be mistaken for a bill.
 *
 * ── SELLER DETAILS (application.properties) ──────────────────────────────────
 *   kashi.billing.seller.name           trading name shown in the header
 *   kashi.billing.seller.legal-name     registered name in the From block
 *   kashi.billing.seller.address        use | for line breaks
 *   kashi.billing.seller.gstin / .pan / .email / .phone / .website
 *   kashi.billing.seller.logo           classpath:billing/invoice-logo.png or file:/path/logo.png
 *   kashi.billing.seller.sac            SAC printed on lines that have none (confirm with your CA)
 *   kashi.billing.bank.name / .account-name / .account-number / .ifsc / .upi
 *   kashi.billing.invoice.footer        closing line
 * Anything unset is simply left off the page.
 *
 * Fonts are the PDF base-14 Helvetica, which has no ₹ glyph, so amounts print
 * as "INR 3,00,000.00" — the form Indian GST invoices commonly use anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private final InvoiceService   invoiceService;
    private final TenantRepository tenantRepository;
    private final ResourceLoader   resourceLoader;

    @Value("${kashi.billing.seller.name:KashiGuard}")              private String sellerName;
    @Value("${kashi.billing.seller.legal-name:}")                  private String sellerLegalName;
    @Value("${kashi.billing.seller.address:}")                     private String sellerAddress;
    @Value("${kashi.billing.seller.gstin:}")                       private String sellerGstin;
    @Value("${kashi.billing.seller.pan:}")                         private String sellerPan;
    @Value("${kashi.billing.seller.email:billing@kashiguard.com}") private String sellerEmail;
    @Value("${kashi.billing.seller.phone:}")                       private String sellerPhone;
    @Value("${kashi.billing.seller.website:www.kashiguard.com}")   private String sellerWebsite;
    @Value("${kashi.billing.seller.logo:classpath:billing/invoice-logo.png}") private String logoLocation;
    @Value("${kashi.billing.seller.sac:}")                         private String defaultSac;
    @Value("${kashi.billing.bank.name:}")                          private String bankName;
    @Value("${kashi.billing.bank.account-name:}")                  private String bankAccountName;
    @Value("${kashi.billing.bank.account-number:}")                private String bankAccountNumber;
    @Value("${kashi.billing.bank.ifsc:}")                          private String bankIfsc;
    @Value("${kashi.billing.bank.upi:}")                           private String bankUpi;
    @Value("${kashi.billing.invoice.footer:This is a computer-generated invoice and does not require a signature.}")
    private String footerNote;

    // ── palette (matches the app's ink/amber brand) ─────────────────────────
    private static final Color INK    = new Color(0x0F, 0x2E, 0x2C);
    private static final Color MUTED  = new Color(0x4A, 0x62, 0x5E);
    private static final Color LINE   = new Color(0xD2, 0xDC, 0xD7);
    private static final Color SUNK   = new Color(0xF2, 0xF5, 0xF3);
    private static final Color ACCENT = new Color(0xD8, 0x92, 0x1B);
    private static final Color OK     = new Color(0x2E, 0x7A, 0x58);
    private static final Color BAD    = new Color(0xA8, 0x41, 0x2C);

    private static final Font H1     = new Font(Font.HELVETICA, 18f, Font.BOLD, INK);
    private static final Font BRAND  = new Font(Font.HELVETICA, 16f, Font.BOLD, INK);
    private static final Font LABEL  = new Font(Font.HELVETICA, 7.5f, Font.BOLD, MUTED);
    private static final Font BODY   = new Font(Font.HELVETICA, 9f, Font.NORMAL, INK);
    private static final Font BODY_B = new Font(Font.HELVETICA, 9f, Font.BOLD, INK);
    private static final Font SMALL  = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, MUTED);
    private static final Font TOTAL  = new Font(Font.HELVETICA, 11f, Font.BOLD, INK);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    @Transactional(readOnly = true)
    public byte[] render(Long invoiceId) {
        BillingInvoice inv = invoiceService.require(invoiceId);
        List<BillingInvoiceLine> lines = invoiceService.lines(invoiceId);
        Tenant tenant = tenantRepository.findById(inv.getTenantId()).orElse(null);
        String status = InvoiceService.effectiveStatus(inv);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 42, 42, 42, 60);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Decorations(status));
            doc.addTitle(title(inv));
            doc.addAuthor(sellerName);
            doc.addCreator("KashiGRC billing");
            doc.open();

            doc.add(header(inv, status));
            doc.add(spacer(14));
            doc.add(parties(inv, tenant));
            doc.add(spacer(14));
            doc.add(lineTable(inv, lines));
            doc.add(totals(inv, status));
            doc.add(spacer(8));
            doc.add(amountInWords(inv));
            if (notBlank(inv.getNotes())) {
                doc.add(spacer(10));
                doc.add(labelled("Notes", inv.getNotes()));
            }
            PdfPTable bank = bankDetails(status);
            if (bank != null) {
                doc.add(spacer(10));
                doc.add(bank);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[INVOICE-PDF] render failed | invoice={} : {}", invoiceId, e.getMessage(), e);
            throw new BusinessException("INVOICE_PDF_FAILED", "Could not generate the invoice PDF.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Download name: the invoice number, or draft-<id> before issue. */
    public String fileName(Long invoiceId) {
        BillingInvoice inv = invoiceService.require(invoiceId);
        String base = notBlank(inv.getInvoiceNumber()) ? inv.getInvoiceNumber().replaceAll("[^A-Za-z0-9_-]", "-") : "draft-" + inv.getId();
        return "invoice-" + base + ".pdf";
    }

    // ── sections ─────────────────────────────────────────────────────────────

    private PdfPTable header(BillingInvoice inv, String status) throws Exception {
        PdfPTable t = table(new float[]{1.2f, 1f});

        PdfPCell brand = cell();
        Image logo = logo();
        if (logo != null) {
            logo.scaleToFit(150, 46);
            brand.addElement(logo);
        } else {
            brand.addElement(new Paragraph(sellerName, BRAND));
        }
        t.addCell(brand);

        PdfPCell right = cell();
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph h = new Paragraph(documentTitle(inv, status), H1);
        h.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(h);
        right.addElement(rightLine("Invoice no.", notBlank(inv.getInvoiceNumber()) ? inv.getInvoiceNumber() : "Not issued yet"));
        right.addElement(rightLine("Invoice date", date(inv.getIssueDate())));
        right.addElement(rightLine("Due date", date(inv.getDueDate())));
        if (inv.getPeriodStart() != null) {
            right.addElement(rightLine("Service period", date(inv.getPeriodStart()) + " – " + date(inv.getPeriodEnd())));
        }
        t.addCell(right);
        return t;
    }

    private PdfPTable parties(BillingInvoice inv, Tenant tenant) {
        PdfPTable t = table(new float[]{1f, 1f});
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        PdfPCell from = boxed();
        from.addElement(new Paragraph("FROM", LABEL));
        from.addElement(new Paragraph(notBlank(sellerLegalName) ? sellerLegalName : sellerName, BODY_B));
        for (String l : splitLines(sellerAddress)) from.addElement(new Paragraph(l, BODY));
        if (notBlank(sellerGstin)) from.addElement(new Paragraph("GSTIN: " + sellerGstin, BODY));
        if (notBlank(sellerPan))   from.addElement(new Paragraph("PAN: " + sellerPan, BODY));
        String contact = join(" · ", sellerEmail, sellerPhone, sellerWebsite);
        if (notBlank(contact)) from.addElement(new Paragraph(contact, SMALL));
        t.addCell(from);

        PdfPCell to = boxed();
        to.addElement(new Paragraph("BILL TO", LABEL));
        String name = notBlank(inv.getBillToName()) ? inv.getBillToName()
                : tenant == null ? "Tenant #" + inv.getTenantId()
                : notBlank(tenant.getLegalName()) ? tenant.getLegalName() : tenant.getName();
        to.addElement(new Paragraph(name, BODY_B));
        String address = notBlank(inv.getBillToAddress()) ? inv.getBillToAddress()
                : tenant == null ? null : tenant.getBillingAddress();
        for (String l : splitLines(address)) to.addElement(new Paragraph(l, BODY));
        String taxId = notBlank(inv.getBillToTaxId()) ? inv.getBillToTaxId() : tenant == null ? null : tenant.getTaxId();
        if (notBlank(taxId)) to.addElement(new Paragraph(("IN".equals(tenant == null ? null : tenant.getCountry()) ? "GSTIN: " : "Tax ID: ") + taxId, BODY));
        if (tenant != null && notBlank(tenant.getBillingEmail())) to.addElement(new Paragraph(tenant.getBillingEmail(), SMALL));
        if (tenant != null && notBlank(tenant.getCountry())) to.addElement(new Paragraph("Place of supply: " + tenant.getCountry(), SMALL));
        t.addCell(to);
        return t;
    }

    private PdfPTable lineTable(BillingInvoice inv, List<BillingInvoiceLine> lines) {
        PdfPTable t = table(new float[]{0.35f, 4.2f, 0.9f, 0.6f, 1.6f, 1.7f});
        t.setHeaderRows(1);
        for (String h : new String[]{"#", "Description", "SAC", "Qty", "Unit price", "Amount"}) {
            PdfPCell c = new PdfPCell(new Phrase(h.toUpperCase(Locale.ROOT), LABEL));
            c.setBackgroundColor(SUNK);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(LINE);
            c.setPadding(6);
            if (!h.equals("Description") && !h.equals("#")) c.setHorizontalAlignment(Element.ALIGN_RIGHT);
            t.addCell(c);
        }
        int i = 1;
        for (BillingInvoiceLine l : lines) {
            t.addCell(row(String.valueOf(i++), Element.ALIGN_LEFT));
            t.addCell(row(l.getDescription(), Element.ALIGN_LEFT));
            t.addCell(row(notBlank(l.getHsnSac()) ? l.getHsnSac() : (notBlank(defaultSac) ? defaultSac : "—"), Element.ALIGN_RIGHT));
            t.addCell(row(String.valueOf(l.getQuantity() == null ? 1 : l.getQuantity()), Element.ALIGN_RIGHT));
            t.addCell(row(money(l.getUnitPriceMinor(), inv.getCurrency()), Element.ALIGN_RIGHT));
            t.addCell(row(money(l.getAmountMinor(), inv.getCurrency()), Element.ALIGN_RIGHT));
        }
        if (lines.isEmpty()) {
            PdfPCell c = new PdfPCell(new Phrase("No line items.", SMALL));
            c.setColspan(6);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(LINE);
            c.setPadding(8);
            t.addCell(c);
        }
        return t;
    }

    private PdfPTable totals(BillingInvoice inv, String status) {
        PdfPTable outer = table(new float[]{1.3f, 1f});
        outer.addCell(cell());   // empty left half

        PdfPTable t = table(new float[]{1.4f, 1f});
        t.addCell(totalLabel("Subtotal"));
        t.addCell(totalValue(money(inv.getSubtotalMinor(), inv.getCurrency()), BODY));
        String taxLabel = notBlank(inv.getTaxLabel()) ? inv.getTaxLabel()
                : "Tax " + new BigDecimal(inv.getTaxRateBp() == null ? 0 : inv.getTaxRateBp()).movePointLeft(2).stripTrailingZeros().toPlainString() + "%";
        t.addCell(totalLabel(taxLabel));
        t.addCell(totalValue(money(inv.getTaxMinor(), inv.getCurrency()), BODY));

        PdfPCell tl = totalLabel("Total");
        tl.setBorder(Rectangle.TOP);
        tl.setBorderColor(INK);
        tl.setPhrase(new Phrase("Total", TOTAL));
        t.addCell(tl);
        PdfPCell tv = totalValue(money(inv.getTotalMinor(), inv.getCurrency()), TOTAL);
        tv.setBorder(Rectangle.TOP);
        tv.setBorderColor(INK);
        t.addCell(tv);

        if ("PAID".equals(status)) {
            t.addCell(totalLabel("Paid " + (inv.getPaidAt() == null ? "" : date(inv.getPaidAt().toLocalDate()))));
            t.addCell(totalValue(money(inv.getTotalMinor(), inv.getCurrency()), new Font(Font.HELVETICA, 9f, Font.BOLD, OK)));
            if (notBlank(inv.getPaymentReference())) {
                PdfPCell ref = totalLabel("Ref: " + inv.getPaymentReference());
                ref.setColspan(2);
                t.addCell(ref);
            }
        } else if (!"VOID".equals(status) && !"DRAFT".equals(status)) {
            t.addCell(totalLabel("Balance due"));
            t.addCell(totalValue(money(inv.getTotalMinor(), inv.getCurrency()),
                    new Font(Font.HELVETICA, 9f, Font.BOLD, "OVERDUE".equals(status) ? BAD : INK)));
        }

        PdfPCell right = cell();
        right.addElement(t);
        outer.addCell(right);
        return outer;
    }

    private PdfPTable amountInWords(BillingInvoice inv) {
        String words = "INR".equals(inv.getCurrency())
                ? "Indian Rupees " + IndianWords.of(inv.getTotalMinor()) + " Only"
                : inv.getCurrency() + " " + money(inv.getTotalMinor(), inv.getCurrency()).replace(inv.getCurrency() + " ", "");
        return labelled("Amount in words", words);
    }

    private PdfPTable bankDetails(String status) {
        if ("PAID".equals(status) || "VOID".equals(status)) return null;
        if (!notBlank(bankAccountNumber) && !notBlank(bankUpi)) return null;
        StringBuilder sb = new StringBuilder();
        if (notBlank(bankAccountName))   sb.append("Account name: ").append(bankAccountName).append('\n');
        if (notBlank(bankName))          sb.append("Bank: ").append(bankName).append('\n');
        if (notBlank(bankAccountNumber)) sb.append("Account no.: ").append(bankAccountNumber).append('\n');
        if (notBlank(bankIfsc))          sb.append("IFSC: ").append(bankIfsc).append('\n');
        if (notBlank(bankUpi))           sb.append("UPI: ").append(bankUpi).append('\n');
        sb.append("Please quote the invoice number with your payment.");
        return labelled("Payment details", sb.toString());
    }

    // ── page decorations: watermark + footer ────────────────────────────────

    private class Decorations extends PdfPageEventHelper {
        private final String status;
        Decorations(String status) { this.status = status; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float mid = (document.left() + document.right()) / 2;

            // Footer
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase(footerNote, SMALL), mid, document.bottom() - 22, 0);
            String legal = join(" · ", notBlank(sellerLegalName) ? sellerLegalName : sellerName,
                    notBlank(sellerGstin) ? "GSTIN " + sellerGstin : null);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase(legal + "   ·   Page " + writer.getPageNumber(), SMALL), mid, document.bottom() - 34, 0);

            // Accent rule along the top edge
            cb.saveState();
            cb.setColorFill(ACCENT);
            cb.rectangle(0, document.getPageSize().getHeight() - 5, document.getPageSize().getWidth(), 5);
            cb.fill();
            cb.restoreState();

            // A draft or void invoice must never pass for a bill.
            if ("DRAFT".equals(status) || "VOID".equals(status)) {
                try {
                    PdfContentByte under = writer.getDirectContentUnder();
                    PdfGState gs = new PdfGState();
                    gs.setFillOpacity(0.08f);
                    under.saveState();
                    under.setGState(gs);
                    under.beginText();
                    under.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, false), 120);
                    under.setColorFill("VOID".equals(status) ? BAD : INK);
                    under.showTextAligned(Element.ALIGN_CENTER, status,
                            document.getPageSize().getWidth() / 2, document.getPageSize().getHeight() / 2, 45);
                    under.endText();
                    under.restoreState();
                } catch (Exception e) {
                    log.warn("[INVOICE-PDF] watermark skipped: {}", e.getMessage());
                }
            }
        }
    }

    // ── building blocks ─────────────────────────────────────────────────────

    private Image logo() {
        if (!notBlank(logoLocation)) return null;
        try {
            Resource r = resourceLoader.getResource(logoLocation);
            if (!r.exists()) return null;
            try (InputStream in = r.getInputStream()) {
                return Image.getInstance(in.readAllBytes());
            }
        } catch (Exception e) {
            log.warn("[INVOICE-PDF] logo {} not usable: {}", logoLocation, e.getMessage());
            return null;
        }
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        return t;
    }

    private static PdfPCell cell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell boxed() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(LINE);
        c.setPadding(10);
        c.setPaddingTop(8);
        return c;
    }

    private static PdfPCell row(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, BODY));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setPadding(6);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static PdfPCell totalLabel(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(5);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static PdfPCell totalValue(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(5);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static Paragraph rightLine(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + "  ", SMALL));
        p.add(new Chunk(value, BODY_B));
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static PdfPTable labelled(String label, String text) {
        PdfPTable t = table(new float[]{1f});
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(SUNK);
        c.setPadding(8);
        c.addElement(new Paragraph(label.toUpperCase(Locale.ROOT), LABEL));
        c.addElement(new Paragraph(text, BODY));
        t.addCell(c);
        return t;
    }

    private static Paragraph spacer(float h) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(h);
        return p;
    }

    private static String documentTitle(BillingInvoice inv, String status) {
        if ("DRAFT".equals(status)) return "DRAFT INVOICE";
        boolean taxed = inv.getTaxRateBp() != null && inv.getTaxRateBp() > 0;
        return taxed ? "TAX INVOICE" : "INVOICE";
    }

    private static String title(BillingInvoice inv) {
        return "Invoice " + (inv.getInvoiceNumber() == null ? "(draft #" + inv.getId() + ")" : inv.getInvoiceNumber());
    }

    private static String date(LocalDate d) { return d == null ? "—" : d.format(DATE); }

    /** INR in Indian grouping (3,00,000.00); other currencies in 1,000.00 grouping. Always with the code. */
    static String money(Long minor, String currency) {
        long v = minor == null ? 0 : minor;
        boolean neg = v < 0;
        BigDecimal major = BigDecimal.valueOf(Math.abs(v)).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        String[] parts = major.toPlainString().split("\\.");
        String whole = parts[0];
        String grouped;
        if ("INR".equals(currency)) {
            if (whole.length() <= 3) grouped = whole;
            else {
                String last3 = whole.substring(whole.length() - 3);
                String rest = whole.substring(0, whole.length() - 3);
                StringBuilder sb = new StringBuilder();
                while (rest.length() > 2) {
                    sb.insert(0, "," + rest.substring(rest.length() - 2));
                    rest = rest.substring(0, rest.length() - 2);
                }
                grouped = rest + sb + "," + last3;
            }
        } else {
            grouped = String.format(Locale.ENGLISH, "%,d", Long.parseLong(whole));
        }
        return (currency == null ? "" : currency + " ") + (neg ? "-" : "") + grouped + "." + parts[1];
    }

    private static List<String> splitLines(String s) {
        if (!notBlank(s)) return List.of();
        return java.util.Arrays.stream(s.split("\\||\\r?\\n")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!notBlank(p)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Rupees and paise in words, Indian system (lakh, crore). */
    static final class IndianWords {
        private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
                "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
        private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

        static String of(Long minor) {
            long v = Math.abs(minor == null ? 0 : minor);
            long rupees = v / 100, paise = v % 100;
            String r = rupees == 0 ? "Zero" : words(rupees);
            return paise == 0 ? r : r + " and " + two((int) paise) + " Paise";
        }

        private static String words(long n) {
            StringBuilder sb = new StringBuilder();
            long crore = n / 10_000_000; n %= 10_000_000;
            long lakh = n / 100_000;     n %= 100_000;
            long thousand = n / 1000;    n %= 1000;
            long hundred = n / 100;      n %= 100;
            if (crore > 0)    sb.append(words(crore)).append(" Crore ");
            if (lakh > 0)     sb.append(two((int) lakh)).append(" Lakh ");
            if (thousand > 0) sb.append(two((int) thousand)).append(" Thousand ");
            if (hundred > 0)  sb.append(ONES[(int) hundred]).append(" Hundred ");
            if (n > 0)        sb.append(sb.length() > 0 ? "and " : "").append(two((int) n));
            return sb.toString().trim();
        }

        private static String two(int n) {
            if (n < 20) return ONES[n];
            return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + ONES[n % 10]);
        }
    }
}
