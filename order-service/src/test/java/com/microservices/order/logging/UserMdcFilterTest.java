package com.microservices.order.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter in the security chain that puts the caller (from the checked token) into the MDC.
 */
class UserMdcFilterTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    private static Jwt jwt(String subject, String email, Object role) {
        Jwt.Builder builder = Jwt.withTokenValue("the-token-value").header("alg", "HS256")
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
        return builder.build();
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_" + jwt.getClaimAsString("role"))));
    }

    /** Runs the filter and returns the MDC as the rest of the chain saw it. */
    private Map<String, String> runFilter(UserMdcFilter filter) throws Exception {
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> seen.set(copyOfMdc());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        return seen.get();
    }

    private static Map<String, String> copyOfMdc() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy == null ? new HashMap<>() : copy;
    }

    @Test
    void aUserToken_putsIdEmailAndRoleInTheMdc() throws Exception {
        authenticate(jwt("42", "ann@example.com", "USER"));

        Map<String, String> seen = runFilter(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "42").containsEntry("userEmail", "ann@example.com")
                .containsEntry("userRole", "USER");
    }

    @Test
    void anAdminToken_showsTheAdminRole() throws Exception {
        authenticate(jwt("1", "root@example.com", "ADMIN"));

        assertThat(runFilter(new UserMdcFilter(true))).containsEntry("userRole", "ADMIN");
    }

    @Test
    void aSERVICEToken_showsTheCallingService() throws Exception {
        authenticate(jwt("order-service", null, "SERVICE"));

        Map<String, String> seen = runFilter(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "order-service").containsEntry("userRole", "SERVICE")
                .doesNotContainKey("userEmail");
    }

    @Test
    void withEmailsSwitchedOff_noEmailIsPutInTheMdc() throws Exception {
        authenticate(jwt("42", "ann@example.com", "USER"));

        Map<String, String> seen = runFilter(new UserMdcFilter(false));

        assertThat(seen).containsEntry("userId", "42").containsEntry("userRole", "USER")
                .doesNotContainKey("userEmail");
        assertThat(seen.values()).noneMatch(value -> value.contains("ann@example.com"));
    }

    @Test
    void noAuthentication_isAnonymous() throws Exception {
        Map<String, String> seen = runFilter(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "anonymous").doesNotContainKey("userEmail")
                .doesNotContainKey("userRole");
    }

    @Test
    void anAnonymousAuthentication_isAnonymous() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(runFilter(new UserMdcFilter(true))).containsEntry("userId", "anonymous");
    }

    @Test
    void aStaleUserFromEarlierInTheSameThread_isReplaced() throws Exception {
        MDC.put("userId", "stale");
        MDC.put("userEmail", "stale@example.com");
        MDC.put("userRole", "ADMIN");

        Map<String, String> seen = runFilter(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "anonymous").doesNotContainKey("userEmail")
                .doesNotContainKey("userRole");
    }

    @Test
    void valuesFromTheToken_areCleaned() throws Exception {
        authenticate(jwt("42", "evil@example.com\r\nINFO fake line " + "x".repeat(500), "USER"));

        Map<String, String> seen = runFilter(new UserMdcFilter(true));

        assertThat(seen.get("userEmail")).doesNotContain("\r").doesNotContain("\n").hasSizeLessThanOrEqualTo(200)
                .startsWith("evil@example.com__INFO fake line");
    }

    @Test
    void aSubjectWithALineBreak_isCleaned() throws Exception {
        authenticate(jwt("12\nfake", "a@b.c", "USER"));

        assertThat(runFilter(new UserMdcFilter(true)).get("userId")).isEqualTo("12_fake");
    }

    @Test
    void aTokenWithoutASubjectOrRole_doesNotBreakAnything() throws Exception {
        authenticate(jwt(null, null, "USER"));
        Map<String, String> seen = runFilter(new UserMdcFilter(true));
        assertThat(seen).containsKey("userId");
        assertThat(seen.get("userId")).isNotBlank();

        cleanUp();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("5", null, null),
                List.of()));
        assertThat(runFilter(new UserMdcFilter(true))).containsEntry("userId", "5").doesNotContainKey("userRole");
    }

    @Test
    void aRoleOrEmailThatIsNotAString_isHandled() throws Exception {
        authenticate(jwt("42", null, List.of("A", "B")));

        Map<String, String> seen = runFilter(new UserMdcFilter(true));

        assertThat(seen).containsEntry("userId", "42");
    }

    @Test
    void aTokenThatThrowsWhenRead_neverBreaksTheRequest() throws Exception {
        Jwt broken = mock(Jwt.class);
        when(broken.getSubject()).thenThrow(new IllegalStateException("broken token object"));
        when(broken.getClaimAsString("role")).thenThrow(new IllegalStateException("broken token object"));
        when(broken.getClaim("email")).thenThrow(new IllegalStateException("broken token object"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(broken, List.of(), "name"));
        MockFilterChain chain = new MockFilterChain();

        new UserMdcFilter(true).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request went on to the rest of the chain").isNotNull();
    }

    @Test
    void theFilterLeavesTheUserInTheMdc_soTheAccessLineThatIsWrittenAfterwardsCanShowIt() throws Exception {
        authenticate(jwt("42", "ann@example.com", "USER"));

        runFilter(new UserMdcFilter(true));

        // Clearing is the job of the outer RequestLoggingFilter, after it has written the access line.
        assertThat(MDC.get("userId")).isEqualTo("42");
        assertThat(MDC.get("userEmail")).isEqualTo("ann@example.com");
    }
}
