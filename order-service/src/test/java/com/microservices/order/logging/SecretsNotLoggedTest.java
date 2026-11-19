package com.microservices.order.logging;

import com.microservices.order.service.ServiceTokenIssuer;
import com.microservices.order.support.FakeHttpServer.Request;
import com.microservices.order.support.FakeShop.Behavior;
import com.microservices.order.support.JwtTestSupport;
import com.microservices.order.support.LoggingTestBase;
import com.microservices.order.support.RealLogFile;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 5 of the logging contract and Review Focus 3 of the build plan: after flows that use tokens (a confirmed
 * order, refusals, failures, bad tokens, a SERVICE token, an admin call), no log line holds a token (whole or in
 * parts), an Authorization header, a password hash or the JWT secret. Checked on the captured events AND on the
 * real JSON file.
 */
class SecretsNotLoggedTest extends LoggingTestBase {

    @Autowired
    private ServiceTokenIssuer tokenIssuer;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQ";
    private static final String PASSWORD = "Sup3r-Secret-Passw0rd!";

    /** A user token that also carries claims no service should ever print. */
    private String tokenWithSensitiveClaims() throws Exception {
        return tokenWithSensitiveClaims(userId);
    }

    private String tokenWithSensitiveClaims(long forUser) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(forUser))
                .claim("email", "user" + forUser + "@example.com")
                .claim("role", "USER")
                .claim("password", PASSWORD)
                .claim("passwordHash", PASSWORD_HASH)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(JwtTestSupport.TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private void assertNoSecret(String text, List<String> secrets) {
        for (String secret : secrets) {
            assertThat(text).as("the log must not contain a secret of length " + secret.length())
                    .doesNotContain(secret);
        }
        assertThat(text).doesNotContain("Bearer ").doesNotContainIgnoringCase("authorization");
    }

    /** The token and its three parts, so that a partly logged token is found as well. */
    private static List<String> partsOf(String token) {
        List<String> parts = new ArrayList<>();
        parts.add(token);
        for (String part : token.split("\\.")) {
            parts.add(part);
        }
        return parts;
    }

    @Test
    void afterAFlowWithTokens_noTokenPasswordHashOrSecretIsInAnyLogLine() throws Exception {
        long offset = RealLogFile.size();
        List<String> secrets = new ArrayList<>(List.of(jwtSecret, PASSWORD_HASH, PASSWORD));
        String sensitiveToken = tokenWithSensitiveClaims();
        secrets.addAll(partsOf(sensitiveToken));

        // Every step uses its own user, because a rejected checkout leaves that user's cart as it was.
        long confirmedUser = userId;
        long refusedUser = userId + 101;
        long failingStockUser = userId + 102;
        long failingCartUser = userId + 103;
        long failingProductUser = userId + 104;
        String confirmedToken = sensitiveToken;
        String refusedToken = tokenWithSensitiveClaims(refusedUser);
        String failingStockToken = tokenWithSensitiveClaims(failingStockUser);
        String failingCartToken = tokenWithSensitiveClaims(failingCartUser);
        String failingProductToken = tokenWithSensitiveClaims(failingProductUser);
        for (String token : List.of(refusedToken, failingStockToken, failingCartToken, failingProductToken)) {
            secrets.addAll(partsOf(token));
        }

        // 1. A confirmed order (the caller's token goes to the cart, a SERVICE token to the inventory).
        shop.addToCart(confirmedUser, "SKU-A", 1, "100.00");
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(confirmedToken)))
                .andExpect(status().isOk());
        checkout(confirmedToken).andExpect(status().isCreated());
        // 2. A refusal with releases, and a stock service that fails.
        shop.addToCart(refusedUser, "SKU-B", 9, "20.50");
        checkout(refusedToken).andExpect(status().isConflict());
        shop.addToCart(failingStockUser, "SKU-C", 1, "5.00");
        shop.setReserveBehavior("SKU-C", Behavior.ERROR_500);
        shop.setReleaseBehavior("SKU-C", Behavior.DROP);
        checkout(failingStockToken).andExpect(status().isServiceUnavailable());
        shop.setReserveBehavior("SKU-C", Behavior.NORMAL);
        shop.setReleaseBehavior("SKU-C", Behavior.NORMAL);
        // 3. The cart and the product service failing.
        shop.setCartReadBehavior(Behavior.UNAUTHORIZED);
        checkout(failingCartToken).andExpect(status().isServiceUnavailable());
        shop.setCartReadBehavior(Behavior.NORMAL);
        shop.addToCart(failingProductUser, "SKU-A", 1, "100.00");
        shop.setProductBehavior(Behavior.ERROR_500);
        checkout(failingProductToken).andExpect(status().isServiceUnavailable());
        shop.setProductBehavior(Behavior.NORMAL);
        // 4. Bad tokens, a SERVICE token as the caller, an admin call.
        String tampered = JwtTestSupport.withTamperedRole(sensitiveToken, "ADMIN");
        secrets.addAll(partsOf(tampered));
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(tampered))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer("garbage.token.value"))).andExpect(status().isUnauthorized());
        String serviceToken = tokenIssuer.issue();
        secrets.addAll(partsOf(serviceToken));
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(serviceToken))).andExpect(status().isForbidden());
        String adminToken = JwtTestSupport.adminToken(userId);
        secrets.addAll(partsOf(adminToken));
        mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/loggers").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))).andExpect(status().isOk());

        // Every SERVICE token that went to the (fake) inventory service.
        for (Request request : shop.inventoryServer.requests()) {
            secrets.addAll(partsOf(request.header(HttpHeaders.AUTHORIZATION).substring("Bearer ".length())));
        }
        assertThat(shop.inventoryServer.requests()).isNotEmpty();
        secrets.removeIf(secret -> secret.length() < 20);

        assertThat(logs.lines()).hasSizeGreaterThan(30);
        assertNoSecret(logs.allText(), secrets);

        // The same on the real JSON file (waits for the last access line to arrive).
        List<String> fileLines = RealLogFile.await(offset, found -> found.stream()
                .anyMatch(line -> line.contains("\"httpPath\":\"/actuator/loggers\"")), Duration.ofSeconds(15));
        assertNoSecret(String.join("\n", fileLines), secrets);
    }

    @Test
    void theClaimsOfATokenAreNotLogged_onlyTheIdTheEmailAndTheRole() throws Exception {
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(tokenWithSensitiveClaims())))
                .andExpect(status().isOk());

        assertThat(logs.allText()).doesNotContain(PASSWORD).doesNotContain(PASSWORD_HASH).doesNotContain("passwordHash");
        assertThat(logs.accessLine("GET", "/api/orders").mdc().keySet())
                .containsExactlyInAnyOrder("traceId", "spanId", "clientIp", "userId", "userEmail", "userRole");
    }

    @Test
    void theAccessLineOfARequestWithAnAuthorizationHeader_hasNoHeaderAtAll() throws Exception {
        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                .header("Cookie", "session=COOKIE-SECRET")).andExpect(status().isOk());

        assertThat(logs.accessLine("GET", "/api/orders").fields().keySet())
                .containsExactlyInAnyOrder("event", "httpMethod", "httpPath", "httpStatus", "durationMs");
        assertThat(logs.allText()).doesNotContain("COOKIE-SECRET").doesNotContain(userToken);
    }
}
