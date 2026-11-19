package com.microservices.cart.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.cart.support.FakeProductService;
import com.microservices.cart.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One short run over real HTTP against the embedded Tomcat on a random port (not 8084), with a fake
 * product-service on another random port, to be sure the flow also works outside MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealServerSmokeTest {

    private static final FakeProductService PRODUCTS = new FakeProductService();

    @DynamicPropertySource
    static void productServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("cart.product-service.url", PRODUCTS::baseUrl);
    }

    @AfterAll
    static void stopFake() {
        PRODUCTS.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpRequest.Builder url(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void addShowAndClear_workOverRealHttp_andTheRulesAnswer401And403() throws Exception {
        PRODUCTS.addProduct("SMOKE-1", "Smoke Phone", "100.00");
        String bearer = "Bearer " + TestTokens.user(TestTokens.newUserId());

        HttpResponse<String> added = send(url("/api/cart/items/SMOKE-1")
                .header("Authorization", bearer).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"quantity\":3}")));
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(added.headers().firstValue("Content-Type").orElse("")).contains("application/json");

        JsonNode cart = objectMapper.readTree(send(url("/api/cart").header("Authorization", bearer).GET()).body());
        assertThat(cart.get("itemCount").asInt()).isEqualTo(3);
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(cart.get("items").get(0).get("name").asText()).isEqualTo("Smoke Phone");

        assertThat(send(url("/api/cart").header("Authorization", bearer).DELETE()).statusCode()).isEqualTo(204);
        JsonNode empty = objectMapper.readTree(send(url("/api/cart").header("Authorization", bearer).GET()).body());
        assertThat(empty.get("items")).isEmpty();

        HttpResponse<String> noToken = send(url("/api/cart").GET());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(noToken.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");

        HttpResponse<String> serviceToken = send(url("/api/cart")
                .header("Authorization", "Bearer " + TestTokens.service()).GET());
        assertThat(serviceToken.statusCode()).isEqualTo(403);

        HttpResponse<String> badSub = send(url("/api/cart")
                .header("Authorization", "Bearer " + TestTokens.withRole("abc", "USER")).GET());
        assertThat(badSub.statusCode()).isEqualTo(401);

        assertThat(send(url("/actuator/health").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/health/liveness").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/health/readiness").GET()).statusCode()).isEqualTo(200);
    }
}
