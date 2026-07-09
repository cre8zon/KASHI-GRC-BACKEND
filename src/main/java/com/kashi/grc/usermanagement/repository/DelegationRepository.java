package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.Delegation;
import org.springframework.data.jpa.repository.JpaRepository;

/** All query logic lives in DelegationRepositoryCustom (JPA Criteria API). */
public interface DelegationRepository
        extends JpaRepository<Delegation, Long>, DelegationRepositoryCustom {
}
