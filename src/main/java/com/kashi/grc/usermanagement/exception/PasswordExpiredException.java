package com.kashi.grc.usermanagement.exception;

import com.kashi.grc.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public class PasswordExpiredException extends BusinessException {

    public PasswordExpiredException() {
        super("AUTH_PASSWORD_EXPIRED",
              "Your password has expired. Please reset it.",
              HttpStatus.FORBIDDEN);
    }
}
