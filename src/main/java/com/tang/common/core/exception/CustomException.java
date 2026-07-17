package com.tang.common.core.exception;

/**
 * Project-native business exception.
 * Package kept as docs require: com.tang.common.core.exception.CustomException
 */
public class CustomException extends RuntimeException {

    public CustomException(String message) {
        super(message);
    }

    public CustomException(String message, Throwable cause) {
        super(message, cause);
    }
}
