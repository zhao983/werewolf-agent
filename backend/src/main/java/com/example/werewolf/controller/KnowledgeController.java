package com.example.werewolf.controller;

import com.example.werewolf.knowledge.KnowledgeBase.*;
import com.example.werewolf.knowledge.KnowledgeBase;
import com.example.werewolf.knowledge.KnowledgeService;
import java.io.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 本机共享知识库的用户管理接口；不向 Agent 暴露整个知识库接口响应。 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    public KnowledgeController(KnowledgeService service) { this.service = service; }
    public record View(Snapshot snapshot, String directory) { }
    @GetMapping
    public ResponseEntity<View> get() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new View(service.get(), service.directory())); }
    // 自定义请求头无法由第三方网页的简单表单发送，避免跨站写入本地共享策略。
    private void requireHeader(String header) {
        if (!"1".equals(header)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请通过本地知识库页面操作");
    }
    @PostMapping
    public Snapshot add(@RequestBody Entry entry, @RequestHeader(value = "X-Werewolf-Knowledge", required = false) String header) {
        requireHeader(header); return service.add(entry);
    }
    @PutMapping("/{id}")
    public Snapshot update(@PathVariable String id, @RequestParam String revision, @RequestBody Entry entry,
                           @RequestHeader(value = "X-Werewolf-Knowledge", required = false) String header) {
        requireHeader(header); return service.update(id, entry, revision);
    }
    @DeleteMapping("/{id}")
    public Snapshot delete(@PathVariable String id, @RequestParam String revision,
                           @RequestHeader(value = "X-Werewolf-Knowledge", required = false) String header) {
        requireHeader(header); return service.delete(id, revision);
    }
    @GetMapping("/export")
    public ResponseEntity<Snapshot> export() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=werewolf-knowledge.json").body(service.get());
    }
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Snapshot importFile(@RequestParam MultipartFile file,
                               @RequestHeader(value = "X-Werewolf-Knowledge", required = false) String header) throws IOException {
        requireHeader(header);
        KnowledgeBase.require(file.getSize() > 0 && file.getSize() <= KnowledgeBase.MAX_BYTES, "知识库 JSON 须非空且不超过 512 KB");
        return service.importFile(file.getBytes());
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }
    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(NoSuchElementException e) { return Map.of("error", e.getMessage()); }
    @ExceptionHandler({UncheckedIOException.class, IOException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> storage() { return Map.of("error", "本地知识库读写失败，请检查目录权限和磁盘空间"); }
}
