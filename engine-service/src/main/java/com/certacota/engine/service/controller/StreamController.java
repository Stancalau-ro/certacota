package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.StartStreamRequest;
import com.certacota.engine.core.dto.StartStreamResponse;
import com.certacota.engine.core.dto.StopStreamRequest;
import com.certacota.engine.core.dto.StopStreamResponse;
import com.certacota.engine.core.service.StreamingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/streams")
@RequiredArgsConstructor
@Slf4j
public class StreamController {

    private final StreamingService streamingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StartStreamResponse startStream(@Valid @RequestBody StartStreamRequest request) {
        log.info("Starting stream {} for account {}", request.streamId(), request.accountId());
        return streamingService.startStream(request);
    }

    @PostMapping("/{streamId}/stop")
    @ResponseStatus(HttpStatus.OK)
    public StopStreamResponse stopStream(
            @PathVariable String streamId,
            @RequestBody(required = false) StopStreamRequest request) {
        StopStreamRequest effectiveRequest = request != null ? request : new StopStreamRequest(false, "stop endpoint call");
        log.info("Stopping stream {}", streamId);
        return streamingService.stopStream(streamId, effectiveRequest);
    }
}
