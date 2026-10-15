package com.kashi.grc.enquiry.service;

/** Published inside EnquiryService transactions; mail goes out AFTER_COMMIT in EnquiryNotifyService. */
public final class EnquiryEvents {
    private EnquiryEvents() {}

    /** A new enquiry was saved from the website. */
    public record Received(Long enquiryId) {}
}
