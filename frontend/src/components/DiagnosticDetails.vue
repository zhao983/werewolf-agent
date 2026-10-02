<script setup lang="ts">
import {
  diagnosticName,
  outputIssueName,
  localRepairName,
  type DecisionDiagnostic,
} from "../experiments";
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
      <template v-if="d.issue">
        · {{ outputIssueName[d.issue] ?? d.issue }}</template
      >
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
      <template v-if="d.repairs?.length"
        ><br />{{
          d.repairs.map((r) => localRepairName[r] ?? r).join("；")
        }}；未为本地修正追加请求。</template
      >
      <template v-if="d.completionTokens != null"
        ><br />报告总输出 {{ d.completionTokens }} Token<template
          v-if="d.reasoningTokens != null"
          >，其中思考 {{ d.reasoningTokens }} Token</template
        >。</template
      >
      <span v-if="d.outputExceededLimit"
        >输出用量与设置存在差异；总输出可能包含思考，请结合额度参数及思考用量核对。旧记录缺少细分用量时无法确定具体原因。</span
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
