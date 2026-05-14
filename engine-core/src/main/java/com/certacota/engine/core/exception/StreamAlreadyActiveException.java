package com.certacota.engine.core.exception;

public class StreamAlreadyActiveException extends RuntimeException {
    public StreamAlreadyActiveException(String streamId) {
        super("Stream already active: " + streamId);
    }
}
