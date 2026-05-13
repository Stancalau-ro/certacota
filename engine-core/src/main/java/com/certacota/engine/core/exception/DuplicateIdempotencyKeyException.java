package com.certacota.engine.core.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String key) {
        super("Duplicate idempotency key: " + key);
    }
}
