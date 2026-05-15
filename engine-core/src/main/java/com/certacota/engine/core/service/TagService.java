package com.certacota.engine.core.service;

import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import org.jspecify.annotations.Nullable;

public interface TagService {

    TagAggregateResponse aggregate(String tag);

    EndByTagResponse endByTag(String tag, String idempotencyKey, @Nullable String reason);
}
