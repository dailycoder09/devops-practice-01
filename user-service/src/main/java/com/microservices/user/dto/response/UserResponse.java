package com.microservices.user.dto.response;

import com.microservices.user.model.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * User Response DTO
 *
 * Used for returning a user's profile. It never contains the password or its hash.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {

    /**
     * Unique user identifier
     */
    private Long id;

    /**
     * Email address (lower case)
     */
    private String email;

    /**
     * USER or ADMIN
     */
    private Role role;
}
