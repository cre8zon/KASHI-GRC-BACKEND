package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String>,
        RevokedTokenRepositoryCustom {
}
