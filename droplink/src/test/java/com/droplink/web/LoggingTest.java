package com.droplink.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What ends up in the log: security events are there, the secrets are not. */
@SpringBootTest
@AutoConfigureMockMvc
class LoggingTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("droplink.mode", () -> "local");
        registry.add("droplink.local-dir", () -> dir.toString());
        registry.add("droplink.secret", () -> "test-secret-test-secret-test-secret");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void attach() {
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void logPathHidesTheIdAndTheToken() {
        assertEquals("/d/AbCd...", LogSafe.path("/d/AbCdEfGhIjKl"));
        assertEquals("/api/links/AbCd.../complete", LogSafe.path("/api/links/AbCdEfGhIjKl/complete"));
        assertEquals("/local-store/download/<token>", LogSafe.path("/local-store/download/dXNlcg.sig"));
        assertEquals("/x_y", LogSafe.path("/x\ny"));
        assertTrue(LogSafe.path("/" + "a".repeat(500)).length() < 130);
    }

    @Test
    void wrongPasswordsAndLockoutAreLoggedWithoutTheSecrets() throws Exception {
        MvcResultHolder created = create();
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/d/" + created.id + "/unlock").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"wrong-pass-" + i + "\"}"));
        }
        List<String> log = lines();
        assertTrue(log.stream().anyMatch(l -> l.contains("wrong password") && l.contains("try 1 of 5")));
        assertTrue(log.stream().anyMatch(l -> l.contains("client is now locked")));
        assertTrue(log.stream().anyMatch(l -> l.contains("Refused POST") && l.contains("429")));
        String all = String.join("\n", log);
        assertFalse(all.contains(created.id), "the full link id must not be logged");
        assertFalse(all.contains("wrong-pass-0"), "a password must not be logged");
        assertFalse(all.contains(created.token), "the manage key must not be logged");
    }

    @Test
    void refusedRequestsAreLoggedAndEveryAnswerHasARequestId() throws Exception {
        MvcResultHolder created = create();
        mvc.perform(get("/api/links/" + created.id + "/activity").header("X-Manage-Token", "wrong"))
                .andExpect(status().isForbidden()).andExpect(header().exists("X-Request-Id"));
        mvc.perform(get("/d/zzzzzzzzzzzz")).andExpect(status().isNotFound());
        List<String> log = lines();
        assertTrue(log.stream().anyMatch(l -> l.contains("Refused GET /api/links/" + created.id.substring(0, 4) + ".../activity -> 403")));
        assertTrue(log.stream().anyMatch(l -> l.contains("-> 404")));
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("-> 403")));
        assertFalse(String.join("\n", log).contains(created.id));
    }

    private record MvcResultHolder(String id, String token) { }

    private MvcResultHolder create() throws Exception {
        String body = "{\"fileName\":\"a.txt\",\"contentType\":\"text/plain\",\"size\":3,\"expiresInHours\":24,\"password\":\"secret1\"}";
        JsonNode node = json.readTree(mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = node.get("id").asText();
        String token = node.get("manageToken").asText();
        mvc.perform(put(URI.create(node.at("/upload/url").asText()).getPath()).contentType("text/plain").content("abc")).andExpect(status().isOk());
        mvc.perform(post("/api/links/" + id + "/complete").header("X-Manage-Token", token)).andExpect(status().isOk());
        return new MvcResultHolder(id, token);
    }
}
