package com.kashi.grc.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resourceName, Long id) {
        super("RESOURCE_NOT_FOUND",
              resourceName + " not found with id: " + id,
              HttpStatus.NOT_FOUND);
    }

    public ResourceNotFoundException(String resourceName, String field, Object value) {
        super("RESOURCE_NOT_FOUND",
              resourceName + " not found with " + field + ": " + value,
              HttpStatus.NOT_FOUND);
    }
}
