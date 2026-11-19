package com.microservices.user.repository;

import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository test backed by a real embedded H2 database with Flyway running the real migration.
 */
@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static User user(String email, Role role) {
        return User.builder().email(email).passwordHash("$2a$10$not-a-real-hash").role(role).build();
    }

    @Test
    void save_assignsIdAndTimestamps_andFindByEmailFindsTheUser() {
        User saved = userRepository.saveAndFlush(user("ann@example.com", Role.USER));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(userRepository.findByEmail("ann@example.com")).get()
                .extracting(User::getRole).isEqualTo(Role.USER);
        assertThat(userRepository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void existsByEmail_tellsIfTheEmailIsTaken() {
        userRepository.saveAndFlush(user("ann@example.com", Role.USER));

        assertThat(userRepository.existsByEmail("ann@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("bob@example.com")).isFalse();
    }

    @Test
    void emailMustBeUnique() {
        userRepository.saveAndFlush(user("ann@example.com", Role.USER));

        assertThatThrownBy(() -> userRepository.saveAndFlush(user("ann@example.com", Role.ADMIN)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseOnlyAllowsTheRolesUserAndAdmin() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role) VALUES ('x@example.com', 'hash', 'SUPERUSER')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("INSERT INTO users (email, password_hash, role) VALUES ('y@example.com', 'hash', 'ADMIN')");

        assertThat(userRepository.findByEmail("y@example.com")).get()
                .extracting(User::getRole).isEqualTo(Role.ADMIN);
    }
}
