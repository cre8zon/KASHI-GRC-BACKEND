package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.UserAttribute;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * All query logic lives in UserAttributeRepositoryCustom (JPA Criteria API).
 * NOTE: the delete methods were @Transactional on the old interface; callers
 * (UserServiceImpl) run inside transactions already, and CriteriaDelete
 * requires one — keep invocations transactional.
 */
public interface UserAttributeRepository
        extends JpaRepository<UserAttribute, Long>, UserAttributeRepositoryCustom {
}
