package com.example.werewolf.experiment;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.experiment.ExperimentRecord.*;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.player.Role;
import java.time.Instant;
import java.util.*;

/** 描述性对照统计：先过滤并去重对局，只有完成局进入指标分母。 */
public final class ExperimentAnalysis {
    private ExperimentAnalysis() { }
    public record Filter(String group, String model, String config, String knowledgeMode,
                          String knowledgeRevision, String source, String agentTypes) {
        public static Filter local() { return new Filter(null, null, null, null, null, "LOCAL", null); }
    }
    public record Request(String nameA, String nameB, Filter groupA, Filter groupB) { }
    public record Options(List<String> groups, List<String> models, List<String> configs,
                           List<String> knowledgeModes, List<String> knowledgeRevisions, List<String> agentTypes) { }
    public record Reference(String experimentId, String gameId, String name, String group, String source,
                             long seed, String status, String model, String knowledgeMode, String knowledgeRevision) { }
    public record Profiles(List<String> configs, List<String> models, List<String> agentTypes, List<String> engineVersions,
                            List<String> knowledgeModes, List<String> knowledgeRevisions) { }
    public record Metrics(int matchedGames, int completedGames, int excludedGames, int duplicateGames,
                           int wolfWins, int goodWins, Double wolfWinRate, Double goodWinRate, Double averageDays,
                           long goodVotes, long goodVotesForWolf, Double goodVoteHitRate,
                           long actionAttempts, long invalidActions, long failedActions, Double invalidActionRate,
                           long apiCalls, long apiFailures, long invalidReplies, Double invalidReplyRate,
                           long correctionRetries, long llmDecisions, Double averageLlmDecisionMillis, Double averageRequestMillis,
                           long usageReportedCalls, Double usageCoverage, Long reportedTokens, Double averageReportedTokens) { }
    public record Cohort(String name, Filter filter, Metrics metrics, Profiles profiles, List<Reference> games) { }
    public record Comparison(String generatedAt, Cohort groupA, Cohort groupB, int overlapGames, List<String> notices) { }
    private record Sample(ExperimentRecord record, GameSnapshot game) { }
    private record Selection(List<Sample> games, int duplicates) { }

    public static String configKey(GameConfig c) {
        return c.playerCount() + "/" + c.werewolves() + "/" + c.villagers() + "/" + c.seers() + "/" + c.witches();
    }
    public static String model(GameSnapshot game) { return game.model() == null ? "" : game.model().model(); }
    public static String knowledgeMode(GameSnapshot game) {
        if (game.seats().stream().noneMatch(s -> s.agentType().equals("LLM"))) return "NOT_APPLICABLE";
        return game.knowledge() == null ? "UNKNOWN" : game.knowledge().mode().name();
    }
    public static String knowledgeRevision(GameSnapshot game) {
        return game.knowledge() == null || game.knowledge().snapshot() == null ? null : game.knowledge().snapshot().revision();
    }
    public static Options options(List<ExperimentRecord> records) {
        var samples = records.stream().flatMap(r -> r.games().stream().map(g -> new Sample(r, g))).toList();
        return new Options(sorted(records.stream().map(ExperimentRecord::group).toList()),
                sorted(samples.stream().map(s -> model(s.game())).toList()),
                sorted(records.stream().map(r -> configKey(r.config())).toList()),
                sorted(samples.stream().map(s -> knowledgeMode(s.game())).toList()),
                sorted(samples.stream().map(s -> knowledgeRevision(s.game())).filter(Objects::nonNull).toList()),
                sorted(records.stream().map(r -> String.join("/", r.agentTypes())).toList()));
    }
    public static Comparison compare(List<ExperimentRecord> records, Request request) {
        if (request == null) throw new IllegalArgumentException("缺少对照条件");
        Filter a = validate(request.groupA()), b = validate(request.groupB());
        Cohort first = cohort(label(request.nameA(), "实验组 A"), a, records);
        Cohort second = cohort(label(request.nameB(), "实验组 B"), b, records);
        var ids = new HashSet<>(first.games().stream().map(Reference::gameId).toList());
        int overlap = (int) second.games().stream().filter(g -> ids.contains(g.gameId())).count();
        List<String> notices = new ArrayList<>();
        if (overlap > 0) notices.add("两组包含 " + overlap + " 场相同对局，不能作为独立样本解释。");
        if (first.metrics().completedGames() == 0 || second.metrics().completedGames() == 0)
            notices.add("至少一组没有完成局，对应比例和平均值显示为暂无数据。");
        if (!first.profiles().configs().equals(second.profiles().configs())) notices.add("两组身份配置不同，胜率差异可能来自规则配置。");
        if (!first.profiles().models().equals(second.profiles().models())) notices.add("两组模型或模型参数不同，比较知识效果时请固定模型与参数。");
        if (!first.profiles().agentTypes().equals(second.profiles().agentTypes())) notices.add("两组座位 Agent 配置不同，请结合控制方式分析。");
        if (!first.profiles().engineVersions().equals(second.profiles().engineVersions())) notices.add("两组引擎版本不同，请核对规则和提示版本。");
        for (Cohort cohort : List.of(first, second)) {
            if (cohort.profiles().configs().size() > 1 || cohort.profiles().models().size() > 1 || cohort.profiles().agentTypes().size() > 1)
                notices.add(cohort.name() + " 包含多种配置，建议继续筛选后比较。");
            if (cohort.profiles().knowledgeRevisions().size() > 1)
                notices.add(cohort.name() + " 包含多个知识版本，可按知识快照筛选。");
        }
        return new Comparison(Instant.now().toString(), first, second, overlap, List.copyOf(notices));
    }
    private static Filter validate(Filter input) {
        Filter f = input == null ? Filter.local() : input;
        length(f.group(), 60); length(f.model(), 300); length(f.config(), 60); length(f.agentTypes(), 200);
        if (f.source() != null && !Set.of("LOCAL", "ALL", "SINGLE", "BATCH", "IMPORTED").contains(f.source()))
            throw new IllegalArgumentException("对照记录来源无效");
        if (f.knowledgeMode() != null && !Set.of("NONE", "COMMON", "ROLE", "NOT_APPLICABLE", "UNKNOWN").contains(f.knowledgeMode()))
            throw new IllegalArgumentException("知识模式筛选无效");
        if (f.knowledgeRevision() != null && !f.knowledgeRevision().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("知识版本筛选无效");
        return new Filter(f.group(), f.model(), f.config(), f.knowledgeMode(), f.knowledgeRevision(), f.source() == null ? "LOCAL" : f.source(), f.agentTypes());
    }
    private static String label(String text, String fallback) {
        String value = text == null || text.isBlank() ? fallback : text.strip(); length(value, 80); return value;
    }
    private static void length(String text, int max) {
        if (text != null && text.length() > max) throw new IllegalArgumentException("对照筛选字段过长");
    }
    private static boolean same(String filter, String actual) { return filter == null || filter.equals(actual); }
    private static Selection select(List<ExperimentRecord> records, Filter filter) {
        Map<String, Sample> distinct = new LinkedHashMap<>(); int duplicates = 0;
        // 同一 gameId 的导入副本只计一次，优先使用本机原记录；不同组也报告交叠。
        var sorted = records.stream().sorted(Comparator.comparing((ExperimentRecord r) -> r.source().equals("IMPORTED"))
                .thenComparing(ExperimentRecord::createdAt, Comparator.reverseOrder()).thenComparing(ExperimentRecord::id)).toList();
        for (var record : sorted) {
            boolean source = filter.source().equals("ALL") || (filter.source().equals("LOCAL") ? !record.source().equals("IMPORTED") : record.source().equals(filter.source()));
            if (!source || !same(filter.group(), record.group()) || !same(filter.config(), configKey(record.config()))
                    || !same(filter.agentTypes(), String.join("/", record.agentTypes()))) continue;
            for (var game : record.games()) {
                if (!same(filter.model(), model(game)) || !same(filter.knowledgeMode(), knowledgeMode(game))
                        || !same(filter.knowledgeRevision(), knowledgeRevision(game))) continue;
                if (distinct.putIfAbsent(game.gameId(), new Sample(record, game)) != null) duplicates++;
            }
        }
        return new Selection(List.copyOf(distinct.values()), duplicates);
    }
    private static Cohort cohort(String name, Filter filter, List<ExperimentRecord> records) {
        Selection selection = select(records, filter);
        var complete = selection.games().stream().filter(s -> s.game().status().equals("COMPLETED")).toList();
        long attempts = 0, invalid = 0, failures = 0, api = 0, apiFailures = 0, invalidReplies = 0, retries = 0,
                llmDecisions = 0, reported = 0, tokens = 0, requests = 0, votes = 0, hits = 0;
        double decisionMillis = 0;
        int wolfWins = 0, goodWins = 0, days = 0;
        for (var sample : complete) {
            var game = sample.game(); days += game.days();
            if (game.result() == com.example.werewolf.game.GameResult.WEREWOLF_WIN) wolfWins++;
            else goodWins++;
            Map<String, Role> roles = new HashMap<>(); game.seats().forEach(s -> roles.put(s.playerId(), s.role()));
            for (var action : game.actions()) {
                attempts++; if (action.status().equals("INVALID")) invalid++; if (action.status().equals("ERROR")) failures++;
                var m = action.metrics(); api += m.apiCalls(); apiFailures += m.apiFailures(); invalidReplies += m.invalidReplies();
                retries += Math.max(0, m.apiCalls() - 1); reported += m.usageReportedCalls(); tokens += m.totalTokens(); requests += m.requestMillis();
                if (m.apiCalls() > 0) { llmDecisions++; decisionMillis += action.decisionMillis(); }
                if (action.status().equals("VALID") && action.action() == ActionType.VOTE && roles.get(action.playerId()) != Role.WEREWOLF) {
                    votes++; if (roles.get(action.targetPlayerId()) == Role.WEREWOLF) hits++;
                }
            }
        }
        int n = complete.size();
        Metrics metrics = new Metrics(selection.games().size(), n, selection.games().size() - n, selection.duplicates(),
                wolfWins, goodWins, ratio(wolfWins, n), ratio(goodWins, n), ratio(days, n), votes, hits, ratio(hits, votes),
                attempts, invalid, failures, ratio(invalid, attempts), api, apiFailures, invalidReplies, ratio(invalidReplies, api),
                retries, llmDecisions, ratio(decisionMillis, llmDecisions), ratio(requests, api), reported, ratio(reported, api),
                reported == 0 ? null : tokens, ratio(tokens, reported));
        Profiles profiles = new Profiles(sorted(complete.stream().map(s -> configKey(s.record().config())).toList()),
                sorted(complete.stream().map(s -> s.game().model() == null ? "无 LLM" : model(s.game()) +
                        " | temperature=" + s.game().model().temperature() + " | maxTokens=" + s.game().model().maxTokens()).toList()),
                sorted(complete.stream().map(s -> String.join("/", s.record().agentTypes())).toList()),
                sorted(complete.stream().map(s -> s.record().engineVersion()).toList()),
                sorted(complete.stream().map(s -> knowledgeMode(s.game())).toList()),
                sorted(complete.stream().map(s -> knowledgeRevision(s.game())).filter(Objects::nonNull).toList()));
        var references = selection.games().stream().map(s -> new Reference(s.record().id(), s.game().gameId(), s.record().name(), s.record().group(),
                s.record().source(), s.game().seed(), s.game().status(), model(s.game()), knowledgeMode(s.game()), knowledgeRevision(s.game()))).toList();
        return new Cohort(name, filter, metrics, profiles, references);
    }
    private static Double ratio(double total, long count) { return count == 0 ? null : total / count; }
    private static List<String> sorted(List<String> values) { return values.stream().distinct().sorted().toList(); }
}
