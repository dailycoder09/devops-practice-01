package com.microservices.user.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global Exception Handler
 *
 * Centralized exception handling for the entire application
 * Uses RFC 7807 Problem Details for HTTP APIs
 *
 * It extends ResponseEntityExceptionHandler, so the standard Spring MVC errors (wrong method, wrong media
 * type, unreadable JSON, unknown URL) also come back as problem JSON with the right status instead of a 500.
 *
 * Careful with passwords: the validation exception message repeats the rejected value, so it is never logged
 * or returned. Only the field names and our own short messages are used.
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Handle Duplicate Email Exception
     *
     * @param ex DuplicateEmailException
     * @param request WebRequest
     * @return ProblemDetail with 409 status
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEmailException(
            DuplicateEmailException ex,
            WebRequest request) {

        log.warn("Registration rejected: email already used");

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Duplicate Email");
        problemDetail.setType(URI.create("https://api.example.com/errors/duplicate-email"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    /**
     * Handle Invalid Credentials Exception
     *
     * The body is exactly the same for an unknown email and for a wrong password:
     * no timestamp and no other changing fields.
     *
     * @param ex InvalidCredentialsException
     * @return ProblemDetail with 401 status
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentialsException(InvalidCredentialsException ex) {
        return unauthorized(ex.getMessage());
    }

    /**
     * Handle User Not Found Exception
     *
     * A valid token for a user who no longer exists is treated like a bad token.
     *
     * @param ex UserNotFoundException
     * @return ProblemDetail with 401 status
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFoundException(UserNotFoundException ex) {
        log.warn("Token of a user that no longer exists was used");
        return unauthorized("A valid login token is needed. Please log in again.");
    }

    private ResponseEntity<ProblemDetail> unauthorized(String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problemDetail.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problemDetail);
    }

    /**
     * Handle Validation Exceptions
     *
     * @param ex MethodArgumentNotValidException
     * @param headers HttpHeaders
     * @param status HttpStatusCode
     * @param request WebRequest
     * @return ProblemDetail with validation errors
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.putIfAbsent(fieldName, error.getDefaultMessage());
        });
        // Only the names of the fields: the message of the exception repeats the rejected value (a password)
        log.warn("Validation failed for fields: {}", errors.keySet());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Validation failed for one or more fields"
        );
        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("https://api.example.com/errors/validation"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));
        problemDetail.setProperty("errors", errors);

        return handleExceptionInternal(ex, problemDetail, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Every standard Spring MVC error (wrong method, unreadable body, bad parameter, unknown URL...) comes through
     * here, so each one is logged exactly once: a handled 4xx as a warning with only the name of the exception
     * (its message can repeat what the caller sent), anything else with the stack trace.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        // a failed validation has already written its own line (with the names of the fields)
        if (!(ex instanceof MethodArgumentNotValidException)) {
            if (statusCode.is5xxServerError()) {
                log.error("Request failed with status {}", statusCode.value(), ex);
            } else {
                log.warn("Request rejected with status {}: {}", statusCode.value(), ex.getClass().getSimpleName());
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /**
     * Handle all other exceptions
     *
     * @param ex Exception
     * @param request WebRequest
     * @return ProblemDetail with 500 status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGlobalException(
            Exception ex,
            WebRequest request) {

        // The one place where an unexpected error is logged, with its stack trace
        log.error("Unexpected error occurred", ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("https://api.example.com/errors/internal-server-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}
