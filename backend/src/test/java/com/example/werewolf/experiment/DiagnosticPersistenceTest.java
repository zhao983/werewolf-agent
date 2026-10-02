package com.example.werewolf.experiment;

import com.example.werewolf.agent.*;
import com.example.werewolf.game.*;
import com.example.werewolf.experiment.ExperimentRecord.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 诊断随本地保存、重启、导入、CSV 保留；旧记录与恶意字段均有明确处理。 */
class DiagnosticPersistenceTest {
    @TempDir Path directory;
    ObjectMapper mapper = new ObjectMapper();
    ExperimentRecord sample() {
        Agent agent = new Agent() {
            public AgentResponse act(AgentContext c) { return AgentResponse.action(ActionType.PASS, null); }
            public AgentMetrics lastMetrics() { return new AgentMetrics(1, 0, 0, 1, 20, 10, 30, 10); }
            public List<DecisionDiagnostic> lastDiagnostics() { return List.of(new DecisionDiagnostic(1, DecisionDiagnostic.Code.SUCCESS, 200,
                    DecisionDiagnostic.FinishReason.TOOL_CALLS, 1, false, 10, null,
                    List.of(DecisionDiagnostic.LocalRepair.REASONING_TRIMMED), 10L, 5L)); }
        };
        var session = new GameEngine(new Random(42), s -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, agent));
        session.advance(AdvanceCommand.NEXT_ACTION);
        var types = Collections.nCopies(7, "LLM");
        var model = new ModelSpec("mock", .7, 1000, com.example.werewolf.ai.DecisionMode.TOOLS, 120, com.example.werewolf.ai.TokenLimitParameter.MAX_TOKENS,
                false, com.example.werewolf.ai.ToolChoiceMode.AUTO);
        var game = GameSnapshot.capture(session.getState(), 42, types, java.time.Instant.now().toString(), model, false);
        return new ExperimentRecord(1, ExperimentRecord.ENGINE_VERSION, game.gameId(), "诊断回归", "SINGLE", "RUNNING",
                game.startedAt(), game.startedAt(), GameConfig.classicSeven(), types, 42, 1, List.of(game), null);
    }
    @Test void persistsAcrossRestartAndImportAndSupportsLegacyActions() throws Exception {
        var sample = sample(); var owner = UUID.randomUUID().toString();
        var service = new ExperimentService(mapper, directory.toString());
        try { service.recordGame(owner, sample.config(), sample.agentTypes(), sample.games().getFirst()); }
        finally { service.close(); }
        var restored = new ExperimentService(mapper, directory.toString());
        try {
            var view = restored.get(owner, sample.id());
            assertEquals(sample.games().getFirst().actions(), view.record().games().getFirst().actions());
            var imported = restored.importRecord(owner, mapper.writeValueAsBytes(view));
            assertEquals(sample.games().getFirst().actions(), imported.record().games().getFirst().actions());
            assertEquals(sample.games().getFirst().model(), imported.record().games().getFirst().model());
            assertTrue(ExperimentCsv.actions(imported.record()).contains("SUCCESS"));
            var legacy = mapper.valueToTree(view.record());
            ((ObjectNode) legacy.path("games").get(0).path("actions").get(0)).remove("diagnostics");
            assertTrue(ExperimentImport.parse(mapper, mapper.writeValueAsBytes(legacy)).games().getFirst().actions().getFirst().diagnostics().isEmpty());
        } finally { restored.close(); }
    }
    @Test void rejectsUnknownCodesNegativeTimingAndMismatchedCalls() throws Exception {
        for (String field : List.of("code", "requestMillis", "attempt")) {
            var tree = mapper.valueToTree(sample());
            var d = (ObjectNode) tree.path("games").get(0).path("actions").get(0).path("diagnostics").get(0);
            if (field.equals("code")) d.put(field, "private-message-not-allowed"); else d.put(field, -1);
            assertThrows(IllegalArgumentException.class, () -> ExperimentImport.parse(mapper, mapper.writeValueAsBytes(tree)));
        }
        var tree = mapper.valueToTree(sample());
        ((ObjectNode) tree.path("games").get(0).path("actions").get(0).path("metrics")).put("apiCalls", 2);
        assertThrows(IllegalArgumentException.class, () -> ExperimentImport.parse(mapper, mapper.writeValueAsBytes(tree)));
    }
    @Test void rejectsUnsafeSubtypesAndInvalidNewUsageFields() throws Exception {
        for (String field : List.of("issue", "repairs", "completionTokens", "reasoningTokens")) {
            var tree = mapper.valueToTree(sample());
            var d = (ObjectNode) tree.path("games").get(0).path("actions").get(0).path("diagnostics").get(0);
            if (field.equals("issue")) d.put(field, "untrusted-provider-text");
            else if (field.equals("repairs")) d.putArray(field).add("untrusted-provider-text");
            else if (field.equals("reasoningTokens")) d.put(field, 11);
            else d.put(field, -1);
            assertThrows(IllegalArgumentException.class, () -> ExperimentImport.parse(mapper, mapper.writeValueAsBytes(tree)));
        }
    }
}
