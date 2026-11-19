package com.microservices.user.service;

import com.microservices.user.dto.request.LoginRequest;
import com.microservices.user.dto.request.RegisterRequest;
import com.microservices.user.dto.response.LoginResponse;
import com.microservices.user.dto.response.UserResponse;
import com.microservices.user.dto.response.UserSummaryResponse;
import org.springframework.data.domain.Page;

/**
 * User Service Interface
 *
 * Defines the business operations for accounts: register, login and profile
 *
 * @author Microservices Team
 * @version 1.0
 */
public interface UserService {

    /**
     * Create a new account with the role USER
     *
     * @param request email and password
     * @return the created user (never the password)
     * @throws com.microservices.user.exception.DuplicateEmailException if the email is already used
     */
    UserResponse register(RegisterRequest request);

    /**
     * Check the password and create a token
     *
     * @param request email and password
     * @return the token and the profile basics
     * @throws com.microservices.user.exception.InvalidCredentialsException if the email or password is wrong
     */
    LoginResponse login(LoginRequest request);

    /**
     * Get a user's profile
     *
     * @param id user id
     * @return the profile
     * @throws com.microservices.user.exception.UserNotFoundException if there is no such user
     */
    UserResponse getById(Long id);

    /**
     * List accounts for the admin area, newest first (highest id first)
     *
     * @param query optional text; only users whose email contains it (any case) are listed
     * @param page  page number, from 0
     * @param size  users per page
     * @return one page of users (never the password hash)
     */
    Page<UserSummaryResponse> list(String query, int page, int size);
}
