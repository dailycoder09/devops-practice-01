package com.microservices.order.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.support.LogCapture;
import com.microservices.order.support.LogCapture.Line;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401 and 403 handlers log one WARN event each (auth_rejected and access_denied), never the token, and the
 * answer they write does not change when logging itself fails.
 */
class ProblemJsonSecurityHandlersLoggingTest {

    private final ProblemJsonSecurityHandlers handlers = new ProblemJsonSecurityHandlers(new ObjectMapper());
    private LogCapture logs;

    @BeforeEach
    void startCapture() {
        logs = LogCapture.start();
    }

    @AfterEach
    void stopCapture() {
        logs.close();
    }

    private static MockHttpServletRequest request(String method, String uri, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    void noAuthorizationHeader_isMissingToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.commence(request("GET", "/api/orders", null), response, new InsufficientAuthenticationException("x"));

        Line event = logs.single("auth_rejected");
        assertThat(event.level()).isEqualTo("WARN");
        assertThat(event.field("reason")).isEqualTo("missing_token");
        assertThat(event.field("httpMethod")).isEqualTo("GET");
        assertThat(event.field("httpPath")).isEqualTo("/api/orders");
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void aBlankAuthorizationHeader_isMissingToken() throws Exception {
        handlers.commence(request("GET", "/api/orders", "   "), new MockHttpServletResponse(),
                new InsufficientAuthenticationException("x"));

        assertThat(logs.single("auth_rejected").field("reason")).isEqualTo("missing_token");
    }

    @Test
    void anAuthorizationHeaderThatWasRejected_isInvalidToken_andTheValueIsNeverLogged() throws Exception {
        for (String header : new String[] {"Bearer SECRET-TOKEN-VALUE", "Basic c2VjcmV0OnNlY3JldA==", "Bearer "}) {
            handlers.commence(request("POST", "/api/orders", header), new MockHttpServletResponse(),
                    new InsufficientAuthenticationException("x"));
        }

        assertThat(logs.events("auth_rejected")).hasSize(3)
                .allSatisfy(event -> assertThat(event.field("reason")).isEqualTo("invalid_token"));
        assertThat(logs.allText()).doesNotContain("SECRET-TOKEN-VALUE").doesNotContain("c2VjcmV0").doesNotContain("Bearer");
    }

    @Test
    void accessDenied_isLoggedAtWarn_withMethodAndPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.handle(request("DELETE", "/api/orders/5", "Bearer SECRET-TOKEN-VALUE"), response,
                new AccessDeniedException("no"));

        Line event = logs.single("access_denied");
        assertThat(event.level()).isEqualTo("WARN");
        assertThat(event.field("httpMethod")).isEqualTo("DELETE");
        assertThat(event.field("httpPath")).isEqualTo("/api/orders/5");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(logs.allText()).doesNotContain("SECRET-TOKEN-VALUE");
    }

    @Test
    void thePathIsCleaned() throws Exception {
        handlers.handle(request("GET", "/api/x\r\nINFO forged", null), new MockHttpServletResponse(),
                new AccessDeniedException("no"));

        assertThat(logs.single("access_denied").hasControlCharacters()).isFalse();
    }

    @Test
    void ifLoggingFails_the401AnswerIsTheSame() throws Exception {
        MockHttpServletRequest broken = new MockHttpServletRequest("GET", "/api/orders") {
            @Override
            public String getHeader(String name) {
                throw new IllegalStateException("cannot read headers");
            }

            @Override
            public String getRequestURI() {
                throw new IllegalStateException("cannot read the path");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.commence(broken, response, new InsufficientAuthenticationException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).contains("\"status\":401").contains("\"title\":\"Unauthorized\"");
    }

    @Test
    void ifLoggingFails_the403AnswerIsTheSame() throws Exception {
        MockHttpServletRequest broken = new MockHttpServletRequest("GET", "/api/orders") {
            @Override
            public String getRequestURI() {
                throw new IllegalStateException("cannot read the path");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.handle(broken, response, new AccessDeniedException("no"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"status\":403").contains("\"title\":\"Forbidden\"");
    }
}
