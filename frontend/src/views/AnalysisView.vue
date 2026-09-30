<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import AnalysisFilters from "../components/AnalysisFilters.vue";
import { experimentError } from "../experiments";
import {
  analysisApi,
  emptyFilter,
  emptyOptions,
  metricRows,
  metricValue,
  modeLabel,
  configLabel,
  type AnalysisComparison,
  type AnalysisOptions,
  type AnalysisRequest,
} from "../analysis";

const options = ref<AnalysisOptions>(emptyOptions());
const request = ref<AnalysisRequest>({
  nameA: "实验组 A",
  nameB: "实验组 B",
  groupA: emptyFilter(),
  groupB: emptyFilter(),
});
const result = ref<AnalysisComparison | null>(null);
const lastRequest = ref<AnalysisRequest | null>(null);
const busy = ref(false);
const downloading = ref(false);
const error = ref("");
const sampleSide = ref<"A" | "B">("A");
const samplePage = ref(1);
let mounted = true;
const dirty = computed(
  () =>
    lastRequest.value !== null &&
    JSON.stringify(request.value) !== JSON.stringify(lastRequest.value),
);
const chartKeys = ["wolfWinRate", "goodWinRate", "goodVoteHitRate"] as const;
const chartTitles = ["狼人胜率", "好人胜率", "好人投票命中率"];
const sample = computed(() =>
  sampleSide.value === "A" ? result.value?.groupA : result.value?.groupB,
);
const samplePages = computed(() =>
  Math.max(1, Math.ceil((sample.value?.games.length ?? 0) / 20)),
);
const samples = computed(
  () =>
    sample.value?.games.slice(
      (samplePage.value - 1) * 20,
      samplePage.value * 20,
    ) ?? [],
);
function snapshotRequest(): AnalysisRequest {
  return JSON.parse(JSON.stringify(request.value));
}
async function loadOptions() {
  error.value = "";
  try {
    const value = await analysisApi.options();
    if (mounted) options.value = value;
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  }
}
async function compare() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  const submitted = snapshotRequest();
  try {
    const value = await analysisApi.compare(submitted);
    if (!mounted) return;
    result.value = value;
    lastRequest.value = submitted;
    samplePage.value = 1;
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  } finally {
    if (mounted) busy.value = false;
  }
}
async function download(format: "csv" | "json") {
  if (!lastRequest.value || downloading.value || dirty.value) return;
  downloading.value = true;
  error.value = "";
  try {
    await analysisApi.download(lastRequest.value, format);
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  } finally {
    if (mounted) downloading.value = false;
  }
}
function difference(key: keyof AnalysisComparison["groupA"]["metrics"]) {
  if (!result.value) return null;
  const a = result.value.groupA.metrics[key],
    b = result.value.groupB.metrics[key];
  return a === null || b === null ? null : b - a;
}
function defaults() {
  request.value = {
    nameA: "实验组 A",
    nameB: "实验组 B",
    groupA: emptyFilter(),
    groupB: emptyFilter(),
  };
  if (
    options.value.knowledgeModes.includes("NONE") &&
    options.value.knowledgeModes.includes("ROLE")
  ) {
    request.value.nameA = "无知识基线";
    request.value.nameB = "角色知识组";
    request.value.groupA.knowledgeMode = "NONE";
    request.value.groupB.knowledgeMode = "ROLE";
  } else {
    const groups = options.value.groups.filter(Boolean);
    if (groups.length >= 2) {
      request.value.groupA.group = groups[0];
      request.value.nameA = groups[0];
      request.value.groupB.group = groups[1];
      request.value.nameB = groups[1];
    }
  }
}
onMounted(async () => {
  await loadOptions();
  if (mounted) defaults();
});
onBeforeUnmount(() => {
  mounted = false;
});
</script>
<template>
  <div class="page analysis-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">CONTROLLED COMPARISONS</div>
        <h1>实验对照分析</h1>
        <p>选择两组对局，比较胜负、投票、行动质量与模型消耗。</p>
      </div>
      <RouterLink to="/experiments" class="text-link"
        >整理实验记录 →</RouterLink
      >
    </div>
    <p v-if="error" class="experiment-message error" role="alert">
      {{ error }}
    </p>
    <section class="panel analysis-intro">
      <strong>固定条件，再比较变化</strong>
      <p>
        默认使用本机运行记录；按对局编号去重，只有已完成对局进入指标分母。Token
        仅累计实际报告的用量，无用量数据时显示“—”。
      </p>
      <p>
        建议固定模型参数、身份配置和座位 Agent
        类型，每次改变一个因素，并平衡身份与座位分配。这里展示描述性统计，差值不代表显著性或因果。
      </p>
    </section>
    <div class="analysis-cohorts">
      <section class="panel" aria-label="实验组 A 条件">
        <div class="section-head">
          <h2><span class="cohort-mark a">A</span>实验组 A</h2>
        </div>
        <label class="cohort-name"
          >显示名称<input v-model="request.nameA" maxlength="80"
        /></label>
        <AnalysisFilters
          v-model="request.groupA"
          :options="options"
          include-source
        />
      </section>
      <section class="panel" aria-label="实验组 B 条件">
        <div class="section-head">
          <h2><span class="cohort-mark b">B</span>实验组 B</h2>
        </div>
        <label class="cohort-name"
          >显示名称<input v-model="request.nameB" maxlength="80"
        /></label>
        <AnalysisFilters
          v-model="request.groupB"
          :options="options"
          include-source
        />
      </section>
    </div>
    <div class="analysis-actions">
      <button class="primary-button" :disabled="busy" @click="compare">
        {{ busy ? "正在计算…" : "生成对照分析" }}
      </button>
      <button class="subtle-button" :disabled="busy" @click="loadOptions">
        刷新筛选选项
      </button>
      <button class="subtle-button" :disabled="busy" @click="defaults">
        恢复建议条件
      </button>
      <button
        class="subtle-button"
        :disabled="!result || dirty || downloading || busy"
        @click="download('csv')"
      >
        导出汇总 CSV
      </button>
      <button
        class="subtle-button"
        :disabled="!result || dirty || downloading || busy"
        @click="download('json')"
      >
        导出分析 JSON
      </button>
    </div>
    <p v-if="dirty" class="experiment-message">
      筛选条件已改变，下方仍是上一次结果。请重新生成分析后导出。
    </p>
    <template v-if="result">
      <p class="analysis-generated">
        生成于
        {{
          new Date(result.generatedAt).toLocaleString("zh-CN", {
            hour12: false,
          })
        }}。导出按本次筛选重新汇总，以文件生成时间为准。
      </p>
      <section
        v-if="result.notices.length"
        class="panel analysis-notices"
        aria-label="比较提示"
      >
        <h2>比较提示</h2>
        <ul>
          <li v-for="notice in result.notices" :key="notice">{{ notice }}</li>
        </ul>
      </section>
      <section class="panel analysis-chart">
        <div class="section-head">
          <h2>结果概览</h2>
          <span
            ><i class="legend a"></i>{{ result.groupA.name }}
            <i class="legend b"></i>{{ result.groupB.name }}</span
          >
        </div>
        <div class="analysis-bars">
          <div
            v-for="(key, index) in chartKeys"
            :key="key"
            class="analysis-bar-pair"
          >
            <h3>{{ chartTitles[index] }}</h3>
            <div
              v-for="(cohort, side) in [result.groupA, result.groupB]"
              :key="side"
              class="analysis-bar-row"
            >
              <span :title="cohort.name">{{ side ? "B" : "A" }}</span>
              <div class="analysis-bar-track">
                <div
                  :class="side ? 'b' : 'a'"
                  :style="{ width: `${(cohort.metrics[key] ?? 0) * 100}%` }"
                ></div>
              </div>
              <strong>{{ metricValue(cohort.metrics[key], "rate") }}</strong>
            </div>
          </div>
        </div>
      </section>
      <section class="panel analysis-table-panel">
        <h2>指标对照</h2>
        <div class="analysis-table-scroll">
          <table class="analysis-table">
            <thead>
              <tr>
                <th>指标与口径</th>
                <th>A：{{ result.groupA.name }}</th>
                <th>B：{{ result.groupB.name }}</th>
                <th>B 减 A</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in metricRows" :key="row.key">
                <th>
                  {{ row.label }}<small>{{ row.definition }}</small>
                </th>
                <td>
                  {{ metricValue(result.groupA.metrics[row.key], row.kind) }}
                </td>
                <td>
                  {{ metricValue(result.groupB.metrics[row.key], row.kind) }}
                </td>
                <td>{{ metricValue(difference(row.key), row.kind, true) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="muted">
          “—”表示缺少分母或用量数据。Token 覆盖率不足 100%
          时，已报告总量不能视为整组实际总消耗。
        </p>
      </section>
      <div class="analysis-cohorts">
        <section
          v-for="(cohort, side) in [result.groupA, result.groupB]"
          :key="side"
          class="panel analysis-profiles"
        >
          <h2>{{ side ? "B" : "A" }} · 完成局配置</h2>
          <p>
            身份配置：{{
              cohort.profiles.configs.map(configLabel).join("；") ||
              "暂无完成局"
            }}
          </p>
          <p>
            模型与参数：{{ cohort.profiles.models.join("；") || "暂无完成局" }}
          </p>
          <p>
            知识模式：{{
              cohort.profiles.knowledgeModes
                .map((m) => modeLabel[m] ?? m)
                .join("；") || "暂无完成局"
            }}
          </p>
          <p>
            知识快照：{{
              cohort.profiles.knowledgeRevisions
                .map((r) => r.slice(0, 12))
                .join("；") || "无知识快照"
            }}
          </p>
          <p>
            引擎版本：{{
              cohort.profiles.engineVersions.join("；") || "暂无完成局"
            }}
          </p>
        </section>
      </div>
      <section class="panel analysis-samples">
        <div class="section-head">
          <h2>样本对局</h2>
          <select
            v-model="sampleSide"
            aria-label="查看哪组样本"
            @change="samplePage = 1"
          >
            <option value="A">A：{{ result.groupA.name }}</option>
            <option value="B">B：{{ result.groupB.name }}</option>
          </select>
        </div>
        <p class="muted">
          {{ sample?.metrics.matchedGames }} 场匹配 ·
          {{ sample?.metrics.completedGames }} 场完成 ·
          {{ sample?.metrics.excludedGames }} 场未完成。
        </p>
        <p v-if="!samples.length" class="muted">暂无匹配对局，请调整条件。</p>
        <RouterLink
          v-for="game in samples"
          :key="game.gameId"
          :to="`/experiments/${game.experimentId}/replay/${game.gameId}`"
          class="analysis-sample-row"
        >
          <span
            ><strong>{{ game.name }}</strong
            ><small
              >{{ game.group || "未分组" }} · 种子 {{ game.seed }} ·
              {{ modeLabel[game.knowledgeMode] }}</small
            ></span
          ><span
            >{{
              game.status === "COMPLETED" ? "已完成" : "未完成，排除指标"
            }}
            ↗</span
          >
        </RouterLink>
        <div class="analysis-pagination">
          <button
            class="subtle-button"
            :disabled="samplePage <= 1"
            @click="samplePage--"
          >
            上一页</button
          ><span>{{ samplePage }} / {{ samplePages }}</span
          ><button
            class="subtle-button"
            :disabled="samplePage >= samplePages"
            @click="samplePage++"
          >
            下一页
          </button>
        </div>
      </section>
    </template>
    <section v-else class="panel empty-state">
      选择两组条件，然后点击“生成对照分析”。旧记录可以先在实验记录中补充分组。
    </section>
  </div>
</template>
<style scoped>
/* 复用深色实验平台布局，以两组颜色标识对照，不暗示数值越高越好。 */
.analysis-intro {
  margin-bottom: 22px;
}
.analysis-intro p,
.analysis-profiles p {
  color: #aeb8b9;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.analysis-cohorts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
  margin-bottom: 22px;
}
.cohort-mark {
  display: inline-grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  margin-right: 10px;
  color: #101720;
}
.a {
  background: #c5aa76;
}
.b {
  background: #82bfa9;
}
.cohort-name {
  display: block;
  color: #aeb8b9;
  font-size: 12px;
}
.cohort-name input {
  width: 100%;
  background: #141e29;
  color: #eeeae1;
  padding: 10px;
  border: 1px solid #ffffff25;
  border-radius: 5px;
  margin-top: 8px;
}
.analysis-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.analysis-generated {
  font-size: 11px;
  color: #93a0a9;
  line-height: 1.8;
}
.analysis-notices {
  margin: 18px 0;
  border-color: #c5aa7640;
}
.analysis-notices h2 {
  font-size: 15px;
}
.analysis-notices li {
  color: #d0bd96;
  font-size: 12px;
  margin: 8px 0;
  line-height: 1.7;
}
.analysis-chart,
.analysis-table-panel {
  margin: 22px 0;
}
.analysis-chart .section-head > span {
  color: #aeb8b9;
  font-size: 11px;
}
.legend {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin: 0 5px 0 12px;
}
.analysis-bars {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
}
.analysis-bar-pair h3 {
  font-size: 12px;
  color: #aeb8b9;
  margin: 0 0 15px;
}
.analysis-bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0;
  font-size: 11px;
}
.analysis-bar-track {
  flex: 1;
  background: #ffffff10;
  height: 9px;
  border-radius: 5px;
}
.analysis-bar-track > div {
  height: 9px;
  border-radius: 5px;
}
.analysis-bar-row strong {
  width: 58px;
  text-align: right;
}
.analysis-table-scroll {
  overflow-x: auto;
}
.analysis-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 650px;
  font-size: 12px;
}
.analysis-table th,
.analysis-table td {
  padding: 14px 12px;
  text-align: right;
  border-bottom: 1px solid #ffffff12;
  vertical-align: middle;
}
.analysis-table th:first-child {
  text-align: left;
  width: 40%;
}
.analysis-table thead th {
  color: #c5aa76;
}
.analysis-table tbody th {
  font-weight: 500;
}
.analysis-table small {
  display: block;
  color: #93a0a9;
  font-size: 10px;
  margin-top: 7px;
  line-height: 1.6;
}
.analysis-table-panel > p {
  font-size: 11px;
  line-height: 1.8;
}
.analysis-sample-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  padding: 15px 0;
  border-bottom: 1px solid #ffffff12;
  font-size: 12px;
}
.analysis-sample-row strong {
  display: block;
  overflow-wrap: anywhere;
}
.analysis-sample-row small {
  display: block;
  color: #93a0a9;
  margin-top: 8px;
  line-height: 1.8;
}
.analysis-sample-row > span:last-child {
  color: #c5aa76;
  flex-shrink: 0;
}
.analysis-pagination {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  font-size: 11px;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
@media (max-width: 1000px) {
  .analysis-cohorts,
  .analysis-bars {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 600px) {
  .analysis-chart .section-head {
    flex-wrap: wrap;
    gap: 12px;
  }
  .analysis-sample-row {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
