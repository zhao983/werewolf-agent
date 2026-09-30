package com.example.werewolf.experiment;

import com.example.werewolf.ai.LlmConfig;
import com.example.werewolf.game.*;
import com.example.werewolf.player.PlayerStatus;
import com.example.werewolf.player.Role;
import java.time.Instant;
import java.util.List;
import com.example.werewolf.knowledge.KnowledgeBase.Run;

/** 可持久化的实验数据契约。所有身份与行动只供创建实验的用户分析。 */
public record ExperimentRecord(int schemaVersion, String engineVersion, String id, String name,
                               String source, String status, String createdAt, String updatedAt,
                               GameConfig config, List<String> agentTypes, long startSeed,
                               int requestedGames, List<GameSnapshot> games, String errorCode) {
    public static final String ENGINE_VERSION = "0.2.0-experiment-v1";
    public ExperimentRecord {
        agentTypes = List.copyOf(agentTypes);
        games = List.copyOf(games);
    }

    /** 模型配置只保留可比较参数；API Key 与服务地址不进入记录。 */
    public record ModelSpec(String model, double temperature, int maxTokens) {
        public static ModelSpec of(LlmConfig config) {
            return config == null ? null : new ModelSpec(config.model(), config.temperature(), config.maxTokens());
        }
    }
    public record Seat(String playerId, Role role, String agentType, PlayerStatus status) { }

    public record GameSnapshot(String gameId, long seed, String startedAt, String endedAt,
                                String status, GameResult result, int days, Long elapsedMillis,
                                ModelSpec model, List<Seat> seats, List<ActionRecord> actions,
                                List<GameEvent> events, List<ObserverNote> observerNotes, Run knowledge) {
        public GameSnapshot {
            seats = List.copyOf(seats);
            actions = List.copyOf(actions);
            events = List.copyOf(events);
            // null 表示旧存档从未保存此字段；空列表表示新存档已保存但暂无私有记录。
            observerNotes = observerNotes == null ? null : List.copyOf(observerNotes);
        }
        public static GameSnapshot capture(GameState state, long seed, List<String> types,
                                            String startedAt, ModelSpec model, boolean failed) {
            boolean complete = state.getResult() != GameResult.ONGOING;
            String endedAt = complete || failed ? Instant.now().toString() : null;
            var seats = java.util.stream.IntStream.range(0, state.getPlayers().size()).mapToObj(i -> {
                var player = state.getPlayers().get(i);
                return new Seat(player.getId(), player.getRole(), types.get(i), player.getStatus());
            }).toList();
            return new GameSnapshot(state.getGameId(), seed, startedAt, endedAt,
                    failed ? "FAILED" : complete ? "COMPLETED" : "IN_PROGRESS", state.getResult(),
                    state.getDayNumber(), endedAt == null ? null :
                    java.time.Duration.between(Instant.parse(startedAt), Instant.parse(endedAt)).toMillis(),
                    model, seats, state.getActionRecords(), state.getEvents(), state.getObserverNotes(), null);
        }
        public GameSnapshot withObserverNotes(List<ObserverNote> notes) {
            return new GameSnapshot(gameId, seed, startedAt, endedAt, status, result, days, elapsedMillis,
                    model, seats, actions, events, notes, knowledge);
        }
        public GameSnapshot withKnowledge(Run value) {
            return new GameSnapshot(gameId, seed, startedAt, endedAt, status, result, days, elapsedMillis,
                    model, seats, actions, events, observerNotes, value);
        }
    }

    public record Summary(int completedGames, int failedGames, int wolfWins, int goodWins,
                           double averageDays, long actionAttempts, long validActions,
                           long invalidActions, long failedActions, long invalidReplies,
                           long apiCalls, long apiFailures, long usageReportedCalls,
                           long promptTokens, long completionTokens, long totalTokens,
                           double decisionMillis, long requestMillis) {
        public static Summary of(List<GameSnapshot> games) {
            var completed = games.stream().filter(g -> g.status().equals("COMPLETED")).toList();
            var actions = games.stream().flatMap(g -> g.actions().stream()).toList();
            return new Summary(completed.size(), (int) games.stream().filter(g -> g.status().equals("FAILED")).count(),
                    (int) completed.stream().filter(g -> g.result() == GameResult.WEREWOLF_WIN).count(),
                    (int) completed.stream().filter(g -> g.result() == GameResult.VILLAGER_WIN).count(),
                    completed.stream().mapToInt(GameSnapshot::days).average().orElse(0), actions.size(),
                    actions.stream().filter(a -> a.status().equals("VALID")).count(),
                    actions.stream().filter(a -> a.status().equals("INVALID")).count(),
                    actions.stream().filter(a -> a.status().equals("ERROR")).count(),
                    actions.stream().mapToLong(a -> a.metrics().invalidReplies()).sum(),
                    actions.stream().mapToLong(a -> a.metrics().apiCalls()).sum(),
                    actions.stream().mapToLong(a -> a.metrics().apiFailures()).sum(),
                    actions.stream().mapToLong(a -> a.metrics().usageReportedCalls()).sum(),
                    actions.stream().mapToLong(a -> a.metrics().promptTokens()).sum(),
                    actions.stream().mapToLong(a -> a.metrics().completionTokens()).sum(),
                    actions.stream().mapToLong(a -> a.metrics().totalTokens()).sum(),
                    actions.stream().mapToDouble(ActionRecord::decisionMillis).sum(),
                    actions.stream().mapToLong(a -> a.metrics().requestMillis()).sum());
        }
    }
    public record View(ExperimentRecord record, Summary summary) { }
    public View view() { return new View(this, Summary.of(games)); }
    public record ListItem(String id, String name, String source, String status, String createdAt,
                            long startSeed, int requestedGames, int recordedGames, Summary summary) { }
    public ListItem listItem() {
        return new ListItem(id, name, source, status, createdAt, startSeed, requestedGames, games.size(), Summary.of(games));
    }
}
