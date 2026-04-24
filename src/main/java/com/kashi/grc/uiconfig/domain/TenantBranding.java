package com.kashi.grc.uiconfig.domain;

import com.kashi.grc.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Per-tenant branding: logo, colors, company name.
 * Injected as CSS variables on the frontend at login time.
 * Each client gets their own look — zero code deploy.
 */
@Entity
@Table(name = "tenant_branding")
@Getter @Setter
@lombok.experimental.SuperBuilder
@NoArgsConstructor @AllArgsConstructor
public class TenantBranding extends BaseEntity {

    @Column(name = "tenant_id", unique = true, nullable = false)
    private Long tenantId;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "favicon_url", length = 500)
    private String faviconUrl;

    /** Primary brand color as hex. e.g. '#1e40af' */
    @Column(name = "primary_color", length = 20)
    @Builder.Default
    private String primaryColor = "#1e40af";

    /** Accent color as hex */
    @Column(name = "accent_color", length = 20)
    @Builder.Default
    private String accentColor = "#7c3aed";

    /** 'light' or 'dark' */
    @Column(name = "sidebar_theme", length = 20)
    @Builder.Default
    private String sidebarTheme = "dark";

    /** Support email shown in the UI footer */
    @Column(name = "support_email", length = 255)
    private String supportEmail;

    /** Support URL for help links */
    @Column(name = "support_url", length = 500)
    private String supportUrl;

    /** Custom footer text */
    @Column(name = "footer_text", length = 500)
    private String footerText;
}
