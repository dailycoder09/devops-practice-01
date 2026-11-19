package com.microservices.cart.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Global Exception Handler
 *
 * Centralized exception handling for the entire application
 * Uses RFC 7807 Problem Details for HTTP APIs: every answer has "title", "status" and "detail"
 * (and "type", which is "about:blank")
 *
 * It extends ResponseEntityExceptionHandler, so the standard Spring MVC errors (wrong method, wrong media
 * type, unreadable JSON, unknown URL) also come back as problem JSON with the right status instead of a 500.
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String BAD_QUANTITY =
            "The request body must be JSON like {\"quantity\": 2}, with a whole number from 1 to 99.";

    /**
     * Handle Unknown Product Exception
     *
     * @param ex UnknownProductException
     * @return ProblemDetail with 422 status
     */
    @ExceptionHandler(UnknownProductException.class)
    public ResponseEntity<ProblemDetail> handleUnknownProduct(UnknownProductException ex) {
        log.warn("Item refused: the product does not exist");
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /**
     * Handle Product Service Unavailable Exception
     *
     * @param ex ProductServiceUnavailableException
     * @return ProblemDetail with 503 status
     */
    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleProductServiceUnavailable(ProductServiceUnavailableException ex) {
        log.warn("Product service did not answer");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * Handle Validation Exceptions (the quantity is missing, below 1 or above 99)
     *
     * Spring's own message repeats the rejected value, so it is not used.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // Only the names of the fields: Spring's own message repeats the rejected value
        log.warn("Validation failed for fields: {}", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField()).toList());
        return handleExceptionInternal(ex, badRequest(), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Handle a body that is missing, is not JSON, or has a quantity that is not a whole number
     * (text, a decimal, a number too big for an int)
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // handleExceptionInternal writes the (one) warning line
        return handleExceptionInternal(ex, badRequest(), headers, HttpStatus.BAD_REQUEST, request);
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
     * @return ProblemDetail with 500 status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGlobalException(Exception ex) {
        // The one place where an unexpected error is logged, with its stack trace
        log.error("Unexpected error occurred", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.");
    }

    private static ProblemDetail badRequest() {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, BAD_QUANTITY);
        problemDetail.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        return problemDetail;
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).body(problemDetail);
    }
}
