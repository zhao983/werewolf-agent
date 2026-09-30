package com.example.werewolf.ai;

import com.example.werewolf.agent.*;
import com.example.werewolf.game.ActionValidator;
import com.example.werewolf.game.GamePhase;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 使用本地模拟服务验证兼容请求，避免依赖真实 API Key。 */
class OpenAiCompatibleClientTest {
    @Test
    void rejectsPlaintextRemoteEndpointWhenApiKeyIsPresent() {
        assertThrows(IllegalArgumentException.class, () ->
                new LlmConfig("http://example.com/v1", "secret", "model", 0.7, 100));
        assertDoesNotThrow(() ->
                new LlmConfig("http://127.0.0.1:12345/v1", "secret", "model", 0.7, 100));
        assertDoesNotThrow(() ->
                new LlmConfig("http://192.168.1.10:11434/v1", "", "model", 0.7, 100));
    }

    @Test
    void explainsWhenReasoningUsesAllOutputTokens() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":100,\"total_tokens\":120}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            LlmConfig config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "", "mock-model", 0.7, 100);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new OpenAiCompatibleClient(new ObjectMapper()).chat(config, "test"));
            assertTrue(error.getMessage().contains("Max Tokens"));
            LlmAgent agent = new LlmAgent(new OpenAiCompatibleClient(new ObjectMapper()), config, new ObjectMapper());
            AgentContext context = new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 1,
                    List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE),
                    Map.of(ActionType.VOTE, List.of("player2")), null);
            assertThrows(IllegalStateException.class, () -> agent.act(context));
            assertEquals(1, agent.lastMetrics().apiFailures());
            assertEquals(1, agent.lastMetrics().usageReportedCalls());
            assertEquals(120, agent.lastMetrics().totalTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postsCompatibleRequestAndParsesStructuredAgentAction() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content",
                    "{\"reasoning\":\"test\",\"speech\":null,\"action\":\"VOTE\",\"targetPlayerId\":\"player2\"}"))),
                    "usage", Map.of("prompt_tokens", 20, "completion_tokens", 5, "total_tokens", 25)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            LlmConfig config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "secret-test", "mock-model", 0.7, 100);
            LlmAgent agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config, mapper);
            AgentContext context = new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 1,
                    List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE),
                    Map.of(ActionType.VOTE, List.of("player2")), null, new GameConfig(9, 2, 4, 1, 2));
            AgentResponse action = agent.act(context);
            ActionValidator.validate(context, action);
            assertEquals(ActionType.VOTE, action.action());
            assertEquals("player2", action.targetPlayerId());
            assertEquals(1, agent.lastMetrics().apiCalls());
            assertEquals(1, agent.lastMetrics().usageReportedCalls());
            assertEquals(25, agent.lastMetrics().totalTokens());
            assertEquals("Bearer secret-test", auth.get());
            assertEquals("mock-model", mapper.readTree(received.get()).path("model").asText());
            assertFalse(mapper.readTree(received.get()).path("messages").path(0).path("content").asText().contains("secret-test"));
            assertTrue(mapper.readTree(received.get()).path("messages").path(0).path("content").asText().contains("9 人狼人杀"));
        } finally {
            server.stop(0);
        }
    }
}
