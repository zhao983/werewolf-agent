package com.example.werewolf.ai;

import com.example.werewolf.agent.*;
import com.example.werewolf.game.*;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 通过本地 HTTP 服务验证真实请求协议，不访问远程模型或使用真实密钥。 */
class OpenAiToolCallingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentContext context() {
        return new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 2,
                List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE),
                Map.of(ActionType.VOTE, List.of("player2")), null);
    }
    private LlmConfig config(HttpServer server, DecisionMode mode) {
        return new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "mock-key", "mock", .7, 1000, mode);
    }
    private String response(String target, String finish) throws Exception {
        Map<String, Object> message = new LinkedHashMap<>(); message.put("content", null);
        message.put("tool_calls", List.of(Map.of("id", "call_vote", "type", "function", "function",
                Map.of("name", "vote_player", "arguments", mapper.writeValueAsString(Map.of("targetPlayerId", target, "reasoning", "公开信息支持此票"))))));
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish, "message", message)),
                "usage", Map.of("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30)));
    }
    private void send(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }

    @Test
    void sendsDynamicStrictToolsAndAcceptsNullTextContent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        String response = response("player2", "tool_calls");
        server.createContext("/v1/chat/completions", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            received.set(mapper.readTree(exchange.getRequestBody())); send(exchange, 200, response);
        });
        server.start();
        try {
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config(server, DecisionMode.TOOLS_STRICT), mapper);
            var action = agent.act(context());
            assertEquals(ActionType.VOTE, action.action()); assertEquals("player2", action.targetPlayerId());
            JsonNode request = received.get();
            assertEquals("required", request.path("tool_choice").asText()); assertFalse(request.path("parallel_tool_calls").asBoolean());
            assertEquals(1, request.path("tools").size());
            assertTrue(request.path("tools").get(0).path("function").path("strict").asBoolean());
            assertEquals("vote_player", request.path("tools").get(0).path("function").path("name").asText());
            assertEquals("Bearer mock-key", auth.get()); assertFalse(request.toString().contains("mock-key"));
            assertEquals(30, agent.lastMetrics().totalTokens()); assertEquals(0, agent.lastMetrics().invalidReplies());
        } finally { server.stop(0); }
    }

    @Test
    void recordsMalformedArgumentsAsInvalidReplyAndCorrectsOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        String wrong = response("player1", "tool_calls"), valid = response("player2", "tool_calls");
        server.createContext("/v1/chat/completions", exchange -> send(exchange, 200, calls.incrementAndGet() == 1 ? wrong : valid));
        server.start();
        try {
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config(server, DecisionMode.TOOLS), mapper);
            assertEquals("player2", agent.act(context()).targetPlayerId());
            assertEquals(2, calls.get()); assertEquals(1, agent.lastMetrics().invalidReplies());
            assertEquals(60, agent.lastMetrics().totalTokens());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsTruncatedToolReplyEvenIfArgumentsAppearValid() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String response = response("player2", "length");
        server.createContext("/v1/chat/completions", exchange -> send(exchange, 200, response)); server.start();
        try {
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config(server, DecisionMode.TOOLS), mapper);
            assertTrue(assertThrows(IllegalStateException.class, () -> agent.act(context())).getMessage().contains("Max Tokens"));
            assertEquals(1, agent.lastMetrics().apiCalls()); assertEquals(30, agent.lastMetrics().totalTokens());
        } finally { server.stop(0); }
    }

    @Test
    void unsupportedServiceStopsWithoutFallbackOrLeakingResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> { calls.incrementAndGet(); send(exchange, 400, "private-provider-error mock-key"); }); server.start();
        try {
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config(server, DecisionMode.TOOLS), mapper);
            var error = assertThrows(IllegalStateException.class, () -> agent.act(context()));
            assertTrue(error.getMessage().contains("JSON")); assertFalse(error.getMessage().contains("mock-key"));
            assertFalse(error.getMessage().contains("private-provider-error"));
            assertEquals(1, calls.get()); assertEquals(1, agent.lastMetrics().apiFailures());
        } finally { server.stop(0); }
    }
}
