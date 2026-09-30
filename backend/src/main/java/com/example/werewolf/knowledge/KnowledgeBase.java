package com.example.werewolf.knowledge;

import com.example.werewolf.game.GamePhase;
import com.example.werewolf.player.Role;
import java.util.*;

/** 静态策略数据；与本局事实、玩家记忆和游戏规则分别保存。 */
public final class KnowledgeBase {
    private KnowledgeBase() { }
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_CONTENT = 1200;
    public static final int MAX_BYTES = 512 * 1024;
    public static final Set<GamePhase> ACTION_PHASES = Set.of(GamePhase.NIGHT_WEREWOLF,
            GamePhase.NIGHT_SEER, GamePhase.NIGHT_WITCH, GamePhase.DAY_DISCUSSION, GamePhase.DAY_VOTE);
    public enum Scope { COMMON, WEREWOLF, VILLAGER, SEER, WITCH }
    public enum Mode { NONE, COMMON, ROLE }
    public record Entry(String id, String title, Scope scope, List<GamePhase> phases,
                        String content, int priority, boolean enabled) {
        public Entry { phases = phases == null ? null : List.copyOf(phases); }
    }
    public record Snapshot(int schemaVersion, String revision, List<Entry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
    }
    /** 条目正文存一次，逐行动记录编号；用户回放时通过冻结快照还原。 */
    public record Usage(int actionSequence, int day, GamePhase phase, int eventIndex,
                        String playerId, List<String> entryIds) {
        public Usage { entryIds = List.copyOf(entryIds); }
    }
    public record Run(Mode mode, Snapshot snapshot, List<Usage> usages) {
        public Run { usages = List.copyOf(usages); }
    }

    public static Entry normalize(Entry entry, String id) {
        require(entry != null && entry.scope() != null, "请选择知识所属角色");
        require(entry.title() != null && !entry.title().isBlank() && entry.title().strip().length() <= 80,
                "知识标题须为 1 至 80 字");
        require(entry.content() != null && !entry.content().isBlank() && entry.content().strip().length() <= MAX_CONTENT,
                "知识正文须为 1 至 1200 字");
        require(entry.priority() >= 0 && entry.priority() <= 100, "优先级须为 0 至 100");
        require(entry.phases() != null && !entry.phases().isEmpty() && entry.phases().size() <= 5
                && new HashSet<>(entry.phases()).size() == entry.phases().size()
                && entry.phases().stream().allMatch(p -> allowedPhases(entry.scope()).contains(p)), "知识适用阶段无效");
        require(id != null && id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "知识编号无效");
        return new Entry(id, entry.title().strip(), entry.scope(), entry.phases(), entry.content().strip(), entry.priority(), entry.enabled());
    }
    public static Set<GamePhase> allowedPhases(Scope scope) {
        Set<GamePhase> phases = new HashSet<>(List.of(GamePhase.DAY_DISCUSSION, GamePhase.DAY_VOTE));
        switch (scope) {
            case COMMON -> phases.addAll(ACTION_PHASES);
            case WEREWOLF -> phases.add(GamePhase.NIGHT_WEREWOLF);
            case SEER -> phases.add(GamePhase.NIGHT_SEER);
            case WITCH -> phases.add(GamePhase.NIGHT_WITCH);
            default -> { }
        }
        return phases;
    }
    /** 先按真实角色过滤；LlmAgent 本身只持有通用条目和自己的角色条目。 */
    public static List<Entry> forRole(Snapshot snapshot, Mode mode, Role role) {
        if (mode == Mode.NONE || snapshot == null) return List.of();
        return snapshot.entries().stream().filter(Entry::enabled)
                .filter(e -> e.scope() == Scope.COMMON || (mode == Mode.ROLE && e.scope().name().equals(role.name()))).toList();
    }
    /** 首版确定性筛选：阶段、优先级、角色与编号排序，并限制数量和注入字符数。 */
    public static List<Entry> select(List<Entry> permitted, GamePhase phase, Role role) {
        var sorted = permitted.stream().filter(Entry::enabled)
                .filter(e -> e.scope() == Scope.COMMON || e.scope().name().equals(role.name()))
                .filter(e -> e.phases().contains(phase))
                .sorted(Comparator.comparingInt(Entry::priority).reversed()
                        .thenComparing(e -> e.scope() == Scope.COMMON ? 1 : 0).thenComparing(Entry::id)).toList();
        List<Entry> selected = new ArrayList<>();
        int chars = 0;
        for (Entry entry : sorted) {
            int size = entry.id().length() + entry.title().length() + entry.content().length();
            if (chars + size > 2400) continue;
            selected.add(entry); chars += size;
            if (selected.size() == 4) break;
        }
        return List.copyOf(selected);
    }
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
