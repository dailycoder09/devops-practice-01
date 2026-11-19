package com.microservices.user.controller;

import com.microservices.user.config.OpenApiConfig;
import com.microservices.user.dto.request.LoginRequest;
import com.microservices.user.dto.request.RegisterRequest;
import com.microservices.user.dto.response.LoginResponse;
import com.microservices.user.dto.response.UserResponse;
import com.microservices.user.dto.response.UserSummaryResponse;
import com.microservices.user.exception.UserNotFoundException;
import com.microservices.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User REST Controller
 *
 * Register, login, the profile of the logged-in user, and the list of accounts for admins
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Accounts", description = "APIs for registering, logging in and reading your own profile")
public class UserController {

    private final UserService userService;

    /**
     * Register a new user (role USER)
     *
     * @param request email and password
     * @return Created user with HTTP 201
     */
    @PostMapping("/register")
    @Operation(summary = "Register a new account", description = "Creates a user with the role USER")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Account created",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid email or password"),
        @ApiResponse(responseCode = "409", description = "An account with this email already exists")
    })
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    /**
     * Log in and get a token
     *
     * @param request email and password
     * @return the token (valid for 8 hours)
     */
    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Returns a JWT to send as 'Authorization: Bearer <token>'")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Login successful",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "Email or password missing"),
        @ApiResponse(responseCode = "401", description = "Wrong email or password")
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    /**
     * Profile of the user the token belongs to
     *
     * @param jwt the validated token
     * @return the profile
     */
    @GetMapping("/me")
    @Operation(summary = "Get my profile", description = "Needs a valid token",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Profile found",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))),
        @ApiResponse(responseCode = "401", description = "No token, or the token is not valid")
    })
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(userService.getById(userId(jwt)));
    }

    /**
     * List the accounts (admin only: the security rules refuse every other role before this method runs)
     *
     * @param page page number, 0 to 100000
     * @param size users per page, 1 to 100
     * @param q    optional text; only emails that contain it (any case) are listed, at most 100 characters
     * @return one page of users, newest first
     */
    @GetMapping
    @Operation(summary = "List accounts (admin only)",
            description = "Newest first. Never returns a password hash",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME))
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "One page of users"),
        @ApiResponse(responseCode = "400", description = "A parameter is outside its limits"),
        @ApiResponse(responseCode = "401", description = "No token, or the token is not valid"),
        @ApiResponse(responseCode = "403", description = "The token is not an ADMIN token")
    })
    public ResponseEntity<Page<UserSummaryResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String q) {
        return ResponseEntity.ok(userService.list(q, page, size));
    }

    /**
     * The "sub" claim is the user id. A token that was signed by us always has a number there.
     */
    private static Long userId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new UserNotFoundException(-1L);
        }
    }
}
