package com.example.werewolf.controller;

import com.example.werewolf.experiment.*;
import com.example.werewolf.experiment.ExperimentRecord.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 创建、轮询和导出实验；仅返回当前浏览器的记录，身份数据不缓存。 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {
    private final ExperimentService service;
    private final ExperimentAccess access;
    private final ObjectMapper mapper;
    public ExperimentController(ExperimentService service, ExperimentAccess access, ObjectMapper mapper) {
        this.service = service; this.access = access; this.mapper = mapper;
    }
    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping
    public List<ListItem> list(HttpServletRequest request) { return service.list(access.owner(request)); }
    @GetMapping("/storage")
    public ExperimentService.StorageInfo storage(HttpServletRequest request) { return service.storageInfo(access.owner(request)); }
    @GetMapping("/games")
    public List<ExperimentService.ArchiveItem> archives(HttpServletRequest request) { return service.archives(access.owner(request)); }
    @GetMapping("/{id}/games/{gameId}")
    public GameSnapshot replay(@PathVariable String id, @PathVariable String gameId, HttpServletRequest request) {
        return service.replay(access.owner(request), id, gameId);
    }
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public View importRecord(@RequestParam("file") MultipartFile file, HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        // 上传文件名从不参与磁盘路径；仅保存解析和校验后的记录。
        if (file.isEmpty() || file.getSize() > ExperimentImport.MAX_BYTES)
            throw new IllegalArgumentException("JSON 文件须非空且不超过 25 MB");
        return service.importRecord(access.getOrCreate(request, response), file.getBytes());
    }
    @PostMapping("/{id}/games/{gameId}/observer-notes")
    public GameSnapshot backfillObserver(@PathVariable String id, @PathVariable String gameId,
                                         @RequestBody List<com.example.werewolf.game.ObserverNote> notes,
                                         HttpServletRequest request) {
        return service.backfillObserverNotes(access.owner(request), id, gameId, notes);
    }
    @PostMapping
    public View start(@RequestBody ExperimentService.BatchRequest body, HttpServletRequest request,
                       HttpServletResponse response) { return service.start(access.getOrCreate(request, response), body); }
    @GetMapping("/{id}")
    public View get(@PathVariable String id, HttpServletRequest request) { return service.get(access.owner(request), id); }
    @PostMapping("/{id}/cancel")
    public View cancel(@PathVariable String id, HttpServletRequest request) { return service.cancel(access.owner(request), id); }
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "json") String format,
                                          HttpServletRequest request) throws JsonProcessingException {
        View view = service.get(access.owner(request), id);
        byte[] content;
        String extension;
        MediaType media;
        switch (format) {
            case "json" -> { content = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(view); extension = ".json"; media = MediaType.APPLICATION_JSON; }
            case "csv", "actions" -> {
                content = (format.equals("csv") ? ExperimentCsv.games(view.record()) : ExperimentCsv.actions(view.record()))
                        .getBytes(StandardCharsets.UTF_8);
                extension = format.equals("actions") ? "-actions.csv" : ".csv";
                media = new MediaType("text", "csv", StandardCharsets.UTF_8);
            }
            default -> throw new IllegalArgumentException("导出格式只能是 json、csv 或 actions");
        }
        return ResponseEntity.ok().contentType(media).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"experiment-" + view.record().id() + extension + "\"")
                .body(content);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalid(IllegalArgumentException e) { return Map.of("error", e.getMessage()); }
    @ExceptionHandler(UncheckedIOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> storageError() { return Map.of("error", "无法保存实验记录，请检查数据目录权限和磁盘空间"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, String> tooLarge() { return Map.of("error", "JSON 文件不能超过 25 MB"); }
}
