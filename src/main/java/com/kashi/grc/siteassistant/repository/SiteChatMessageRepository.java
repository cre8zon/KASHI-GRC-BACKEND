package com.kashi.grc.siteassistant.repository;

import com.kashi.grc.siteassistant.domain.SiteChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteChatMessageRepository
        extends JpaRepository<SiteChatMessage, Long>, SiteChatMessageRepositoryCustom {
}
