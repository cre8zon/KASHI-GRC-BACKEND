package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatSourcePage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteChatSourcePageRepository
        extends JpaRepository<SiteChatSourcePage, Long>, SiteChatSourcePageRepositoryCustom {
}
