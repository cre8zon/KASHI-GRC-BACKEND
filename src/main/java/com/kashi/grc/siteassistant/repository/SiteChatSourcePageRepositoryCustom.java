package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatSourcePage;

import java.util.List;
import java.util.Optional;

/** Criteria API lookups — see the Impl. */
public interface SiteChatSourcePageRepositoryCustom {

    Optional<SiteChatSourcePage> findByPath(String path);

    /** Pages currently answerable from. */
    List<SiteChatSourcePage> findActive();
}
