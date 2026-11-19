package com.microservices.user.service;

import com.microservices.user.config.SecurityProperties;
import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSeederLogicTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SecurityProperties properties;
    private AdminSeeder seeder;

    @BeforeEach
    void setUp() {
        properties = new SecurityProperties();
        properties.getAdmin().setEmail("  Boss@Company.TEST ");
        properties.getAdmin().setPassword("a-strong-admin-password");
        seeder = new AdminSeeder(userRepository, passwordEncoder, properties);
    }

    @Test
    void createsTheAdmin_withLowerCaseEmail_roleAdmin_andHashedPassword() {
        when(userRepository.existsByEmail("boss@company.test")).thenReturn(false);
        when(passwordEncoder.encode("a-strong-admin-password")).thenReturn("hashed-value");

        seeder.run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("boss@company.test");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed-value");
    }

    @Test
    void doesNothing_whenAUserWithThatEmailAlreadyExists() {
        when(userRepository.existsByEmail("boss@company.test")).thenReturn(true);

        seeder.run(null);

        verify(userRepository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void doesNothing_whenNoAdminIsConfigured() {
        properties.getAdmin().setEmail("");
        properties.getAdmin().setPassword("");

        seeder.run(null);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void doesNotFail_whenAnotherInstanceCreatedTheAdminAtTheSameTime() {
        when(userRepository.existsByEmail("boss@company.test")).thenReturn(false);
        when(passwordEncoder.encode("a-strong-admin-password")).thenReturn("hashed-value");
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> seeder.run(null)).doesNotThrowAnyException();
    }
}
