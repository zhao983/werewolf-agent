package com.example.werewolf.knowledge;

import com.example.werewolf.agent.*;
import com.example.werewolf.ai.*;
import com.example.werewolf.experiment.*;
import com.example.werewolf.game.*;
import com.example.werewolf.knowledge.KnowledgeBase.*;
import com.example.werewolf.player.Role;
import com.example.werewolf.service.GameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 模拟模型验证实际提示的权限、版本冻结和实验 JSON 往返，不消耗真实 API。 */
class KnowledgeIntegrationTest {
    @TempDir Path directory;
    ObjectMapper mapper = new ObjectMapper();
    LlmConfig config = new LlmConfig("http://127.0.0.1:12345/v1", "secret-key", "mock", .7, 1000);
    @Test void gameFreezesKnowledgeAndExportsRoleSafeUsageIncludingFailures() throws Exception {
        var knowledge = new KnowledgeService(mapper, directory.resolve("knowledge").toString());
        for (Scope scope : Scope.values()) knowledge.add(new Entry(null, scope.name(), scope,
                new ArrayList<>(KnowledgeBase.allowedPhases(scope)), "ONLY_" + scope.name(), 90, true));
        Map<String, String> prompts = new HashMap<>();
        LlmClient client = (settings, prompt) -> {
            try {
                var context = mapper.readTree(prompt.split("游戏信息：", 2)[1]);
                String role = context.path("role").asText();
                prompts.put(context.path("playerId").asText(), prompt);
                assertTrue(prompt.contains("ONLY_COMMON"));
                assertTrue(prompt.contains("ONLY_" + role));
                for (Role other : Role.values()) if (!other.name().equals(role)) assertFalse(prompt.contains("ONLY_" + other.name()));
                assertFalse(prompt.contains("CHANGED_AFTER_START"));
                var action = context.path("availableActions").get(0).asText();
                if (action.equals("SPEAK")) return "{\"action\":\"SPEAK\",\"speech\":\"公开发言\"}";
                var targets = context.path("legalTargets").path(action);
                return "{\"action\":\"" + action + "\",\"targetPlayerId\":" + (targets.isArray() && !targets.isEmpty() ? targets.get(0) : "null") + "}";
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        };
        var service = new GameService(mapper, knowledge, client);
        var game = service.create(new GameService.CreateGameRequest(GameConfig.classicSeven(), Collections.nCopies(7, "LLM"), 42L, config, false, Mode.ROLE));
        String id = game.summary().gameId();
        assertTrue(game.manual()); assertTrue(prompts.isEmpty());
        String revision = service.observe(id).knowledge().snapshot().revision();
        var edit = knowledge.get().entries().getLast();
        knowledge.update(edit.id(), new Entry(null, edit.title(), edit.scope(), edit.phases(), "CHANGED_AFTER_START", 90, true), knowledge.get().revision());
        for (int i = 0; i < 70 && game.summary().result() == GameResult.ONGOING; i++) game = service.advance(id, game.nextCommand());
        assertFalse(prompts.isEmpty());
        var saved = service.snapshot(id).game();
        assertEquals(revision, saved.knowledge().snapshot().revision());
        assertEquals(saved.actions().size(), saved.knowledge().usages().size());
        var experiments = new ExperimentService(mapper, directory.resolve("experiments").toString());
        try {
            experiments.recordGame("test-owner", GameConfig.classicSeven(), Collections.nCopies(7, "LLM"), saved);
            var recorded = experiments.get("test-owner", saved.gameId());
            var exported = mapper.writeValueAsBytes(recorded);
            assertFalse(new String(exported, java.nio.charset.StandardCharsets.UTF_8).contains("secret-key"));
            var parsed = ExperimentImport.parse(mapper, exported);
            assertEquals(saved.knowledge(), parsed.games().getFirst().knowledge());
            var tampered = mapper.readTree(exported);
            ((com.fasterxml.jackson.databind.node.ObjectNode)tampered.path("record").path("games").get(0).path("knowledge").path("snapshot")).put("revision", "fake");
            assertThrows(IllegalArgumentException.class, () -> ExperimentImport.parse(mapper, mapper.writeValueAsBytes(tampered)));
        } finally { experiments.close(); }
    }
    @Test void failedRequestRecordsKnowledgeAndOffModeHasNoAdvice() {
        var knowledge = new KnowledgeService(mapper, directory.toString());
        var service = new GameService(mapper, knowledge, (settings, prompt) -> { throw new IllegalStateException("模拟请求失败"); });
        var game = service.create(new GameService.CreateGameRequest(GameConfig.classicSeven(), Collections.nCopies(7, "LLM"), 42L, config, true, Mode.ROLE));
        assertThrows(IllegalStateException.class, () -> service.advance(game.summary().gameId(), AdvanceCommand.NEXT_ACTION));
        var saved = service.snapshot(game.summary().gameId()).game();
        assertEquals("ERROR", saved.actions().getFirst().status());
        assertEquals(1, saved.knowledge().usages().getFirst().actionSequence());
        assertFalse(saved.knowledge().usages().getFirst().entryIds().isEmpty());
        var off = new GameService(mapper, knowledge, (settings, prompt) -> {
            assertFalse(prompt.contains("策略建议：")); return "{\"action\":\"PASS\"}";
        });
        var disabled = off.create(new GameService.CreateGameRequest(GameConfig.classicSeven(), Collections.nCopies(7, "LLM"), 42L, config, true, Mode.NONE));
        off.advance(disabled.summary().gameId(), AdvanceCommand.NEXT_ACTION);
        assertNull(off.observe(disabled.summary().gameId()).knowledge().snapshot());
        assertTrue(off.observe(disabled.summary().gameId()).knowledge().usages().getFirst().entryIds().isEmpty());
    }
    @Test void agentRefusesOtherRoleKnowledgeBeforeCallingModel() {
        var wolf = new Entry(UUID.randomUUID().toString(), "狼策略", Scope.WEREWOLF, List.of(GamePhase.DAY_VOTE), "狼策略", 50, true);
        var llm = new LlmAgent((settings, prompt) -> { fail("不应调用模型"); return ""; }, config, mapper);
        assertThrows(IllegalArgumentException.class, () -> llm.bindKnowledge(Role.VILLAGER, List.of(wolf), (c, e) -> { }));
    }
}
