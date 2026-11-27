package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteChatSessionRepository
        extends JpaRepository<SiteChatSession, Long>, SiteChatSessionRepositoryCustom {
}
