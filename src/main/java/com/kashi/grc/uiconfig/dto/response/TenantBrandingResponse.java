package com.kashi.grc.uiconfig.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantBrandingResponse {
    private String companyName;
    private String logoUrl;
    private String faviconUrl;
    private String primaryColor;
    private String accentColor;
    private String sidebarTheme;
    private String supportEmail;
    private String supportUrl;
    private String footerText;
}
