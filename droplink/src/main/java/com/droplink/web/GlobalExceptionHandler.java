package com.droplink.web;

import com.droplink.service.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/** Refusals become a clear JSON answer for the API and a friendly page for people. Unexpected errors show nothing internal. */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public Object handleApi(ApiException e, HttpServletRequest request) {
        return answer(e.status(), e.getMessage(), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleUnreadable(HttpServletRequest request) {
        return answer(HttpStatus.BAD_REQUEST, "The request could not be read.", request);
    }

    @ExceptionHandler(Exception.class)
    public Object handleOther(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse spring) {      // unknown address, wrong method, missing header: the status Spring chose
            HttpStatus status = HttpStatus.valueOf(spring.getStatusCode().value());
            return answer(status, status == HttpStatus.NOT_FOUND ? "This page does not exist." : "The request is not valid.", request);
        }
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI().replaceAll("[0-9A-Za-z]{12}", "<id>"), e);
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.", request);
    }

    private static Object answer(HttpStatus status, String message, HttpServletRequest request) {
        String path = request.getRequestURI();
        String accept = request.getHeader("Accept");
        if (path.startsWith("/api/") || path.startsWith("/local-store/") || (accept != null && accept.contains("application/json"))) {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
        }
        ModelAndView page = new ModelAndView("error");
        page.setStatus(status);
        page.addObject("status", status.value());
        page.addObject("title", switch (status) {
            case NOT_FOUND -> "Link not found";
            case GONE -> "This link is no longer available";
            case FORBIDDEN -> "Not allowed";
            case TOO_MANY_REQUESTS -> "Please wait";
            default -> "Something went wrong";
        });
        page.addObject("message", message);
        return page;
    }
}
