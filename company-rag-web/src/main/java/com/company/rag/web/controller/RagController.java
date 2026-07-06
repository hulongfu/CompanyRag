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
 * RAG检索接口
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagSearchService ragSearchService;

    @PostMapping("/search")
    public R<RagResult> search(@RequestBody RagQuery query) {
        return R.ok(ragSearchService.search(query));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody RagQuery query) {
        query.setStream(true);
        return ragSearchService.streamAnswer(query);
    }

    @PostMapping("/retrieve")
    public R<?> retrieve(@RequestBody RagQuery query) {
        return R.ok(ragSearchService.retrieve(query));
    }
}
