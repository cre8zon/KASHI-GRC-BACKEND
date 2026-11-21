package com.kashi.grc.siteassistant.service;

/** Events of the website assistant, handled after commit (see SiteChatNotifyService). */
public final class SiteChatEvents {

    private SiteChatEvents() {}

    /** The team replied to a chat visitor: email them the reply. */
    public record StaffReplied(Long enquiryId, Long messageId) {}
}
