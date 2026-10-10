package com.carrent.common.exception;

/** Requested resource does not exist (404 view). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
