package com.example.werewolf.knowledge;

import com.example.werewolf.game.GamePhase;
import com.example.werewolf.knowledge.KnowledgeBase.*;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 检查持久化、导入约束、角色筛选及注入预算，全部使用临时目录。 */
class KnowledgeServiceTest {
    @TempDir Path directory;
    ObjectMapper mapper = new ObjectMapper();
    Entry entry(Scope scope, String content) {
        return new Entry(UUID.randomUUID().toString(), "建议", scope, List.of(GamePhase.DAY_VOTE), content, 80, true);
    }
    @Test void persistsEditsDeletesAndDoesNotMutateOldSnapshot() throws Exception {
        var service = new KnowledgeService(mapper, directory.toString());
        var old = service.get();
        var added = service.add(entry(Scope.SEER, "原始建议"));
        var e = added.entries().getLast();
        var updated = service.update(e.id(), entry(Scope.SEER, "新建议"), added.revision());
        assertThrows(IllegalArgumentException.class, () -> service.delete(e.id(), added.revision()));
        assertEquals("原始建议", added.entries().getLast().content());
        assertEquals(old.entries().size() + 1, updated.entries().size());
        assertEquals(updated, new KnowledgeService(mapper, directory.toString()).get());
        var deleted = service.delete(e.id(), updated.revision());
        assertEquals(old.entries().size(), deleted.entries().size());
        assertEquals(deleted, new KnowledgeService(mapper, directory.toString()).get());
    }
    @Test void importAppendsNewIdsAndRejectsInvalidFileWithoutChanges() throws Exception {
        var service = new KnowledgeService(mapper, directory.toString());
        var old = service.get();
        var appended = service.importFile(mapper.writeValueAsBytes(old));
        assertEquals(old.entries().size() * 2, appended.entries().size());
        assertEquals(appended.entries().size(), appended.entries().stream().map(Entry::id).distinct().count());
        assertThrows(IllegalArgumentException.class, () -> service.importFile("{\"schemaVersion\":2,\"entries\":[]}".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> service.importFile("{\"schemaVersion\":1,\"schemaVersion\":1,\"entries\":[]}".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> service.importFile("{\"schemaVersion\":1,\"entries\":[null]}".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> service.importFile(new byte[KnowledgeBase.MAX_BYTES + 1]));
        assertEquals(appended, service.get());
    }
    @Test void enforcesRolePhaseEnabledAndSizeBudget() {
        List<Entry> all = new ArrayList<>(List.of(entry(Scope.COMMON, "通用"), entry(Scope.WEREWOLF, "狼私有"), entry(Scope.SEER, "预言家私有")));
        Entry disabled = entry(Scope.SEER, "已关闭");
        all.add(new Entry(disabled.id(), disabled.title(), disabled.scope(), disabled.phases(), disabled.content(), 100, false));
        var snapshot = KnowledgeService.snapshot(all);
        var permitted = KnowledgeBase.forRole(snapshot, Mode.ROLE, Role.SEER);
        assertEquals(2, permitted.size());
        assertFalse(permitted.stream().anyMatch(e -> e.content().contains("狼私有")));
        assertEquals(1, KnowledgeBase.forRole(snapshot, Mode.COMMON, Role.SEER).size());
        assertTrue(KnowledgeBase.forRole(snapshot, Mode.NONE, Role.SEER).isEmpty());
        assertTrue(KnowledgeBase.select(permitted, GamePhase.NIGHT_SEER, Role.SEER).isEmpty());
        assertEquals(2, KnowledgeBase.select(all, GamePhase.DAY_VOTE, Role.SEER).size());
        var many = java.util.stream.IntStream.range(0, 10).mapToObj(i -> entry(Scope.SEER, "策".repeat(1000))).toList();
        var chosen = KnowledgeBase.select(many, GamePhase.DAY_VOTE, Role.SEER);
        assertEquals(2, chosen.size());
        assertTrue(chosen.stream().mapToInt(e -> e.id().length() + e.title().length() + e.content().length()).sum() <= 2400);
        assertEquals(4, KnowledgeBase.select(java.util.stream.IntStream.range(0, 10).mapToObj(i -> entry(Scope.SEER, "短建议")).toList(), GamePhase.DAY_VOTE, Role.SEER).size());
    }
    @Test void invalidFieldsOrBrokenExistingFileAreNeverSilentlyOverwritten() throws Exception {
        var service = new KnowledgeService(mapper, directory.toString());
        var old = service.get();
        assertThrows(IllegalArgumentException.class, () -> service.add(entry(Scope.VILLAGER, "策".repeat(1201))));
        assertThrows(IllegalArgumentException.class, () -> service.add(new Entry(null, "标题", Scope.VILLAGER, List.of(GamePhase.NIGHT_WEREWOLF), "正文", 50, true)));
        assertEquals(old, service.get());
        Files.writeString(directory.resolve("knowledge.json"), "损坏内容");
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeService(mapper, directory.toString()));
        assertEquals("损坏内容", Files.readString(directory.resolve("knowledge.json")));
    }
}
