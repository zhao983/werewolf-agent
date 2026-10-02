package com.example.werewolf.agent;

import com.example.werewolf.ai.*;
import com.example.werewolf.game.*;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 复现摘要超长、缺少工具、重复失败和错误轮次；所有模型调用均为本地替身。 */
class LlmStabilityTest {
    ObjectMapper mapper = new ObjectMapper();
    LlmConfig config = new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000, DecisionMode.TOOLS);
    AgentContext vote() { return new AgentContext("player2", Role.VILLAGER, GamePhase.DAY_VOTE, 3,
            List.of("player2", "player5", "player7", "player8"), List.of(), List.of(), List.of(ActionType.VOTE),
            Map.of(ActionType.VOTE, List.of("player5", "player7", "player8")), null); }
    LlmClient client(java.util.function.Function<String, String> arguments) {
        return new LlmClient() {
            public String chat(LlmConfig c, String p) { throw new AssertionError("不能退回文本"); }
            public ChatResult chatWithTools(LlmConfig c, String p, List<Map<String,Object>> tools) {
                return new ChatResult(null, 10L, 20L, 30L, null, List.of(new ToolCall("function", "vote_player", arguments.apply(p))));
            }
        };
    }
    @Test void locallyShortensOnlyPrivateSummaryAndDoesNotSpendASecondRequest() throws Exception {
        String longReason = "根据公开信息分析。".repeat(25);
        var agent = new LlmAgent(client(p -> "{\"targetPlayerId\":\"player8\",\"reasoning\":\"" + longReason + "\"}"), config, mapper);
        var response = agent.act(vote());
        assertEquals("player8", response.targetPlayerId()); assertEquals(ActionType.VOTE, response.action());
        assertTrue(response.reasoning().length() <= 80);
        assertEquals(1, agent.lastMetrics().apiCalls()); assertEquals(0, agent.lastMetrics().invalidReplies());
        assertEquals(List.of(DecisionDiagnostic.LocalRepair.REASONING_TRIMMED), agent.lastDiagnostics().getFirst().repairs());
        assertFalse(mapper.writeValueAsString(agent.lastDiagnostics()).contains(longReason));
    }
    @Test void omittedSummaryIsAcceptedInBasicToolsButNotStrictTools() {
        var agent = new LlmAgent(client(p -> "{\"targetPlayerId\":\"player8\"}"), config, mapper);
        assertEquals("", agent.act(vote()).reasoning());
        assertEquals(1, agent.lastMetrics().apiCalls());
        assertEquals(List.of(DecisionDiagnostic.LocalRepair.REASONING_DEFAULTED), agent.lastDiagnostics().getFirst().repairs());
        var strict = new LlmAgent(client(p -> "{\"targetPlayerId\":\"player8\"}"),
                new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000, DecisionMode.TOOLS_STRICT), mapper);
        assertThrows(IllegalStateException.class, () -> strict.act(vote()));
        assertEquals(DecisionDiagnostic.OutputIssue.MISSING_FIELD, strict.lastDiagnostics().getFirst().issue());
    }
    @Test void checksFullSummaryBeforeTrimmingAndDoesNotHideContradictionBeyondLimit() {
        AtomicInteger calls = new AtomicInteger();
        var agent = new LlmAgent(client(p -> {
            if (calls.incrementAndGet() == 1) return "{\"targetPlayerId\":\"player8\",\"reasoning\":\"" + "分析。".repeat(40) + "必须投5号\"}";
            assertTrue(p.contains("私有摘要明确投票对象"));
            return "{\"targetPlayerId\":\"player8\",\"reasoning\":\"投8号\"}";
        }), config, mapper);
        assertEquals("player8", agent.act(vote()).targetPlayerId());
        assertEquals(DecisionDiagnostic.OutputIssue.VOTE_TARGET, agent.lastDiagnostics().getFirst().issue());
        assertEquals(2, calls.get());
    }
    @Test void longPublicSpeechMustBeRewrittenAndNeverSilentlyTruncated() throws Exception {
        var context = new AgentContext("player2", Role.VILLAGER, GamePhase.DAY_DISCUSSION, 3,
                vote().alivePlayerIds(), List.of(), List.of(), List.of(ActionType.SPEAK), Map.of(), null);
        AtomicInteger calls = new AtomicInteger();
        LlmClient model = new LlmClient() {
            public String chat(LlmConfig c, String p) { return ""; }
            public ChatResult chatWithTools(LlmConfig c, String p, List<Map<String,Object>> tools) {
                String speech = calls.incrementAndGet() == 1 ? "长".repeat(501) : "我是2号，依据公开信息判断。";
                if (calls.get() == 2) assertTrue(p.contains("公开发言超过500"));
                return new ChatResult(null, null, null, null, null,
                        List.of(new ToolCall("function", "speak", "{\"speech\":\"" + speech + "\"}")));
            }
        };
        var agent = new LlmAgent(model, config, mapper);
        assertEquals("我是2号，依据公开信息判断。", agent.act(context).speech());
        assertEquals(DecisionDiagnostic.OutputIssue.SPEECH_TOO_LONG, agent.lastDiagnostics().getFirst().issue());
        assertEquals(2, calls.get());
    }
    @Test void pausesRepeatedRequestsUntilExplicitResetWithoutAdvancingTheEngine() {
        AtomicInteger requests = new AtomicInteger();
        LlmClient failing = new LlmClient() {
            public String chat(LlmConfig c, String p) { return ""; }
            public ChatResult chatWithTools(LlmConfig c, String p, List<Map<String,Object>> tools) {
                requests.incrementAndGet(); return new ChatResult("没有工具", null, null, null);
            }
        };
        var agent = new LlmAgent(failing, config, mapper);
        var session = new GameEngine(new Random(1), s -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, agent));
        String actor = session.getNextActorId();
        for (int i = 0; i < 3; i++) assertThrows(IllegalStateException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION));
        assertTrue(session.retryBlocked()); assertEquals(6, requests.get());
        assertEquals(3, session.getState().getActionRecords().size());
        for (int i = 0; i < 5; i++) assertThrows(IllegalStateException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION));
        assertEquals(6, requests.get()); assertEquals(3, session.getState().getActionRecords().size());
        assertEquals(actor, session.getNextActorId());
        session.allowRetry(); assertFalse(session.retryBlocked()); assertEquals(6, requests.get());
        assertThrows(IllegalStateException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION)); assertEquals(8, requests.get());
        assertEquals(actor, session.getNextActorId());
    }
    @Test void recentNightFactsAndOngoingWolfBoundCatchTheActualGameMistakes() {
        var context = new AgentContext("player2", Role.VILLAGER, GamePhase.DAY_DISCUSSION, 2,
                List.of("player2", "player5", "player7", "player8"), List.of(), List.of(), List.of(ActionType.SPEAK), Map.of(), null,
                GameConfig.classicSeven(), List.of("player1", "player3", "player4"), List.of(
                    new GameEvent(1, GamePhase.DAY_ANNOUNCEMENT, "DAY_ANNOUNCEMENT", null, null, "Last night: no deaths"),
                    new GameEvent(2, GamePhase.NIGHT_RESOLVE, "PLAYER_DIED", null, "player1", "player1 died last night"),
                    new GameEvent(2, GamePhase.DAY_ANNOUNCEMENT, "DAY_ANNOUNCEMENT", null, null, "Last night: player1")), List.of(), null, null);
        var facts = DecisionFacts.from(context);
        assertTrue(facts.announcedNights().getFirst().peaceful()); assertFalse(facts.lastAnnouncedNight().peaceful());
        assertEquals(List.of("player1"), facts.lastAnnouncedNight().deaths()); assertEquals(1, facts.maximumLivingWolvesWhileOngoing());
        for (String reason : List.of("昨晚是平安夜，需继续分析", "场上剩2、5、7、8四人和两狼。", "票8可致狼人数量不大于好人，直接获胜。"))
            assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(context, new AgentResponse(reason, "我是2号。", ActionType.SPEAK, null)), reason);
        assertDoesNotThrow(() -> DecisionConsistency.validate(context, new AgentResponse("首夜平安夜，但第二夜1号死亡", "3号说昨晚平安夜，我认为不对。", ActionType.SPEAK, null)));
    }
    @Test void malformedJsonAndFieldTypesHaveSpecificSafeDiagnostics() {
        for (String arguments : List.of("{", "{\"targetPlayerId\":8}", "{\"targetPlayerId\":\"player8\",\"extra\":true}")) {
            var agent = new LlmAgent(client(p -> arguments), config, mapper);
            assertThrows(IllegalStateException.class, () -> agent.act(vote()));
            assertNotNull(agent.lastDiagnostics().getFirst().issue());
            assertEquals(2, agent.lastMetrics().apiCalls());
        }
    }
    @Test void wolfReceiptDistinguishesProposalsAndKeepsPastNightReferencesValid() {
        var wolf = new AgentContext("player4", Role.WEREWOLF, GamePhase.DAY_VOTE, 2,
                List.of("player2", "player4", "player5", "player8"), List.of(), List.of(
                    "Wolf teammate: player8", "Wolf team night 1 final target: player2; individual KILL is only a proposal",
                    "Wolf team night 2 final target: player5; individual KILL is only a proposal"),
                List.of(ActionType.VOTE), Map.of(ActionType.VOTE, List.of("player2", "player5", "player8")), null);
        assertEquals(List.of("player8"), DecisionFacts.from(wolf).knownWolfTeammateIds());
        assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(wolf,
                new AgentResponse("昨晚刀了2号，今天需统一票型", null, ActionType.VOTE, "player2")));
        assertDoesNotThrow(() -> DecisionConsistency.validate(wolf,
                new AgentResponse("首夜刀了2号，昨晚刀了5号", null, ActionType.VOTE, "player2")));
        assertDoesNotThrow(() -> DecisionConsistency.validate(wolf,
                new AgentResponse("首夜我提议刀1号，但最终目标是2号", null, ActionType.VOTE, "player2")));
        assertTrue(DecisionFacts.from(vote()).knownWolfTeammateIds().isEmpty());
        assertTrue(DecisionFacts.from(vote()).wolfTeamTargets().isEmpty());
    }
}
