package com.example.werewolf.experiment;

import com.example.werewolf.agent.Agent;
import com.example.werewolf.agent.RandomAgent;
import com.example.werewolf.agent.RuleAgent;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.game.GameEngine;
import com.example.werewolf.game.GameResult;
import com.example.werewolf.experiment.ExperimentRecord.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 实验记录与后台批量运行。复用纯 Java 引擎，JSON 文件代替本阶段尚不需要的数据库。 */
@Service
public class ExperimentService {
    private static final Logger LOG = LoggerFactory.getLogger(ExperimentService.class);
    private static final long MAX_SAFE_SEED = 9_007_199_254_740_991L;
    private final Path directory;
    private final ObjectMapper mapper;
    private final Map<String, OwnedRun> records = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "experiment-runner");
        thread.setDaemon(true);
        return thread;
    });

    public ExperimentService(ObjectMapper mapper,
                              @Value("${werewolf.experiments.directory:}") String directory) {
        this.mapper = mapper;
        this.directory = ExperimentStorage.resolve(directory);
        load();
    }

    /** 持久化格式包含访问凭据，API 和导出只返回 record，不能序列化整个 StoredRun。 */
    public record StoredRun(String owner, ExperimentRecord record) { }
    private static class OwnedRun {
        final String owner;
        final AtomicBoolean cancelled = new AtomicBoolean();
        ExperimentRecord record;
        OwnedRun(String owner, ExperimentRecord record) { this.owner = owner; this.record = record; }
    }
    public record BatchRequest(String name, GameConfig config, List<String> agentTypes, long startSeed, int runs,
                               String group, String notes) {
        public BatchRequest(String name, GameConfig config, List<String> agentTypes, long startSeed, int runs) {
            this(name, config, agentTypes, startSeed, runs, "", "");
        }
    }

    public synchronized View start(String owner, BatchRequest request) {
        if (request == null) throw new IllegalArgumentException("缺少实验配置");
        if (request.runs() < 1 || request.runs() > 200)
            throw new IllegalArgumentException("每批实验须为 1 到 200 局");
        GameConfig config = request.config() == null ? GameConfig.classicSeven() : request.config();
        List<String> types = request.agentTypes() == null || request.agentTypes().isEmpty()
                ? Collections.nCopies(config.playerCount(), "RANDOM") : request.agentTypes();
        if (types.size() != config.playerCount() || types.stream().anyMatch(t -> t == null || !Set.of("RANDOM", "RULE").contains(t)))
            throw new IllegalArgumentException("批量实验仅支持 RANDOM / RULE，且每个座位必须有一个 Agent");
        types = List.copyOf(types);
        long lastSeed;
        try { lastSeed = Math.addExact(request.startSeed(), request.runs() - 1L); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("随机种子范围溢出"); }
        if (request.startSeed() < -MAX_SAFE_SEED || lastSeed > MAX_SAFE_SEED)
            throw new IllegalArgumentException("种子须在浏览器可精确表示的整数范围内");
        var metadata = ExperimentMetadata.normalize(request.name(), request.group(), request.notes(), "基线实验");
        // 限制单个后台批次，避免多次点击或多浏览器同时创建无界任务队列。
        if (records.values().stream().anyMatch(r -> r.record.source().equals("BATCH") && r.record.status().equals("RUNNING")))
            throw new IllegalArgumentException("已有批量实验正在运行，请等待完成或停止后再试");
        String now = Instant.now().toString();
        ExperimentRecord record = new ExperimentRecord(1, ExperimentRecord.ENGINE_VERSION,
                UUID.randomUUID().toString(), metadata.name(), "BATCH", "RUNNING", now, now,
                config, types, request.startSeed(), request.runs(), List.of(), null, metadata.group(), metadata.notes());
        OwnedRun run = new OwnedRun(owner, record);
        save(run);
        records.put(record.id(), run);
        worker.execute(() -> execute(run));
        return record.view();
    }

    private void execute(OwnedRun run) {
        ExperimentRecord initial = run.record;
        for (int index = 0; index < initial.requestedGames(); index++) {
            if (Thread.currentThread().isInterrupted()) { finish(run, "INTERRUPTED", null); return; }
            if (run.cancelled.get()) { finish(run, "CANCELLED", null); return; }
            long seed = initial.startSeed() + index;
            Random random = new Random(seed);
            List<Agent> agents = initial.agentTypes().stream().map(t ->
                    t.equals("RULE") ? (Agent) new RuleAgent() : new RandomAgent(random)).toList();
            GameEngine.GameSession session = new GameEngine(random, ignored -> { }).newSession(initial.config(), agents);
            String startedAt = Instant.now().toString();
            boolean failed = false;
            try {
                while (session.getState().getResult() == GameResult.ONGOING)
                    session.advance(session.getAvailableCommand());
            } catch (RuntimeException e) {
                failed = true;
                LOG.warn("批量实验规则运行失败：{}", e.getClass().getSimpleName());
            }
            try {
                append(run, GameSnapshot.capture(session.getState(), seed, initial.agentTypes(), startedAt, null, failed));
            } catch (UncheckedIOException e) {
                // 磁盘异常不能伪装成成功；内存视图仍能显示错误，已写入的对局不会丢失。
                synchronized (this) { run.record = copy(run.record, "FAILED", run.record.games(), "STORAGE_FAILED"); }
                LOG.error("实验记录写入失败：{}", e.getClass().getSimpleName());
                return;
            }
            if (failed) { finish(run, "FAILED", "GAME_FAILED"); return; }
        }
        finish(run, "COMPLETED", null);
    }

    private synchronized void append(OwnedRun run, GameSnapshot game) {
        List<GameSnapshot> games = new ArrayList<>(run.record.games());
        games.add(game);
        run.record = copy(run.record, "RUNNING", games, null);
        save(run);
    }
    private synchronized void finish(OwnedRun run, String status, String error) {
        run.record = copy(run.record, status, run.record.games(), error);
        try { save(run); }
        catch (UncheckedIOException e) {
            run.record = copy(run.record, "FAILED", run.record.games(), "STORAGE_FAILED");
            LOG.error("实验结束状态写入失败：{}", e.getClass().getSimpleName());
        }
    }

    /** 普通对局每次用户推进后更新，模型失败尝试也会留在记录中。 */
    public synchronized void recordGame(String owner, GameConfig config, List<String> types, GameSnapshot game) {
        recordGame(owner, config, types, game, null);
    }
    public synchronized void recordGame(String owner, GameConfig config, List<String> types, GameSnapshot game,
                                         ExperimentMetadata initialMetadata) {
        OwnedRun run = records.get(game.gameId());
        if (run != null && !run.owner.equals(owner)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String now = Instant.now().toString();
        String status = game.status().equals("COMPLETED") ? "COMPLETED" : "RUNNING";
        var metadata = run == null ? (initialMetadata == null ? ExperimentMetadata.normalize(null, null, null,
                "单局记录 · " + game.seed()) : ExperimentMetadata.normalize(initialMetadata.name(), initialMetadata.group(),
                initialMetadata.notes(), "单局记录 · " + game.seed()))
                : new ExperimentMetadata(run.record.name(), run.record.group(), run.record.notes());
        ExperimentRecord record = new ExperimentRecord(1, ExperimentRecord.ENGINE_VERSION, game.gameId(),
                metadata.name(), "SINGLE", status, game.startedAt(), now,
                config, types, game.seed(), 1, List.of(game), null, metadata.group(), metadata.notes());
        run = new OwnedRun(owner, record);
        save(run);
        records.put(record.id(), run);
    }

    public synchronized List<ListItem> list(String owner) {
        if (owner == null) return List.of();
        return records.values().stream().filter(r -> r.owner.equals(owner)).map(r -> r.record.listItem())
                .sorted(Comparator.comparing(ListItem::createdAt).reversed()).toList();
    }
    public synchronized View get(String owner, String id) { return owned(owner, id).record.view(); }

    /** 使用新编号和本机访问凭据保存副本，外部文件不能覆盖已有记录或继承授权。 */
    public synchronized View importRecord(String owner, byte[] bytes) {
        ExperimentRecord original = ExperimentImport.parse(mapper, bytes);
        String now = Instant.now().toString();
        ExperimentRecord record = new ExperimentRecord(original.schemaVersion(), original.engineVersion(),
                UUID.randomUUID().toString(), original.name(), "IMPORTED",
                original.status().equals("RUNNING") ? "INTERRUPTED" : original.status(),
                original.createdAt(), now, original.config(), original.agentTypes(), original.startSeed(),
                original.requestedGames(), original.games(), original.errorCode(), original.group(), original.notes());
        OwnedRun run = new OwnedRun(owner, record);
        save(run);
        records.put(record.id(), run);
        return record.view();
    }

    public record StorageInfo(String directory, int visibleRecords) { }
    public synchronized StorageInfo storageInfo(String owner) { return new StorageInfo(directory.toString(), list(owner).size()); }
    public record ArchiveItem(String experimentId, String experimentName, String source, String experimentStatus,
                              String gameId, String status, GameResult result, int days, int playerCount, String startedAt) { }
    /** 历史入口读取持久化快照；对局编号保留原值，导入副本由实验编号区分。 */
    public synchronized List<ArchiveItem> archives(String owner) {
        if (owner == null) return List.of();
        return records.values().stream().filter(r -> r.owner.equals(owner)).flatMap(run -> run.record.games().stream()
                .map(g -> new ArchiveItem(run.record.id(), run.record.name(), run.record.source(), run.record.status(),
                        g.gameId(), g.status(), g.result(), g.days(), g.seats().size(), g.startedAt())))
                .sorted(Comparator.comparing(ArchiveItem::startedAt).reversed()).toList();
    }
    public synchronized GameSnapshot replay(String owner, String id, String gameId) {
        return owned(owner, id).record.games().stream().filter(g -> g.gameId().equals(gameId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "存档中没有这场对局"));
    }

    /** 旧版观战数据由原浏览器读取后回填；仅填补缺失字段，不覆盖新存档或导入副本。 */
    public synchronized GameSnapshot backfillObserverNotes(String owner, String id, String gameId,
                                                           List<com.example.werewolf.game.ObserverNote> notes) {
        OwnedRun run = owned(owner, id);
        if (!run.record.source().equals("SINGLE") || !id.equals(gameId))
            throw new IllegalArgumentException("只允许回填原始普通对局的旧观战记录");
        GameSnapshot original = replay(owner, id, gameId);
        if (original.observerNotes() != null) return original;
        ExperimentImport.validateObserverNotes(original, notes);
        GameSnapshot restored = original.withObserverNotes(notes);
        ExperimentRecord replacement = copy(run.record, run.record.status(), List.of(restored), run.record.errorCode());
        // 先成功落盘再更新内存，失败时保留旧记录，浏览器仍可重试。
        save(new OwnedRun(run.owner, replacement));
        run.record = replacement;
        return restored;
    }
    public synchronized View cancel(String owner, String id) {
        OwnedRun run = owned(owner, id);
        if (!run.record.source().equals("BATCH") || !run.record.status().equals("RUNNING"))
            throw new IllegalArgumentException("只有运行中的批量实验可以停止");
        // 在当前局结束后停止，已完成的局仍可导出，不计入尚未运行的种子。
        run.cancelled.set(true);
        return run.record.view();
    }
    private OwnedRun owned(String owner, String id) {
        OwnedRun run = records.get(id);
        if (owner == null || run == null || !run.owner.equals(owner))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该实验只对创建它的浏览器开放");
        return run;
    }
    private ExperimentRecord copy(ExperimentRecord r, String status, List<GameSnapshot> games, String error) {
        return new ExperimentRecord(r.schemaVersion(), r.engineVersion(), r.id(), r.name(), r.source(), status,
                r.createdAt(), Instant.now().toString(), r.config(), r.agentTypes(), r.startSeed(), r.requestedGames(), games, error, r.group(), r.notes());
    }

    /** 整理历史记录也可编辑；版本校验避免覆盖另一页面或正在推进的新记录。 */
    public synchronized View updateMetadata(String owner, String id, ExperimentMetadata metadata, String expectedRevision) {
        OwnedRun run = owned(owner, id);
        if (metadata == null) throw new IllegalArgumentException("缺少实验标签");
        if (!ExperimentMetadata.revision(run.record.name(), run.record.group(), run.record.notes()).equals(expectedRevision))
            throw new IllegalArgumentException("实验标签已在其他页面修改，请刷新后重新编辑");
        var value = ExperimentMetadata.normalize(metadata.name(), metadata.group(), metadata.notes(), run.record.name());
        var r = run.record;
        var next = new ExperimentRecord(r.schemaVersion(), r.engineVersion(), r.id(), value.name(), r.source(), r.status(),
                r.createdAt(), Instant.now().toString(), r.config(), r.agentTypes(), r.startSeed(), r.requestedGames(), r.games(), r.errorCode(), value.group(), value.notes());
        save(new OwnedRun(owner, next)); run.record = next;
        return next.view();
    }
    private List<ExperimentRecord> visibleRecords(String owner) {
        return owner == null ? List.of() : records.values().stream().filter(r -> r.owner.equals(owner)).map(r -> r.record).toList();
    }
    public synchronized ExperimentAnalysis.Options analysisOptions(String owner) {
        return ExperimentAnalysis.options(visibleRecords(owner));
    }
    public synchronized ExperimentAnalysis.Comparison compare(String owner, ExperimentAnalysis.Request request) {
        return ExperimentAnalysis.compare(visibleRecords(owner), request);
    }

    private void load() {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted().toList()) {
                    try {
                        StoredRun stored = mapper.readValue(path.toFile(), StoredRun.class);
                        ExperimentRecord record = stored.record();
                        if (stored.owner() == null || !stored.owner().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                                || !path.getFileName().toString().equals(record.id() + ".json") || record.schemaVersion() != 1)
                            throw new IllegalArgumentException("不兼容的记录格式");
                        OwnedRun run = new OwnedRun(stored.owner(), record);
                        if (record.status().equals("RUNNING")) {
                            run.record = copy(record, "INTERRUPTED", record.games(), "BACKEND_RESTARTED");
                            try { save(run); }
                            catch (UncheckedIOException e) {
                                // 只读磁盘也应允许查看已有存档，不能因为更新状态失败而隐藏记录。
                                LOG.warn("无法更新实验中断状态，已有记录仍可读取：{}", path.getFileName());
                            }
                        }
                        records.put(record.id(), run);
                    } catch (IOException | RuntimeException e) {
                        // 单个损坏文件不阻止其他结果恢复，日志不输出文件内容或访问凭据。
                        LOG.warn("跳过无法读取的实验文件 {}", path.getFileName());
                    }
                }
            }
        } catch (IOException e) { throw new UncheckedIOException("无法创建或读取实验记录目录", e); }
    }

    private void save(OwnedRun run) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "experiment-", ".tmp");
            mapper.writeValue(temporary.toFile(), new StoredRun(run.owner, run.record));
            Path target = directory.resolve(run.record.id() + ".json");
            // 同目录原子替换，防止写入中断留下半个 JSON；不支持原子移动时退回普通替换。
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException("无法保存实验记录", e); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
