package com.carrent.common.exception;

/** Business rule violation — shows a Vietnamese flash message and redirects back. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
