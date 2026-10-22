package com.kashi.grc.usermanagement.scheduler;

import com.kashi.grc.usermanagement.repository.RevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Removes revoked-token rows once the token they block has expired anyway.
 * Keeps revoked_tokens proportional to live sessions, not to all time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RevokedTokenPurgeJob {

    private final RevokedTokenRepository revokedTokenRepository;

    /** 03:20 server time, daily. */
    @Scheduled(cron = "0 20 3 * * *")
    public void purge() {
        int removed = revokedTokenRepository.purgeExpiredBefore(LocalDateTime.now());
        if (removed > 0) log.info("[AUTH] Purged {} expired revoked-token rows", removed);
    }
}
