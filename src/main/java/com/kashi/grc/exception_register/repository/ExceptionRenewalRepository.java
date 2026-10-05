package com.kashi.grc.exception_register.repository;

import com.kashi.grc.exception_register.domain.ExceptionRenewal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExceptionRenewalRepository extends JpaRepository<ExceptionRenewal, Long> {
    List<ExceptionRenewal> findByExceptionIdOrderByRenewedAtDesc(Long exceptionId);
}
