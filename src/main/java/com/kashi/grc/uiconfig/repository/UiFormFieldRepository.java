package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiFormField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UiFormFieldRepository extends JpaRepository<UiFormField, Long> {
    List<UiFormField> findByFormIdAndIsVisibleTrueOrderBySortOrder(Long formId);
}
