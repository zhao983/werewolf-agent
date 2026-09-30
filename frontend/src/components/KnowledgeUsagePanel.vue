<script setup lang="ts">
import { computed } from "vue";
import { phaseName } from "../types";
import { knowledgeModeName, type KnowledgeRun } from "../knowledge";
const props = defineProps<{
  knowledge?: KnowledgeRun | null;
  cursor: number;
  live?: boolean;
  selectedPlayerId: string;
  eventCount?: number;
}>();
// 原始快照仅供用户查看，回放进度过滤不改变后端 Agent 的角色隔离。
const visible = computed(() =>
  (props.knowledge?.usages ?? [])
    .filter(
      (u) =>
        (props.live ||
          u.eventIndex <= props.cursor ||
          // 失败尝试没有新增公共事件，在存档最后位置展示其知识使用记录。
          (u.eventIndex === props.eventCount &&
            props.cursor === (props.eventCount ?? 0) - 1)) &&
        (props.selectedPlayerId === "all" ||
          props.selectedPlayerId === u.playerId),
    )
    .slice()
    .reverse(),
);
const entries = computed(
  () => new Map(props.knowledge?.snapshot?.entries.map((e) => [e.id, e]) ?? []),
);
</script>
<template>
  <div v-if="knowledge" class="knowledge-usage">
    <h3>本局策略知识</h3>
    <p class="observer-description">
      {{ knowledgeModeName[knowledge.mode]
      }}<span v-if="knowledge.snapshot">
        · 快照 {{ knowledge.snapshot.revision.slice(0, 12) }}</span
      >。仅向行动者提供筛选后的建议。
    </p>
    <p v-if="knowledge.mode === 'NONE'" class="observer-description">
      本局未启用本地策略知识。
    </p>
    <p v-else-if="!visible.length" class="observer-description">
      当前回放位置暂无该玩家的知识使用记录。
    </p>
    <div v-else class="knowledge-usage-list">
      <details
        v-for="usage in visible"
        :key="usage.actionSequence"
        class="knowledge-usage-item"
      >
        <summary>
          {{ usage.playerId }} · 第 {{ usage.day }} 天 ·
          {{ phaseName[usage.phase] }} · {{ usage.entryIds.length }} 条建议
          <small>行动 #{{ usage.actionSequence }}</small>
        </summary>
        <p v-if="!usage.entryIds.length">本次行动没有匹配的已启用知识。</p>
        <article v-for="id in usage.entryIds" :key="id">
          <strong>{{ entries.get(id)?.title ?? "条目不可用" }}</strong>
          <p>{{ entries.get(id)?.content ?? "此记录未保存知识正文。" }}</p>
        </article>
      </details>
    </div>
  </div>
</template>
<style scoped>
.knowledge-usage {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid #ffffff16;
}
h3 {
  font-size: 14px;
}
.knowledge-usage-list {
  max-height: 350px;
  overflow-y: auto;
}
.knowledge-usage-item {
  background: #141e29;
  padding: 12px;
  margin: 8px 0;
  border-radius: 6px;
}
summary {
  font-size: 11px;
  cursor: pointer;
  line-height: 1.8;
  color: #c5aa76;
}
summary small {
  color: #93a0a9;
  margin-left: 8px;
}
article {
  margin-top: 14px;
}
article strong {
  font-size: 12px;
}
p {
  font-size: 12px;
  color: #b9c3c5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
}
</style>
