package com.kashi.grc.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for all business/domain exceptions.
 * Carry an error code and HTTP status so GlobalExceptionHandler
 * can map them automatically without any if-else chains.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public BusinessException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode  = errorCode;
        this.httpStatus = httpStatus;
    }

    public BusinessException(String errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST);
    }
}
