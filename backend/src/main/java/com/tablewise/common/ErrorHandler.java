package com.tablewise.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ErrorHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        log.warn("request refused: {} {}", e.status.value(), e.getMessage());
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders h, HttpStatusCode s, WebRequest r) {
        var f = e.getBindingResult().getFieldError();
        String msg = f == null ? "Invalid request" : f.getField() + " " + f.getDefaultMessage();
        log.warn("request refused: 400 {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    // Re-thrown so Spring Security answers 403 instead of the generic 500 below.
    @ExceptionHandler(AccessDeniedException.class)
    void denied(AccessDeniedException e) { throw e; }

    /** Anything unexpected: full stack trace in the log, nothing internal in the response. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(500).body(Map.of("error", "Something went wrong. Please try again."));
    }
}
