package com.kashi.grc.usermanagement.exception;

import com.kashi.grc.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

public class AccountLockedException extends BusinessException {

    public AccountLockedException(LocalDateTime lockedUntil) {
        super("AUTH_ACCOUNT_LOCKED",
              "Account is locked until " + (lockedUntil != null ? lockedUntil : "administrator review"),
              HttpStatus.LOCKED);
    }
}
