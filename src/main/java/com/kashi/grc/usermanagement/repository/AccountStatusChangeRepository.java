package com.kashi.grc.usermanagement.repository;

import com.kashi.grc.usermanagement.domain.AccountStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountStatusChangeRepository extends JpaRepository<AccountStatusChange, Long> {

    /** A subject's history, newest first. */
    List<AccountStatusChange> findBySubjectTypeAndSubjectIdOrderByIdDesc(String subjectType, Long subjectId);
}
