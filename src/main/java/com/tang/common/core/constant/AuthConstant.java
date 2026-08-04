package com.tang.common.core.constant;

/**
 * Gateway auth header names (aligned with tang-common-core).
 */
public final class AuthConstant {
    private AuthConstant() {}

    /** JSON user payload from API gateway. */
    public static final String USER_TOKEN_HEADER = "user";
    /** Alternate string/JSON user payload header. */
    public static final String USER_STR_TOKEN_HEADER = "user_str";
}
