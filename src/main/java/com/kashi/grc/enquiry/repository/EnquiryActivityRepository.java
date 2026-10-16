package com.kashi.grc.enquiry.repository;

import com.kashi.grc.enquiry.domain.EnquiryActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnquiryActivityRepository extends JpaRepository<EnquiryActivity, Long> {
    List<EnquiryActivity> findByEnquiryIdOrderByIdAsc(Long enquiryId);
}
