package com.kashi.grc.usermanagement.repository;

import java.time.LocalDateTime;

/** Criteria-backed queries for revoked tokens (Custom/Impl split, no @Query). */
public interface RevokedTokenRepositoryCustom {

    /** Deletes rows whose token has expired on its own. Returns rows removed. */
    int purgeExpiredBefore(LocalDateTime cutoff);
}
