package com.iiot.auth;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return error(e.getStatusCode().value(), e.getReason());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> duplicate() {
        return error(409, "Username or email is already registered");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() {
        return error(400, "Invalid request");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied() {
        return error(403, "Access denied");
    }

    private ResponseEntity<?> error(int status, String message) {
        var builder = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == 401) builder.header("WWW-Authenticate", "Bearer");
        return builder.body(Map.of("status", status, "message", message == null ? "Request failed" : message));
    }
}
