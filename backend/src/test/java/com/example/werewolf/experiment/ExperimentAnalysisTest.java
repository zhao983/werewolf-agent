package com.example.werewolf.experiment;

import com.example.werewolf.agent.*;
import com.example.werewolf.experiment.ExperimentAnalysis.*;
import com.example.werewolf.experiment.ExperimentRecord.*;
import com.example.werewolf.game.*;
import com.example.werewolf.knowledge.KnowledgeBase;
import com.example.werewolf.knowledge.KnowledgeService;
import com.example.werewolf.player.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用可手算的对局验证分母、投票身份、缺失用量、去重和筛选，避免只对照实现。 */
class ExperimentAnalysisTest {
    final String time = "2026-09-30T10:00:00Z";
    GameSnapshot game(String id, boolean complete, List<ActionRecord> actions) {
        var roles = List.of(Role.WEREWOLF, Role.WEREWOLF, Role.VILLAGER, Role.VILLAGER, Role.VILLAGER, Role.SEER, Role.WITCH);
        var seats = java.util.stream.IntStream.range(0, 7).mapToObj(i -> new Seat("player" + (i + 1), roles.get(i), "LLM", PlayerStatus.ALIVE)).toList();
        return new GameSnapshot(id, 42, time, complete ? time : null, complete ? "COMPLETED" : "IN_PROGRESS",
                complete ? GameResult.WEREWOLF_WIN : GameResult.ONGOING, 2, complete ? 0L : null,
                new ModelSpec("mock", .7, 1000), seats, actions, List.of(), List.of(), new KnowledgeBase.Run(KnowledgeBase.Mode.NONE, null, List.of()));
    }
    ExperimentRecord record(String id, String source, String group, GameSnapshot... games) {
        return new ExperimentRecord(1, ExperimentRecord.ENGINE_VERSION, id, "测试", source, "COMPLETED", time, time,
                GameConfig.classicSeven(), Collections.nCopies(7, "LLM"), 42, games.length, List.of(games), null, group, "备注");
    }
    ActionRecord action(int sequence, String player, String target, String status, int api, int invalid, int usage, long tokens) {
        return new ActionRecord(sequence, 2, GamePhase.DAY_VOTE, player, ActionType.VOTE, target, status, sequence * 10,
                new AgentMetrics(api, status.equals("ERROR") ? 1 : 0, invalid, usage, tokens, 0, tokens, sequence * 2 + 4));
    }
    Filter group(String label) { return new Filter(label, null, null, null, null, "LOCAL", null); }
    @Test void completedDenominatorsGoodVotesAndUsageAreCorrect() {
        var done = game("done", true, List.of(action(1, "player3", "player1", "VALID", 2, 1, 2, 100),
                action(2, "player1", "player3", "VALID", 1, 0, 0, 0),
                action(3, "player4", "player5", "INVALID", 1, 1, 1, 50),
                action(4, "player4", "player1", "ERROR", 1, 0, 0, 0),
                action(5, "player4", "player5", "VALID", 1, 0, 0, 0)));
        var pending = game("pending", false, List.of(action(1, "player3", "player1", "VALID", 10, 0, 10, 10000)));
        var value = ExperimentAnalysis.compare(List.of(record("r", "SINGLE", "A", done, pending)), new Request("A", "B", group("A"), group("empty")));
        var m = value.groupA().metrics();
        assertEquals(2, m.matchedGames()); assertEquals(1, m.completedGames()); assertEquals(1, m.excludedGames());
        assertEquals(1.0, m.wolfWinRate()); assertEquals(2.0, m.averageDays());
        assertEquals(2, m.goodVotes()); assertEquals(1, m.goodVotesForWolf()); assertEquals(.5, m.goodVoteHitRate());
        assertEquals(5, m.actionAttempts()); assertEquals(.2, m.invalidActionRate()); assertEquals(1, m.failedActions());
        assertEquals(6, m.apiCalls()); assertEquals(2.0 / 6, m.invalidReplyRate()); assertEquals(1, m.correctionRetries());
        assertEquals(30.0, m.averageLlmDecisionMillis()); assertEquals(50.0 / 6, m.averageRequestMillis());
        assertEquals(.5, m.usageCoverage()); assertEquals(150, m.reportedTokens()); assertEquals(50.0, m.averageReportedTokens());
        assertNull(value.groupB().metrics().wolfWinRate()); assertNull(value.groupB().metrics().averageLlmDecisionMillis());
        assertNull(value.groupB().metrics().reportedTokens());
    }
    @Test void importedDuplicatesAreExcludedByDefaultAndDeduplicatedWhenIncluded() {
        var done = game("same-id", true, List.of());
        var records = List.of(record("original", "SINGLE", "A", done), record("copy", "IMPORTED", "A", done));
        var local = ExperimentAnalysis.compare(records, new Request(null, null, Filter.local(), Filter.local()));
        assertEquals(1, local.groupA().metrics().matchedGames()); assertEquals(0, local.groupA().metrics().duplicateGames());
        assertEquals(1, local.overlapGames()); assertTrue(local.notices().stream().anyMatch(n -> n.contains("独立样本")));
        var all = new Filter(null, null, null, null, null, "ALL", null);
        var value = ExperimentAnalysis.compare(records, new Request(null, null, all, group("empty")));
        assertEquals(1, value.groupA().metrics().matchedGames()); assertEquals(1, value.groupA().metrics().duplicateGames());
        assertEquals("original", value.groupA().games().getFirst().experimentId());
        assertNull(value.groupA().metrics().usageCoverage()); assertNull(value.groupA().metrics().reportedTokens());
    }
    @Test void filtersMatchOneGameAndKeepLegacyKnowledgeUnknown() {
        var a = game("off", true, List.of());
        var snapshot = KnowledgeService.snapshot(List.of());
        var b = game("role", true, List.of()).withKnowledge(new KnowledgeBase.Run(KnowledgeBase.Mode.ROLE, snapshot, List.of()));
        var legacy = game("old", true, List.of()).withKnowledge(null);
        var records = List.of(record("r", "SINGLE", "研究组", a, b, legacy));
        var filter = new Filter("研究组", "mock", "7/2/3/1/1", "ROLE", snapshot.revision(), "LOCAL", String.join("/", Collections.nCopies(7, "LLM")));
        var value = ExperimentAnalysis.compare(records, new Request(null, null, filter, new Filter(null, null, null, "UNKNOWN", null, "LOCAL", null)));
        assertEquals(List.of("role"), value.groupA().games().stream().map(Reference::gameId).toList());
        assertEquals(List.of("old"), value.groupB().games().stream().map(Reference::gameId).toList());
        assertEquals(0, value.overlapGames());
        var options = ExperimentAnalysis.options(records);
        assertEquals(List.of("NONE", "ROLE", "UNKNOWN"), options.knowledgeModes());
        assertThrows(IllegalArgumentException.class, () -> ExperimentAnalysis.compare(records, new Request(null, null,
                new Filter(null, null, null, "FAKE", null, "LOCAL", null), null)));
    }
    @Test void csvExportsMissingValuesAndIncludesRawDenominators() {
        var value = ExperimentAnalysis.compare(List.of(), new Request("A", "B", null, null));
        String csv = ExperimentCsv.comparison(value);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"狼人胜率\",\"狼人胜场 / 完成局\",,,,"));
        assertTrue(csv.contains("好人合法投票数")); assertTrue(csv.contains("用量覆盖率")); assertTrue(csv.contains("生成时间"));
    }
}
