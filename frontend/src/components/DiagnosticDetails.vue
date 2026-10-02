<script setup lang="ts">
import { diagnosticName, type DecisionDiagnostic } from "../experiments";
defineProps<{ diagnostics?: DecisionDiagnostic[] }>();
</script>
<template>
  <details v-if="diagnostics?.length" class="diagnostic-details">
    <summary>
      {{ diagnostics.map((d) => diagnosticName[d.code] ?? d.code).join(" → ")
      }}<span v-if="diagnostics.some((d) => d.outputExceededLimit)">
        · 用量超额提示</span
      >
    </summary>
    <p v-for="d in diagnostics" :key="d.attempt">
      第 {{ d.attempt }} 次：{{ diagnosticName[d.code] ?? d.code }} ·
      {{ d.requestMillis }} ms
      <template v-if="d.httpStatus != null">
        · HTTP {{ d.httpStatus }}</template
      >
      <template v-if="d.finishReason">
        · 结束原因 {{ d.finishReason }}</template
      >
      <template v-if="d.toolCallCount != null">
        · 工具 {{ d.toolCallCount }} 个</template
      >
      <br v-if="d.outputExceededLimit" />
      <span v-if="d.outputExceededLimit"
        >服务报告的输出 Token
        超过本次设置；请核对服务支持的额度参数及推理用量口径。</span
      >
    </p>
  </details>
  <span v-else class="muted">未记录诊断</span>
</template>
<style scoped>
.diagnostic-details {
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
  color: #d3bc91;
}
p {
  white-space: normal;
  color: #b6c0c4;
  margin: 6px 0;
}
</style>
