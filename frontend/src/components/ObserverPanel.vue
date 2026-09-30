<script setup lang="ts">
import { computed } from "vue";
import { phaseName, type ObserverNote } from "../types";
import { describeNote } from "../observer";
import KnowledgeUsagePanel from "./KnowledgeUsagePanel.vue";
import type { KnowledgeRun } from "../knowledge";

const props = defineProps<{
  notes: ObserverNote[] | null;
  players: string[];
  cursor: number;
  live?: boolean;
  notice?: string;
  knowledge?: KnowledgeRun | null;
  eventCount?: number;
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
  </section>
</template>
