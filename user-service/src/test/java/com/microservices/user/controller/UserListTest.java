package com.microservices.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import com.microservices.user.support.JwtTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/users: the list of accounts for the admin area. ADMIN only, newest first, paged, with a search
 * on the email, and it never shows a password hash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserListTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    private String tag;
    private List<Long> ids;
    private String adminToken;

    /** Five users with a tag in the email that only this test uses; ids[0] is the oldest. */
    @BeforeEach
    void createUsers() throws Exception {
        tag = "lt" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MvcResult result = mockMvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", tag + "-" + i + "@Example.com", "password", PASSWORD))))
                    .andExpect(status().isCreated()).andReturn();
            ids.add(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong());
        }
        adminToken = loginToken("test.admin@example.com", "test-admin-password");
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions list(String token, String query) throws Exception {
        var request = get("/api/users" + query);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private JsonNode page(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---------------------------------------------------------------- who may call it

    @Test
    void withoutAToken_is401() throws Exception {
        list(null, "").andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void withAnInvalidToken_is401() throws Exception {
        list("not-a-token", "").andExpect(status().isUnauthorized());
    }

    @Test
    void withAUserToken_is403() throws Exception {
        String userToken = loginToken(tag + "-0@example.com", PASSWORD);

        list(userToken, "").andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void withAServiceToken_is403() throws Exception {
        String serviceToken = JwtTestSupport.validToken(jwtSecret, "order-service", "", "SERVICE");

        list(serviceToken, "").andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void withAnAdminToken_is200() throws Exception {
        list(adminToken, "").andExpect(status().isOk());
    }

    @Test
    void aUserIsRefusedBeforeTheParametersAreLookedAt() throws Exception {
        String userToken = loginToken(tag + "-1@example.com", PASSWORD);

        list(userToken, "?size=100000&page=-4").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- shape and content

    @Test
    void thePage_hasTheSpringPageShape_andEachUserHasExactlyFourFields() throws Exception {
        JsonNode page = page(list(adminToken, "?q=" + tag).andExpect(status().isOk()));

        assertThat(page.get("content").isArray()).isTrue();
        assertThat(page.get("totalElements").asLong()).isEqualTo(5);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("number").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("numberOfElements").asInt()).isEqualTo(5);
        assertThat(page.get("first").asBoolean()).isTrue();
        assertThat(page.get("last").asBoolean()).isTrue();
        assertThat(page.get("empty").asBoolean()).isFalse();
        assertThat(page.has("pageable")).isTrue();
        assertThat(page.has("sort")).isTrue();

        JsonNode first = page.get("content").get(0);
        Set<String> fields = new HashSet<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "email", "role", "createdAt");
        assertThat(first.get("id").isNumber()).isTrue();
        assertThat(first.get("role").asText()).isEqualTo("USER");
        assertThat(first.get("createdAt").asText()).matches("\\d{4}-\\d\\d-\\d\\dT.*");
    }

    @Test
    void theEmail_isShownInLowerCase_asStored() throws Exception {
        JsonNode page = page(list(adminToken, "?q=" + tag + "-0").andExpect(status().isOk()));

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("email").asText()).isEqualTo(tag + "-0@example.com");
    }

    @Test
    void newestFirst_highestIdFirst() throws Exception {
        JsonNode page = page(list(adminToken, "?q=" + tag).andExpect(status().isOk()));

        List<Long> seen = new ArrayList<>();
        page.get("content").forEach(user -> seen.add(user.get("id").asLong()));
        List<Long> newestFirst = new ArrayList<>(ids);
        java.util.Collections.reverse(newestFirst);
        assertThat(seen).containsExactlyElementsOf(newestFirst);
    }

    @Test
    void paging_givesTheNextUsers_andTheTotals() throws Exception {
        JsonNode second = page(list(adminToken, "?q=" + tag + "&size=2&page=1").andExpect(status().isOk()));

        assertThat(second.get("totalElements").asLong()).isEqualTo(5);
        assertThat(second.get("totalPages").asInt()).isEqualTo(3);
        assertThat(second.get("number").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("first").asBoolean()).isFalse();
        assertThat(second.get("last").asBoolean()).isFalse();
        assertThat(second.get("content")).hasSize(2);
        assertThat(second.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(second.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));

        JsonNode last = page(list(adminToken, "?q=" + tag + "&size=2&page=2").andExpect(status().isOk()));
        assertThat(last.get("content")).hasSize(1);
        assertThat(last.get("last").asBoolean()).isTrue();
    }

    @Test
    void theDefaultPageSizeIs20() throws Exception {
        JsonNode page = page(list(adminToken, "").andExpect(status().isOk()));

        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("number").asInt()).isZero();
    }

    @Test
    void theSeededAdmin_isListedWithRoleAdmin() throws Exception {
        JsonNode page = page(list(adminToken, "?q=test.admin").andExpect(status().isOk()));

        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("role").asText()).isEqualTo("ADMIN");
        assertThat(page.get("content").get(0).get("email").asText()).isEqualTo("test.admin@example.com");
    }

    // ---------------------------------------------------------------- search

    @Test
    void q_isACaseInsensitiveContainsOnTheEmail() throws Exception {
        assertThat(page(list(adminToken, "?q=" + tag.toUpperCase()).andExpect(status().isOk()))
                .get("totalElements").asLong()).isEqualTo(5);
        assertThat(page(list(adminToken, "?q=EXAMPLE.com").andExpect(status().isOk()))
                .get("totalElements").asLong()).isGreaterThanOrEqualTo(5);
        assertThat(page(list(adminToken, "?q=" + tag + "-3@").andExpect(status().isOk()))
                .get("totalElements").asLong()).isEqualTo(1);
        assertThat(page(list(adminToken, "?q=no-such-person-" + tag).andExpect(status().isOk()))
                .get("totalElements").asLong()).isZero();
    }

    @Test
    void q_treatsPercentAndUnderscoreAsPlainCharacters() throws Exception {
        for (String wildcard : new String[] {"%", "_", "%%", "a%"}) {
            JsonNode page = page(mockMvc.perform(get("/api/users").param("q", wildcard)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk()));

            assertThat(page.get("totalElements").asLong()).as("q=" + wildcard).isZero();
        }
    }

    @Test
    void anEmptyQ_meansNoFilter() throws Exception {
        long all = page(list(adminToken, "").andExpect(status().isOk())).get("totalElements").asLong();

        assertThat(page(list(adminToken, "?q=").andExpect(status().isOk())).get("totalElements").asLong())
                .isEqualTo(all);
    }

    @Test
    void q_of100Characters_isAllowed_101_is400() throws Exception {
        list(adminToken, "?q=" + "a".repeat(100)).andExpect(status().isOk());
        list(adminToken, "?q=" + "a".repeat(101)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ---------------------------------------------------------------- limits

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=-1", "size=101", "size=1000000", "size=abc", "size=2.5",
            "size=99999999999999999999", "page=-1", "page=100001", "page=abc", "page=1.5",
            "page=99999999999999999999", "size=0&page=0", "size=20&page=-3"})
    void aParameterOutsideTheLimits_is400(String query) throws Exception {
        list(adminToken, "?" + query).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=1", "size=100", "page=0", "page=100000", "size=100&page=100000"})
    void aParameterAtTheLimit_is200(String query) throws Exception {
        list(adminToken, "?" + query).andExpect(status().isOk());
    }

    @Test
    void pageFarBeyondTheEnd_isAnEmptyPage_not500() throws Exception {
        JsonNode page = page(list(adminToken, "?page=100000").andExpect(status().isOk()));

        assertThat(page.get("content")).isEmpty();
        assertThat(page.get("empty").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- the hash

    @Test
    void theAnswer_neverContainsAPasswordOrAHash() throws Exception {
        MvcResult result = list(adminToken, "?size=100").andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("password").doesNotContain("Password").doesNotContain("hash")
                .doesNotContain("$2a$").doesNotContain("$2b$");
        for (User user : userRepository.findAll()) {
            assertThat(body).doesNotContain(user.getPasswordHash());
        }
    }

    // ---------------------------------------------------------------- /me is unchanged

    @Test
    void me_isUnchanged_idEmailAndRoleOnly() throws Exception {
        String userToken = loginToken(tag + "-2@example.com", PASSWORD);

        JsonNode me = objectMapper.readTree(mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        Set<String> fields = new HashSet<>();
        me.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "email", "role");
    }
}
