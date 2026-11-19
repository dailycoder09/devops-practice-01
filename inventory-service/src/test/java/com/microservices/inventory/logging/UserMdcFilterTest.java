package com.microservices.inventory.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserMdcFilterTest {

    @BeforeEach
    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    private static JwtAuthenticationToken tokenOf(String subject, String email, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("not-logged").header("alg", "HS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) {
            builder.subject(subject);
        }
        if (email != null) {
            builder.claim("email", email);
        }
        if (role != null) {
            builder.claim("role", role);
        }
        return new JwtAuthenticationToken(builder.build(), AuthorityUtils.createAuthorityList("ROLE_" + role));
    }

    /** Runs the filter and returns the MDC values the chain saw. */
    private static Map<String, String> runAndCollect(UserMdcFilter filter) throws Exception {
        Map<String, String> seen = new HashMap<>();
        FilterChain chain = (req, res) -> {
            for (String key : new String[] {"userId", "userEmail", "userRole"}) {
                seen.put(key, MDC.get(key));
            }
        };
        filter.doFilter(new MockHttpServletRequest("GET", "/api/inventory"), new MockHttpServletResponse(), chain);
        return seen;
    }

    @Test
    void aUserToken_putsIdEmailAndRoleInMdc() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenOf("42", "anna@example.com", "USER"));

        Map<String, String> seen = runAndCollect(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "42").containsEntry("userEmail", "anna@example.com")
                .containsEntry("userRole", "USER");
    }

    @Test
    void aServiceToken_showsTheServiceName() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenOf("order-service", null, "SERVICE"));

        Map<String, String> seen = runAndCollect(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "order-service").containsEntry("userRole", "SERVICE");
        assertThat(seen.get("userEmail")).isNull();
    }

    @Test
    void noAuthentication_isAnonymous() throws Exception {
        Map<String, String> seen = runAndCollect(new UserMdcFilter(true));

        assertThat(seen.get("userId")).isEqualTo("anonymous");
        assertThat(seen.get("userEmail")).isNull();
        assertThat(seen.get("userRole")).isNull();
    }

    @Test
    void anAuthenticationThatIsNotAJwt_isAnonymous() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "pw", AuthorityUtils.createAuthorityList("ROLE_USER")));

        Map<String, String> seen = runAndCollect(new UserMdcFilter(true));

        assertThat(seen.get("userId")).isEqualTo("anonymous");
        assertThat(seen.values()).doesNotContain("someone", "pw");
    }

    @Test
    void emailSwitchedOff_leavesOutTheEmail() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenOf("42", "anna@example.com", "ADMIN"));

        Map<String, String> seen = runAndCollect(new UserMdcFilter(false));

        assertThat(seen.get("userId")).isEqualTo("42");
        assertThat(seen.get("userRole")).isEqualTo("ADMIN");
        assertThat(seen.get("userEmail")).isNull();
    }

    @Test
    void valuesWithLineBreaks_areCleaned() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                tokenOf("42\r\nforged", "evil@example.com\r\n{\"event\":\"forged\"}", "USER"));

        Map<String, String> seen = runAndCollect(new UserMdcFilter(true));

        assertThat(seen.get("userId")).isEqualTo("42__forged");
        assertThat(seen.get("userEmail")).doesNotContain("\r").doesNotContain("\n").startsWith("evil@example.com__");
    }

    @Test
    void aTokenWithoutSubject_isStillHandled() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenOf(null, null, "USER"));

        Map<String, String> seen = runAndCollect(new UserMdcFilter(true));

        assertThat(seen.get("userId")).isEqualTo("unknown");
    }

    @Test
    void theFilterLeavesTheKeysInPlace_soTheAccessLineCanStillUseThem() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(tokenOf("42", "anna@example.com", "USER"));

        runAndCollect(new UserMdcFilter(true));

        assertThat(MDC.get("userId")).isEqualTo("42"); // RequestLoggingFilter removes it after writing the access line
    }

    @Test
    void aBrokenToken_doesNotBreakTheRequest() throws Exception {
        boolean[] armed = {false};
        Jwt broken = new Jwt("not-logged", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "HS256"), Map.of("sub", "42")) {
            @Override
            public String getSubject() {
                if (armed[0]) {
                    throw new IllegalStateException("token trouble");
                }
                return super.getSubject();
            }
        };
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(broken, AuthorityUtils.createAuthorityList("ROLE_USER")));
        armed[0] = true;
        boolean[] chainRan = {false};

        new UserMdcFilter(true).doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                (req, res) -> chainRan[0] = true);

        assertThat(chainRan[0]).isTrue();
    }
}
