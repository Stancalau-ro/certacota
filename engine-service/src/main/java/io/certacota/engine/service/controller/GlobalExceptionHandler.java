package io.certacota.engine.service.controller;

import io.certacota.engine.core.exception.AccountClosedException;
import io.certacota.engine.core.exception.AccountNotFoundException;
import io.certacota.engine.core.exception.BalanceFloorViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(AccountNotFoundException ex) {
        log.warn("Account not found: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(BalanceFloorViolationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleFloorViolation(BalanceFloorViolationException ex) {
        log.warn("Balance floor violation: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(AccountClosedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleClosed(AccountClosedException ex) {
        log.warn("Account closed: {}", ex.getMessage());
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        return Map.of("error", "Validation failed: " + ex.getBindingResult().getAllErrors().get(0).getDefaultMessage());
    }
}
