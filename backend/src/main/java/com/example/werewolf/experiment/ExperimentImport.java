package com.example.werewolf.experiment;

import com.example.werewolf.experiment.ExperimentRecord.*;
import com.example.werewolf.agent.AgentMetrics;
import com.example.werewolf.game.GameResult;
import com.example.werewolf.game.ObserverNote;
import com.example.werewolf.player.Role;
import com.example.werewolf.knowledge.KnowledgeBase;
import com.example.werewolf.knowledge.KnowledgeBase.*;
import com.example.werewolf.knowledge.KnowledgeService;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** 导入仅解析数据，不创建 Agent 或游戏会话；所有统计在本机重新计算。 */
public final class ExperimentImport {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final long MAX_NUMBER = 1_000_000_000_000L;
    private static final Set<String> AGENTS = Set.of("RANDOM", "RULE", "LLM");
    private ExperimentImport() { }

    public static ExperimentRecord parse(ObjectMapper mapper, byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= MAX_BYTES, "JSON 文件须非空且不超过 25 MB");
        try {
            ObjectMapper reader = mapper.copy();
            reader.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(64).maxStringLength(100_000).maxNumberLength(32).build());
            reader.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            reader.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            reader.configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false);
            reader.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            reader.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = reader.readTree(bytes);
            require(root != null && root.isObject(), "JSON 顶层须为实验对象");
            // 支持导出的 {record, summary} 以及独立 record；忽略外部汇总和内部 owner。
            JsonNode data = root.has("record") ? root.get("record") : root;
            ExperimentRecord record = reader.treeToValue(data, ExperimentRecord.class);
            validate(record);
            return record;
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException && !(e instanceof com.fasterxml.jackson.databind.RuntimeJsonMappingException))
                throw (IllegalArgumentException) e;
            // 不回显原始 JSON，以免解析错误暴露文件内的私有内容。
            throw new IllegalArgumentException("JSON 格式或字段无效，请选择本项目导出的完整 JSON 文件");
        }
    }

    static void validate(ExperimentRecord r) {
        require(r != null && r.schemaVersion() == 1, "不支持的实验版本，目前支持 schemaVersion = 1");
        uuid(r.id()); text(r.name(), 80); text(r.engineVersion(), 100);
        text(r.group(), 60); text(r.notes(), 2000);
        require(Set.of("BATCH", "SINGLE", "IMPORTED").contains(r.source()), "实验来源无效");
        require(Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(r.status()), "实验状态无效");
        instant(r.createdAt()); instant(r.updatedAt());
        require(r.config() != null && r.agentTypes().size() == r.config().playerCount()
                && r.agentTypes().stream().allMatch(AGENTS::contains), "座位 Agent 配置无效");
        seed(r.startSeed());
        require(r.requestedGames() >= 1 && r.requestedGames() <= 200 && r.games().size() <= r.requestedGames(), "实验局数无效");
        if (r.errorCode() != null) text(r.errorCode(), 100);
        Set<String> ids = new HashSet<>();
        long entries = 0;
        for (GameSnapshot g : r.games()) {
            uuid(g.gameId()); require(ids.add(g.gameId()), "同一实验包含重复的对局编号"); seed(g.seed());
            instant(g.startedAt()); if (g.endedAt() != null) {
                instant(g.endedAt());
                require(!Instant.parse(g.endedAt()).isBefore(Instant.parse(g.startedAt())), "结束时间早于开始时间");
            }
            require(Set.of("IN_PROGRESS", "COMPLETED", "FAILED").contains(g.status()) && g.result() != null, "对局状态无效");
            require((g.status().equals("COMPLETED")) == (g.result() != GameResult.ONGOING), "对局结果与完成状态不一致");
            require(g.days() >= 1 && g.days() <= 1000, "对局轮次无效");
            if (g.elapsedMillis() != null) number(g.elapsedMillis());
            require(g.seats().size() == r.config().playerCount(), "玩家人数与实验配置不一致");
            Set<String> players = new HashSet<>();
            for (int i = 0; i < g.seats().size(); i++) {
                Seat seat = g.seats().get(i);
                require(seat != null && ("player" + (i + 1)).equals(seat.playerId()) && seat.role() != null && seat.status() != null
                        && r.agentTypes().get(i).equals(seat.agentType()), "玩家身份或座位无效");
                players.add(seat.playerId());
            }
            require(count(g, Role.WEREWOLF) == r.config().werewolves() && count(g, Role.VILLAGER) == r.config().villagers()
                    && count(g, Role.SEER) == r.config().seers() && count(g, Role.WITCH) == r.config().witches(), "身份数量与配置不一致");
            if (g.model() != null) {
                text(g.model().model(), 300);
                require(Double.isFinite(g.model().temperature()) && g.model().temperature() >= 0 && g.model().temperature() <= 2
                        && g.model().maxTokens() >= 1 && g.model().maxTokens() <= 100_000, "模型比较参数无效");
            }
            entries += g.actions().size() + (long) g.events().size() + (g.observerNotes() == null ? 0 : g.observerNotes().size());
            require(entries <= 200_000, "行动和事件数量过多");
            int sequence = 0;
            for (var a : g.actions()) {
                require(a != null && a.sequence() == ++sequence && a.day() >= 1 && a.day() <= g.days() && a.phase() != null
                        && players.contains(a.playerId()) && Set.of("VALID", "INVALID", "ERROR").contains(a.status()), "行动记录字段无效");
                require(Double.isFinite(a.decisionMillis()) && a.decisionMillis() >= 0 && a.decisionMillis() <= MAX_NUMBER, "行动耗时无效");
                require(!a.status().equals("VALID") || a.action() != null, "合法行动缺少动作类型");
                if (a.targetPlayerId() != null) text(a.targetPlayerId(), 100);
                metrics(a.metrics());
            }
            for (var e : g.events()) {
                require(e != null && e.day() >= 1 && e.day() <= g.days() && e.phase() != null, "回放事件无效");
                text(e.type(), 80); text(e.text(), 100_000);
                if (e.actorId() != null) text(e.actorId(), 100);
                if (e.targetId() != null) text(e.targetId(), 100);
            }
            if (g.observerNotes() != null) validateObserverNotes(g, g.observerNotes());
            if (g.knowledge() != null) {
                validateKnowledge(g);
                entries += g.knowledge().usages().size() + (g.knowledge().snapshot() == null ? 0 : g.knowledge().snapshot().entries().size());
                require(entries <= 200_000, "知识与行动记录数量过多");
            }
        }
        require(!r.status().equals("COMPLETED") || (r.games().size() == r.requestedGames()
                && r.games().stream().allMatch(g -> g.status().equals("COMPLETED"))), "已完成实验缺少完整对局");
    }
    /** 导入知识只用于回放；验证其属于当时行动者，并且与冻结版本和实际阶段相符。 */
    private static void validateKnowledge(GameSnapshot game) {
        Run run = game.knowledge();
        require(run.mode() != null && run.usages().size() <= 20_000, "知识使用记录无效");
        require((run.mode() == Mode.NONE) == (run.snapshot() == null), "知识开关与快照不一致");
        if (run.snapshot() != null) {
            Snapshot snapshot = run.snapshot();
            require(snapshot.schemaVersion() == 1 && snapshot.entries().size() <= KnowledgeBase.MAX_ENTRIES, "知识快照版本或数量无效");
            Set<String> ids = new HashSet<>();
            for (Entry entry : snapshot.entries()) {
                require(entry != null && entry.enabled() && entry.equals(KnowledgeBase.normalize(entry, entry.id()))
                        && ids.add(entry.id()), "知识快照条目无效");
            }
            require(KnowledgeService.snapshot(snapshot.entries()).revision().equals(snapshot.revision()), "知识快照版本校验失败");
        }
        Set<Integer> sequences = new HashSet<>();
        for (Usage usage : run.usages()) {
            require(usage != null && usage.actionSequence() >= 1 && usage.actionSequence() <= game.actions().size()
                    && sequences.add(usage.actionSequence()) && usage.eventIndex() >= 0 && usage.eventIndex() <= game.events().size(),
                    "知识使用记录位置无效");
            var action = game.actions().get(usage.actionSequence() - 1);
            Seat seat = game.seats().stream().filter(s -> s.playerId().equals(usage.playerId())).findFirst().orElse(null);
            require(seat != null && seat.agentType().equals("LLM") && action.playerId().equals(usage.playerId())
                    && action.day() == usage.day() && action.phase() == usage.phase(), "知识使用记录与行动不一致");
            var permitted = KnowledgeBase.forRole(run.snapshot(), run.mode(), seat.role());
            require(KnowledgeBase.select(permitted, usage.phase(), seat.role()).stream().map(Entry::id).toList().equals(usage.entryIds()),
                    "知识使用记录包含不属于当前角色或阶段的条目");
        }
    }
    /** 兼容旧记录回填与 JSON 导入，私有记录只能引用本局玩家和已有时间线。 */
    static void validateObserverNotes(GameSnapshot game, List<ObserverNote> notes) {
        require(notes != null && notes.size() <= 20_000, "观战私有记录数量无效");
        Set<String> players = new HashSet<>(game.seats().stream().map(Seat::playerId).toList());
        for (ObserverNote note : notes) {
            require(note != null && note.day() >= 1 && note.day() <= game.days() && note.phase() != null
                    && note.eventIndex() >= 0 && note.eventIndex() <= game.events().size() && players.contains(note.playerId())
                    && Set.of("CLUE", "DECISION").contains(note.kind()), "观战私有记录字段无效");
            text(note.text(), 1000);
        }
    }
    private static long count(GameSnapshot g, Role role) { return g.seats().stream().filter(s -> s.role() == role).count(); }
    private static void metrics(AgentMetrics m) {
        require(m != null, "缺少行动指标");
        for (long n : new long[]{m.apiCalls(), m.apiFailures(), m.invalidReplies(), m.usageReportedCalls(),
                m.promptTokens(), m.completionTokens(), m.totalTokens(), m.requestMillis()}) number(n);
        require(m.apiCalls() <= 100_000 && m.apiFailures() <= m.apiCalls() && m.usageReportedCalls() <= m.apiCalls()
                && m.invalidReplies() <= m.apiCalls(), "调用指标无效");
    }
    private static void number(long n) { require(n >= 0 && n <= MAX_NUMBER, "指标数值无效"); }
    private static void seed(long n) { require(n >= -9_007_199_254_740_991L && n <= 9_007_199_254_740_991L, "随机种子超出浏览器整数范围"); }
    private static void uuid(String value) { require(value != null && value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "记录编号无效"); }
    private static void text(String s, int max) { require(s != null && s.length() <= max, "文本字段缺失或过长"); }
    private static void instant(String s) {
        require(s != null && s.length() <= 40, "时间字段无效");
        try { Instant.parse(s); } catch (java.time.DateTimeException e) { throw new IllegalArgumentException("时间字段无效"); }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
