package com.example.werewolf.experiment;

import com.example.werewolf.ai.LlmConfig;
import com.example.werewolf.experiment.ExperimentRecord.*;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.service.GameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证同种子复现、统计分母、记录恢复及拒绝自动批量调用模型。 */
class ExperimentServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String owner = "9b27f71f-fdc0-4b9c-8913-14226143fc68";

    private View completed(ExperimentService service, String id) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        View view;
        do {
            view = service.get(owner, id);
            if (!view.record().status().equals("RUNNING")) return view;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("批量实验未在限定时间内完成");
        return null;
    }

    @Test
    void sameSeedsProduceSameRolesActionsAndResultsAcrossRestart() throws Exception {
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            var config = new GameConfig(9, 2, 4, 1, 2);
            var types = List.of("RANDOM", "RULE", "RANDOM", "RULE", "RANDOM", "RULE", "RANDOM", "RULE", "RANDOM");
            var request = new ExperimentService.BatchRequest("混合基线", config, types, -3L, 12);
            View first = completed(service, service.start(owner, request).record().id());
            View second = completed(service, service.start(owner, request).record().id());
            assertEquals("COMPLETED", first.record().status());
            assertEquals(12, first.summary().completedGames());
            assertEquals(12, first.summary().wolfWins() + first.summary().goodWins());
            assertEquals(0, first.summary().apiCalls());
            assertEquals(0, first.summary().invalidActions());
            assertEquals(0, first.summary().failedActions());
            assertEquals(first.summary().validActions(), first.summary().actionAttempts());
            for (int i = 0; i < first.record().games().size(); i++) {
                var a = first.record().games().get(i); var b = second.record().games().get(i);
                assertEquals(-3 + i, a.seed());
                assertEquals(a.seats(), b.seats());
                assertEquals(a.events(), b.events());
                assertEquals(a.result(), b.result());
                assertEquals(a.actions().stream().map(x -> List.of(x.day(), x.phase(), x.playerId(), x.action(),
                        Objects.toString(x.targetPlayerId(), ""), x.status())).toList(),
                        b.actions().stream().map(x -> List.of(x.day(), x.phase(), x.playerId(), x.action(),
                        Objects.toString(x.targetPlayerId(), ""), x.status())).toList());
            }
            service.close();
            ExperimentService restored = new ExperimentService(mapper, directory.toString());
            try {
                assertEquals(2, restored.list(owner).size());
                assertEquals(first.record(), restored.get(owner, first.record().id()).record());
                assertEquals(List.of(), restored.list("different-owner"));
                assertThrows(ResponseStatusException.class, () -> restored.get("different-owner", first.record().id()));
            } finally { restored.close(); }
        } finally { service.close(); }
    }

    @Test
    void manualRecordSurvivesRestartWithoutKeyAndIsNotCountedAsFinished() throws Exception {
        GameService games = new GameService(mapper);
        var types = Collections.nCopies(7, "LLM");
        var llm = new LlmConfig("http://127.0.0.1:12345/v1", "secret-not-for-disk", "mock-model", 0.7, 100);
        var game = games.create(new GameService.CreateGameRequest(GameConfig.classicSeven(), types, 42L, llm, true));
        var data = games.snapshot(game.summary().gameId());
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        service.recordGame(owner, data.config(), data.agentTypes(), data.game());
        service.close();
        String stored = Files.readString(directory.resolve(game.summary().gameId() + ".json"));
        assertFalse(stored.contains("secret-not-for-disk"));
        assertFalse(stored.contains("baseUrl"));
        assertFalse(stored.contains("apiKey"));
        ExperimentService restored = new ExperimentService(mapper, directory.toString());
        try {
            View view = restored.get(owner, game.summary().gameId());
            assertEquals("INTERRUPTED", view.record().status());
            assertEquals("mock-model", view.record().games().getFirst().model().model());
            assertEquals(0, view.summary().completedGames());
            assertEquals(0, view.summary().averageDays());
            assertFalse(mapper.writeValueAsString(view).contains(owner));
        } finally { restored.close(); }
    }

    @Test
    void invalidBatchConfigurationDoesNotStartAnExperiment() {
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            assertThrows(IllegalArgumentException.class, () -> service.start(owner,
                    new ExperimentService.BatchRequest("AI", null, Collections.nCopies(7, "LLM"), 1, 2)));
            assertThrows(IllegalArgumentException.class, () -> service.start(owner,
                    new ExperimentService.BatchRequest("空座位", null, Arrays.asList(null, "RULE", "RULE", "RULE", "RULE", "RULE", "RULE"), 1, 2)));
            assertThrows(IllegalArgumentException.class, () -> new GameConfig(4, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, 5));
            assertThrows(IllegalArgumentException.class, () -> service.start(owner,
                    new ExperimentService.BatchRequest("溢出", null, null, Long.MAX_VALUE, 2)));
            assertThrows(IllegalArgumentException.class, () -> service.start(owner,
                    new ExperimentService.BatchRequest("过大", null, null, 1, 201)));
            assertTrue(service.list(owner).isEmpty());
        } finally { service.close(); }
    }

    @Test
    void csvEscapesFormulaQuotesAndSeparatesPerGameAndPerActionRows() throws Exception {
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            View view = completed(service, service.start(owner,
                    new ExperimentService.BatchRequest("=SUM(1,2)\n\"测试\"", null, null, 4, 2)).record().id());
            String csv = ExperimentCsv.games(view.record());
            assertTrue(csv.startsWith("\uFEFF"));
            assertTrue(csv.contains("\"'=SUM(1,2)\n\"\"测试\"\"\""));
            String actions = ExperimentCsv.actions(view.record());
            assertEquals(view.summary().actionAttempts() + 1, actions.lines().count());
            assertTrue(actions.contains("total_tokens_reported"));
        } finally { service.close(); }
    }

    @Test
    void cancellationKeepsFinishedGamesAndRejectsConcurrentBatches() throws Exception {
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            String id;
            // 在 worker 写入第一局前提出停止，稳定验证当前局边界和队列限制。
            synchronized (service) {
                var request = new ExperimentService.BatchRequest("停止测试", null, null, 100, 200);
                id = service.start(owner, request).record().id();
                assertThrows(IllegalArgumentException.class, () -> service.start(owner, request));
                service.cancel(owner, id);
            }
            View view = completed(service, id);
            assertEquals("CANCELLED", view.record().status());
            assertTrue(view.summary().completedGames() <= 1);
            assertEquals(200, view.record().requestedGames());
            assertEquals(view.record().games().size(), view.summary().completedGames());
        } finally { service.close(); }
    }
}
