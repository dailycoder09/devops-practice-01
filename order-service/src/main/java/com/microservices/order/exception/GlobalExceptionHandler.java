package com.microservices.order.exception;

import com.microservices.order.logging.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Global Exception Handler
 *
 * Centralized exception handling for the entire application
 * Uses RFC 7807 Problem Details for HTTP APIs: every error body has type, title, status and detail
 * (the same shape as the 401 and 403 answers of the security layer).
 *
 * It extends ResponseEntityExceptionHandler, so the standard Spring MVC errors (wrong method, bad parameter,
 * unknown URL) also come back as problem JSON with the right status instead of a 500.
 *
 * Logging: every failure is logged ONCE here (the exception is not logged again anywhere else). A handled problem
 * (4xx) is one WARN line without a stack trace; only an unexpected error (5xx) is ERROR with the stack trace.
 * A rejected order is not logged here: it already has its own audit event (order_rejected). Messages that contain a
 * value from the caller are cleaned, and the text of a bad parameter is never logged (only the kind of error).
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * The cart is empty: 400
     */
    @ExceptionHandler(EmptyCartException.class)
    public ResponseEntity<ProblemDetail> handleEmptyCart(EmptyCartException ex) {
        log.warn("Checkout refused: the cart is empty");
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * A product of the cart is gone: 422
     */
    @ExceptionHandler(ProductNotAvailableException.class)
    public ResponseEntity<ProblemDetail> handleProductNotAvailable(ProductNotAvailableException ex) {
        log.warn("Checkout refused: {}", LogSanitizer.clean(ex.getMessage()));
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /**
     * The cart service or the product service did not answer, and nothing was saved: 503
     */
    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(DependencyUnavailableException ex) {
        log.warn("Checkout stopped before saving anything: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * The order was saved and then rejected: 409 (stock short) or 503 (a service failed), with the order id
     */
    @ExceptionHandler(OrderRejectedException.class)
    public ResponseEntity<ProblemDetail> handleOrderRejected(OrderRejectedException ex) {
        // Not logged here: the order_rejected audit event of the checkout is the one line for this.
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setTitle(ex.getStatus().getReasonPhrase());
        problemDetail.setProperty("orderId", ex.getOrderId());
        return ResponseEntity.status(ex.getStatus()).body(problemDetail);
    }

    /**
     * No such order, or not the caller's: 404
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotFound(OrderNotFoundException ex) {
        log.warn("Order not found, or not visible to the caller");
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * A valid token whose "sub" is not a user id: treated like a bad token, 401
     */
    @ExceptionHandler(InvalidTokenSubjectException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTokenSubject(InvalidTokenSubjectException ex,
                                                                   HttpServletRequest request) {
        logRejectedToken(request);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problemDetail.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problemDetail);
    }

    /**
     * Handle all other exceptions
     *
     * @param ex Exception
     * @return ProblemDetail with 500 status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGlobalException(Exception ex) {
        log.error("Unexpected error while handling the request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. Please try again later.");
    }

    /**
     * The standard Spring MVC errors (400 for a bad parameter, 404, 405, 415 ...). One line per error: WARN without a
     * stack trace for a 4xx, ERROR with the stack trace for a 5xx. Only the kind of error is logged, never the text
     * of the exception, because that text often repeats what the caller sent.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        try {
            if (statusCode.is5xxServerError()) {
                log.error("Request failed with status {} ({})", statusCode.value(), ex.getClass().getSimpleName(), ex);
            } else {
                log.warn("Request refused with status {} ({})", statusCode.value(), ex.getClass().getSimpleName());
            }
        } catch (RuntimeException ignored) {
            // Logging must never change the answer.
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /** A token whose subject is not a user id is refused like a bad token: the same security event as a 401. */
    private static void logRejectedToken(HttpServletRequest request) {
        try {
            String method = LogSanitizer.clean(request.getMethod());
            String path = LogSanitizer.clean(request.getRequestURI());
            log.warn("Request rejected (invalid_token) {} {}", method, path,
                    kv("event", "auth_rejected"), kv("reason", "invalid_token"),
                    kv("httpMethod", method), kv("httpPath", path));
        } catch (RuntimeException ignored) {
            // Logging must never change the answer.
        }
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).body(problemDetail);
    }
}
