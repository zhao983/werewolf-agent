package com.example.werewolf.controller;

import com.example.werewolf.service.GameService;
import com.example.werewolf.service.GameService.*;
import com.example.werewolf.game.AdvanceCommand;
import com.example.werewolf.experiment.ExperimentAccess;
import com.example.werewolf.experiment.ExperimentService;
import com.example.werewolf.experiment.ExperimentMetadata;
import java.io.UncheckedIOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 对局 REST 接口；前端只提交创建参数和推进指令。 */
@RestController
@RequestMapping("/api/games")
public class GameController {
    private static final String OWNER_PREFIX = "game-owner:";
    private final GameService service;
    private final ExperimentService experiments;
    private final ExperimentAccess access;

    public GameController(GameService service, ExperimentService experiments, ExperimentAccess access) {
        this.service = service; this.experiments = experiments; this.access = access;
    }

    @GetMapping
    public List<GameSummary> list(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return List.of();
        return service.list().stream().filter(game -> owns(session, game.gameId())).toList();
    }

    @GetMapping("/{id}")
    public GameView get(@PathVariable String id, HttpServletRequest request) {
        requireOwner(id, request);
        return service.get(id);
    }

    /** 仅创建对局的浏览器会话可读取；响应不缓存，也不作为 Agent 的模型输入。 */
    @GetMapping("/{id}/observer")
    public ObserverView observer(@PathVariable String id, HttpServletRequest request,
                                 HttpServletResponse response) {
        requireOwner(id, request);
        response.setHeader("Cache-Control", "no-store");
        return service.observe(id);
    }

    @PostMapping
    public GameView create(@RequestBody CreateGameRequest request, HttpSession session,
                           HttpServletRequest servletRequest, HttpServletResponse response) {
        String owner = access.getOrCreate(servletRequest, response);
        if (request == null) throw new IllegalArgumentException("缺少创建对局参数");
        var metadata = ExperimentMetadata.normalize(request.experimentName(), request.experimentGroup(), request.experimentNotes(),
                request.seed() == null ? "单局实验" : "单局记录 · " + request.seed());
        GameView game = service.create(request);
        session.setAttribute(OWNER_PREFIX + game.summary().gameId(), Boolean.TRUE);
        var data = service.snapshot(game.summary().gameId());
        experiments.recordGame(owner, data.config(), data.agentTypes(), data.game(), metadata);
        return game;
    }

    /** 一次请求只推进一个玩家行动或一个阶段边界，供手动 AI 对局使用。 */
    @PostMapping("/{id}/advance")
    public GameView advance(@PathVariable String id, @RequestBody AdvanceRequest request,
                            HttpServletRequest servletRequest, HttpServletResponse response) {
        requireOwner(id, servletRequest);
        String owner = access.getOrCreate(servletRequest, response);
        try { return service.advance(id, request.command()); }
        finally { record(owner, id); }
    }

    private void record(String owner, String id) {
        var data = service.snapshot(id);
        experiments.recordGame(owner, data.config(), data.agentTypes(), data.game());
    }

    private boolean owns(HttpSession session, String id) {
        return Boolean.TRUE.equals(session.getAttribute(OWNER_PREFIX + id));
    }

    private void requireOwner(String id, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !owns(session, id))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该对局只对创建它的浏览器会话开放");
    }

    public record AdvanceRequest(AdvanceCommand command) { }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) { return Map.of("error", ex.getMessage()); }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> missing(NoSuchElementException ex) { return Map.of("error", ex.getMessage()); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> modelError(IllegalStateException ex) { return Map.of("error", ex.getMessage()); }

    @ExceptionHandler(UncheckedIOException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> storageError() { return Map.of("error", "对局状态已更新，但实验记录未能保存，请检查磁盘空间后刷新页面"); }
}
