<script setup lang="ts">
import { computed } from "vue";
import { phaseName, type ObserverNote } from "../types";
import { describeNote } from "../observer";
import KnowledgeUsagePanel from "./KnowledgeUsagePanel.vue";
import type { KnowledgeRun } from "../knowledge";
import type { ExperimentAction } from "../experiments";
import DiagnosticDetails from "./DiagnosticDetails.vue";

const props = defineProps<{
  notes: ObserverNote[] | null;
  players: string[];
  cursor: number;
  live?: boolean;
  notice?: string;
  knowledge?: KnowledgeRun | null;
  eventCount?: number;
  actions?: ExperimentAction[];
}>();
const selectedPlayerId = defineModel<string>("selectedPlayerId", {
  default: "all",
});
// 游标之前的私有内容只在用户面板展示；不会被提交到 Agent 决策接口。
const visible = computed(() =>
  (props.notes ?? [])
    .filter(
      (note) =>
        (props.live || note.eventIndex <= props.cursor) &&
        (selectedPlayerId.value === "all" ||
          note.playerId === selectedPlayerId.value),
    )
    .slice()
    .reverse(),
);
// 诊断展示当前对局/完整存档的最新尝试，独立于回放游标；不会作为模型输入。
const attempts = computed(() =>
  (props.actions ?? [])
    .filter(
      (a) =>
        a.metrics.apiCalls > 0 &&
        (selectedPlayerId.value === "all" ||
          a.playerId === selectedPlayerId.value),
    )
    .slice(-20)
    .reverse(),
);
</script>
<template>
  <section class="panel observer-panel">
    <div class="section-head">
      <div>
        <span class="section-index">OBSERVER ONLY</span>
        <h2>观战私有信息</h2>
      </div>
      <select v-model="selectedPlayerId" aria-label="筛选玩家的私有记录">
        <option value="all">全部玩家</option>
        <option v-for="id in players" :key="id" :value="id">{{ id }}</option>
      </select>
    </div>
    <p class="observer-description">
      这里展示玩家收到的私有线索和每步决策摘要，仅供用户观战；其他 Agent
      不会读取此面板。
    </p>
    <p v-if="notice" class="observer-description observer-notice">
      {{ notice }}
    </p>
    <div v-if="notes === null" class="observer-empty">观战信息尚未载入。</div>
    <div v-else-if="!visible.length" class="observer-empty">
      当前回放位置暂无该玩家的私有记录，推进时间线可查看后续内容。
    </div>
    <div v-else class="observer-list">
      <div
        v-for="(note, index) in visible"
        :key="`${note.playerId}-${note.eventIndex}-${index}`"
        class="observer-entry"
      >
        <div class="observer-entry-meta">
          <span
            >{{ note.playerId }} · 第 {{ note.day }} 天 ·
            {{ phaseName[note.phase] ?? note.phase }}</span
          ><span>{{ note.kind === "CLUE" ? "私有线索" : "决策摘要" }}</span>
        </div>
        <p>{{ describeNote(note) }}</p>
      </div>
    </div>
    <KnowledgeUsagePanel
      :knowledge="knowledge"
      :cursor="cursor"
      :live="live"
      :selected-player-id="selectedPlayerId"
      :event-count="eventCount"
    />
    <details v-if="attempts.length" class="model-diagnostics">
      <summary>
        模型调用诊断（完整记录最近 {{ attempts.length }} 次行动尝试）
      </summary>
      <p class="observer-description">
        诊断包含失败与纠错调用，与回放位置无关。更多逐行动记录可在“实验记录”查看。旧记录无法补回原始错误原因。
      </p>
      <div v-for="a in attempts" :key="a.sequence" class="observer-entry">
        <div class="observer-entry-meta">
          #{{ a.sequence }} · {{ a.playerId }} · 第 {{ a.day }} 天 ·
          {{ phaseName[a.phase] ?? a.phase }}
        </div>
        <DiagnosticDetails :diagnostics="a.diagnostics" />
      </div>
    </details>
  </section>
</template>
<style scoped>
.model-diagnostics {
  margin-top: 20px;
  font-size: 13px;
}
.model-diagnostics > summary {
  cursor: pointer;
  color: #d3bc91;
}
</style>
