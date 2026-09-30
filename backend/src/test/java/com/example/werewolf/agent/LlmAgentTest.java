package com.example.werewolf.agent;

import com.example.werewolf.ai.LlmClient;
import com.example.werewolf.ai.LlmConfig;
import com.example.werewolf.game.GamePhase;
import com.example.werewolf.message.GameMessage;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 模拟第二天投票时模型的格式偏差，确认只提交合法行动。 */
class LlmAgentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmConfig config = new LlmConfig("http://127.0.0.1:12345/v1", "", "mock", 0.7, 1000);

    private AgentContext voteContext(List<GameMessage> history) {
        return new AgentContext("player3", Role.VILLAGER, GamePhase.DAY_VOTE, 2,
                List.of("player3", "player4", "player5"), history, List.of(),
                List.of(ActionType.VOTE), Map.of(ActionType.VOTE, List.of("player4", "player5")), null);
    }

    @Test
    void acceptsJsonInsideExplanationAndFence() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = (settings, prompt) -> {
            calls.incrementAndGet();
            return "我的投票如下：\n```json\n{\"action\":\"vote\",\"targetPlayerId\":\"player4\"}\n```";
        };
        AgentResponse response = new LlmAgent(client, config, mapper).act(voteContext(List.of()));
        assertEquals(ActionType.VOTE, response.action());
        assertEquals("player4", response.targetPlayerId());
        assertEquals(1, calls.get());
    }

    @Test
    void keepsShortDecisionSummaryForObserver() {
        LlmClient client = (settings, prompt) ->
                "{\"action\":\"VOTE\",\"targetPlayerId\":\"player4\",\"reasoning\":\"根据公开发言选择4号\"}";
        AgentResponse response = new LlmAgent(client, config, mapper).act(voteContext(List.of()));
        assertEquals("根据公开发言选择4号", response.reasoning());
    }

    @Test
    void retriesInvalidTargetAndUsesShortPrompt() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> prompt = new AtomicReference<>();
        LlmClient client = (settings, message) -> {
            prompt.set(message);
            return calls.incrementAndGet() == 1
                    ? "{\"action\":\"VOTE\",\"targetPlayerId\":\"player3\"}"
                    : "{\"action\":\"VOTE\",\"targetPlayerId\":\"player5\"}";
        };
        AgentResponse response = new LlmAgent(client, config, mapper).act(voteContext(List.of()));
        assertEquals("player5", response.targetPlayerId());
        assertEquals(2, calls.get());
        assertTrue(prompt.get().contains("上次回复无法作为合法动作"));
        assertTrue(prompt.get().contains("reasoning"));
    }

    @Test
    void preservesModelServiceErrorInsteadOfCallingItUnreadable() {
        LlmClient client = (settings, prompt) -> { throw new IllegalStateException("Model API returned HTTP 429"); };
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LlmAgent(client, config, mapper).act(voteContext(List.of())));
        assertEquals("Model API returned HTTP 429", error.getMessage());
    }

    @Test
    void limitsOldPublicDiscussionInModelRequest() {
        AtomicReference<String> prompt = new AtomicReference<>();
        LlmClient client = (settings, message) -> {
            prompt.set(message);
            return "{\"action\":\"VOTE\",\"targetPlayerId\":\"player4\"}";
        };
        List<GameMessage> history = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new GameMessage(i < 15 ? 1 : 2, "player" + i, "发".repeat(1000)))
                .toList();
        new LlmAgent(client, config, mapper).act(voteContext(history));
        assertTrue(prompt.get().length() < 8000);
        assertFalse(prompt.get().contains("player0"));
        assertTrue(prompt.get().contains("player29"));
    }
}
