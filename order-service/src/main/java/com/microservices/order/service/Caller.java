package com.microservices.order.service;

import com.microservices.order.exception.InvalidTokenSubjectException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The person who made the request, read from their token.
 *
 * @param userId        the "sub" claim of the token, as a number
 * @param email         the "email" claim (can be null; cut to 255 characters, the size of the column)
 * @param admin         true if the role is ADMIN
 * @param authorization the caller's own Authorization header value ("Bearer ..."), passed to cart-service
 *                      so that the cart service itself decides whose cart it is
 */
public record Caller(long userId, String email, boolean admin, String authorization) {

    private static final int MAX_EMAIL_LENGTH = 255;

    /**
     * Read the caller from the checked token of the current request.
     *
     * @param authentication the authentication of the request (set by the security layer)
     * @return the caller
     * @throws InvalidTokenSubjectException if the "sub" claim is missing or is not a number (answer 401)
     */
    public static Caller from(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new InvalidTokenSubjectException();
        }
        Jwt jwt = jwtAuthentication.getToken();

        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidTokenSubjectException();
        }

        String email = jwt.getClaim("email") instanceof String text ? text : null;
        if (email != null && email.length() > MAX_EMAIL_LENGTH) {
            email = email.substring(0, MAX_EMAIL_LENGTH);
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new Caller(userId, email, admin, "Bearer " + jwt.getTokenValue());
    }
}
