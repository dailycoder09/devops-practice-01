package com.microservices.user.service.impl;

import com.microservices.user.dto.request.LoginRequest;
import com.microservices.user.dto.request.RegisterRequest;
import com.microservices.user.dto.response.LoginResponse;
import com.microservices.user.dto.response.UserResponse;
import com.microservices.user.dto.response.UserSummaryResponse;
import com.microservices.user.exception.DuplicateEmailException;
import com.microservices.user.exception.InvalidCredentialsException;
import com.microservices.user.exception.UserNotFoundException;
import com.microservices.user.logging.AuditLog;
import com.microservices.user.logging.LogSanitizer;
import com.microservices.user.logging.LoggingProperties;
import com.microservices.user.logging.UserMdcFilter;
import com.microservices.user.mapper.UserMapper;
import com.microservices.user.model.Role;
import com.microservices.user.model.User;
import com.microservices.user.repository.UserRepository;
import com.microservices.user.service.IssuedToken;
import com.microservices.user.service.TokenService;
import com.microservices.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * User Service Implementation
 *
 * Implements register, login, profile and the list of accounts for admins. Passwords are only ever handled as
 * BCrypt hashes and are never logged. Registration and login write audit events (see AuditLog).
 *
 * @author Microservices Team
 * @version 1.0
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserMapper userMapper;

    /**
     * A hash of a throw-away password. When the email is unknown, the login password is still
     * compared with this hash, so an unknown email takes as long as a wrong password.
     */
    private final String dummyHash;

    /** Settings of the logging. The default is used when the service is built without Spring (in a unit test). */
    private LoggingProperties loggingProperties = new LoggingProperties();

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
                           TokenService tokenService, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.userMapper = userMapper;
        this.dummyHash = passwordEncoder.encode("not-the-password-of-anyone");
    }

    @Autowired(required = false)
    void setLoggingProperties(LoggingProperties loggingProperties) {
        this.loggingProperties = loggingProperties;
    }

    /**
     * Emails are compared and stored in lower case.
     */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same email at the same moment: the unique key in the database decides.
            throw new DuplicateEmailException();
        }

        AuditLog.event("user_registered", "User registered",
                fields(kv("newUserId", user.getId()), emailField(email)));
        return userMapper.toResponse(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());

        Optional<User> found = userRepository.findByEmail(email);
        String hashToCheck = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            // The logs say why. The answer to the caller is the same in both cases.
            String reason = found.isEmpty() ? "unknown_email" : "bad_password";
            AuditLog.warn("login_failed", "Login failed: {}", fields(kv("reason", reason), emailField(email)));
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        IssuedToken token = tokenService.issue(user);
        // From here on this request is the request of the user who logged in: every line, the access line
        // too, shows who it is. The audit event takes the user id from there.
        UserMdcFilter.putUser(String.valueOf(user.getId()), user.getRole().name(), user.getEmail(),
                loggingProperties.isIncludeEmail());
        AuditLog.event("login_succeeded", "User logged in", fields(emailField(email)));

        return LoginResponse.builder()
                .token(token.value())
                .expiresAt(token.expiresAt())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    @Override
    public UserResponse getById(Long id) {
        return userRepository.findById(id)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    @Override
    public Page<UserSummaryResponse> list(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        String search = query == null ? "" : query.trim();
        Page<User> users = search.isEmpty()
                ? userRepository.findAll(pageable)
                : userRepository.findByEmailContainingIgnoreCase(search, pageable);
        return users.map(userMapper::toSummary);
    }

    /**
     * The "email" field of an audit event: a cleaned copy of what the caller typed, or nothing at all when the
     * logs must not hold emails (app.logging.include-email=false). Never a password or a hash.
     */
    private Object emailField(String email) {
        return loggingProperties.isIncludeEmail() ? kv("email", LogSanitizer.clean(email)) : null;
    }

    private static Object[] fields(Object... candidates) {
        List<Object> fields = new ArrayList<>();
        for (Object candidate : candidates) {
            if (candidate != null) {
                fields.add(candidate);
            }
        }
        return fields.toArray();
    }
}
