package com.example.werewolf.agent;

import com.example.werewolf.ai.*;
import com.example.werewolf.game.*;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 对局01的明确输出矛盾回归；不限制狼人伪装或公开隐藏真实查验。 */
class DecisionConsistencyTest {
    ObjectMapper mapper = new ObjectMapper();
    AgentContext context(Role role, ActionType action, List<PersonalAction> own) {
        return new AgentContext("player5", role, action == ActionType.SPEAK ? GamePhase.DAY_DISCUSSION : GamePhase.DAY_VOTE, 3,
                List.of("player1", "player5", "player6"), List.of(), List.of(), List.of(action),
                action == ActionType.VOTE ? Map.of(action, List.of("player1", "player6")) : Map.of(), null,
                GameConfig.classicSeven(), List.of("player2", "player3", "player4", "player7"), List.of(), own, null, null);
    }
    @Test void rejectsWrongSelfIdDeadPlayerRequestAndVoteReasonMismatch() {
        assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(context(Role.VILLAGER, ActionType.SPEAK, List.of()),
                new AgentResponse("根据发言判断", "我是3号村民。", ActionType.SPEAK, null)));
        assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(context(Role.VILLAGER, ActionType.SPEAK, List.of()),
                new AgentResponse("根据发言判断", "请4号解释昨晚的判断。", ActionType.SPEAK, null)));
        assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(context(Role.VILLAGER, ActionType.VOTE, List.of()),
                new AgentResponse("票型需要统一，必须投4号", null, ActionType.VOTE, "player1")));
    }
    @Test void keepsRoleBluffDiscussionOfDeadPlayersAndPublicConcealmentLegal() {
        assertDoesNotThrow(() -> DecisionConsistency.validate(context(Role.WEREWOLF, ActionType.SPEAK, List.of()),
                new AgentResponse("假扮预言家", "我是5号预言家，4号之前的发言值得回顾。", ActionType.SPEAK, null)));
        var checked = List.of(new PersonalAction(1, GamePhase.NIGHT_SEER, ActionType.CHECK, "player1"));
        assertDoesNotThrow(() -> DecisionConsistency.validate(context(Role.SEER, ActionType.SPEAK, checked),
                new AgentResponse("隐藏已知查验以观察投票", "我没有进行验人。", ActionType.SPEAK, null)));
        assertDoesNotThrow(() -> DecisionConsistency.validate(context(Role.VILLAGER, ActionType.SPEAK, List.of()),
                new AgentResponse("讨论其他人身份声明", "3号刚才说‘我是2号村民’，这不可信。", ActionType.SPEAK, null)));
    }
    @Test void separatesPrivateFactDenialFromPublicStrategyAndCatchesInternalAside() {
        var saved = List.of(new PersonalAction(1, GamePhase.NIGHT_WITCH, ActionType.SAVE, "player2"));
        assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(context(Role.WITCH, ActionType.SPEAK, saved),
                new AgentResponse("首夜平安夜且未用药", "我是5号村民。", ActionType.SPEAK, null)));
        assertDoesNotThrow(() -> DecisionConsistency.validate(context(Role.WITCH, ActionType.SPEAK, saved),
                new AgentResponse("第二夜未用药，解药在首夜用过", "请6号说明判断。", ActionType.SPEAK, null)));
        assertThrows(DecisionConsistency.Problem.class, () -> DecisionConsistency.validate(context(Role.WITCH, ActionType.SPEAK, saved),
                new AgentResponse("隐藏女巫身份", "我是闭眼玩家（虽然我是女巫但我不能明说）。", ActionType.SPEAK, null)));
    }
    @Test void retriesConsistencyErrorsInJsonAndBothToolModesWithDiagnostics() {
        for (DecisionMode mode : DecisionMode.values()) {
            AtomicInteger calls = new AtomicInteger();
            LlmClient client = new LlmClient() {
                public String chat(LlmConfig settings, String prompt) {
                    return "{\"action\":\"VOTE\",\"targetPlayerId\":\"player1\",\"reasoning\":\"" + reason(prompt) + "\"}";
                }
                String reason(String prompt) {
                    if (calls.incrementAndGet() == 1) return "必须投4号";
                    assertTrue(prompt.contains("私有摘要明确投票对象"));
                    return "投1号";
                }
                public ChatResult chatWithTools(LlmConfig settings, String prompt, List<Map<String,Object>> tools) {
                    return new ChatResult(null, 20L, 10L, 30L, null, List.of(new ToolCall("function", "vote_player",
                            "{\"targetPlayerId\":\"player1\",\"reasoning\":\"" + reason(prompt) + "\"}")));
                }
            };
            LlmAgent agent = new LlmAgent(client, new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000, mode), mapper);
            assertEquals("player1", agent.act(context(Role.VILLAGER, ActionType.VOTE, List.of())).targetPlayerId());
            assertEquals(2, agent.lastMetrics().apiCalls()); assertEquals(1, agent.lastMetrics().invalidReplies());
            assertEquals(List.of(DecisionDiagnostic.Code.INCONSISTENT_ACTION, DecisionDiagnostic.Code.SUCCESS), agent.lastDiagnostics().stream().map(DecisionDiagnostic::code).toList());
        }
    }
    @Test void enrichedFactsSurvivePromptCompaction() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        var own = List.of(new PersonalAction(1, GamePhase.NIGHT_WITCH, ActionType.SAVE, "player2"));
        var fact = new GameEvent(2, GamePhase.DAY_RESOLVE, "PLAYER_EXILED", null, "player4", "player4 is exiled");
        var c = new AgentContext("player5", Role.WITCH, GamePhase.DAY_DISCUSSION, 3,
                List.of("player1", "player5", "player6"), List.of(), List.of(), List.of(ActionType.SPEAK), Map.of(), null,
                GameConfig.classicSeven(), List.of("player2", "player3", "player4", "player7"), List.of(fact), own, false, true);
        LlmClient client = (settings, prompt) -> { captured.set(prompt); return "{\"action\":\"SPEAK\",\"speech\":\"我是5号村民。\",\"reasoning\":\"隐瞒用药身份\"}"; };
        new LlmAgent(client, new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000), mapper).act(c);
        String prompt = captured.get();
        var payload = mapper.readTree(prompt.substring(prompt.indexOf("游戏信息：") + 5, prompt.indexOf("\n最终")));
        assertFalse(payload.path("antidoteAvailable").asBoolean()); assertTrue(payload.path("poisonAvailable").asBoolean());
        assertEquals("SAVE", payload.path("ownActionHistory").get(0).path("action").asText());
        assertEquals("PLAYER_EXILED", payload.path("publicFacts").get(0).path("type").asText());
        assertEquals(4, payload.path("deadPlayerIds").size());
    }
}
