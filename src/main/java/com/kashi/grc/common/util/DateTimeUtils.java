package com.kashi.grc.common.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class DateTimeUtils {

    private DateTimeUtils() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static LocalDateTime plusMinutes(int minutes) {
        return now().plusMinutes(minutes);
    }

    public static boolean isExpired(LocalDateTime expiry) {
        return expiry != null && expiry.isBefore(now());
    }
}
