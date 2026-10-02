package com.example.werewolf.knowledge;

import com.example.werewolf.experiment.ExperimentStorage;
import com.example.werewolf.game.GamePhase;
import com.example.werewolf.knowledge.KnowledgeBase.*;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 全机共享的角色策略库；写入成功后才更新内存，避免磁盘失败造成假保存。 */
@Service
public class KnowledgeService {
    private final ObjectMapper mapper;
    private final Path file;
    private Snapshot current;
    public KnowledgeService(ObjectMapper mapper, @Value("${werewolf.knowledge.directory:}") String directory) {
        this.mapper = mapper;
        Path home = ExperimentStorage.resolve(directory == null || directory.isBlank() ? "data/knowledge" : directory);
        file = home.resolve("knowledge.json");
        try {
            Files.createDirectories(home);
            if (Files.exists(file)) {
                if (Files.size(file) > KnowledgeBase.MAX_BYTES) throw new IllegalArgumentException("知识库文件过大");
                current = snapshot(parse(Files.readAllBytes(file)));
            } else {
                current = snapshot(defaults());
                save(current);
            }
        } catch (IOException e) { throw new UncheckedIOException("无法读取本地知识库", e); }
        // 无效已有文件会明确阻止加载，绝不静默覆盖用户编写的知识。
    }
    public synchronized Snapshot get() { return current; }
    public String directory() { return file.getParent().toString(); }
    public synchronized Snapshot add(Entry entry) {
        var next = new ArrayList<>(current.entries());
        next.add(KnowledgeBase.normalize(entry, UUID.randomUUID().toString()));
        return commit(next);
    }
    public synchronized Snapshot update(String id, Entry entry, String revision) {
        checkRevision(revision);
        var next = new ArrayList<>(current.entries());
        int index = index(id); next.set(index, KnowledgeBase.normalize(entry, id));
        return commit(next);
    }
    public synchronized Snapshot delete(String id, String revision) {
        checkRevision(revision);
        var next = new ArrayList<>(current.entries()); next.remove(index(id));
        return commit(next);
    }
    /** 导入追加新编号，保留本机内容；不采纳外部路径、版本哈希或同名编号。 */
    public synchronized Snapshot importFile(byte[] bytes) {
        var next = new ArrayList<>(current.entries());
        for (Entry entry : parse(bytes)) next.add(KnowledgeBase.normalize(entry, UUID.randomUUID().toString()));
        return commit(next);
    }
    public List<Entry> parse(byte[] bytes) {
        KnowledgeBase.require(bytes.length > 0 && bytes.length <= KnowledgeBase.MAX_BYTES, "知识库 JSON 须非空且不超过 512 KB");
        try {
            ObjectMapper reader = mapper.copy();
            reader.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(12).maxStringLength(5000).maxNumberLength(10).build());
            reader.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            reader.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
            reader.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            reader.configure(MapperFeature.ALLOW_COERCION_OF_SCALARS, false);
            JsonNode root = reader.readTree(bytes);
            KnowledgeBase.require(root != null && root.isObject() && root.path("schemaVersion").isInt()
                    && root.path("schemaVersion").intValue() == 1 && root.path("entries").isArray(), "知识库版本或格式无效");
            KnowledgeBase.require(root.path("entries").size() <= KnowledgeBase.MAX_ENTRIES, "知识条目不能超过 200 条");
            List<Entry> entries = new ArrayList<>(); Set<String> ids = new HashSet<>();
            for (JsonNode node : root.path("entries")) {
                Entry e = reader.treeToValue(node, Entry.class);
                e = KnowledgeBase.normalize(e, e == null ? null : e.id());
                KnowledgeBase.require(ids.add(e.id()), "知识库包含重复编号"); entries.add(e);
            }
            return List.copyOf(entries);
        } catch (IOException e) { throw new IllegalArgumentException("知识库 JSON 格式或字段无效"); }
    }
    private int index(String id) {
        for (int i = 0; i < current.entries().size(); i++) if (current.entries().get(i).id().equals(id)) return i;
        throw new NoSuchElementException("知识条目不存在");
    }
    private void checkRevision(String revision) {
        KnowledgeBase.require(current.revision().equals(revision), "知识库已在其他页面修改，请刷新后重新编辑");
    }
    private Snapshot commit(List<Entry> entries) {
        KnowledgeBase.require(entries.size() <= KnowledgeBase.MAX_ENTRIES, "知识条目不能超过 200 条");
        Snapshot next = snapshot(entries); save(next); current = next; return next;
    }
    public static Snapshot snapshot(List<Entry> entries) {
        try {
            // 对规范化条目计算稳定摘要，便于对照实验和导出文件校验。
            String json = new ObjectMapper().writeValueAsString(entries);
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(1, revision, entries);
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("无法生成知识库版本", e); }
    }
    private void save(Snapshot value) {
        Path temporary = null;
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            KnowledgeBase.require(bytes.length <= KnowledgeBase.MAX_BYTES, "知识库内容总量不能超过 512 KB");
            temporary = Files.createTempFile(file.getParent(), "knowledge-", ".tmp");
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException("知识库保存失败", e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private static List<Entry> defaults() {
        var day = List.of(GamePhase.DAY_DISCUSSION, GamePhase.DAY_VOTE);
        return List.of(
                example("区分事实与身份声明", Scope.COMMON, day, "玩家公开声明的身份和查验结果属于待验证信息。结合历次发言、矛盾与投票关系判断，不能把猜测当作事实。"),
                example("根据合法信息选择袭击目标", Scope.WEREWOLF, List.of(GamePhase.NIGHT_WEREWOLF), "结合公开发言选择可能提供有效信息的对手，参考自己已知的狼队友信息；不要臆造其他玩家的真实身份。"),
                example("维护有依据的公开立场", Scope.WEREWOLF, day, "保持发言与之前公开立场连贯，结合票型考虑阵营利益。公开发言时注意隐藏自己的私有策略和队友信息，不要无意中泄露狼队线索。"),
                example("综合公开证据投票", Scope.VILLAGER, day, "优先分析前后矛盾、身份声明与投票理由。比较不同玩家的证据，给出明确但保留不确定性的判断。"),
                example("扩大查验的信息收益", Scope.SEER, List.of(GamePhase.NIGHT_SEER), "在合法目标中优先考虑未查验且争议较大的存活玩家。仅将自己实际收到的查验结果作为确定阵营信息。"),
                example("有条件地公开查验结果", Scope.SEER, day, "结合局势、存活人数与实际查验结果决定何时公布身份或查验信息。不要将好人阵营结果描述为具体神职身份。"),
                example("谨慎使用有限药剂", Scope.WITCH, List.of(GamePhase.NIGHT_WITCH), "根据本夜剩余药剂以及实际可救、可毒的对象，结合夜间信息和公开证据权衡救人与用毒；证据不足时可以保留药剂，本夜不用药。")
        );
    }
    private static Entry example(String title, Scope scope, List<GamePhase> phases, String content) {
        return new Entry(UUID.randomUUID().toString(), title, scope, phases, content, 50, true);
    }
}
