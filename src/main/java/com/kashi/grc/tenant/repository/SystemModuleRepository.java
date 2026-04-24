package com.kashi.grc.tenant.repository;

import com.kashi.grc.tenant.domain.SystemModule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SystemModuleRepository extends JpaRepository<SystemModule, Long> {
    Optional<SystemModule> findByCode(String code);
    boolean existsByCode(String code);
}
