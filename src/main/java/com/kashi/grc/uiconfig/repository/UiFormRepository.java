package com.kashi.grc.uiconfig.repository;

import com.kashi.grc.uiconfig.domain.UiForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UiFormRepository extends JpaRepository<UiForm, Long> {
    Optional<UiForm> findByFormKey(String formKey);
    Optional<UiForm> findByFormKeyAndTenantId(String formKey, Long tenantId);
}
