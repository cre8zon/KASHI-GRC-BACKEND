package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatMessage;

import java.time.LocalDateTime;
import java.util.List;

/** Criteria API lookups — see the Impl. */
public interface SiteChatMessageRepositoryCustom {

    /** A whole conversation, oldest first. */
    List<SiteChatMessage> findBySession(Long sessionId);

    /** The last {@code limit} messages of a conversation, oldest first. */
    List<SiteChatMessage> findRecentBySession(Long sessionId, int limit);

    /** Assistant answers written since a moment — the site-wide daily cap. */
    long countByRoleSince(String role, LocalDateTime since);

    /** Answers the assistant was not confident in, newest first. */
    List<SiteChatMessage> findUnconfidentSince(LocalDateTime since, int limit);
}
