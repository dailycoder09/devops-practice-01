package com.microservices.inventory.logging;

import com.microservices.inventory.security.JwtTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Test 5: after a flow that uses tokens (good, expired, tampered, wrongly signed, a SERVICE token) and request
 * bodies, nothing secret is in the captured logs: no token (or part of one), no Authorization header, no bearer
 * prefix, no JWT secret, no request body.
 */
class SecretsNotLoggedTest extends AbstractLoggingIT {

    /** Not a field of the API: ignored by the service, so it can only reach a log if somebody logs the raw body. */
    private static final String BODY_MARKER = "BODY-MARKER-d41d8cd98f00b204";

    private static String body(String sku) {
        return "{\"skuCode\":\"" + sku + "\",\"quantityOnHand\":5,\"note\":\"" + BODY_MARKER + "\"}";
    }

    @Test
    void afterAFlowWithTokens_theLogsContainNoSecrets() throws Exception {
        String admin = JwtTestSupport.token("ADMIN");
        String user = JwtTestSupport.token("USER");
        String service = JwtTestSupport.serviceToken();
        String expired = JwtTestSupport.expiredToken("ADMIN");
        String tampered = JwtTestSupport.tamperedToAdmin();
        String wrongSecret = JwtTestSupport.tokenSignedWithOtherSecret("ADMIN");

        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service));
        mockMvc.perform(post("/api/inventory/sku/SECRET-TEST-1/reserve?quantity=1&orderId=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongSecret));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"));
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-1")));
        mockMvc.perform(get("/api/inventory/sku/SECRET-TEST-1").header(HttpHeaders.AUTHORIZATION, "Bearer " + user));

        assertThat(capture.lines()).isNotEmpty();
        String logs = capture.allText();
        for (String token : new String[] {admin, user, service, expired, tampered, wrongSecret}) {
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
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-2"))
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN")));
        mockMvc.perform(get("/api/inventory/sku/SECRET-TEST-2"));

        assertThat(capture.lines()).isNotEmpty();
        assertThat(capture.allText()).doesNotContain(BODY_MARKER);
    }

    @Test
    void theCallToProductService_neverCarriesTheCallersToken() throws Exception {
        mockMvc.perform(post("/api/inventory").contentType(MediaType.APPLICATION_JSON).content(body("SECRET-TEST-3"))
                .header(HttpHeaders.AUTHORIZATION, JwtTestSupport.bearer("ADMIN")));

        assertThat(PRODUCTS.received()).isNotEmpty();
        assertThat(PRODUCTS.last().headers()).doesNotContainKey("authorization");
    }
}
