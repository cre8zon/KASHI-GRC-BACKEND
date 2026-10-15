package com.kashi.grc.enquiry.repository;

import com.kashi.grc.enquiry.domain.Enquiry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EnquiryRepository extends JpaRepository<Enquiry, Long>, EnquiryRepositoryCustom {
    Optional<Enquiry> findByReference(String reference);
    boolean existsByReference(String reference);
    Optional<Enquiry> findFirstByEmailAndStatusInOrderByIdDesc(String email, Collection<String> statuses);
    List<Enquiry> findByEmailOrderByIdDesc(String email);
    long countByStatus(String status);
}
