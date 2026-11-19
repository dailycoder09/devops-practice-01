package com.microservices.user.service;

import com.microservices.user.config.SecurityProperties;
import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Admin Seeder
 *
 * On every start: if no user has the configured admin email (ADMIN_EMAIL), create it with the
 * role ADMIN and the configured password (ADMIN_PASSWORD). An existing account is never changed,
 * so running this twice creates one admin.
 *
 * @author Microservices Team
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    @Override
    public void run(ApplicationArguments args) {
        String email = securityProperties.getAdmin().getEmail();
        String password = securityProperties.getAdmin().getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("No admin account is configured (ADMIN_EMAIL / ADMIN_PASSWORD), so none was created");
            return;
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.info("Admin account already exists, nothing to create");
            return;
        }

        User admin = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(password))
                .role(Role.ADMIN)
                .build();
        try {
            userRepository.saveAndFlush(admin);
            // The email is personal data, so it is not written to the log
            log.info("Created the admin account");
        } catch (DataIntegrityViolationException e) {
            // Another instance created it at the same moment. That is fine.
            log.info("Admin account was created by another instance at the same time");
        }
    }
}
