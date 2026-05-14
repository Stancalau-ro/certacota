package com.certacota.engine.spring.event;

import java.util.List;

public record StreamSettledEvent(String streamId, String accountId, List<String> tags) {
}
