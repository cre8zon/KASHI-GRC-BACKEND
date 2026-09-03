package com.kashi.grc.ai.repository;

import com.kashi.grc.ai.domain.AiPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiPromptTemplateRepository extends JpaRepository<AiPromptTemplate, Long> {

    /**
     * Tenant-first, global-fallback resolution in ONE query.
     *
     * The ordering clause is the whole trick: rows where tenant_id matches sort
     * before the global row, so the first result is the tenant override when one
     * exists and the platform default otherwise. Doing this as two queries would
     * cost a second Aiven round-trip on a path that runs before every model call.
     */
    @Query("""
           select t from AiPromptTemplate t
           where t.templateKey = :key
             and t.active = true
             and (t.tenantId = :tenantId or t.tenantId is null)
           order by case when t.tenantId is null then 1 else 0 end asc, t.version desc
           """)
    List<AiPromptTemplate> resolveActive(@Param("key") String key, @Param("tenantId") Long tenantId);

    Optional<AiPromptTemplate> findByTemplateKeyAndVersionAndTenantId(String key, Integer version, Long tenantId);

    List<AiPromptTemplate> findByTemplateKeyOrderByVersionDesc(String templateKey);

    @Query("select coalesce(max(t.version), 0) from AiPromptTemplate t where t.templateKey = :key and (t.tenantId = :tenantId or (:tenantId is null and t.tenantId is null))")
    Integer maxVersion(@Param("key") String key, @Param("tenantId") Long tenantId);

    boolean existsByTemplateKeyAndTenantId(String templateKey, Long tenantId);

    List<AiPromptTemplate> findByActiveTrueOrderByTemplateKeyAsc();
}
