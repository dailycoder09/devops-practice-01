package com.microservices.order.config;

import com.microservices.order.logging.UserMdcFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Security Configuration
 *
 * - Stateless: no session, no cookies, CSRF off (the login travels in the Authorization header).
 * - Tokens are HS256 JWTs signed by user-service. This service checks them (JwtDecoder) with the same shared
 *   secret as the other services, and it also creates the short-lived internal SERVICE token (JwtEncoder)
 *   that it needs to call inventory-service.
 * - The authority comes from the "role" claim with the prefix ROLE_ (ADMIN becomes ROLE_ADMIN).
 * - /api/orders needs the role USER or ADMIN. A SERVICE token is valid, but it gets 403 here.
 * - /api/orders/all and /api/orders/stats (the admin area) need the role ADMIN. That rule comes BEFORE the generic
 *   /api/orders/** rule, so a USER or SERVICE token gets 403 there (and never a 400 from the numeric {id} path).
 * - UserMdcFilter (right after the bearer token filter) puts the caller in the logging MDC.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
@EnableWebSecurity
@Import(JwtStartupCheck.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   ProblemJsonSecurityHandlers problemHandlers,
                                                   @Value("${app.logging.include-email:true}") boolean includeEmailInLogs)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The admin-only literal paths first: the first matching rule wins.
                        .requestMatchers("/api/orders/all", "/api/orders/stats").hasRole("ADMIN")
                        .requestMatchers("/api/orders", "/api/orders/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/env/**", "/actuator/loggers/**").hasRole("ADMIN")
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
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
                // Not a bean on purpose (a Filter bean would run twice): it is created here, for this chain only.
                .addFilterAfter(new UserMdcFilter(includeEmailInLogs), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Checks incoming tokens: HS256 only, signed with the shared secret, not expired.
     * The startup check is a parameter so it always runs before a key is built from the secret.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtStartupCheck check) {
        return NimbusJwtDecoder.withSecretKey(secretKey(check))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Creates the internal SERVICE token (see ServiceTokenIssuer), signed with the same shared secret.
     */
    @Bean
    public JwtEncoder jwtEncoder(JwtStartupCheck check) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(check)));
    }

    private static SecretKey secretKey(JwtStartupCheck check) {
        return new SecretKeySpec(check.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
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
