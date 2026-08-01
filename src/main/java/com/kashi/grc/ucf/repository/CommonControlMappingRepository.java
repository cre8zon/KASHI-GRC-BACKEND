package com.kashi.grc.ucf.repository;

import com.kashi.grc.ucf.domain.CommonControlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommonControlMappingRepository extends JpaRepository<CommonControlMapping, Long> {

    List<CommonControlMapping> findByCommonControlCodeAndActiveTrue(String commonControlCode);

    List<CommonControlMapping> findByCommonControlCodeInAndActiveTrue(List<String> codes);

    List<CommonControlMapping> findByFrameworkRefAndActiveTrueOrderByCitationAsc(String frameworkRef);

    boolean existsByCommonControlCodeAndFrameworkRefAndCitation(
            String commonControlCode, String frameworkRef, String citation);
}