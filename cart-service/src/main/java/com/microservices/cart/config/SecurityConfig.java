package com.microservices.cart.config;

import com.microservices.cart.logging.LoggingProperties;
import com.microservices.cart.logging.UserMdcFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

/**
 * Security Configuration
 *
 * - Stateless: no session, no cookies, CSRF off (the login travels in the Authorization header).
 * - user-service signs the tokens (HS256). This service only checks them, with the same shared secret.
 * - The authority comes from the "role" claim with the prefix ROLE_ (ADMIN becomes ROLE_ADMIN).
 * - Every /api/cart request needs the role USER or ADMIN. The internal SERVICE token of order-service is
 *   valid, but gets 403 here. A token whose "sub" is not a user id gets 401 (see CartUserIdFilter).
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
@EnableWebSecurity
@Import(JwtStartupCheck.class)
public class SecurityConfig {

    private static final String USER = "USER";
    private static final String ADMIN = "ADMIN";

    /**
     * All cart URLs (/api/cart and everything below it)
     */
    private static final RequestMatcher CART_REQUESTS = antMatcher("/api/cart/**");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   ProblemJsonSecurityHandlers problemHandlers,
                                                   ObjectProvider<LoggingProperties> loggingProperties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(antMatcher("/actuator/health/**")).permitAll()
                        .requestMatchers(antMatcher("/actuator/env/**"), antMatcher("/actuator/loggers/**"))
                                .hasRole(ADMIN)
                        .requestMatchers(antMatcher("/swagger-ui.html"), antMatcher("/swagger-ui/**"),
                                antMatcher("/api-docs/**")).permitAll()
                        .requestMatchers(CART_REQUESTS).hasAnyRole(USER, ADMIN)
                        .requestMatchers(antMatcher("/actuator/**")).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                // Puts the caller into the log context, right after the token has been read
                .addFilterAfter(new UserMdcFilter(loggingProperties.getIfAvailable(LoggingProperties::new)),
                        BearerTokenAuthenticationFilter.class)
                // After the role check: a SERVICE token is already refused, a bad "sub" is refused here
                .addFilterAfter(new CartUserIdFilter(CART_REQUESTS, problemHandlers), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Checks incoming tokens: HS256 only, signed with the shared secret, not expired.
     * The startup check is a parameter so it always runs before a key is built from the secret.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtStartupCheck check) {
        SecretKey key = new SecretKeySpec(check.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
