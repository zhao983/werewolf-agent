<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import {
  experimentApi,
  experimentError,
  type BatchRequest,
  type ExperimentItem,
  type ExperimentRecord,
  type ExperimentView,
} from "../experiments";
import { roleName, phaseName } from "../types";
import ExperimentLabelFields from "../components/ExperimentLabelFields.vue";
import AnalysisFilters from "../components/AnalysisFilters.vue";
import DiagnosticDetails from "../components/DiagnosticDetails.vue";
import {
  configKey,
  emptyFilter,
  type AnalysisOptions,
  type ExperimentMetadata,
} from "../analysis";

const route = useRoute();
const form = ref<BatchRequest>({
  name: "Random 基线",
  group: "",
  notes: "",
  runs: 20,
  startSeed: 1,
  config: { playerCount: 7, werewolves: 2, villagers: 3, seers: 1, witches: 1 },
  agentTypes: Array(7).fill("RANDOM"),
});
const items = ref<ExperimentItem[]>([]);
const selectedId = ref("");
const detail = ref<ExperimentView | null>(null);
const selectedGameId = ref("");
const scope = ref("ALL");
const busy = ref(false);
const downloading = ref(false);
const loadingDetail = ref(false);
const cancelRequested = ref(false);
const error = ref("");
const notice = ref("");
const importInput = ref<HTMLInputElement | null>(null);
const importing = ref(false);
const storageDirectory = ref("");
const recordFilter = ref(emptyFilter());
const searchText = ref("");
const editingMetadata = ref(false);
const metadataBusy = ref(false);
const metadataVersion = ref("");
const metadataDraft = ref<ExperimentMetadata>({
  name: "",
  group: "",
  notes: "",
});
const filterOptions = computed<AnalysisOptions>(() => {
  const sorted = (values: string[]) => [...new Set(values)].sort();
  return {
    groups: sorted(items.value.map((i) => i.group ?? "")),
    models: sorted(items.value.flatMap((i) => i.models ?? [])),
    configs: sorted(
      items.value.filter((i) => i.config).map((i) => configKey(i.config)),
    ),
    knowledgeModes: sorted(items.value.flatMap((i) => i.knowledgeModes ?? [])),
    knowledgeRevisions: sorted(
      items.value.flatMap((i) => i.knowledgeRevisions ?? []),
    ),
    agentTypes: sorted(
      items.value
        .filter((i) => i.agentTypes)
        .map((i) => i.agentTypes.join("/")),
    ),
  };
});
const sourceName: Record<string, string> = {
  BATCH: "批量",
  SINGLE: "单局",
  IMPORTED: "导入",
};
const actionPage = ref(1);
let mounted = true;
let refreshing = false;
let detailRequest = 0;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;

watch(
  () => form.value.config.playerCount,
  (count) => {
    if (Number.isInteger(count) && count >= 3 && count <= 20)
      form.value.agentTypes = Array.from(
        { length: count },
        (_, i) => form.value.agentTypes[i] ?? "RANDOM",
      );
  },
);
const valid = computed(() => {
  const c = form.value.config;
  return (
    [c.playerCount, c.werewolves, c.villagers, c.seers, c.witches].every(
      Number.isInteger,
    ) &&
    c.playerCount >= 3 &&
    c.playerCount <= 20 &&
    c.werewolves >= 1 &&
    c.villagers >= 0 &&
    c.seers >= 0 &&
    c.witches >= 0 &&
    c.werewolves + c.villagers + c.seers + c.witches === c.playerCount &&
    c.werewolves < c.playerCount - c.werewolves &&
    Number.isInteger(form.value.runs) &&
    form.value.runs >= 1 &&
    form.value.runs <= 200 &&
    Number.isSafeInteger(form.value.startSeed) &&
    Number.isSafeInteger(form.value.startSeed + form.value.runs - 1)
  );
});
const activeBatch = computed(
  () =>
    items.value.some((i) => i.source === "BATCH" && i.status === "RUNNING") ||
    (detail.value?.record.source === "BATCH" &&
      detail.value.record.status === "RUNNING"),
);
const filteredItems = computed(() =>
  items.value.filter((i) => {
    const f = recordFilter.value;
    return (
      (scope.value === "ALL" || i.source === scope.value) &&
      (f.group === null || f.group === (i.group ?? "")) &&
      (f.model === null || (i.models ?? []).includes(f.model)) &&
      (f.config === null || (i.config && f.config === configKey(i.config))) &&
      (f.knowledgeMode === null ||
        (i.knowledgeModes ?? []).includes(f.knowledgeMode)) &&
      (f.knowledgeRevision === null ||
        (i.knowledgeRevisions ?? []).includes(f.knowledgeRevision)) &&
      (f.agentTypes === null ||
        (i.agentTypes && f.agentTypes === i.agentTypes.join("/"))) &&
      `${i.name} ${i.group ?? ""} ${i.notes ?? ""}`
        .toLowerCase()
        .includes(searchText.value.trim().toLowerCase())
    );
  }),
);
// 导入副本默认不进入本机实验总览，避免重复导入改变论文统计。
const localItems = computed(() =>
  items.value.filter((i) => i.source !== "IMPORTED"),
);
const completed = computed(() =>
  localItems.value.reduce((n, i) => n + i.summary.completedGames, 0),
);
const wolfWins = computed(() =>
  localItems.value.reduce((n, i) => n + i.summary.wolfWins, 0),
);
const goodWins = computed(() =>
  localItems.value.reduce((n, i) => n + i.summary.goodWins, 0),
);
const averageDays = computed(() =>
  completed.value
    ? (
        localItems.value.reduce(
          (n, i) => n + i.summary.averageDays * i.summary.completedGames,
          0,
        ) / completed.value
      ).toFixed(2)
    : "—",
);
const record = computed(() => detail.value?.record);
const summary = computed(() => detail.value?.summary);
const chosenGame = computed(() =>
  record.value?.games.find((g) => g.gameId === selectedGameId.value),
);
const actions = computed(() => chosenGame.value?.actions ?? []);
const pagedActions = computed(() =>
  actions.value.slice((actionPage.value - 1) * 50, actionPage.value * 50),
);
const actionPages = computed(() =>
  Math.max(1, Math.ceil(actions.value.length / 50)),
);
watch(selectedGameId, () => {
  actionPage.value = 1;
});

const statusName: Record<string, string> = {
  RUNNING: "进行中",
  COMPLETED: "已完成",
  FAILED: "失败",
  CANCELLED: "已停止",
  INTERRUPTED: "已中断",
};
const actionName: Record<string, string> = {
  SPEAK: "发言",
  VOTE: "投票",
  KILL: "袭击",
  CHECK: "查验",
  SAVE: "救人",
  POISON: "用毒",
  PASS: "跳过",
};
function outcome(value: string) {
  return value === "WEREWOLF_WIN"
    ? "狼人胜"
    : value === "VILLAGER_WIN"
      ? "好人胜"
      : "未结束";
}
function date(value: string) {
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}
function preset(type: "RANDOM" | "RULE") {
  if (
    !Number.isInteger(form.value.config.playerCount) ||
    form.value.config.playerCount < 3 ||
    form.value.config.playerCount > 20
  )
    return;
  form.value.agentTypes = Array(form.value.config.playerCount).fill(type);
}
function reuse(value: ExperimentRecord) {
  if (value.agentTypes.includes("LLM")) {
    error.value = "模型对局需在总览中手动推进，不能加入自动批量实验。";
    return;
  }
  form.value = {
    name: `${value.name} · 复跑`.slice(0, 80),
    config: { ...value.config },
    agentTypes: value.agentTypes as ("RANDOM" | "RULE")[],
    startSeed: value.startSeed,
    runs: value.requestedGames,
    group: value.group ?? "",
    notes: value.notes ?? "",
  };
  form.value.agentTypes = [...form.value.agentTypes];
  notice.value =
    "已填入相同配置与种子。点击运行即可复跑；时间和对局编号会变化。";
}
async function select(id: string) {
  const request = ++detailRequest;
  if (selectedId.value !== id) {
    editingMetadata.value = false;
    detail.value = null;
    selectedGameId.value = "";
    cancelRequested.value = false;
  }
  selectedId.value = id;
  loadingDetail.value = true;
  try {
    const result = await experimentApi.get(id);
    if (!mounted || request !== detailRequest) return;
    detail.value = result;
    if (!result.record.games.some((g) => g.gameId === selectedGameId.value))
      selectedGameId.value = result.record.games[0]?.gameId ?? "";
  } catch (e) {
    if (mounted && request === detailRequest) error.value = experimentError(e);
  } finally {
    if (mounted && request === detailRequest) loadingDetail.value = false;
  }
}
function editLabels() {
  if (!record.value) return;
  metadataDraft.value = {
    name: record.value.name,
    group: record.value.group ?? "",
    notes: record.value.notes ?? "",
  };
  metadataVersion.value = detail.value?.metadataRevision ?? "";
  editingMetadata.value = true;
}
async function saveLabels() {
  if (!record.value || metadataBusy.value) return;
  const id = record.value.id;
  metadataBusy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const value = await experimentApi.updateMetadata(
      id,
      metadataVersion.value,
      metadataDraft.value,
    );
    if (!mounted) return;
    if (selectedId.value === id) {
      detail.value = value;
      editingMetadata.value = false;
    }
    notice.value = "实验标签已保存，继续推进对局时会保留这些标签。";
    await refresh();
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  } finally {
    if (mounted) metadataBusy.value = false;
  }
}
function clearFilters() {
  recordFilter.value = emptyFilter();
  searchText.value = "";
  scope.value = "ALL";
}
async function refresh() {
  if (!mounted || refreshing) return;
  refreshing = true;
  if (refreshTimer) clearTimeout(refreshTimer);
  try {
    const result = await experimentApi.list();
    if (!mounted) return;
    items.value = result;
    if (!selectedId.value && result.length) {
      const requested =
        typeof route.query.id === "string" ? route.query.id : "";
      await select(
        result.some((i) => i.id === requested) ? requested : result[0].id,
      );
    } else {
      const item = result.find((i) => i.id === selectedId.value);
      // 只在结果增长或状态改变时读取完整行动记录，避免每次轮询重复下载大批数据。
      if (
        item &&
        (item.recordedGames !== record.value?.games.length ||
          item.status !== record.value?.status ||
          item.source === "SINGLE")
      )
        await select(item.id);
    }
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  } finally {
    refreshing = false;
    if (mounted && activeBatch.value) refreshTimer = setTimeout(refresh, 1200);
  }
}
async function start() {
  if (!valid.value || busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const result = await experimentApi.start(form.value);
    if (!mounted) return;
    ++detailRequest;
    loadingDetail.value = false;
    selectedId.value = result.record.id;
    detail.value = result;
    selectedGameId.value = "";
    cancelRequested.value = false;
    await refresh();
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  } finally {
    if (mounted) busy.value = false;
  }
}
async function cancel() {
  if (!record.value || busy.value) return;
  const id = record.value.id;
  busy.value = true;
  error.value = "";
  try {
    await experimentApi.cancel(id);
    if (selectedId.value === id) cancelRequested.value = true;
    await refresh();
  } catch (e) {
    error.value = experimentError(e);
  } finally {
    busy.value = false;
  }
}
async function download(format: "json" | "csv" | "actions") {
  if (!record.value || downloading.value) return;
  downloading.value = true;
  error.value = "";
  try {
    await experimentApi.download(record.value.id, format);
  } catch (e) {
    error.value = experimentError(e);
  } finally {
    downloading.value = false;
  }
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || importing.value) return;
  error.value = "";
  notice.value = "";
  if (
    !file.name.toLowerCase().endsWith(".json") ||
    file.size > 25 * 1024 * 1024 ||
    file.size === 0
  ) {
    error.value = "请选择非空且不超过 25 MB 的 JSON 文件。";
    input.value = "";
    return;
  }
  importing.value = true;
  try {
    const result = await experimentApi.importFile(file);
    if (!mounted) return;
    ++detailRequest;
    loadingDetail.value = false;
    selectedId.value = result.record.id;
    detail.value = result;
    selectedGameId.value = result.record.games[0]?.gameId ?? "";
    scope.value = "ALL";
    notice.value = `已导入「${result.record.name}」，记录已保存到本机，可查看和回放 ${result.record.games.length} 场对局。`;
    await refresh();
  } catch (e) {
    if (mounted) error.value = experimentError(e);
  } finally {
    input.value = "";
    if (mounted) importing.value = false;
  }
}
onMounted(() => {
  refresh();
  experimentApi
    .storage()
    .then((info) => {
      if (mounted) storageDirectory.value = info.directory;
    })
    .catch(() => {});
});
onBeforeUnmount(() => {
  mounted = false;
  ++detailRequest;
  if (refreshTimer) clearTimeout(refreshTimer);
});
</script>

<template>
  <div class="page experiment-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">REPRODUCIBLE EXPERIMENTS</div>
        <h1>实验记录与统计</h1>
        <p>
          固定种子重复试验，查看行动记录并导出数据。结果保存在本机，后端重启后仍可查看。
        </p>
      </div>
      <div class="experiment-actions">
        <RouterLink to="/analysis" class="experiment-button"
          >对照分析 →</RouterLink
        >
        <input
          ref="importInput"
          type="file"
          accept=".json,application/json"
          hidden
          aria-label="选择实验 JSON"
          @change="importFile"
        />
        <button
          class="experiment-button"
          :disabled="importing"
          @click="importInput?.click()"
        >
          {{ importing ? "正在导入…" : "导入 JSON 记录" }}
        </button>
        <button class="experiment-button" @click="refresh">刷新记录</button>
      </div>
    </div>
    <div v-if="error" class="experiment-message error" role="alert">
      {{ error }}
    </div>
    <div v-if="notice" class="experiment-message" role="status">
      {{ notice }}
    </div>
    <p class="muted storage-hint">
      本地保存目录：<code>{{ storageDirectory || "正在读取…" }}</code
      >。可导入其他电脑导出的完整 JSON（最多 25 MB）。
    </p>
    <div class="stats-grid">
      <div class="panel stat-card">
        <span>已完成对局</span><strong>{{ completed }}</strong
        ><small>本机运行；不包含导入副本</small>
      </div>
      <div class="panel stat-card wolf">
        <span>狼人胜场</span><strong>{{ wolfWins }}</strong
        ><small
          >{{ completed ? ((wolfWins / completed) * 100).toFixed(1) : 0 }}%
          胜率</small
        >
      </div>
      <div class="panel stat-card good">
        <span>好人胜场</span><strong>{{ goodWins }}</strong
        ><small
          >{{ completed ? ((goodWins / completed) * 100).toFixed(1) : 0 }}%
          胜率</small
        >
      </div>
      <div class="panel stat-card">
        <span>平均轮次</span><strong>{{ averageDays }}</strong
        ><small>仅统计完成局；混合配置汇总只供概览</small>
      </div>
    </div>
    <div class="experiment-layout">
      <section class="panel experiment-setup">
        <div class="section-head">
          <div>
            <span class="section-index">01 / BATCH</span>
            <h2>运行基线实验</h2>
          </div>
        </div>
        <p class="muted">
          每局使用一个种子：起始种子、起始种子 + 1…；仅 Random / Rule 自动运行。
        </p>
        <ExperimentLabelFields
          v-model:name="form.name"
          v-model:group="form.group"
          v-model:notes="form.notes"
        />
        <div class="experiment-fields">
          <label class="config-field"
            >局数<input
              v-model.number="form.runs"
              type="number"
              min="1"
              max="200"
          /></label>
          <label class="config-field"
            >起始种子<input
              v-model.number="form.startSeed"
              type="number"
              step="1"
          /></label>
        </div>
        <div class="config-grid">
          <label class="config-field"
            >总人数<input
              v-model.number="form.config.playerCount"
              type="number"
              min="3"
              max="20"
          /></label>
          <label class="config-field"
            >狼人<input
              v-model.number="form.config.werewolves"
              type="number"
              min="1"
              max="20"
          /></label>
          <label class="config-field"
            >村民<input
              v-model.number="form.config.villagers"
              type="number"
              min="0"
              max="20"
          /></label>
          <label class="config-field"
            >预言家<input
              v-model.number="form.config.seers"
              type="number"
              min="0"
              max="20"
          /></label>
          <label class="config-field"
            >女巫<input
              v-model.number="form.config.witches"
              type="number"
              min="0"
              max="20"
          /></label>
        </div>
        <div class="preset-row">
          <span>座位配置</span
          ><button @click="preset('RANDOM')">全部 Random</button
          ><button @click="preset('RULE')">全部 Rule</button>
        </div>
        <div class="experiment-seats">
          <label
            v-for="(_, index) in form.agentTypes"
            :key="index"
            class="seat-row"
          >
            <span>Player {{ index + 1 }}</span
            ><select v-model="form.agentTypes[index]">
              <option value="RANDOM">Random Agent</option>
              <option value="RULE">Rule Agent</option>
            </select>
          </label>
        </div>
        <p v-if="!valid" class="experiment-validation">
          请检查身份总和、阵营人数、局数和整数种子。
        </p>
        <button
          class="primary-button"
          :disabled="!valid || busy || activeBatch"
          @click="start"
        >
          {{
            busy
              ? "正在提交…"
              : activeBatch
                ? "已有批次运行中"
                : `运行 ${form.runs} 局实验`
          }}
        </button>
        <p class="muted">
          建议论文对照先固定 7
          人配置，每次改变一个因素。同配置与种子可复现规则型 Agent
          的身份、行动和胜负。
        </p>
      </section>
      <section class="panel experiment-history">
        <div class="section-head">
          <div>
            <span class="section-index">02 / RECORDS</span>
            <h2>已保存记录</h2>
          </div>
          <select v-model="scope" aria-label="记录类型">
            <option value="ALL">全部记录</option>
            <option value="BATCH">批量实验</option>
            <option value="SINGLE">普通对局</option>
            <option value="IMPORTED">导入记录</option>
          </select>
        </div>
        <div class="record-search-row">
          <input
            v-model="searchText"
            aria-label="搜索实验记录"
            placeholder="搜索名称、分组或备注…"
          />
          <button class="subtle-button" @click="clearFilters">清空筛选</button>
        </div>
        <AnalysisFilters v-model="recordFilter" :options="filterOptions" />
        <p class="muted">
          {{ filteredItems.length }} /
          {{ items.length }}
          条记录符合条件。筛选作用于记录列表，顶部为本机全部运行概览。
        </p>
        <div v-if="!filteredItems.length" class="empty-small">
          当前条件暂无记录。可清空筛选，或创建、导入实验记录。
        </div>
        <div class="experiment-records">
          <button
            v-for="item in filteredItems"
            :key="item.id"
            class="experiment-record"
            :class="{ selected: selectedId === item.id }"
            @click="select(item.id)"
          >
            <span
              ><strong>{{ item.name }}</strong
              ><small
                >{{ date(item.createdAt) }} ·
                {{ sourceName[item.source] ?? item.source }} · 种子
                {{ item.startSeed }}</small
              ><small class="record-group-tag">{{
                item.group || "未分组"
              }}</small></span
            >
            <span
              ><b
                class="experiment-status"
                :class="`status-${item.status.toLowerCase()}`"
                >{{ statusName[item.status] ?? item.status }}</b
              ><small
                >{{ item.summary.completedGames }} /
                {{ item.requestedGames }} 局完成</small
              ></span
            >
          </button>
        </div>
      </section>
    </div>

    <section
      v-if="record && summary"
      class="panel experiment-detail"
      :aria-busy="loadingDetail"
    >
      <div class="section-head">
        <div>
          <span class="section-index">03 / DETAILS</span>
          <h2>{{ record.name }}</h2>
          <p class="muted">
            {{ statusName[record.status] }} · {{ record.engineVersion }} ·
            起始种子 {{ record.startSeed }}
          </p>
        </div>
        <div class="experiment-actions">
          <button
            class="experiment-button"
            :disabled="metadataBusy"
            @click="editLabels"
          >
            编辑实验标签
          </button>
          <button
            v-if="!record.agentTypes.includes('LLM')"
            class="experiment-button"
            @click="reuse(record)"
          >
            复用配置
          </button>
          <button
            v-if="record.source === 'BATCH' && record.status === 'RUNNING'"
            class="experiment-button"
            :disabled="busy || cancelRequested"
            @click="cancel"
          >
            {{ cancelRequested ? "等待本局结束…" : "停止后续对局" }}
          </button>
        </div>
      </div>
      <p class="experiment-label-summary">
        分组：{{ record.group || "未分组"
        }}<span v-if="record.notes"> · 备注：{{ record.notes }}</span>
      </p>
      <form
        v-if="editingMetadata"
        class="experiment-label-editor"
        @submit.prevent="saveLabels"
      >
        <fieldset :disabled="metadataBusy">
          <ExperimentLabelFields
            v-model:name="metadataDraft.name"
            v-model:group="metadataDraft.group"
            v-model:notes="metadataDraft.notes"
          />
          <div class="experiment-actions">
            <button class="primary-button" type="submit">
              {{ metadataBusy ? "正在保存…" : "保存实验标签" }}</button
            ><button
              class="subtle-button"
              type="button"
              @click="editingMetadata = false"
            >
              取消编辑
            </button>
          </div>
        </fieldset>
      </form>
      <progress
        v-if="record.source === 'BATCH'"
        :value="record.games.length"
        :max="record.requestedGames"
        :aria-label="`已记录 ${record.games.length} / ${record.requestedGames} 局`"
      ></progress>
      <p v-if="record.status === 'INTERRUPTED'" class="experiment-message">
        此记录尚未完整结束，已保存的局仍可分析、回放和导出。回放不会恢复运行或调用模型。
      </p>
      <p v-if="record.source === 'IMPORTED'" class="experiment-message">
        这是外部 JSON
        的本地副本，内容真实性由文件提供者负责。统计已重新计算，不计入本机运行总览。
      </p>
      <p
        v-if="record.errorCode && record.status === 'FAILED'"
        class="experiment-message error"
      >
        {{
          record.errorCode === "STORAGE_FAILED"
            ? "记录保存失败，请检查数据目录与磁盘空间。"
            : "实验运行失败，请保留导出记录检查规则或 Agent。"
        }}
      </p>
      <div class="experiment-metrics">
        <div>
          <span>完成 / 请求</span
          ><strong
            >{{ summary.completedGames }} / {{ record.requestedGames }}</strong
          >
        </div>
        <div>
          <span>狼人 / 好人胜场</span
          ><strong>{{ summary.wolfWins }} / {{ summary.goodWins }}</strong
          ><small v-if="summary.completedGames"
            >胜率
            {{
              ((summary.wolfWins / summary.completedGames) * 100).toFixed(1)
            }}% /
            {{
              ((summary.goodWins / summary.completedGames) * 100).toFixed(1)
            }}%</small
          >
        </div>
        <div>
          <span>平均轮次</span
          ><strong>{{
            summary.completedGames ? summary.averageDays.toFixed(2) : "—"
          }}</strong>
        </div>
        <div>
          <span>合法 / 行动尝试</span
          ><strong
            >{{ summary.validActions }} / {{ summary.actionAttempts }}</strong
          >
        </div>
        <div>
          <span>非法动作 / 模型回复</span
          ><strong
            >{{ summary.invalidActions }} / {{ summary.invalidReplies }}</strong
          >
        </div>
        <div>
          <span>API 调用 / 失败</span
          ><strong>{{ summary.apiCalls }} / {{ summary.apiFailures }}</strong>
        </div>
        <div>
          <span>已报告 token</span
          ><strong>{{
            summary.apiCalls === 0
              ? "0"
              : summary.usageReportedCalls
                ? summary.totalTokens.toLocaleString()
                : "未报告"
          }}</strong
          ><small
            >{{ summary.usageReportedCalls }} /
            {{ summary.apiCalls }} 次调用报告用量</small
          >
        </div>
        <div>
          <span>平均决策耗时</span
          ><strong
            >{{
              summary.actionAttempts
                ? (summary.decisionMillis / summary.actionAttempts).toFixed(1)
                : "—"
            }}
            ms</strong
          ><small>不包含用户点击等待</small>
        </div>
      </div>
      <div class="experiment-actions export-row">
        <button
          class="experiment-button"
          :disabled="downloading"
          @click="download('json')"
        >
          导出完整 JSON
        </button>
        <button
          class="experiment-button"
          :disabled="downloading"
          @click="download('csv')"
        >
          导出对局 CSV
        </button>
        <button
          class="experiment-button"
          :disabled="downloading"
          @click="download('actions')"
        >
          导出行动 CSV
        </button>
        <span class="muted">包含身份与行动，仅供本浏览器用户分析。</span>
      </div>
      <div class="experiment-table-wrap">
        <table class="experiment-table">
          <caption>
            对局结果 · 点击“查看记录”查看座位和行动
          </caption>
          <thead>
            <tr>
              <th>种子</th>
              <th>结果</th>
              <th>轮次</th>
              <th>行动尝试</th>
              <th>总历时</th>
              <th>查看</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="game in record.games"
              :key="game.gameId"
              :class="{ chosen: selectedGameId === game.gameId }"
            >
              <td>{{ game.seed }}</td>
              <td>{{ outcome(game.result) }}</td>
              <td>{{ game.days }}</td>
              <td>{{ game.actions.length }}</td>
              <td>
                {{
                  game.elapsedMillis === null ? "—" : `${game.elapsedMillis} ms`
                }}
              </td>
              <td>
                <button
                  class="experiment-button"
                  @click="selectedGameId = game.gameId"
                >
                  查看记录
                </button>
                <RouterLink
                  class="experiment-button"
                  :to="`/experiments/${record.id}/replay/${game.gameId}`"
                  >对局回放 ↗</RouterLink
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="!record.games.length" class="muted">正在等待第一局完成…</p>
      <p class="muted">
        单局总历时包含人工等待；中断、失败和未结束的局不计入胜率及平均轮次。
      </p>
      <template v-if="chosenGame">
        <h3>种子 {{ chosenGame.seed }} · 座位身份</h3>
        <div class="experiment-role-list">
          <span v-for="seat in chosenGame.seats" :key="seat.playerId"
            >{{ seat.playerId }} · {{ roleName[seat.role] }} ·
            {{ seat.agentType }}</span
          >
        </div>
        <p v-if="chosenGame.model" class="muted">
          模型 {{ chosenGame.model.model }} · temperature
          {{ chosenGame.model.temperature }} · maxTokens
          {{ chosenGame.model.maxTokens }} · 决策方式
          {{
            chosenGame.model.decisionMode === "TOOLS_STRICT"
              ? "工具调用（严格参数）"
              : chosenGame.model.decisionMode === "TOOLS"
                ? "工具调用"
                : "JSON 回复"
          }}
          · 超时 {{ chosenGame.model.requestTimeoutSeconds ?? 45 }} 秒 ·
          额度参数
          {{
            chosenGame.model.tokenLimitParameter === "MAX_COMPLETION_TOKENS"
              ? "max_completion_tokens"
              : "max_tokens"
          }}。 模型对局的种子只控制引擎随机过程，不保证外部模型输出可复现。
        </p>
        <div class="experiment-table-wrap">
          <table class="experiment-table">
            <caption>
              逐行动记录
            </caption>
            <thead>
              <tr>
                <th>序号</th>
                <th>日 / 阶段</th>
                <th>玩家</th>
                <th>行动 / 目标</th>
                <th>结果</th>
                <th>决策耗时</th>
                <th>API / 非法回复</th>
                <th>请求诊断</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="action in pagedActions" :key="action.sequence">
                <td>{{ action.sequence }}</td>
                <td>
                  {{ action.day }} /
                  {{ phaseName[action.phase] ?? action.phase }}
                </td>
                <td>{{ action.playerId }}</td>
                <td>
                  {{
                    action.action
                      ? (actionName[action.action] ?? action.action)
                      : "未返回动作"
                  }}
                  {{
                    action.targetPlayerId ? `→ ${action.targetPlayerId}` : ""
                  }}
                </td>
                <td>
                  {{
                    action.status === "VALID"
                      ? "合法"
                      : action.status === "INVALID"
                        ? "非法"
                        : "决策失败"
                  }}
                </td>
                <td>{{ action.decisionMillis }} ms</td>
                <td>
                  {{ action.metrics.apiCalls }} /
                  {{ action.metrics.invalidReplies }}
                </td>
                <td>
                  <DiagnosticDetails
                    v-if="action.metrics.apiCalls"
                    :diagnostics="action.diagnostics"
                  /><span v-else>—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="actionPages > 1" class="experiment-actions export-row">
          <button
            class="experiment-button"
            :disabled="actionPage === 1"
            @click="actionPage--"
          >
            上一页</button
          ><span>{{ actionPage }} / {{ actionPages }}</span
          ><button
            class="experiment-button"
            :disabled="actionPage === actionPages"
            @click="actionPage++"
          >
            下一页
          </button>
        </div>
      </template>
    </section>
    <p class="muted">
      清除浏览器访问凭据后可通过“导入 JSON
      记录”恢复导出的副本。请备份本地目录或导出的
      JSON；文件包含身份和私有行动，已排除 Git 提交。
    </p>
  </div>
</template>

<style scoped>
.record-search-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.record-search-row input {
  flex: 1;
  min-width: 150px;
  background: #141e29;
  color: #eeeae1;
  border: 1px solid #ffffff25;
  border-radius: 5px;
  padding: 9px;
  font-size: 12px;
}
.record-group-tag {
  color: #c5aa76;
  font-size: 10px;
  overflow-wrap: anywhere;
}
.experiment-label-summary {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  color: #aeb8b9;
  line-height: 1.8;
}
.experiment-label-editor {
  margin: 16px 0;
  padding: 16px;
  background: #141e29;
  border-radius: 8px;
}
.experiment-label-editor fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.experiment-layout {
  display: grid;
  grid-template-columns: minmax(330px, 0.9fr) minmax(320px, 1.1fr);
  gap: 20px;
  margin: 22px 0;
}
.experiment-setup,
.experiment-history,
.experiment-detail {
  padding: 24px;
  min-width: 0;
}
.experiment-setup .config-field {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
}
.experiment-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.experiment-setup .config-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.experiment-seats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 18px;
}
.experiment-seats .seat-row {
  display: grid;
  gap: 6px;
  padding: 10px;
  font-size: 11px;
}
.experiment-page select {
  background: #202a34;
  border: 1px solid #ffffff22;
  border-radius: 6px;
  color: #eeeae1;
  padding: 7px;
  max-width: 100%;
}
.experiment-page .muted {
  font-size: 12px;
  line-height: 1.8;
}
.experiment-button {
  background: #202a34;
  border: 1px solid #ffffff22;
  color: #e1c99e;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  white-space: nowrap;
}
.experiment-button:disabled {
  opacity: 0.45;
  cursor: default;
}
.experiment-records {
  max-height: 640px;
  overflow: auto;
}
.experiment-record {
  display: flex;
  justify-content: space-between;
  text-align: left;
  width: 100%;
  gap: 14px;
  background: transparent;
  color: #c4cbd0;
  border: 0;
  border-bottom: 1px solid #ffffff12;
  padding: 18px 10px;
}
.experiment-record.selected {
  background: #ffffff08;
  box-shadow: inset 2px 0 #d3a862;
}
.experiment-record strong {
  font-size: 13px;
  overflow-wrap: anywhere;
}
.experiment-record small {
  display: block;
  color: #89949c;
  margin-top: 7px;
  font-size: 10px;
}
.experiment-record > span:last-child {
  flex-shrink: 0;
  text-align: right;
}
.experiment-status {
  font-size: 11px;
  color: #aaafb8;
}
.status-completed {
  color: #7cc9ab;
}
.status-running {
  color: #dfbb79;
}
.status-failed {
  color: #ee9393;
}
.experiment-message {
  padding: 12px 16px;
  border: 1px solid #d3a86244;
  background: #d3a86210;
  border-radius: 8px;
  font-size: 12px;
  margin-bottom: 16px;
  line-height: 1.8;
}
.experiment-message.error,
.experiment-validation {
  color: #f4a7a7;
}
.experiment-message.error {
  border-color: #e46d6d44;
  background: #e46d6d10;
}
.experiment-validation {
  font-size: 12px;
}
.experiment-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.export-row {
  margin: 20px 0;
}
.experiment-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
  margin: 24px 0;
}
.experiment-metrics span {
  display: block;
  font-size: 11px;
  color: #909ca4;
}
.experiment-metrics strong {
  display: block;
  font-size: 20px;
  margin-top: 8px;
}
.experiment-metrics small {
  font-size: 10px;
  color: #7e8e96;
}
progress {
  width: 100%;
  height: 9px;
  accent-color: #d3a862;
}
.experiment-table-wrap {
  overflow: auto;
  max-height: 420px;
}
.experiment-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  text-align: left;
  white-space: nowrap;
}
.experiment-table caption {
  text-align: left;
  padding: 12px 0;
  color: #ad956b;
  font-size: 12px;
}
.experiment-table th {
  color: #85919b;
  font-size: 11px;
  font-weight: 500;
}
.experiment-table td,
.experiment-table th {
  padding: 12px 10px;
  border-bottom: 1px solid #ffffff12;
}
.experiment-table .chosen {
  background: #ffffff07;
}
.experiment-role-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0;
}
.experiment-role-list span {
  padding: 8px 10px;
  background: #202a34;
  border-radius: 6px;
  font-size: 11px;
  color: #bccacb;
}
.experiment-detail h3 {
  font-size: 15px;
  font-weight: 500;
  margin-top: 24px;
}
@media (max-width: 1100px) {
  .experiment-layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 700px) {
  .experiment-metrics {
    grid-template-columns: repeat(2, 1fr);
  }
  .experiment-page .page-heading,
  .experiment-page .section-head {
    flex-wrap: wrap;
    gap: 12px;
  }
  .experiment-seats {
    grid-template-columns: 1fr;
  }
}
</style>
