package com.microservices.order.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.order.support.FakeShop;
import com.microservices.order.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
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
 * One short run over real HTTP against the embedded Tomcat on a random port (not 8085), with the fake cart,
 * product and inventory services, to be sure the whole flow also works outside MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealServerSmokeTest {

    private static final FakeShop shop = FakeShop.get();

    @DynamicPropertySource
    static void fakeServiceUrls(DynamicPropertyRegistry registry) {
        registry.add("order.cart-service.url", shop.cartServer::baseUrl);
        registry.add("order.product-service.url", shop.productServer::baseUrl);
        registry.add("order.inventory-service.url", shop.inventoryServer::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        shop.reset();
        shop.addProduct("SKU-A", "Phone A", "100.00");
        shop.addStock("SKU-A", 10);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder url(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    @Test
    void checkoutAndReading_workOverRealHttp_andProtectedUrlsAnswer401And403() throws Exception {
        long userId = 4242;
        String token = JwtTestSupport.userToken(userId);
        shop.addToCart(userId, "SKU-A", 3, "100.00");

        HttpResponse<String> noToken = send(url("/api/orders").GET());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(noToken.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");

        HttpResponse<String> serviceToken = send(url("/api/orders").header("Authorization",
                "Bearer " + JwtTestSupport.serviceToken()).GET());
        assertThat(serviceToken.statusCode()).isEqualTo(403);

        HttpResponse<String> placed = send(url("/api/orders").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(placed.statusCode()).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placed.body());
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(order.get("cartCleared").asBoolean()).isTrue();
        assertThat(shop.stockOf("SKU-A")).isEqualTo(7);

        HttpResponse<String> read = send(url("/api/orders/" + order.get("id").asLong())
                .header("Authorization", "Bearer " + token).GET());
        assertThat(read.statusCode()).isEqualTo(200);

        HttpResponse<String> list = send(url("/api/orders").header("Authorization", "Bearer " + token).GET());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(list.body()).get("totalElements").asLong()).isEqualTo(1);

        HttpResponse<String> again = send(url("/api/orders").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(again.statusCode()).isEqualTo(400);

        assertThat(send(url("/actuator/health").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/health/liveness").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/health/readiness").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/env").header("Authorization", "Bearer " + token).GET()).statusCode())
                .isEqualTo(403);
    }
}
