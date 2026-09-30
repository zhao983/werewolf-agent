<script setup lang="ts">
import {
  agentsLabel,
  configLabel,
  modeLabel,
  type AnalysisFilter,
  type AnalysisOptions,
} from "../analysis";
defineProps<{ options: AnalysisOptions; includeSource?: boolean }>();
const filter = defineModel<AnalysisFilter>({ required: true });
</script>
<template>
  <div class="analysis-filters">
    <label
      >实验分组<select v-model="filter.group">
        <option :value="null">全部分组</option>
        <option v-for="group in options.groups" :key="group" :value="group">
          {{ group || "未分组" }}
        </option>
      </select></label
    >
    <label
      >模型<select v-model="filter.model">
        <option :value="null">全部模型</option>
        <option v-for="model in options.models" :key="model" :value="model">
          {{ model || "无 LLM" }}
        </option>
      </select></label
    >
    <label
      >身份配置<select v-model="filter.config">
        <option :value="null">全部身份配置</option>
        <option v-for="config in options.configs" :key="config" :value="config">
          {{ configLabel(config) }}
        </option>
      </select></label
    >
    <label
      >知识模式<select v-model="filter.knowledgeMode">
        <option :value="null">全部知识模式</option>
        <option
          v-for="mode in options.knowledgeModes"
          :key="mode"
          :value="mode"
        >
          {{ modeLabel[mode] ?? mode }}
        </option>
      </select></label
    >
    <details class="analysis-advanced">
      <summary>更多筛选条件</summary>
      <label
        >知识快照<select v-model="filter.knowledgeRevision">
          <option :value="null">全部知识版本</option>
          <option
            v-for="revision in options.knowledgeRevisions"
            :key="revision"
            :value="revision"
            :title="revision"
          >
            {{ revision.slice(0, 12) }}
          </option>
        </select></label
      >
      <label
        >座位 Agent 配置<select v-model="filter.agentTypes">
          <option :value="null">全部座位配置</option>
          <option
            v-for="agents in options.agentTypes"
            :key="agents"
            :value="agents"
            :title="agentsLabel(agents)"
          >
            {{ agentsLabel(agents) }}
          </option>
        </select></label
      >
      <label v-if="includeSource"
        >记录来源<select v-model="filter.source">
          <option value="LOCAL">本机运行（不含导入副本）</option>
          <option value="SINGLE">普通单局</option>
          <option value="BATCH">批量实验</option>
          <option value="IMPORTED">仅导入记录</option>
          <option value="ALL">全部来源（自动去重）</option>
        </select></label
      >
    </details>
  </div>
</template>
<style scoped>
.analysis-filters {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 14px 0;
}
label {
  display: block;
  font-size: 11px;
  color: #aeb8b9;
  min-width: 0;
}
select {
  display: block;
  width: 100%;
  margin-top: 7px;
  background: #141e29;
  border: 1px solid #ffffff25;
  color: #eeeae1;
  padding: 9px;
  border-radius: 5px;
  font-size: 11px;
  text-overflow: ellipsis;
}
.analysis-advanced {
  grid-column: 1 / -1;
}
.analysis-advanced summary {
  cursor: pointer;
  color: #c5aa76;
  font-size: 11px;
}
.analysis-advanced label {
  margin-top: 12px;
}
@media (max-width: 550px) {
  .analysis-filters {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
