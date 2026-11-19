package com.microservices.user.service;

import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private static final String SECRET = "test-only-secret-for-token-service-0123456789-abcdef";

    @Test
    void issue_createsAnHs256TokenWithTheContractClaims_validFor8Hours() {
        SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        TokenService tokenService = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)));
        User user = User.builder().id(15L).email("ann@example.com").role(Role.ADMIN).passwordHash("x").build();
        Instant before = Instant.now().minusSeconds(2);

        IssuedToken issued = tokenService.issue(user);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
                .decode(issued.value());
        assertThat(jwt.getSubject()).isEqualTo("15");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("ann@example.com");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getIssuedAt()).isBetween(before, Instant.now().plusSeconds(2));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(8));
        assertThat(issued.expiresAt()).isEqualTo(jwt.getExpiresAt());
    }
}
