package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.EstimatedBalanceResponse;
import com.certacota.engine.core.dto.StartStreamRequest;
import com.certacota.engine.core.dto.StartStreamResponse;
import com.certacota.engine.core.dto.StopStreamRequest;
import com.certacota.engine.core.dto.StopStreamResponse;

public interface StreamingService {

    StartStreamResponse startStream(StartStreamRequest request);

    StopStreamResponse stopStream(String streamId, StopStreamRequest request);

    EstimatedBalanceResponse estimateBalance(String accountId);

    void autoTerminate(String streamId);
}
