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
                "prompt_tokens_reported", "completion_tokens_reported", "total_tokens_reported", "decision_ms", "request_ms",
                "experiment_group", "experiment_notes", "knowledge_mode", "knowledge_revision");
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
                    s.usageReportedCalls() == 0 ? null : s.totalTokens(), s.decisionMillis(), s.requestMillis(),
                    record.group(), record.notes(), ExperimentAnalysis.knowledgeMode(game), ExperimentAnalysis.knowledgeRevision(game));
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
    /** 对照表保留原始分母与用量覆盖率，不将缺失用量输出为零。 */
    public static String comparison(ExperimentAnalysis.Comparison value) {
        StringBuilder out = new StringBuilder("\uFEFF");
        row(out, "指标", "统计口径", "A：" + value.groupA().name(), "B：" + value.groupB().name(), "B 减 A", "单位");
        row(out, "生成时间", value.generatedAt(), null, null, null, null);
        row(out, "筛选条件", "null 为全部，空分组为未分组，空模型为无 LLM", json(value.groupA().filter()), json(value.groupB().filter()), null, null);
        row(out, "配置摘要", "仅完成局", json(value.groupA().profiles()), json(value.groupB().profiles()), null, null);
        var a = value.groupA().metrics(); var b = value.groupB().metrics();
        metric(out, "匹配对局", "筛选后按 gameId 去重", a.matchedGames(), b.matchedGames(), "局");
        metric(out, "完成对局", "全部指标仅使用完成局", a.completedGames(), b.completedGames(), "局");
        metric(out, "排除未完成局", "匹配局减完成局", a.excludedGames(), b.excludedGames(), "局");
        metric(out, "重复副本", "同组内已去重的副本数", a.duplicateGames(), b.duplicateGames(), "份");
        metric(out, "狼人胜场", "完成局", a.wolfWins(), b.wolfWins(), "局");
        metric(out, "好人胜场", "完成局", a.goodWins(), b.goodWins(), "局");
        metric(out, "狼人胜率", "狼人胜场 / 完成局", percent(a.wolfWinRate()), percent(b.wolfWinRate()), "%；差值为百分点");
        metric(out, "好人胜率", "好人胜场 / 完成局", percent(a.goodWinRate()), percent(b.goodWinRate()), "%；差值为百分点");
        metric(out, "平均轮次", "完成局轮次之和 / 完成局", a.averageDays(), b.averageDays(), "轮");
        metric(out, "好人合法投票数", "实际好人身份的 VALID VOTE", a.goodVotes(), b.goodVotes(), "票");
        metric(out, "好人投中狼人票数", "目标的实际角色是狼人", a.goodVotesForWolf(), b.goodVotesForWolf(), "票");
        metric(out, "好人投票命中率", "投中狼人票数 / 好人合法投票数", percent(a.goodVoteHitRate()), percent(b.goodVoteHitRate()), "%；差值为百分点");
        metric(out, "行动尝试数", "含 VALID、INVALID 与 ERROR", a.actionAttempts(), b.actionAttempts(), "次");
        metric(out, "非法行动数", "INVALID 行动", a.invalidActions(), b.invalidActions(), "次");
        metric(out, "失败行动数", "ERROR 行动", a.failedActions(), b.failedActions(), "次");
        metric(out, "非法行动率", "INVALID / 行动尝试", percent(a.invalidActionRate()), percent(b.invalidActionRate()), "%；差值为百分点");
        metric(out, "模型调用数", "含纠错重试和失败调用", a.apiCalls(), b.apiCalls(), "次");
        metric(out, "模型调用失败数", "接口或服务错误", a.apiFailures(), b.apiFailures(), "次");
        metric(out, "非法模型回复数", "格式或动作校验未通过", a.invalidReplies(), b.invalidReplies(), "次");
        metric(out, "非法回复率", "非法模型回复 / 模型调用", percent(a.invalidReplyRate()), percent(b.invalidReplyRate()), "%；差值为百分点");
        metric(out, "模型纠错重试", "每次行动调用次数减一；不含用户手动重试", a.correctionRetries(), b.correctionRetries(), "次");
        metric(out, "模型决策次数", "调用过模型的行动尝试", a.llmDecisions(), b.llmDecisions(), "次");
        metric(out, "平均模型决策耗时", "模型行动耗时 / 模型决策次数；含重试", a.averageLlmDecisionMillis(), b.averageLlmDecisionMillis(), "ms");
        metric(out, "平均接口请求耗时", "接口耗时 / 模型调用数", a.averageRequestMillis(), b.averageRequestMillis(), "ms");
        metric(out, "已报告用量调用数", "兼容接口实际返回 usage", a.usageReportedCalls(), b.usageReportedCalls(), "次");
        metric(out, "用量覆盖率", "已报告用量调用数 / 模型调用数", percent(a.usageCoverage()), percent(b.usageCoverage()), "%；差值为百分点");
        metric(out, "已报告 Token 总数", "缺失用量显示空值", a.reportedTokens(), b.reportedTokens(), "Token");
        metric(out, "平均已报告 Token", "已报告 Token / 已报告用量调用数", a.averageReportedTokens(), b.averageReportedTokens(), "Token/调用");
        row(out, "两组交叠", "相同 gameId", value.overlapGames(), null, null, "局");
        for (String notice : value.notices()) row(out, "比较提示", notice, null, null, null, null);
        row(out, "分析说明", "描述性汇总，不自动判断显著性或因果；固定模型、规则，平衡座位与身份。", null, null, null, null);
        return out.toString();
    }
    private static Double percent(Double value) { return value == null ? null : value * 100; }
    private static void metric(StringBuilder out, String name, String definition, Number a, Number b, String unit) {
        row(out, name, definition, a, b, a == null || b == null ? null : b.doubleValue() - a.doubleValue(), unit);
    }
    private static String json(Object object) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(object); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("无法生成汇总筛选说明", e); }
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
