package com.certacota.engine.spring.event;

public record StreamSettledEvent(String streamId, String accountId) {
}
