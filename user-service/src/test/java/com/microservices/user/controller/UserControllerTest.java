package com.microservices.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import com.microservices.user.support.JwtTestSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the register, login and me endpoints through the real security filter chain
 * and an in-memory H2 database (Flyway runs the real migration).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    // ---------------------------------------------------------------- helpers

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String json(String email, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        return objectMapper.writeValueAsString(body);
    }

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Registers a new user and returns the token from a successful login. */
    private String registerAndLogin(String email) throws Exception {
        register(email, PASSWORD).andExpect(status().isCreated());
        return body(login(email, PASSWORD).andExpect(status().isOk())).get("token").asText();
    }

    private ResultActions me(String authorizationHeader) throws Exception {
        if (authorizationHeader == null) {
            return mockMvc.perform(get("/api/users/me"));
        }
        return mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, authorizationHeader));
    }

    // ---------------------------------------------------------------- register

    @Test
    void register_returns201_withIdEmailAndRoleUser() throws Exception {
        String email = uniqueEmail();

        register(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_storesEmailInLowerCase() throws Exception {
        String lower = uniqueEmail();
        String mixed = lower.replace("user-", "USER-").replace("@example.com", "@Example.COM");

        register(mixed, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(lower));

        assertThat(userRepository.findByEmail(lower)).isPresent();
        assertThat(userRepository.findByEmail(mixed)).isEmpty();
    }

    @Test
    void register_returns409_whenEmailIsAlreadyUsed() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        register(email, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void register_returns409_whenEmailDiffersOnlyByCase() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        register(email.toUpperCase(), PASSWORD).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "plainaddress", "@no-local-part.com", "a@", "a@b@c.com",
            "with space@example.com", ""})
    void register_returns400_whenEmailIsNotValid(String badEmail) throws Exception {
        register(badEmail, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void register_returns400_whenEmailIsMissing() throws Exception {
        register(null, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void register_returns400_whenEmailIsLongerThan255Characters() throws Exception {
        String tooLong = "a".repeat(250) + "@example.com";

        register(tooLong, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void register_returns400_whenPasswordIsShorterThan8Characters() throws Exception {
        register(uniqueEmail(), "1234567")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void register_returns400_whenPasswordIsLongerThan72Characters() throws Exception {
        register(uniqueEmail(), "p".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void register_returns400_whenPasswordIsMissing() throws Exception {
        register(uniqueEmail(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void register_accepts_passwordsOfExactly8And72Characters() throws Exception {
        register(uniqueEmail(), "12345678").andExpect(status().isCreated());
        register(uniqueEmail(), "p".repeat(72)).andExpect(status().isCreated());
    }

    @Test
    void register_returns400_whenBodyIsNotJson() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void register_neverEchoesThePasswordBackInAnError() throws Exception {
        String secretLookingPassword = "pw-7chr";

        String responseBody = register(uniqueEmail(), secretLookingPassword)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(secretLookingPassword);
    }

    @Test
    void register_alwaysCreatesRoleUser_evenWhenTheBodyAsksForAdmin() throws Exception {
        String email = uniqueEmail();
        String bodyWithRole = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}";

        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithRole))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));

        assertThat(userRepository.findByEmail(email).orElseThrow().getRole().name()).isEqualTo("USER");
    }

    @Test
    void register_storesAPasswordHash_neverThePlainPassword() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        User stored = userRepository.findByEmail(email).orElseThrow();

        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(stored.toString()).doesNotContain(stored.getPasswordHash());
    }

    @Test
    void register_givesTwoUsersWithTheSamePasswordDifferentHashes() throws Exception {
        String first = uniqueEmail();
        String second = uniqueEmail();
        register(first, PASSWORD).andExpect(status().isCreated());
        register(second, PASSWORD).andExpect(status().isCreated());

        assertThat(userRepository.findByEmail(first).orElseThrow().getPasswordHash())
                .isNotEqualTo(userRepository.findByEmail(second).orElseThrow().getPasswordHash());
    }

    @Test
    void register_worksEvenWhenAStaleTokenIsSent() throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer this.is.garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(uniqueEmail(), PASSWORD)))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_returns200_withTokenAndContractFields() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void login_returnsHs256TokenWithSubEmailRoleIatAndExpiryAbout8HoursLater() throws Exception {
        String email = uniqueEmail();
        long id = body(register(email, PASSWORD).andExpect(status().isCreated())).get("id").asLong();
        Instant before = Instant.now().minusSeconds(2);

        JsonNode loginBody = body(login(email, PASSWORD).andExpect(status().isOk()));
        Instant after = Instant.now().plusSeconds(2);

        SignedJWT jwt = SignedJWT.parse(loginBody.get("token").asText());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(jwtSecret))).isTrue();

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(id));
        assertThat(claims.getStringClaim("email")).isEqualTo(email);
        assertThat(claims.getStringClaim("role")).isEqualTo("USER");

        Instant issuedAt = claims.getIssueTime().toInstant();
        Instant expiresAt = claims.getExpirationTime().toInstant();
        assertThat(issuedAt).isBetween(before, after);
        assertThat(expiresAt.getEpochSecond() - issuedAt.getEpochSecond()).isEqualTo(8 * 60 * 60);

        // expiresAt in the response is the same moment as the exp claim, as an ISO-8601 UTC instant
        assertThat(loginBody.get("expiresAt").asText()).endsWith("Z");
        assertThat(Instant.parse(loginBody.get("expiresAt").asText())).isEqualTo(expiresAt);
    }

    @Test
    void login_worksWhenTheEmailIsTypedWithOtherCase() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        login(email.toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void login_returnsTheSame401Body_forWrongPasswordAndUnknownEmail() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        ResultActions wrongPassword = login(email, "this-is-not-the-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        ResultActions unknownEmail = login(uniqueEmail(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        String wrongPasswordBody = wrongPassword.andReturn().getResponse().getContentAsString();
        String unknownEmailBody = unknownEmail.andReturn().getResponse().getContentAsString();

        assertThat(wrongPasswordBody).isNotBlank().isEqualTo(unknownEmailBody);
        JsonNode problem = objectMapper.readTree(wrongPasswordBody);
        assertThat(problem.get("status").asInt()).isEqualTo(401);
        assertThat(problem.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(problem.get("detail").asText()).isEqualTo("Wrong email or password.");
        assertThat(wrongPasswordBody).doesNotContain(email);
    }

    @Test
    void login_returns400_whenFieldsAreMissing() throws Exception {
        login(null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void login_worksEvenWhenAStaleTokenIsSent() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        String expired = JwtTestSupport.token(jwtSecret, "1", email, "USER",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        mockMvc.perform(post("/api/users/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());
    }

    // ---------------------------------------------------------------- me

    @Test
    void me_returns200_withProfile_whenTokenIsValid() throws Exception {
        String email = uniqueEmail();
        long id = body(register(email, PASSWORD).andExpect(status().isCreated())).get("id").asLong();
        String token = body(login(email, PASSWORD)).get("token").asText();

        me("Bearer " + token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void me_returns401_withoutToken() throws Exception {
        me(null)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void me_returns401_withExpiredToken() throws Exception {
        String email = uniqueEmail();
        long id = body(register(email, PASSWORD).andExpect(status().isCreated())).get("id").asLong();
        String expired = JwtTestSupport.token(jwtSecret, String.valueOf(id), email, "USER",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        me("Bearer " + expired).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_withTokenSignedWithAnotherSecret() throws Exception {
        String email = uniqueEmail();
        long id = body(register(email, PASSWORD).andExpect(status().isCreated())).get("id").asLong();
        String foreign = JwtTestSupport.validToken("another-secret-that-is-also-long-enough-0123456789",
                String.valueOf(id), email, "USER");

        me("Bearer " + foreign).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_withTamperedToken() throws Exception {
        String token = registerAndLogin(uniqueEmail());
        String tampered = JwtTestSupport.withTamperedRole(token, "ADMIN");

        me("Bearer " + tampered).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_withUnsignedToken() throws Exception {
        me("Bearer " + JwtTestSupport.unsigned("1", "someone@example.com", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_withGarbageToken() throws Exception {
        me("Bearer not-a-token").andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_whenTheAuthorizationSchemeIsNotBearer() throws Exception {
        String token = registerAndLogin(uniqueEmail());

        me("Basic " + token).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_whenTheUserOfAValidTokenNoLongerExists() throws Exception {
        String token = JwtTestSupport.validToken(jwtSecret, "987654321", "gone@example.com", "USER");

        me("Bearer " + token).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns401_whenTheSubjectOfAValidTokenIsNotANumber() throws Exception {
        String token = JwtTestSupport.validToken(jwtSecret, "not-a-number", "odd@example.com", "USER");

        me("Bearer " + token)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void me_showsTheRoleOfTheSeededAdmin() throws Exception {
        String token = body(login("test.admin@example.com", "test-admin-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))).get("token").asText();

        me("Bearer " + token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test.admin@example.com"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }
}
