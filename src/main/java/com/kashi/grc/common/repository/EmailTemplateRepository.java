package com.kashi.grc.common.repository;

import com.kashi.grc.common.domain.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {
    Optional<EmailTemplate> findByName(String name);
    Optional<EmailTemplate> findByNameAndIsActiveTrue(String name);
}
