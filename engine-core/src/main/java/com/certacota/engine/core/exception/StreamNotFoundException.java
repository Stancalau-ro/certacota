package com.certacota.engine.core.exception;

public class StreamNotFoundException extends RuntimeException {
    public StreamNotFoundException(String streamId) {
        super("Stream not found: " + streamId);
    }
}
