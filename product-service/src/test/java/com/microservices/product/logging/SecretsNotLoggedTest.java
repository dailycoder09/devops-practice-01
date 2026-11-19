package com.microservices.product.logging;

import com.microservices.product.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Test 5: after a flow that uses tokens (good, expired, tampered, wrongly signed) and request bodies,
 * nothing secret is in the captured logs: no token (or part of one), no Authorization header, no
 * bearer prefix, no JWT secret, no request body.
 */
class SecretsNotLoggedTest extends AbstractLoggingIT {

    private static final String BODY_MARKER = "BODY-MARKER-d41d8cd98f00b204";

    @Test
    void afterAFlowWithTokens_theLogsContainNoSecrets() throws Exception {
        String admin = JwtTestSupport.token("ADMIN");
        String user = JwtTestSupport.token("USER");
        String expired = JwtTestSupport.expiredToken("ADMIN");
        String tampered = JwtTestSupport.tamperedToAdmin();
        String wrongSecret = JwtTestSupport.tokenSignedWithOtherSecret("ADMIN");
        String body = "{\"name\":\"Secret Test Product\",\"description\":\"" + BODY_MARKER
                + "\",\"price\":10.50,\"category\":\"Electronics\",\"skuCode\":\"SECRET-TEST-1\"}";

        // good admin write, user refused, expired, tampered, wrong secret, garbage, no token, a read with a token
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongSecret));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"));
        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(get("/api/products/sku/SECRET-TEST-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + user));

        assertThat(capture.lines()).isNotEmpty();
        String logs = capture.allText();
        for (String token : new String[] {admin, user, expired, tampered, wrongSecret}) {
            assertThat(logs).as("a token").doesNotContain(token);
            for (String part : token.split("\\.")) {
                assertThat(logs).as("a part of a token").doesNotContain(part);
            }
        }
        assertThat(logs).doesNotContain("Bearer").doesNotContain("bearer ").doesNotContain("Authorization")
                .doesNotContain("authorization").doesNotContain(JwtTestSupport.TEST_SECRET)
                .doesNotContain(JwtTestSupport.OTHER_SECRET).doesNotContain("not.a.jwt");
    }

    @Test
    void requestBodies_areNeverLogged() throws Exception {
        String body = "{\"name\":\"Body Test Product\",\"description\":\"" + BODY_MARKER
                + "\",\"price\":10.50,\"category\":\"Electronics\",\"skuCode\":\"SECRET-TEST-2\"}";

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN")));
        mockMvc.perform(get("/api/products/sku/SECRET-TEST-2"));

        assertThat(capture.lines()).isNotEmpty();
        assertThat(capture.allText()).doesNotContain(BODY_MARKER);
    }

    @Test
    void sqlParameterValues_areNeverLogged() throws Exception {
        String body = "{\"name\":\"Sql Test Product\",\"description\":\"" + BODY_MARKER
                + "\",\"price\":10.50,\"category\":\"Electronics\",\"skuCode\":\"SECRET-TEST-3\"}";

        mockMvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN")));

        assertThat(capture.lines()).isNotEmpty();
        assertThat(capture.allText()).doesNotContain(BODY_MARKER);
    }
}
