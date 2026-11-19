package com.microservices.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Login Request DTO
 *
 * Only checks that both fields are there and not absurdly long. A wrong email or password
 * is answered with 401, never with a hint about which of the two was wrong.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    @ToString.Exclude
    @NotBlank(message = "Password is required")
    @Size(max = 72, message = "Password must be at most 72 characters")
    private String password;
}
