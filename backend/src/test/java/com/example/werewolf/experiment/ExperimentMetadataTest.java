package com.example.werewolf.experiment;

import com.example.werewolf.game.GameConfig;
import com.example.werewolf.service.GameService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 标签更新必须持久化、保留推进状态、兼容旧 JSON，并遵守原来的浏览器访问边界。 */
class ExperimentMetadataTest {
    @TempDir Path directory;
    ObjectMapper mapper = new ObjectMapper();
    String owner = "9b27f71f-fdc0-4b9c-8913-14226143fc68";
    @Test void labelsSurviveAdvanceRestartAndImport() throws Exception {
        var games = new GameService(mapper);
        var game = games.create(new GameService.CreateGameRequest(GameConfig.classicSeven(), null, 42L, null, true));
        String id = game.summary().gameId();
        var service = new ExperimentService(mapper, directory.toString());
        try {
            var data = games.snapshot(id); service.recordGame(owner, data.config(), data.agentTypes(), data.game());
            var before = service.get(owner, id);
            var edited = service.updateMetadata(owner, id, new ExperimentMetadata("命名实验", "角色知识组", "控制条件"), before.metadataRevision());
            assertThrows(IllegalArgumentException.class, () -> service.updateMetadata(owner, id, new ExperimentMetadata("旧修改", "", ""), before.metadataRevision()));
            games.advance(id, game.nextCommand());
            var later = games.snapshot(id); service.recordGame(owner, later.config(), later.agentTypes(), later.game());
            assertEquals(edited.metadataRevision(), service.get(owner, id).metadataRevision());
            assertEquals("命名实验", service.get(owner, id).record().name());
            assertEquals("角色知识组", service.get(owner, id).record().group());
            assertEquals(1, service.get(owner, id).record().games().getFirst().actions().size());
            var imported = service.importRecord(owner, mapper.writeValueAsBytes(service.get(owner, id)));
            assertEquals("角色知识组", imported.record().group()); assertEquals("控制条件", imported.record().notes());
        } finally { service.close(); }
        var restarted = new ExperimentService(mapper, directory.toString());
        try { assertEquals("命名实验", restarted.get(owner, id).record().name()); assertEquals("控制条件", restarted.get(owner, id).record().notes()); }
        finally { restarted.close(); }
    }
    @Test void ownerBoundaryLegacyJsonAndTextLimitsArePreserved() throws Exception {
        var games = new GameService(mapper);
        var game = games.create(new GameService.CreateGameRequest(GameConfig.classicSeven(), null, 42L, null, false));
        var service = new ExperimentService(mapper, directory.toString());
        try {
            var data = games.snapshot(game.summary().gameId()); service.recordGame(owner, data.config(), data.agentTypes(), data.game());
            var before = service.get(owner, game.summary().gameId());
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.updateMetadata("other", before.record().id(), new ExperimentMetadata("名称", "组", ""), before.metadataRevision()));
            assertTrue(service.analysisOptions("other").groups().isEmpty());
            assertEquals(0, service.compare("other", new ExperimentAnalysis.Request(null, null, null, null)).groupA().metrics().matchedGames());
            assertThrows(IllegalArgumentException.class, () -> service.updateMetadata(owner, before.record().id(), new ExperimentMetadata("名称", "组".repeat(61), ""), before.metadataRevision()));
            var legacy = mapper.valueToTree(before);
            ((com.fasterxml.jackson.databind.node.ObjectNode) legacy.path("record")).remove(List.of("group", "notes"));
            var parsed = ExperimentImport.parse(mapper, mapper.writeValueAsBytes(legacy));
            assertEquals("", parsed.group()); assertEquals("", parsed.notes());
            var edited = service.updateMetadata(owner, before.record().id(), new ExperimentMetadata("=formula", "标签", "@memo"), before.metadataRevision());
            String csv = ExperimentCsv.games(edited.record()); assertTrue(csv.contains("'@memo")); assertTrue(csv.contains("'=formula"));
        } finally { service.close(); }
    }
}
