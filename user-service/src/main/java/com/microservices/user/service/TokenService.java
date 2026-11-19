package com.microservices.user.service;

import com.microservices.user.model.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Token Service
 *
 * Creates the signed JWT (HS256) that product-service and inventory-service accept.
 * Claims: sub (user id), email, role, iat, exp. The token is valid for 8 hours.
 * There is no refresh token and no way to cancel a token before it expires.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
public class TokenService {

    /**
     * How long a token is valid
     */
    public static final Duration TOKEN_LIFETIME = Duration.ofHours(8);

    private final JwtEncoder jwtEncoder;

    public TokenService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    /**
     * Create a token for a user
     *
     * @param user the user who logged in
     * @return the signed token and the moment it expires
     */
    public IssuedToken issue(User user) {
        // Whole seconds, because that is all a JWT can hold. This keeps "expiresAt" equal to the "exp" claim.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(TOKEN_LIFETIME);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
