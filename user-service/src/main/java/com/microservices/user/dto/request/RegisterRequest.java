package com.microservices.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Register Request DTO
 *
 * The role is not part of the request: everyone who registers is a USER.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterRequest {

    /**
     * Email address (valid format, at most 255 characters)
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Email is not a valid email address")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    /**
     * Password, 8 to 72 characters (72 is the most BCrypt can use)
     */
    @ToString.Exclude
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;
}
