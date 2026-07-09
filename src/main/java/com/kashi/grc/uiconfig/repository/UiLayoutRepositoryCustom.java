package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiLayout;
import java.util.List;

/** Criteria API fragment for UiLayoutRepository. */
public interface UiLayoutRepositoryCustom {

    /** All layouts for a screen key (tenant-overlay) — Screen Designer list. ORDER BY id. */
    List<UiLayout> findAllByScreenAndTenant(String screen, Long tenantId);

    /** ALL layouts across screens (tenant-overlay) — Screen Designer registry. ORDER BY screen, id. */
    List<UiLayout> findAllByTenant(Long tenantId);
}
