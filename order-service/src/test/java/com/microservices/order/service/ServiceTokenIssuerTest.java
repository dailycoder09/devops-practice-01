package com.microservices.order.service;

import com.microservices.order.support.JwtTestSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The internal SERVICE token: who it says it is, how long it lives, and that other services can check it
 * with the shared secret.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceTokenIssuerTest {

    @Autowired
    private ServiceTokenIssuer issuer;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void theToken_hasTheContractClaims_andAboutTwoMinutesOfLife() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        String token = issuer.issue();
        Instant after = Instant.now().plusSeconds(1);

        SignedJWT jwt = SignedJWT.parse(token);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(claims.getSubject()).isEqualTo("order-service");
        assertThat(claims.getClaim("role")).isEqualTo("SERVICE");
        assertThat(claims.getIssueTime().toInstant()).isBetween(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), after);
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(claims.getExpirationTime().toInstant()).isAfter(Instant.now());
    }

    @Test
    void theSignature_isValid_withTheSharedSecret_andInvalidWithAnotherOne() throws Exception {
        SignedJWT jwt = SignedJWT.parse(issuer.issue());

        assertThat(jwt.verify(new MACVerifier(JwtTestSupport.TEST_SECRET))).isTrue();
        assertThat(jwt.verify(new MACVerifier("another-secret-that-is-long-enough-for-hs256-0123456789"))).isFalse();
    }

    @Test
    void theServiceDecoder_acceptsIt_andTheRoleClaimIsSERVICE() {
        Jwt decoded = jwtDecoder.decode(issuer.issue());

        assertThat(decoded.getSubject()).isEqualTo("order-service");
        assertThat(decoded.<String>getClaim("role")).isEqualTo("SERVICE");
    }

    @Test
    void theToken_carriesNoPersonalData() throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(issuer.issue()).getJWTClaimsSet();

        assertThat(claims.getClaims().keySet()).containsExactlyInAnyOrder("sub", "role", "iat", "exp");
    }
}
