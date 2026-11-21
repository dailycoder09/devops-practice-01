package com.droplink.web;

import com.droplink.service.ApiException;
import com.droplink.service.ClientInfo;
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
        record(e.status(), e.getMessage(), request);
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
            String message = status == HttpStatus.NOT_FOUND ? "This page does not exist." : "The request is not valid.";
            record(status, message, request);
            return answer(status, message, request);
        }
        log.error("Unexpected error on {} {}", request.getMethod(), LogSafe.path(request.getRequestURI()), e);
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.", request);
    }

    /** One line for every refused request. Wrong keys, wrong or locked passwords and forbidden access are WARN; the rest INFO. */
    private static void record(HttpStatus status, String message, HttpServletRequest request) {
        boolean security = status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN || status == HttpStatus.TOO_MANY_REQUESTS;
        String line = "Refused {} {} -> {} \"{}\" from {}";
        Object[] values = {request.getMethod(), LogSafe.path(request.getRequestURI()), status.value(), message,
                ClientInfo.maskIp(request.getRemoteAddr())};
        if (security) {
            log.warn(line, values);
        } else {
            log.info(line, values);
        }
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
