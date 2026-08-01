package com.kashi.grc.ucf.repository;

import com.kashi.grc.ucf.domain.CommonControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Derived-name queries only. Anything needing predicates built at runtime lives
 * in CommonControlRepositoryCustom, implemented with the JPA Criteria API in
 * CommonControlRepositoryImpl — matching the convention used across the audit
 * and evidence packages.
 */
@Repository
public interface CommonControlRepository
        extends JpaRepository<CommonControl, Long>, CommonControlRepositoryCustom {

    Optional<CommonControl> findByCode(String code);

    boolean existsByCode(String code);

    List<CommonControl> findByParentCodeOrderBySortOrderAsc(String parentCode);

    List<CommonControl> findByDomainCodeOrderBySortOrderAsc(String domainCode);

    List<CommonControl> findByActiveTrueOrderBySortOrderAsc();

    List<CommonControl> findByCodeIn(List<String> codes);

    Optional<CommonControl> findByLegacyTag(String legacyTag);
}