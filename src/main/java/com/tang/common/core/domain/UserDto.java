package com.tang.common.core.domain;

import com.tang.common.core.enums.auth.IdentityEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * Minimal gateway user payload (aligned with tang-common-core UserDto).
 */
@Data
public class UserDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String user_name;
    private String email;
    private IdentityEnum identity;
}
