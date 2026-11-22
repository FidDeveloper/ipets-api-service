package com.ipets;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Expected client error with a stable machine code; never carries secrets or stack traces. */
public class ApiError extends RuntimeException {
    final HttpStatus status;
    final String code;

    public ApiError(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(ApiError.class)
        ResponseEntity<Map<String, String>> api(ApiError e) {
            return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage()));
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<Map<String, String>> unreadable() {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_body", "message", "The request body is not valid JSON."));
        }
    }
}
