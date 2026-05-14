package com.certacota.engine.service.controller;

import com.certacota.engine.core.dto.EndByTagResponse;
import com.certacota.engine.core.dto.TagAggregateResponse;
import com.certacota.engine.core.service.TagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
@Slf4j
public class TagController {

    private final TagService tagService;

    @GetMapping("/{tag}/aggregate")
    @ResponseStatus(HttpStatus.OK)
    public TagAggregateResponse aggregate(@PathVariable String tag) {
        log.info("Tag aggregate request for tag={}", tag);
        return tagService.aggregate(tag);
    }

    @PostMapping("/{tag}/end")
    @ResponseStatus(HttpStatus.OK)
    public EndByTagResponse endByTag(
            @PathVariable String tag,
            @Valid @RequestBody EndByTagRequest request) {
        log.info("End-by-tag request for tag={}, idempotencyKey={}", tag, request.idempotencyKey());
        return tagService.endByTag(tag, request.idempotencyKey(), request.reason());
    }

    public record EndByTagRequest(
        @NotBlank String idempotencyKey,
        String reason
    ) { }
}
