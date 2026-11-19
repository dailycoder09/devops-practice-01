package com.microservices.product.config;

import com.microservices.product.logging.LogSanitizer;
import com.microservices.product.logging.UserMdcFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static net.logstash.logback.argument.StructuredArguments.kv;
import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Security Configuration
 *
 * user-service signs a JWT (HS256) when a person logs in; this service only checks it, with the
 * same shared secret. Rules:
 *  - GET, HEAD and OPTIONS are public (this also covers /actuator/health, Swagger and /api-docs).
 *  - POST, PUT, PATCH, DELETE (and anything else) need a valid token with the role ADMIN.
 *  - /actuator/env and /actuator/loggers need ADMIN for every method.
 *  - No token, a bad token or an expired token gives 401. A valid token without the ADMIN role gives 403.
 * There are no sessions, no cookies and no CSRF token: every request carries its own Bearer token.
 *
 * Logging: the {@link UserMdcFilter} sits right after the bearer-token filter and puts the user in the MDC. A
 * refused request is logged once, as auth_rejected (401) or access_denied (403), at WARN. The token is never logged.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
@EnableWebSecurity
@Import(JwtStartupCheck.class)
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * The security rules
     *
     * @param http HttpSecurity builder
     * @param includeEmail app.logging.include-email: false leaves the user's email out of the logs
     * @return the filter chain
     * @throws Exception when the chain cannot be built
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${app.logging.include-email:true}") boolean includeEmail) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // created here and not as a bean, so it is in the security chain only (and runs once per request)
                .addFilterAfter(new UserMdcFilter(includeEmail), BearerTokenAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(antMatcher("/actuator/env/**"), antMatcher("/actuator/loggers/**"))
                                .hasRole(ADMIN)
                        .requestMatchers(antMatcher(HttpMethod.GET, "/**"), antMatcher(HttpMethod.HEAD, "/**"),
                                antMatcher(HttpMethod.OPTIONS, "/**"))
                                .permitAll()
                        .anyRequest().hasRole(ADMIN))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Checks the signature (HS256, shared secret) and the expiry of a token
     *
     * @param check the startup check, which holds the checked secret
     * @return the decoder
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtStartupCheck check) {
        SecretKeySpec key = new SecretKeySpec(check.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Turns the "role" claim into an authority with the ROLE_ prefix (ADMIN becomes ROLE_ADMIN)
     */
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response,
                              AuthenticationException ex) throws IOException {
        // missing_token: no token was sent. invalid_token: one was sent and is bad (signature, expiry, shape). Never the token itself.
        boolean invalid = ex instanceof OAuth2AuthenticationException
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        log.warn("Request refused: {}", invalid ? "invalid token" : "no token",
                kv("event", "auth_rejected"), kv("reason", invalid ? "invalid_token" : "missing_token"));
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writeProblem(response, HttpStatus.UNAUTHORIZED,
                "A valid login token is required. Please log in and try again.");
    }

    private void forbidden(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException ex) throws IOException {
        log.warn("Access denied: {} {}", LogSanitizer.clean(request.getMethod()), LogSanitizer.clean(request.getRequestURI()),
                kv("event", "access_denied"), kv("httpMethod", LogSanitizer.clean(request.getMethod())),
                kv("httpPath", LogSanitizer.clean(request.getRequestURI())));
        writeProblem(response, HttpStatus.FORBIDDEN,
                "You are logged in, but only an administrator can do this.");
    }

    /**
     * Writes the same problem-JSON shape the rest of the service uses (RFC 7807).
     * The texts are fixed, so nothing from the request ends up in the body.
     */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}";
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
