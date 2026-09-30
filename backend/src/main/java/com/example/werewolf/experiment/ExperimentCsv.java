package com.example.werewolf.experiment;

import com.example.werewolf.experiment.ExperimentRecord.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/** CSV 分为对局汇总和逐行动记录；UTF-8 BOM 方便中文 Excel 直接打开。 */
public final class ExperimentCsv {
    private ExperimentCsv() { }
    public static String games(ExperimentRecord record) {
        StringBuilder out = new StringBuilder("\uFEFF");
        row(out, "experiment_id", "experiment_name", "engine_version", "source", "experiment_status", "requested_games",
                "start_seed", "game_id", "seed", "player_count",
                "werewolves", "villagers", "seers", "witches", "seats", "model", "temperature", "max_tokens",
                "status", "result", "days", "elapsed_ms", "action_attempts", "valid_actions", "invalid_actions",
                "failed_actions", "invalid_replies", "api_calls", "api_failures", "usage_reported_calls",
                "prompt_tokens_reported", "completion_tokens_reported", "total_tokens_reported", "decision_ms", "request_ms");
        for (GameSnapshot game : record.games()) {
            Summary s = Summary.of(java.util.List.of(game));
            var c = record.config();
            String seats = game.seats().stream().map(p -> p.playerId() + ":" + p.role() + ":" + p.agentType())
                    .collect(Collectors.joining(";"));
            row(out, record.id(), record.name(), record.engineVersion(), record.source(), record.status(), record.requestedGames(),
                    record.startSeed(), game.gameId(), game.seed(), c.playerCount(),
                    c.werewolves(), c.villagers(), c.seers(), c.witches(), seats,
                    game.model() == null ? null : game.model().model(),
                    game.model() == null ? null : game.model().temperature(),
                    game.model() == null ? null : game.model().maxTokens(), game.status(), game.result(), game.days(),
                    game.elapsedMillis(), s.actionAttempts(), s.validActions(), s.invalidActions(), s.failedActions(),
                    s.invalidReplies(), s.apiCalls(), s.apiFailures(), s.usageReportedCalls(),
                    s.usageReportedCalls() == 0 ? null : s.promptTokens(),
                    s.usageReportedCalls() == 0 ? null : s.completionTokens(),
                    s.usageReportedCalls() == 0 ? null : s.totalTokens(), s.decisionMillis(), s.requestMillis());
        }
        return out.toString();
    }
    public static String actions(ExperimentRecord record) {
        StringBuilder out = new StringBuilder("\uFEFF");
        row(out, "experiment_id", "game_id", "seed", "sequence", "day", "phase", "player_id", "role",
                "agent_type", "action", "target_player_id", "status", "decision_ms", "api_calls", "api_failures",
                "invalid_replies", "usage_reported_calls", "total_tokens_reported", "request_ms");
        for (GameSnapshot game : record.games()) for (var action : game.actions()) {
            Seat seat = game.seats().stream().filter(p -> p.playerId().equals(action.playerId())).findFirst().orElseThrow();
            var m = action.metrics();
            row(out, record.id(), game.gameId(), game.seed(), action.sequence(), action.day(), action.phase(),
                    action.playerId(), seat.role(), seat.agentType(), action.action(), action.targetPlayerId(),
                    action.status(), action.decisionMillis(), m.apiCalls(), m.apiFailures(), m.invalidReplies(),
                    m.usageReportedCalls(), m.usageReportedCalls() == 0 ? null : m.totalTokens(), m.requestMillis());
        }
        return out.toString();
    }
    private static void row(StringBuilder out, Object... values) {
        out.append(Arrays.stream(values).map(ExperimentCsv::cell).collect(Collectors.joining(","))).append("\r\n");
    }
    private static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof Number) return value.toString();
        String text = value.toString();
        // 实验名称可由用户输入，避免 CSV 被表格软件当作公式执行。
        String stripped = text.stripLeading();
        if ((!stripped.isEmpty() && "=+@-".indexOf(stripped.charAt(0)) >= 0)
                || text.startsWith("\t") || text.startsWith("\r")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
