package com.example.werewolf.ai;

import com.example.werewolf.agent.*;
import com.example.werewolf.game.GamePhase;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 本地模拟响应验证额度字段、错误分类和用量超限提示；不调用收费模型。 */
class ModelDiagnosticsTest {
    ObjectMapper mapper = new ObjectMapper();
    AgentContext vote() { return new AgentContext("player1", Role.VILLAGER, GamePhase.DAY_VOTE, 2,
            List.of("player1", "player2"), List.of(), List.of(), List.of(ActionType.VOTE), Map.of(ActionType.VOTE, List.of("player2")), null); }
    @Test void sendsOnlySelectedTokenFieldAndWarnsWithoutDiscardingValidAction() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> body = new AtomicReference<>();
        server.createContext("/v1/chat/completions", e -> {
            body.set(mapper.readTree(e.getRequestBody()));
            byte[] response = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content",
                    "{\"action\":\"VOTE\",\"targetPlayerId\":\"player2\"}"))), "usage", Map.of("prompt_tokens", 20, "completion_tokens", 1200, "total_tokens", 1220)));
            e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        });
        server.start();
        try {
            var config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fake-secret", "mock", .7, 1000,
                    DecisionMode.JSON, 120, TokenLimitParameter.MAX_COMPLETION_TOKENS);
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config, mapper);
            assertEquals("player2", agent.act(vote()).targetPlayerId());
            assertEquals(1000, body.get().path("max_completion_tokens").asInt()); assertFalse(body.get().has("max_tokens"));
            var diagnostic = agent.lastDiagnostics().getFirst();
            assertEquals(DecisionDiagnostic.Code.SUCCESS, diagnostic.code()); assertEquals(200, diagnostic.httpStatus());
            assertEquals(DecisionDiagnostic.FinishReason.STOP, diagnostic.finishReason()); assertTrue(diagnostic.outputExceededLimit());
            String saved = mapper.writeValueAsString(agent.lastDiagnostics());
            assertFalse(saved.contains("fake-secret")); assertFalse(saved.contains("targetPlayerId"));
        } finally { server.stop(0); }
    }
    @Test void recordsTimeoutOnceAndResetsDiagnosticsForNextManualAttempt() {
        LlmClient timeout = (c, p) -> { throw new ModelRequestException(DecisionDiagnostic.Code.TIMEOUT, null, "请求超时"); };
        var agent = new LlmAgent(timeout, new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000), mapper);
        for (int i = 0; i < 2; i++) {
            assertThrows(ModelRequestException.class, () -> agent.act(vote()));
            assertEquals(1, agent.lastMetrics().apiCalls()); assertEquals(1, agent.lastMetrics().apiFailures());
            assertEquals(1, agent.lastDiagnostics().size()); assertEquals(DecisionDiagnostic.Code.TIMEOUT, agent.lastDiagnostics().getFirst().code());
        }
    }
    @Test void classifiesHttpAndMalformedResponseWithoutLeakingProviderBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", e -> {
            byte[] bytes = "private-prompt secret-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int status = e.getRequestHeaders().getFirst("Authorization") == null ? 200 : 429;
            e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        try {
            for (String key : List.of("", "fake-key")) {
                var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", key, "mock", .7, 1000), mapper);
                var error = assertThrows(ModelRequestException.class, () -> agent.act(vote()));
                assertFalse(error.getMessage().contains("secret-key"));
                assertEquals(key.isEmpty() ? DecisionDiagnostic.Code.RESPONSE_ERROR : DecisionDiagnostic.Code.HTTP_ERROR, agent.lastDiagnostics().getFirst().code());
                if (!key.isEmpty()) assertEquals(429, agent.lastDiagnostics().getFirst().httpStatus());
            }
        } finally { server.stop(0); }
    }
    @Test void acceptsLegacyConfigAndRejectsInvalidTimeout() throws Exception {
        var legacy = mapper.readValue("{\"baseUrl\":\"http://localhost:12345/v1\",\"apiKey\":\"\",\"model\":\"mock\",\"temperature\":0.7,\"maxTokens\":1000}", LlmConfig.class);
        assertEquals(45, legacy.requestTimeoutSeconds()); assertEquals(TokenLimitParameter.MAX_TOKENS, legacy.tokenLimitParameter());
        assertThrows(IllegalArgumentException.class, () -> new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000, DecisionMode.JSON, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new LlmConfig("http://localhost:12345/v1", "", "mock", .7, 1000, DecisionMode.JSON, 181, null));
    }
    @Test void realHttpRequestUsesConfiguredTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/chat/completions", e -> {
            try { release.await(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { e.close(); }
        }); server.start();
        try {
            var config = new LlmConfig("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", "mock", .7, 1000, DecisionMode.JSON, 10, null);
            var agent = new LlmAgent(new OpenAiCompatibleClient(mapper), config, mapper);
            var error = assertThrows(ModelRequestException.class, () -> agent.act(vote()));
            assertTrue(error.getMessage().contains("10 秒"));
            assertEquals(DecisionDiagnostic.Code.TIMEOUT, agent.lastDiagnostics().getFirst().code());
            assertEquals(1, agent.lastMetrics().apiCalls());
        } finally { release.countDown(); server.stop(0); }
    }
}
