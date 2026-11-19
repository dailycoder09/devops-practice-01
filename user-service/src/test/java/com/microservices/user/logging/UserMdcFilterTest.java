package com.microservices.user.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Puts the caller from the token into the MDC. It only fills the MDC; the request filter removes the keys later.
 */
class UserMdcFilterTest {

    private final Map<String, String> seen = new HashMap<>();

    @BeforeEach
    void clean() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanAfter() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwt(String sub, String email, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", sub);
        if (email != null) {
            claims.put("email", email);
        }
        if (role != null) {
            claims.put("role", role);
        }
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "HS256"), claims);
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_" + jwt.getClaimAsString("role"))));
    }

    private AtomicInteger runFilter(boolean includeEmail) throws Exception {
        LoggingProperties properties = new LoggingProperties();
        properties.setIncludeEmail(includeEmail);
        AtomicInteger chainCalls = new AtomicInteger();
        new UserMdcFilter(properties).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> {
                    chainCalls.incrementAndGet();
                    for (String key : List.of("userId", "userEmail", "userRole")) {
                        if (MDC.get(key) != null) {
                            seen.put(key, MDC.get(key));
                        }
                    }
                });
        return chainCalls;
    }

    @Test
    void aUserToken_putsIdEmailAndRoleInTheMdc() throws Exception {
        authenticate(jwt("42", "ann@example.com", "USER"));

        assertThat(runFilter(true)).hasValue(1);

        assertThat(seen).containsEntry("userId", "42").containsEntry("userEmail", "ann@example.com")
                .containsEntry("userRole", "USER");
    }

    @Test
    void aServiceToken_showsTheServiceName_andNoEmail() throws Exception {
        authenticate(jwt("order-service", null, "SERVICE"));

        runFilter(true);

        assertThat(seen).containsEntry("userId", "order-service").containsEntry("userRole", "SERVICE")
                .doesNotContainKey("userEmail");
    }

    @Test
    void noLogin_isAnonymous_withoutRoleOrEmail() throws Exception {
        runFilter(true);

        assertThat(seen).containsEntry("userId", "anonymous").doesNotContainKey("userEmail")
                .doesNotContainKey("userRole");
    }

    @Test
    void anAnonymousAuthentication_isAnonymous() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        runFilter(true);

        assertThat(seen).containsEntry("userId", "anonymous").doesNotContainKey("userRole");
    }

    @Test
    void emailCanBeSwitchedOff() throws Exception {
        authenticate(jwt("42", "ann@example.com", "USER"));

        runFilter(false);

        assertThat(seen).containsEntry("userId", "42").containsEntry("userRole", "USER")
                .doesNotContainKey("userEmail");
    }

    @Test
    void valuesFromTheToken_areCleaned() throws Exception {
        authenticate(jwt("4\r\n2", "ann@example.com\r\nINJECTED" + "x".repeat(300), "US\nER"));

        runFilter(true);

        assertThat(seen.get("userId")).isEqualTo("4__2");
        assertThat(seen.get("userRole")).isEqualTo("US_ER");
        assertThat(seen.get("userEmail")).startsWith("ann@example.com__INJECTED").hasSize(200).doesNotContain("\n");
    }

    @Test
    void aProblemReadingTheToken_neverBreaksTheRequest() throws Exception {
        JwtAuthenticationToken broken = mock(JwtAuthenticationToken.class);
        when(broken.getToken()).thenThrow(new IllegalStateException("cannot read the token"));
        SecurityContextHolder.getContext().setAuthentication(broken);

        AtomicInteger chainCalls = runFilter(true);

        assertThat(chainCalls).hasValue(1);
        assertThat(seen).containsEntry("userId", "anonymous");
    }
}
