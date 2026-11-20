package com.law.backend.controller;

import com.law.backend.contract.ContractScanService;
import com.law.backend.util.Blocking;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tools/contract-scans")
public class ContractScanController {
    private final ContractScanService scans;

    public ContractScanController(ContractScanService scans) { this.scans = scans; }

    @GetMapping("/policy")
    public ResponseEntity<ContractScanService.Policy> policy() { return privateResponse().body(scans.policy()); }

    @GetMapping("/jobs")
    public Mono<ResponseEntity<List<ContractScanService.Summary>>> list(@AuthenticationPrincipal Long owner) {
        return response(() -> scans.list(owner));
    }

    @PostMapping("/jobs")
    public Mono<ResponseEntity<ContractScanService.Detail>> create(@AuthenticationPrincipal Long owner,
                                                                    @RequestBody ContractScanService.CreateRequest request) {
        return response(() -> scans.create(owner, request));
    }

    @GetMapping("/jobs/{id}")
    public Mono<ResponseEntity<ContractScanService.Detail>> detail(@AuthenticationPrincipal Long owner, @PathVariable String id) {
        return response(() -> scans.detail(owner, id));
    }

    @PutMapping(value = "/jobs/{id}/uploads/{asset}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<ResponseEntity<ContractScanService.Offset>> upload(@AuthenticationPrincipal Long owner, @PathVariable String id,
            @PathVariable String asset, @RequestParam long offset, ServerHttpRequest request) {
        return Mono.defer(() -> {
            if (!scans.beginUpload()) return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
            return DataBufferUtils.join(request.getBody(), ContractScanService.CHUNK_BYTES)
                    .map(buffer -> {
                        try {
                            var bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            return bytes;
                        } finally { DataBufferUtils.release(buffer); }
                    })
                    .defaultIfEmpty(new byte[0])
                    .flatMap(bytes -> response(() -> scans.upload(owner, id, asset, offset, bytes)))
                    .doFinally(signal -> scans.endUpload());
        });
    }

    @PostMapping("/jobs/{id}/start")
    public Mono<ResponseEntity<ContractScanService.Detail>> start(@AuthenticationPrincipal Long owner, @PathVariable String id,
                                                                   @RequestBody ContractScanService.Consent consent) {
        return response(() -> scans.start(owner, id, consent));
    }

    @PostMapping("/jobs/{id}/pages/{page}/replacement")
    public Mono<ResponseEntity<ContractScanService.Detail>> replacement(@AuthenticationPrincipal Long owner, @PathVariable String id,
            @PathVariable int page, @RequestBody ContractScanService.Replacement replacement) {
        return response(() -> scans.prepareReplacement(owner, id, page, replacement));
    }

    @PostMapping("/jobs/{id}/pages/{page}/retry")
    public Mono<ResponseEntity<ContractScanService.Detail>> retry(@AuthenticationPrincipal Long owner, @PathVariable String id,
            @PathVariable int page, @RequestBody ContractScanService.Consent consent) {
        return response(() -> scans.retry(owner, id, page, consent));
    }

    @DeleteMapping("/jobs/{id}")
    public Mono<ResponseEntity<Void>> delete(@AuthenticationPrincipal Long owner, @PathVariable String id) {
        return Blocking.call(() -> { scans.delete(owner, id); return ResponseEntity.noContent().build(); });
    }

    @GetMapping("/jobs/{id}/pages/{page}/preview")
    public Mono<ResponseEntity<Flux<DataBuffer>>> preview(@AuthenticationPrincipal Long owner, @PathVariable String id, @PathVariable int page) {
        return Blocking.call(() -> scans.preview(owner, id, page)).map(this::binary);
    }

    @GetMapping("/jobs/{id}/files/{asset}")
    public Mono<ResponseEntity<Flux<DataBuffer>>> file(@AuthenticationPrincipal Long owner, @PathVariable String id, @PathVariable String asset) {
        return Blocking.call(() -> scans.download(owner, id, asset)).map(this::binary);
    }

    private ResponseEntity<Flux<DataBuffer>> binary(ContractScanService.Binary file) {
        var factory = DefaultDataBufferFactory.sharedInstance;
        var response = privateResponse().contentType(MediaType.parseMediaType(file.mime())).contentLength(file.size());
        if (file.name() != null) response.header("Content-Disposition", ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString());
        Flux<DataBuffer> body = file.path() != null ? DataBufferUtils.read(file.path(), factory, 64 * 1024).subscribeOn(Schedulers.boundedElastic())
                : Flux.just(factory.wrap(file.bytes()));
        return response.body(body);
    }

    @ExceptionHandler(ContractScanService.Problem.class)
    public ResponseEntity<Map<String, String>> problem(ContractScanService.Problem error) { return error(error.status(), error.getMessage()); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ignored) { return error(HttpStatus.BAD_REQUEST, "文件无效或超出处理范围"); }

    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<Map<String, String>> tooLarge(DataBufferLimitException ignored) { return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传分片大小超限"); }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unavailable(IOException ignored) { return error(HttpStatus.SERVICE_UNAVAILABLE, "合同文件暂不可用，请稍后重试"); }

    private <T> Mono<ResponseEntity<T>> response(java.util.concurrent.Callable<T> action) {
        return Blocking.call(action).map(value -> privateResponse().body(value));
    }

    private static ResponseEntity.BodyBuilder privateResponse() {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store").body(Map.of("message", message));
    }
}
