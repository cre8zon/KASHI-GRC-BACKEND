package com.kashi.grc.enquiry.dto;

import lombok.Data;

/**
 * What the website forms post to POST /v1/enquiries/public.
 * Every field is optional except fullName and email; the service validates.
 */
@Data
public class EnquirySubmitRequest {
    /** DEMO | TRIAL | CONTACT_SALES | AUDIT_FIRM | PARTNERSHIP | SUPPORT | GENERAL. Default DEMO. */
    private String type;

    private String fullName;
    private String email;
    private String phone;
    private String company;
    private String jobTitle;
    private String country;
    private String employeeBand;
    private Integer vendorCount;

    private String planCode;
    private String topic;
    private String subject;
    private String message;
    private String heardAbout;

    private String sourcePage;
    private String landingPage;
    private String referrer;
    private String utmSource;
    private String utmMedium;
    private String utmCampaign;
    private String utmTerm;
    private String utmContent;
    private String referralCode;

    private Boolean marketingConsent;

    /**
     * Honeypot. Rendered off-screen on the website, so people never fill it and
     * form-filling bots do. A non-empty value is accepted with the normal
     * response and silently not stored.
     */
    private String website;
}
