package com.carrent.common.exception;

/** Authenticated user lacks permission (403 view). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
