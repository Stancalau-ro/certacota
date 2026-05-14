package com.certacota.engine.core.service;

import com.certacota.engine.core.domain.StreamState;

import java.util.List;
import java.util.Optional;

public interface StreamRegistry {

    void register(StreamState state);

    Optional<StreamState> get(String streamId);

    void remove(String streamId, String accountId, List<String> tags);

    List<StreamState> getActiveStreams(String accountId);

    boolean hasActiveStreams(String accountId);

    List<StreamState> getStreamsByTag(String tag);
}
