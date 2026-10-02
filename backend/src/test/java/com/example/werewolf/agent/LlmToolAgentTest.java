package com.example.werewolf.agent;

import com.example.werewolf.ai.*;
import com.example.werewolf.game.*;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 工具输出重试只影响决策，不能提前消耗玩家行动或药物。 */
class LlmToolAgentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmConfig config = new LlmConfig("http://127.0.0.1:12345/v1", "", "mock", .7, 1000, DecisionMode.TOOLS);
    private AgentContext voteContext() {
        return new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 2,
                List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE), Map.of(ActionType.VOTE, List.of("player2")), null);
    }

    @Test
    void retriesOnlyToolArgumentsAndRetainsUsageAndPrivateSummary() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = new LlmClient() {
            public String chat(LlmConfig c, String p) { fail("工具模式不能调用文本接口"); return null; }
            public ChatResult chatWithTools(LlmConfig c, String p, List<Map<String, Object>> tools) {
                int attempt = calls.incrementAndGet();
                assertEquals(1, tools.size());
                assertFalse(p.contains("最终仍须只输出上述格式的合法 JSON"));
                if (attempt == 2) assertTrue(p.contains("上次回复无法作为合法动作"));
                String target = attempt == 1 ? "player1" : "player2";
                return new ChatResult(null, 20L, 5L, 25L, null, List.of(new ToolCall("function", "vote_player",
                        "{\"targetPlayerId\":\"" + target + "\",\"reasoning\":\"根据公开发言投票\"}")));
            }
        };
        var agent = new LlmAgent(client, config, mapper);
        var response = agent.act(voteContext());
        assertEquals("player2", response.targetPlayerId());
        assertEquals("根据公开发言投票", response.reasoning());
        assertEquals(2, agent.lastMetrics().apiCalls()); assertEquals(1, agent.lastMetrics().invalidReplies());
        assertEquals(50, agent.lastMetrics().totalTokens());
    }

    @Test
    void ordinaryJsonCannotSilentlyReplaceRequiredToolCall() {
        AtomicInteger calls = new AtomicInteger();
        var client = new LlmClient() {
            public String chat(LlmConfig c, String p) { return ""; }
            public ChatResult chatWithTools(LlmConfig c, String p, List<Map<String, Object>> tools) {
                calls.incrementAndGet(); return new ChatResult("{\"action\":\"VOTE\",\"targetPlayerId\":\"player2\"}", null, null, null);
            }
        };
        var agent = new LlmAgent(client, config, mapper);
        assertThrows(IllegalStateException.class, () -> agent.act(voteContext()));
        assertEquals(2, calls.get()); assertEquals(2, agent.lastMetrics().invalidReplies());
    }

    @Test
    void rejectsMultipleToolsWithoutAdvancingAndCanRetrySamePlayer() {
        AtomicInteger requests = new AtomicInteger();
        var client = new LlmClient() {
            public String chat(LlmConfig c, String p) { return ""; }
            @SuppressWarnings("unchecked")
            public ChatResult chatWithTools(LlmConfig c, String p, List<Map<String, Object>> tools) {
                var function = (Map<String, Object>) tools.getFirst().get("function");
                var parameters = (Map<String, Object>) function.get("parameters");
                var properties = (Map<String, Object>) parameters.get("properties");
                var target = (Map<String, Object>) properties.get("targetPlayerId");
                String player = ((List<String>) target.get("enum")).getFirst();
                var call = new ToolCall("function", function.get("name").toString(), "{\"reasoning\":\"\",\"targetPlayerId\":\"" + player + "\"}");
                return new ChatResult(null, null, null, null, null, requests.incrementAndGet() <= 2 ? List.of(call, call) : List.of(call));
            }
        };
        var agent = new LlmAgent(client, config, mapper);
        var session = new GameEngine(new Random(1), ignored -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, agent));
        String actor = session.getNextActorId();
        assertThrows(IllegalStateException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION));
        assertEquals(actor, session.getNextActorId());
        assertEquals(0, session.getState().getEvents().stream().filter(e -> e.type().equals("WOLF_VOTE")).count());
        assertEquals("ERROR", session.getState().getActionRecords().getFirst().status());
        session.advance(AdvanceCommand.NEXT_ACTION);
        assertNotEquals(actor, session.getNextActorId());
        assertEquals(1, session.getState().getEvents().stream().filter(e -> e.type().equals("WOLF_VOTE")).count());
    }
}
