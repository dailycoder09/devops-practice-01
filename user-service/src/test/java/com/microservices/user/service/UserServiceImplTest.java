package com.microservices.user.service;

import com.microservices.user.dto.request.LoginRequest;
import com.microservices.user.dto.request.RegisterRequest;
import com.microservices.user.dto.response.LoginResponse;
import com.microservices.user.dto.response.UserResponse;
import com.microservices.user.exception.DuplicateEmailException;
import com.microservices.user.exception.InvalidCredentialsException;
import com.microservices.user.exception.UserNotFoundException;
import com.microservices.user.mapper.UserMapper;
import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import com.microservices.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String DUMMY_HASH = "$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdummyhashd";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private final UserMapper userMapper = Mappers.getMapper(UserMapper.class);

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        // The service hashes one throw-away password at start (used to keep login timing equal).
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        service = new UserServiceImpl(userRepository, passwordEncoder, tokenService, userMapper);
    }

    private static RegisterRequest registerRequest(String email) {
        return RegisterRequest.builder().email(email).password("a-fine-password").build();
    }

    private static LoginRequest loginRequest(String email, String password) {
        return LoginRequest.builder().email(email).password(password).build();
    }

    private static User storedUser() {
        return User.builder().id(7L).email("ann@example.com").passwordHash("stored-hash").role(Role.USER).build();
    }

    // ---------------------------------------------------------------- register

    @Test
    void register_savesALowerCaseEmail_roleUser_andTheHashNotThePassword() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        when(passwordEncoder.encode("a-fine-password")).thenReturn("the-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            user.setId(42L);
            return user;
        });

        UserResponse response = service.register(registerRequest("Ann@Example.COM"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ann@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("the-hash");
        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getEmail()).isEqualTo("ann@example.com");
        assertThat(response.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void register_throwsDuplicateEmail_whenTheEmailExists() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(registerRequest("ann@example.com")))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_throwsDuplicateEmail_whenTheDatabaseRejectsTheEmail() {
        when(userRepository.existsByEmail("ann@example.com")).thenReturn(false);
        when(passwordEncoder.encode("a-fine-password")).thenReturn("the-hash");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.register(registerRequest("ann@example.com")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    // ---------------------------------------------------------------- login

    @Test
    void login_returnsTheTokenAndProfile_whenThePasswordMatches() {
        User user = storedUser();
        Instant expiresAt = Instant.parse("2030-01-01T08:00:00Z");
        when(userRepository.findByEmail("ann@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("right-password", "stored-hash")).thenReturn(true);
        when(tokenService.issue(user)).thenReturn(new IssuedToken("the.jwt.value", expiresAt));

        LoginResponse response = service.login(loginRequest("Ann@Example.com", "right-password"));

        assertThat(response.getToken()).isEqualTo("the.jwt.value");
        assertThat(response.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(response.getEmail()).isEqualTo("ann@example.com");
        assertThat(response.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void login_throwsInvalidCredentials_whenThePasswordIsWrong() {
        when(userRepository.findByEmail("ann@example.com")).thenReturn(Optional.of(storedUser()));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(loginRequest("ann@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(tokenService, never()).issue(any());
    }

    @Test
    void login_throwsInvalidCredentials_forAnUnknownEmail_afterDoingTheSameHashingWork() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(eq("whatever"), eq(DUMMY_HASH))).thenReturn(false);

        assertThatThrownBy(() -> service.login(loginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);

        // the password is still compared with a dummy hash, so an unknown email is not faster than a wrong password
        verify(passwordEncoder).matches("whatever", DUMMY_HASH);
        verify(tokenService, never()).issue(any());
    }

    // ---------------------------------------------------------------- profile

    @Test
    void getById_returnsTheProfile() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(storedUser()));

        UserResponse response = service.getById(7L);

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getEmail()).isEqualTo("ann@example.com");
        assertThat(response.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void getById_throwsUserNotFound_whenThereIsNoSuchUser() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(99L)).isInstanceOf(UserNotFoundException.class);
    }
}
