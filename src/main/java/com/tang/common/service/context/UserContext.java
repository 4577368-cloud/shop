package com.tang.common.service.context;

import com.tang.common.core.domain.UserDto;

/**
 * Thread-local user holder (aligned with tang-common-service).
 */
public final class UserContext {
    private static final ThreadLocal<UserDto> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public static void set(UserDto user) {
        HOLDER.set(user);
    }

    public static UserDto get() {
        return HOLDER.get();
    }

    public static void remove() {
        HOLDER.remove();
    }
}
