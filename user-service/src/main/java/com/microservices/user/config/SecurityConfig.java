package com.microservices.user.config;

import com.microservices.user.logging.LoggingProperties;
import com.microservices.user.logging.UserMdcFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Security Configuration
 *
 * - Stateless: no session, no cookies, CSRF off (the login travels in the Authorization header).
 * - Tokens are HS256 JWTs. This service creates them (JwtEncoder) and checks them (JwtDecoder)
 *   with the same shared secret as product-service and inventory-service.
 * - The authority comes from the "role" claim with the prefix ROLE_ (ADMIN becomes ROLE_ADMIN).
 *
 * @author Microservices Team
 * @version 1.0
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * Login and register are public, and they ignore an Authorization header, so an old or broken token
     * left in the browser cannot stop a user from logging in again.
     */
    private static final Set<String> PUBLIC_POST_PATHS = Set.of("/api/users/register", "/api/users/login");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   ProblemJsonSecurityHandlers problemHandlers,
                                                   ObjectProvider<LoggingProperties> loggingProperties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/users/register", "/api/users/login").permitAll()
                        .requestMatchers("/api/users/me").authenticated()
                        // the list of all accounts is for the admin area only (a USER or SERVICE token gets 403)
                        .requestMatchers("/api/users").hasRole("ADMIN")
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/env/**", "/actuator/loggers/**").hasRole("ADMIN")
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver())
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
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Checks incoming tokens: HS256 only, signed with the shared secret, not expired.
     * The startup check is a parameter so it always runs before a key is built from the secret.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties properties, StartupSecurityCheck startupCheck) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Creates tokens at login, signed with the same shared secret.
     */
    @Bean
    public JwtEncoder jwtEncoder(SecurityProperties properties, StartupSecurityCheck startupCheck) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    private static SecretKey secretKey(SecurityProperties properties) {
        return new SecretKeySpec(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        return request -> {
            boolean publicLoginCall = "POST".equals(request.getMethod())
                    && PUBLIC_POST_PATHS.contains(request.getRequestURI());
            return publicLoginCall ? null : defaultResolver.resolve(request);
        };
    }
}
