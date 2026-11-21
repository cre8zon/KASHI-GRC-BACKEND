package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatSession;

import java.util.Optional;

/** Criteria API lookups — see the Impl. */
public interface SiteChatSessionRepositoryCustom {

    /** The session whose browser token hashes to this value. */
    Optional<SiteChatSession> findByTokenHash(String tokenHash);

    /** The chat an enquiry came from, if it came from one. */
    Optional<SiteChatSession> findByEnquiryId(Long enquiryId);
}
