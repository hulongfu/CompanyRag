package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * @deprecated 使用 {@link ChatController#ragSearch(RagQuery, Long)} 替代
 */
@Deprecated
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagSearchService ragSearchService;

    @PostMapping("/search")
    public R<RagResult> search(@RequestBody RagQuery query,
                                @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        query.setUserId(userId);
        return R.ok(ragSearchService.search(query));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody RagQuery query,
                                @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        query.setUserId(userId);
        query.setStream(true);
        return ragSearchService.streamAnswer(query);
    }

    @PostMapping("/retrieve")
    public R<?> retrieve(@RequestBody RagQuery query,
                          @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        query.setUserId(userId);
        return R.ok(ragSearchService.retrieve(query));
    }
}
