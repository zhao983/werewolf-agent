package com.example.werewolf.experiment;

import com.example.werewolf.experiment.ExperimentRecord.*;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.service.GameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 模拟两台电脑导出、导入和重启；仅使用临时目录，不修改真实实验文件。 */
class ExperimentImportTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String firstOwner = UUID.randomUUID().toString();
    private final String secondOwner = UUID.randomUUID().toString();

    private ExperimentRecord sample(boolean manual) {
        GameService games = new GameService(mapper);
        var game = games.create(new GameService.CreateGameRequest(GameConfig.classicSeven(),
                Collections.nCopies(7, "RULE"), 42L, null, manual));
        var snapshot = games.snapshot(game.summary().gameId());
        var g = snapshot.game();
        return new ExperimentRecord(1, ExperimentRecord.ENGINE_VERSION, g.gameId(), "导入测试", "SINGLE",
                manual ? "RUNNING" : "COMPLETED", g.startedAt(), g.startedAt(), snapshot.config(), snapshot.agentTypes(),
                g.seed(), 1, List.of(g), null);
    }

    @Test void crossComputerImportKeepsEventsRecalculatesSummaryAndSurvivesRestart() throws Exception {
        var original = sample(false);
        ObjectNode exported = mapper.valueToTree(original.view());
        exported.withObject("summary").put("wolfWins", 9999);
        exported.put("owner", firstOwner);
        exported.withObject("record").put("apiKey", "must-be-discarded");
        Path computerA = directory.resolve("a");
        String importedId;
        try (var unused = Files.newDirectoryStream(Files.createDirectories(computerA))) { /* 建立独立电脑目录。 */ }
        ExperimentService service = new ExperimentService(mapper, computerA.toString());
        try {
            service.recordGame(firstOwner, original.config(), original.agentTypes(), original.games().getFirst());
            String before = Files.readString(computerA.resolve(original.id() + ".json"));
            View imported = service.importRecord(secondOwner, mapper.writeValueAsBytes(exported));
            importedId = imported.record().id();
            assertNotEquals(original.id(), importedId);
            assertEquals("IMPORTED", imported.record().source());
            assertEquals(original.games(), imported.record().games());
            assertEquals(original.view().summary(), imported.summary());
            assertEquals(before, Files.readString(computerA.resolve(original.id() + ".json")));
            assertFalse(Files.readString(computerA.resolve(importedId + ".json")).contains("must-be-discarded"));
            assertThrows(ResponseStatusException.class, () -> service.get(firstOwner, importedId));
        } finally { service.close(); }
        // 用第二个物理目录恢复同一个本机存档，模拟应用重启与目录备份恢复。
        Path computerB = Files.createDirectories(directory.resolve("b"));
        Files.copy(computerA.resolve(importedId + ".json"), computerB.resolve(importedId + ".json"));
        ExperimentService restored = new ExperimentService(mapper, computerB.toString());
        try {
            assertEquals(original.games().getFirst(), restored.replay(secondOwner, importedId, original.games().getFirst().gameId()));
            assertEquals(1, restored.archives(secondOwner).size());
            assertTrue(restored.archives(firstOwner).isEmpty());
            assertThrows(ResponseStatusException.class, () -> restored.replay(firstOwner, importedId, original.id()));
        } finally { restored.close(); }
    }

    @Test void ongoingImportIsInterruptedAndNeverRunsAutomatically() throws Exception {
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            var original = sample(true);
            View imported = service.importRecord(secondOwner, mapper.writeValueAsBytes(original.view()));
            assertEquals("INTERRUPTED", imported.record().status());
            assertEquals(0, imported.summary().actionAttempts());
            assertEquals(0, imported.summary().completedGames());
            assertEquals(imported, service.get(secondOwner, imported.record().id()));
            assertThrows(IllegalArgumentException.class, () -> service.cancel(secondOwner, imported.record().id()));
        } finally { service.close(); }
    }

    @Test void observerNotesSurviveDiskRestartAndJsonTransfer() throws Exception {
        var original = sample(false);
        assertFalse(original.games().getFirst().observerNotes().isEmpty());
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        String id;
        try {
            View imported = service.importRecord(secondOwner, mapper.writeValueAsBytes(original.view()));
            id = imported.record().id();
            assertEquals(original.games().getFirst().observerNotes(), imported.record().games().getFirst().observerNotes());
            assertThrows(ResponseStatusException.class, () -> service.replay(firstOwner, id, original.id()));
        } finally { service.close(); }
        ExperimentService restored = new ExperimentService(mapper, directory.toString());
        try { assertEquals(original.games().getFirst().observerNotes(), restored.replay(secondOwner, id, original.id()).observerNotes()); }
        finally { restored.close(); }
    }

    @Test void missingLegacyObserverFieldIsCompatibleAndOriginalRecordCanBeBackfilledOnce() throws Exception {
        var original = sample(false);
        ObjectNode json = mapper.valueToTree(original);
        ((ObjectNode) json.path("games").get(0)).remove("observerNotes");
        var legacy = ExperimentImport.parse(mapper, mapper.writeValueAsBytes(json));
        assertNull(legacy.games().getFirst().observerNotes());
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            service.recordGame(firstOwner, original.config(), original.agentTypes(), legacy.games().getFirst());
            var notes = original.games().getFirst().observerNotes();
            assertThrows(ResponseStatusException.class, () -> service.backfillObserverNotes(secondOwner, original.id(), original.id(), notes));
            var bad = new com.example.werewolf.game.ObserverNote(1, com.example.werewolf.game.GamePhase.GAME_START, 0, "player999", "CLUE", "bad");
            assertThrows(IllegalArgumentException.class, () -> service.backfillObserverNotes(firstOwner, original.id(), original.id(), List.of(bad)));
            assertNull(service.replay(firstOwner, original.id(), original.id()).observerNotes());
            assertEquals(notes, service.backfillObserverNotes(firstOwner, original.id(), original.id(), notes).observerNotes());
            // 已保存的原始信息不能被之后的浏览器提交覆盖。
            assertEquals(notes, service.backfillObserverNotes(firstOwner, original.id(), original.id(), List.of()).observerNotes());
            View imported = service.importRecord(secondOwner, mapper.writeValueAsBytes(json));
            assertThrows(IllegalArgumentException.class, () -> service.backfillObserverNotes(secondOwner, imported.record().id(), original.id(), notes));
        } finally { service.close(); }
    }

    @Test void importedPrivateNotesRejectFutureEventsAndInvalidKinds() throws Exception {
        var original = sample(false);
        for (String field : List.of("eventIndex", "kind", "text")) {
            ObjectNode json = mapper.valueToTree(original);
            ObjectNode note = (ObjectNode) json.path("games").get(0).path("observerNotes").get(0);
            if (field.equals("eventIndex")) note.put(field, 99999);
            if (field.equals("kind")) note.put(field, "PROMPT");
            if (field.equals("text")) note.put(field, "x".repeat(1001));
            assertThrows(IllegalArgumentException.class, () -> ExperimentImport.parse(mapper, mapper.writeValueAsBytes(json)));
        }
    }

    @Test void malformedVersionRolesIdsMetricsAndOversizedFilesAreRejectedWithoutSaving() throws Exception {
        var original = sample(false);
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            for (String field : List.of("version", "id", "roles", "metric", "sequence", "fraction", "status", "duplicate")) {
                ObjectNode record = mapper.valueToTree(original);
                switch (field) {
                    case "version" -> record.put("schemaVersion", 2);
                    case "id" -> record.put("id", "../../escape");
                    case "roles" -> ((ObjectNode) record.path("games").get(0).path("seats").get(0)).put("role", "HUNTER");
                    case "metric" -> ((ObjectNode) record.path("games").get(0).path("actions").get(0).path("metrics")).put("totalTokens", -1);
                    case "sequence" -> ((ObjectNode) record.path("games").get(0).path("actions").get(0)).put("sequence", 100);
                    case "fraction" -> record.withObject("config").put("playerCount", 7.5);
                    case "status" -> ((ObjectNode) record.path("games").get(0)).put("result", "ONGOING");
                    case "duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) record.path("games")).add(record.path("games").get(0));
                }
                assertThrows(IllegalArgumentException.class, () -> service.importRecord(secondOwner, mapper.writeValueAsBytes(record)), field);
            }
            for (byte[] bytes : new byte[][]{"null".getBytes(), "{}".getBytes(), "{".getBytes(), new byte[ExperimentImport.MAX_BYTES + 1]})
                assertThrows(IllegalArgumentException.class, () -> service.importRecord(secondOwner, bytes));
            assertTrue(service.list(secondOwner).isEmpty());
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        } finally { service.close(); }
    }

    @Test void duplicateImportsAreIndependentAndBareRecordIsSupported() throws Exception {
        var bytes = mapper.writeValueAsBytes(sample(false));
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try {
            View one = service.importRecord(secondOwner, bytes);
            View two = service.importRecord(secondOwner, bytes);
            assertNotEquals(one.record().id(), two.record().id());
            assertEquals(2, service.archives(secondOwner).size());
            assertEquals(one.record().games(), two.record().games());
        } finally { service.close(); }
    }

    @Test void restartStillShowsSavedRecordWhenInterruptedStatusCannotBeWritten() throws Exception {
        var original = sample(true);
        ExperimentService service = new ExperimentService(mapper, directory.toString());
        try { service.recordGame(firstOwner, original.config(), original.agentTypes(), original.games().getFirst()); }
        finally { service.close(); }
        ObjectMapper readOnly = new ObjectMapper() {
            @Override public void writeValue(java.io.File file, Object value) throws java.io.IOException {
                throw new java.io.IOException("模拟磁盘只读");
            }
        };
        ExperimentService restored = new ExperimentService(readOnly, directory.toString());
        try {
            assertEquals(1, restored.list(firstOwner).size());
            assertEquals("INTERRUPTED", restored.get(firstOwner, original.id()).record().status());
            assertEquals(original.games().getFirst(), restored.replay(firstOwner, original.id(), original.id()));
            // 失败的状态写入不能破坏先前成功保存的文件。
            assertEquals("RUNNING", mapper.readTree(directory.resolve(original.id() + ".json").toFile()).path("record").path("status").asText());
        } finally { restored.close(); }
    }

    @Test void defaultDirectoryUsesBackendApplicationHomeAndAbsoluteOverrideIsPreserved() {
        Path classes = directory.resolve("project/backend/target/classes");
        assertEquals(directory.resolve("project/backend"), ExperimentStorage.applicationHome(classes));
        assertEquals(directory.resolve("deploy"), ExperimentStorage.applicationHome(directory.resolve("deploy/app.jar")));
        assertEquals(directory.normalize(), ExperimentStorage.resolve(directory.toString()));
        assertEquals(Path.of("target/classes").toAbsolutePath().getParent().getParent().resolve("data/experiments"), ExperimentStorage.resolve(""));
    }
}
