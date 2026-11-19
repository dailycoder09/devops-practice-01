package com.microservices.order.service;

import com.microservices.order.logging.AuditLog;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Service Token Issuer
 *
 * Makes the internal token that order-service uses to call inventory-service reserve and release.
 * Claims: sub = order-service, role = SERVICE, iat and exp (2 minutes apart). HS256, signed with the same
 * shared secret as every other token. user-service never issues it, and no endpoint of this service hands it out.
 *
 * Known weakness (also in the README): with one shared secret, any service that knows the secret could make
 * any token. The later fix is a key pair, so that only user-service can sign user tokens.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
public class ServiceTokenIssuer {

    /**
     * The "sub" claim of the token
     */
    public static final String SERVICE_SUBJECT = "order-service";

    /**
     * The "role" claim of the token
     */
    public static final String SERVICE_ROLE = "SERVICE";

    /**
     * How long a token is valid
     */
    public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(2);

    private final JwtEncoder jwtEncoder;

    public ServiceTokenIssuer(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    /**
     * Create a fresh token
     *
     * @return the signed token (never log it: only the event service_token_issued with the lifetime is logged)
     */
    public String issue() {
        // Whole seconds, because that is all a JWT can hold.
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(SERVICE_SUBJECT)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(TOKEN_LIFETIME))
                .claim("role", SERVICE_ROLE)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        AuditLog.debug("service_token_issued", "Internal service token issued",
                kv("lifetimeSeconds", TOKEN_LIFETIME.toSeconds()));
        return token;
    }
}
