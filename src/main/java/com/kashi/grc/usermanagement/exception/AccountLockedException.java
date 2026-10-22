package com.kashi.grc.usermanagement.exception;

import com.kashi.grc.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Thrown on sign-in while the account is locked after repeated failures.
 *
 * The message used to embed the raw LocalDateTime ("Account is locked until
 * 2026-10-22T11:30:12.123456"), which is unreadable and in the server's time
 * zone. It now says how long to wait in plain words, and carries the details
 * (minutes remaining) for the client to render its own countdown.
 */
public class AccountLockedException extends BusinessException {

    public AccountLockedException(LocalDateTime lockedUntil) {
        super("AUTH_ACCOUNT_LOCKED", message(lockedUntil), HttpStatus.LOCKED, details(lockedUntil));
    }

    private static long minutesLeft(LocalDateTime until) {
        if (until == null) return -1;
        long secs = Duration.between(LocalDateTime.now(), until).getSeconds();
        return Math.max(1, (secs + 59) / 60);
    }

    private static String message(LocalDateTime until) {
        long m = minutesLeft(until);
        if (m < 0) {
            return "Your account is locked. Contact your administrator to unlock it.";
        }
        return "Too many failed sign-in attempts. Your account is locked — try again in "
                + m + (m == 1 ? " minute" : " minutes") + ", or reset your password.";
    }

    private static Map<String, Object> details(LocalDateTime until) {
        Map<String, Object> d = new HashMap<>();
        long m = minutesLeft(until);
        if (m >= 0) d.put("retryAfterMinutes", m);
        return d;
    }
}
