package com.microservices.inventory.exception;

import com.microservices.inventory.logging.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Global Exception Handler
 *
 * Centralized exception handling for the entire application
 * Uses RFC 7807 Problem Details for HTTP APIs
 *
 * Logging: a handled client error (4xx) is one WARN line without a stack trace; an unexpected error (5xx) is one
 * ERROR line with the stack trace. Texts that come from the caller are cleaned first, and a validation error
 * logs only the names of the fields, never the rejected values.
 *
 * @author Microservices Team
 * @version 1.0
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle Inventory Not Found Exception
     *
     * @param ex InventoryNotFoundException
     * @param request WebRequest
     * @return ProblemDetail with 404 status
     */
    @ExceptionHandler(InventoryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleInventoryNotFoundException(
            InventoryNotFoundException ex,
            WebRequest request) {

        log.warn("Inventory record not found: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            ex.getMessage()
        );

        problemDetail.setTitle("Inventory Record Not Found");
        problemDetail.setType(URI.create("https://api.example.com/errors/inventory-not-found"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle SKU Not Found In Catalog Exception
     *
     * @param ex SkuNotFoundInCatalogException
     * @param request WebRequest
     * @return ProblemDetail with 422 status
     */
    @ExceptionHandler(SkuNotFoundInCatalogException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ProblemDetail handleSkuNotFoundInCatalogException(
            SkuNotFoundInCatalogException ex,
            WebRequest request) {

        log.warn("SKU not found in catalog: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage()
        );

        problemDetail.setTitle("SKU Not Found In Catalog");
        problemDetail.setType(URI.create("https://api.example.com/errors/sku-not-found-in-catalog"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle Duplicate Inventory Exception
     *
     * @param ex DuplicateInventoryException
     * @param request WebRequest
     * @return ProblemDetail with 409 status
     */
    @ExceptionHandler(DuplicateInventoryException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleDuplicateInventoryException(
            DuplicateInventoryException ex,
            WebRequest request) {

        log.warn("Duplicate inventory record: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problemDetail.setTitle("Duplicate Inventory Record");
        problemDetail.setType(URI.create("https://api.example.com/errors/duplicate-inventory"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle Insufficient Stock Exception (reserve asked for more than is on hand)
     *
     * @param ex InsufficientStockException
     * @param request WebRequest
     * @return ProblemDetail with 409 status
     */
    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleInsufficientStockException(
            InsufficientStockException ex,
            WebRequest request) {

        log.debug("Not enough stock: {}", LogSanitizer.clean(ex.getMessage())); // the audit event stock_reserve_refused is the WARN record

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problemDetail.setTitle("Not Enough Stock");
        problemDetail.setType(URI.create("https://api.example.com/errors/insufficient-stock"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle Reservation Conflict Exception (the order and SKU were already released, or reserved with another quantity)
     *
     * @param ex ReservationConflictException
     * @param request WebRequest
     * @return ProblemDetail with 409 status
     */
    @ExceptionHandler(ReservationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleReservationConflictException(
            ReservationConflictException ex,
            WebRequest request) {

        log.warn("Reservation conflict: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.getMessage()
        );

        problemDetail.setTitle("Reservation Conflict");
        problemDetail.setType(URI.create("https://api.example.com/errors/reservation-conflict"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle a request parameter that has the wrong type, for example text or a number that is too big
     * for the parameter. The text of the exception is not sent to the client, only the parameter name.
     *
     * @param ex MethodArgumentTypeMismatchException
     * @param request WebRequest
     * @return ProblemDetail with 400 status
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleTypeMismatchException(
            MethodArgumentTypeMismatchException ex,
            WebRequest request) {

        log.warn("Request parameter '{}' has the wrong type", LogSanitizer.clean(ex.getName()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            String.format("The value of '%s' is not valid", ex.getName())
        );

        problemDetail.setTitle("Invalid Request");
        problemDetail.setType(URI.create("https://api.example.com/errors/invalid-request"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle a required request parameter that is missing
     *
     * @param ex MissingServletRequestParameterException
     * @param request WebRequest
     * @return ProblemDetail with 400 status
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleMissingParameterException(
            MissingServletRequestParameterException ex,
            WebRequest request) {

        log.warn("Request parameter '{}' is missing", LogSanitizer.clean(ex.getParameterName()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            String.format("The parameter '%s' is required", ex.getParameterName())
        );

        problemDetail.setTitle("Invalid Request");
        problemDetail.setType(URI.create("https://api.example.com/errors/invalid-request"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle Product Service Unavailable Exception
     *
     * @param ex ProductServiceUnavailableException
     * @param request WebRequest
     * @return ProblemDetail with 503 status
     */
    @ExceptionHandler(ProductServiceUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ProblemDetail handleProductServiceUnavailableException(
            ProductServiceUnavailableException ex,
            WebRequest request) {

        log.warn("Product service unavailable: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            ex.getMessage()
        );

        problemDetail.setTitle("Product Service Unavailable");
        problemDetail.setType(URI.create("https://api.example.com/errors/product-service-unavailable"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle Validation Exceptions
     *
     * @param ex MethodArgumentNotValidException
     * @param request WebRequest
     * @return ProblemDetail with validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleValidationException(
            MethodArgumentNotValidException ex,
            WebRequest request) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        // only the field names: the rejected values can be personal data or secrets
        log.warn("Validation failed for fields: {}", LogSanitizer.clean(String.join(", ", new TreeSet<>(errors.keySet()))));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Validation failed for one or more fields"
        );

        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("https://api.example.com/errors/validation"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));
        problemDetail.setProperty("errors", errors);

        return problemDetail;
    }

    /**
     * Handle Illegal Argument Exception
     * Also used for the "adjustment would make quantity negative" case
     *
     * @param ex IllegalArgumentException
     * @param request WebRequest
     * @return ProblemDetail with 400 status
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleIllegalArgumentException(
            IllegalArgumentException ex,
            WebRequest request) {

        log.warn("Invalid request: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.getMessage()
        );

        problemDetail.setTitle("Invalid Request");
        problemDetail.setType(URI.create("https://api.example.com/errors/invalid-request"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle requests to unmapped URLs
     * Spring's default resource-handler fallback raises this for any path that
     * matches no controller mapping and no static resource - without this handler
     * it falls through to the generic 500 handler below, which is wrong: a
     * nonexistent route is a 404, not a server error.
     *
     * @param ex NoResourceFoundException
     * @param request WebRequest
     * @return ProblemDetail with 404 status
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleNoResourceFoundException(
            NoResourceFoundException ex,
            WebRequest request) {

        log.debug("No resource found: {}", LogSanitizer.clean(ex.getMessage()));

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND,
            "The requested resource was not found"
        );

        problemDetail.setTitle("Resource Not Found");
        problemDetail.setType(URI.create("https://api.example.com/errors/resource-not-found"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }

    /**
     * Handle all other exceptions
     *
     * @param ex Exception
     * @param request WebRequest
     * @return ProblemDetail with 500 status
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail handleGlobalException(
            Exception ex,
            WebRequest request) {

        log.error("Unexpected error: {}", ex.getClass().getName(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred. Please try again later."
        );

        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("https://api.example.com/errors/internal-server-error"));
        problemDetail.setProperty("timestamp", LocalDateTime.now());
        problemDetail.setProperty("path", request.getDescription(false).replace("uri=", ""));

        return problemDetail;
    }
}
