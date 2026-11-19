package com.microservices.user.dto.response;

import com.microservices.user.model.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Login Response DTO
 *
 * The token is sent by the client as "Authorization: Bearer ...".
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginResponse {

    /**
     * The signed JWT
     */
    private String token;

    /**
     * When the token stops being valid (ISO-8601, UTC)
     */
    private Instant expiresAt;

    /**
     * Email address of the user (lower case)
     */
    private String email;

    /**
     * USER or ADMIN
     */
    private Role role;
}
