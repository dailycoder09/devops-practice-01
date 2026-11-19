package com.microservices.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One short run over real HTTP against the embedded Tomcat on a random port (not 8083),
 * to be sure the flow also works outside MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealServerSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder url(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(url(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    @Test
    void registerLoginAndMe_workOverRealHttp_andProtectedUrlsAnswer401And403() throws Exception {
        String email = "smoke-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}";

        assertThat(postJson("/api/users/register", body).statusCode()).isEqualTo(201);

        HttpResponse<String> login = postJson("/api/users/login", body);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode loginBody = objectMapper.readTree(login.body());
        String token = loginBody.get("token").asText();
        assertThat(loginBody.get("role").asText()).isEqualTo("USER");

        HttpResponse<String> me = send(url("/api/users/me").header("Authorization", "Bearer " + token).GET());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(me.body()).get("email").asText()).isEqualTo(email);

        HttpResponse<String> noToken = send(url("/api/users/me").GET());
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(noToken.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");

        HttpResponse<String> userOnEnv = send(url("/actuator/env").header("Authorization", "Bearer " + token).GET());
        assertThat(userOnEnv.statusCode()).isEqualTo(403);

        assertThat(send(url("/actuator/health").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/health/liveness").GET()).statusCode()).isEqualTo(200);
        assertThat(send(url("/actuator/health/readiness").GET()).statusCode()).isEqualTo(200);
    }
}
