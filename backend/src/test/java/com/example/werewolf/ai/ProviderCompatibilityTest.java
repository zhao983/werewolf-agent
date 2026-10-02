package com.example.werewolf.ai;

import com.example.werewolf.agent.*;
import com.example.werewolf.game.*;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 本地服务模拟 Qwen 工具选择和思考用量口径，验证实际 HTTP 请求而非真实收费接口。 */
class ProviderCompatibilityTest {
    ObjectMapper mapper = new ObjectMapper();
    @Test void supportsExplicitThinkingControlAndNamedSingleToolWithoutRequired() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            payload.set(mapper.readTree(exchange.getRequestBody()));
            var message = Map.of("tool_calls", List.of(Map.of("type", "function", "function",
                    Map.of("name", "vote_player", "arguments", "{\"targetPlayerId\":\"player2\"}"))));
            byte[] bytes = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message)),
                    "usage", Map.of("prompt_tokens", 100, "completion_tokens", 8000, "total_tokens", 8100,
                            "completion_tokens_details", Map.of("reasoning_tokens", 7200))));
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            for (var limit : TokenLimitParameter.values()) {
                var config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "mock-key", "qwen-mock", .7, 1000,
                        DecisionMode.TOOLS, 120, limit, false, ToolChoiceMode.AUTO);
                var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config, mapper);
                var context = new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 2,
                        List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE), Map.of(ActionType.VOTE, List.of("player2")), null);
                assertEquals("player2", agent.act(context).targetPlayerId());
                assertFalse(payload.get().path("enable_thinking").asBoolean(true));
                assertEquals("vote_player", payload.get().path("tool_choice").path("function").path("name").asText());
                assertEquals(1000, payload.get().path(limit.field()).asInt());
                assertFalse(payload.get().has(limit == TokenLimitParameter.MAX_TOKENS ? "max_completion_tokens" : "max_tokens"));
                var diagnostic = agent.lastDiagnostics().getFirst();
                assertEquals(8000L, diagnostic.completionTokens()); assertEquals(7200L, diagnostic.reasoningTokens());
                assertEquals(limit == TokenLimitParameter.MAX_COMPLETION_TOKENS, diagnostic.outputExceededLimit());
                assertEquals(1, agent.lastMetrics().apiCalls());
                assertFalse(mapper.writeValueAsString(agent.lastDiagnostics()).contains("mock-key"));
            }
        } finally { server.stop(0); }
    }
    @Test void multipleToolsUseAutoAndDefaultDoesNotSendProviderThinkingFlag() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> payload = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            payload.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"no tool\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var context = new AgentContext("player1", Role.WEREWOLF, GamePhase.NIGHT_WEREWOLF, 1,
                    List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.KILL, ActionType.PASS),
                    Map.of(ActionType.KILL, List.of("player2")), null);
            var config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "mock", .7, 1000,
                    DecisionMode.TOOLS, 120, TokenLimitParameter.MAX_TOKENS, null, ToolChoiceMode.AUTO);
            new OpenAiCompatibleClient(mapper).chatWithTools(config, "mock prompt", GameActionTools.definitions(context, false));
            assertEquals("auto", payload.get().path("tool_choice").asText());
            assertFalse(payload.get().has("enable_thinking"));
            var required = new LlmConfig(config.baseUrl(), "", "mock", .7, 1000, DecisionMode.TOOLS);
            new OpenAiCompatibleClient(mapper).chatWithTools(required, "mock prompt", GameActionTools.definitions(context, false));
            assertEquals("required", payload.get().path("tool_choice").asText());
        } finally { server.stop(0); }
    }
    @Test void missingChoiceIsAResponseErrorAndDoesNotTriggerCorrectionCalls() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); byte[] bytes = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "mock", .7, 1000);
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config, mapper);
            var context = new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 2,
                    List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE), Map.of(ActionType.VOTE, List.of("player2")), null);
            assertThrows(ModelRequestException.class, () -> agent.act(context));
            assertEquals(1, calls.get()); assertEquals(DecisionDiagnostic.Code.RESPONSE_ERROR, agent.lastDiagnostics().getFirst().code());
        } finally { server.stop(0); }
    }
}
