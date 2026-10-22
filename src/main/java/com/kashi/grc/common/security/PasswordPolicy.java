package com.kashi.grc.common.security;

/**
 * The ONE password policy — referenced by every request DTO that sets a
 * password (reset-by-link, first-login change, change-own-password) and
 * mirrored exactly by the frontend (src/lib/passwordPolicy.js).
 *
 *   • 12–128 characters, no whitespace
 *   • at least one lowercase, one uppercase, one digit
 *   • at least one symbol — ANY non-alphanumeric character
 *
 * The old reset regex only allowed the symbols @$!%*?&, so a perfectly strong
 * password containing # - _ . or ^ was rejected with a message ("must contain
 * a special character") that the user had in fact satisfied. Change-password
 * had no complexity rule at all, only @Size(min = 12).
 */
public final class PasswordPolicy {

    private PasswordPolicy() { }

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    public static final String REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S{12,128}$";

    public static final String MESSAGE =
            "Password must be 12–128 characters with no spaces and include an uppercase letter, "
                    + "a lowercase letter, a number and a symbol";

    public static boolean isValid(String password) {
        return password != null && password.matches(REGEX);
    }
}
