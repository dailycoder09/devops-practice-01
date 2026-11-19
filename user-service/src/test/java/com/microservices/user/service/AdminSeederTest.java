package com.microservices.user.service;

import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin account is created on start from the configuration, and only once.
 * The test configuration has the admin email "Test.Admin@Example.com" (mixed case).
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminSeederTest {

    private static final String ADMIN_EMAIL = "test.admin@example.com";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminSeeder adminSeeder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long adminCount() {
        return userRepository.findAll().stream().filter(user -> ADMIN_EMAIL.equals(user.getEmail())).count();
    }

    @Test
    void adminIsCreatedOnStart_withRoleAdmin_lowerCaseEmail_andHashedPassword() {
        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getPasswordHash()).isNotEqualTo("test-admin-password").startsWith("$2");
        assertThat(passwordEncoder.matches("test-admin-password", admin.getPasswordHash())).isTrue();
    }

    @Test
    void runningTheSeederAgain_doesNotCreateASecondAdmin() throws Exception {
        long usersBefore = userRepository.count();
        String hashBefore = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getPasswordHash();

        adminSeeder.run(null);
        adminSeeder.run(null);

        assertThat(adminCount()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
    }
}
