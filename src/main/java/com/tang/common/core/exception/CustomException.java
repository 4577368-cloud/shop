package com.tang.common.core.exception;

/**
 * Project-native business exception.
 * Package kept as docs require: com.tang.common.core.exception.CustomException
 */
public class CustomException extends RuntimeException {

    /** HTTP status for PluginExceptionHandler; defaults to 400. */
    private final int httpStatus;
    /** Optional machine-readable code (e.g. PRODUCT_CONFLICT). */
    private final String code;

    public CustomException(String message) {
        this(message, 400, null, null);
    }

    public CustomException(String message, Throwable cause) {
        this(message, 400, null, cause);
    }

    public CustomException(String message, int httpStatus) {
        this(message, httpStatus, null, null);
    }

    public CustomException(String message, int httpStatus, String code) {
        this(message, httpStatus, code, null);
    }

    public CustomException(String message, int httpStatus, String code, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
