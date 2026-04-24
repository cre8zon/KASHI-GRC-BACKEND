package com.kashi.grc.uiconfig.dto.request;

import lombok.Data;

@Data
public class TenantBrandingRequest {
    public String companyName;
    public String logoUrl;
    public String faviconUrl;
    public String primaryColor;
    public String accentColor;
    public String sidebarTheme;
    public String supportEmail;
    public String supportUrl;
    public String footerText;
}
