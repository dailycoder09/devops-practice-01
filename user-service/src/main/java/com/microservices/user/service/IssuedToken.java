package com.microservices.user.service;

import java.time.Instant;

/**
 * A freshly created token and the moment it stops being valid.
 *
 * @param value     the signed JWT
 * @param expiresAt the same moment as the token's "exp" claim
 */
public record IssuedToken(String value, Instant expiresAt) {
}
