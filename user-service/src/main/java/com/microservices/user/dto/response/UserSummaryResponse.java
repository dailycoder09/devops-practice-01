package com.microservices.user.dto.response;

import com.microservices.user.model.Role;

import java.time.LocalDateTime;

/**
 * One account in the admin list: id, email, role and the moment it was created.
 * It has no password field, so the hash can never be copied into it by mistake.
 *
 * @param id        unique user identifier
 * @param email     email address (lower case)
 * @param role      USER or ADMIN
 * @param createdAt when the account was created
 */
public record UserSummaryResponse(Long id, String email, Role role, LocalDateTime createdAt) {
}
