package com.microservices.user.service;

import com.microservices.user.dto.request.RegisterRequest;
import com.microservices.user.events.EventPublisher;
import com.microservices.user.exception.DuplicateEmailException;
import com.microservices.user.mapper.UserMapper;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import com.microservices.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** A new user registered: one event USER_REGISTERED. A refused registration: no event. The user is never harmed by the event. */
@ExtendWith(MockitoExtension.class)
class UserRegistrationEventTest {

    private record Sent(String type, Map<String, Object> data) { }

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);
    private final List<Sent> sent = new ArrayList<>();
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("a-hash");
        service = new UserServiceImpl(userRepository, passwordEncoder, tokenService, userMapper);
        service.setEventPublisher((type, data) -> sent.add(new Sent(type, data)));
    }

    private static RegisterRequest request(String email) {
        return RegisterRequest.builder().email(email).password("a-fine-password").build();
    }

    private void theDatabaseSavesTheUserWithId(long id) {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            user.setId(id);
            return user;
        });
    }

    @Test
    void register_sendsOneUserRegisteredEvent_withTheIdTheEmailAndTheRole_butNeverThePassword() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        theDatabaseSavesTheUserWithId(42L);

        service.register(request("Ann@Example.COM"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).type()).isEqualTo("USER_REGISTERED");
        assertThat(sent.get(0).data()).containsEntry("userId", 42L)
                .containsEntry("email", "ann@example.com")
                .containsEntry("role", "USER");
        assertThat(sent.get(0).data().toString()).doesNotContain("a-fine-password").doesNotContain("a-hash");
    }

    @Test
    void register_sendsNoEvent_whenTheEmailExists() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(request("ann@example.com"))).isInstanceOf(DuplicateEmailException.class);

        assertThat(sent).isEmpty();
    }

    @Test
    void register_sendsNoEvent_whenTheDatabaseRejectsTheEmail() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.register(request("ann@example.com"))).isInstanceOf(DuplicateEmailException.class);

        assertThat(sent).isEmpty();
    }

    @Test
    void register_stillWorks_whenNoPublisherIsSet() {
        UserServiceImpl plain = new UserServiceImpl(userRepository, passwordEncoder, tokenService, userMapper);
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        theDatabaseSavesTheUserWithId(5L);

        assertThat(plain.register(request("ann@example.com")).getId()).isEqualTo(5L);
    }

    @Test
    void register_stillWorks_whenThePublisherFails() {
        service.setEventPublisher((EventPublisher) (type, data) -> {
            throw new IllegalStateException("must never reach the caller");
        });
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        theDatabaseSavesTheUserWithId(6L);

        assertThat(service.register(request("ann@example.com")).getId()).isEqualTo(6L);
    }
}
