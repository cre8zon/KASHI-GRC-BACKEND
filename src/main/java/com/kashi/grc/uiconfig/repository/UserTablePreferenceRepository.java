package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UserTablePreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTablePreferenceRepository
        extends JpaRepository<UserTablePreference, Long>, UserTablePreferenceRepositoryCustom {
}
