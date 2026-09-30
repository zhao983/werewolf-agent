<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { phaseName } from "../types";
import {
  knowledgeApi,
  knowledgeError,
  scopeName,
  type KnowledgeEntry,
  type KnowledgeScope,
  type KnowledgeSnapshot,
} from "../knowledge";

const snapshot = ref<KnowledgeSnapshot | null>(null);
const directory = ref("");
const scope = ref<KnowledgeScope>("COMMON");
const scopes = Object.keys(scopeName) as KnowledgeScope[];
const query = ref("");
const busy = ref(false);
const error = ref("");
const notice = ref("");
const editing = ref("");
const editRevision = ref("");
const pendingDelete = ref("");
const fileInput = ref<HTMLInputElement | null>(null);
const draft = ref<KnowledgeEntry>(fresh());
const phases = computed(() => allowedPhases(draft.value.scope));
const entries = computed(() =>
  (snapshot.value?.entries ?? []).filter(
    (e) =>
      e.scope === scope.value &&
      `${e.title} ${e.content}`
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()),
  ),
);
const enabledCount = computed(
  () => snapshot.value?.entries.filter((e) => e.enabled).length ?? 0,
);

function allowedPhases(role: KnowledgeScope) {
  const night: Partial<Record<KnowledgeScope, string>> = {
    WEREWOLF: "NIGHT_WEREWOLF",
    SEER: "NIGHT_SEER",
    WITCH: "NIGHT_WITCH",
  };
  return [
    ...(role === "COMMON"
      ? ["NIGHT_WEREWOLF", "NIGHT_SEER", "NIGHT_WITCH"]
      : night[role]
        ? [night[role]!]
        : []),
    "DAY_DISCUSSION",
    "DAY_VOTE",
  ];
}
function fresh(): KnowledgeEntry {
  return {
    id: "",
    title: "",
    scope: scope.value,
    phases: ["DAY_DISCUSSION", "DAY_VOTE"],
    content: "",
    priority: 50,
    enabled: true,
  };
}
function reset() {
  editing.value = "";
  editRevision.value = "";
  draft.value = fresh();
  pendingDelete.value = "";
}
function startEdit(entry: KnowledgeEntry) {
  editing.value = entry.id;
  editRevision.value = snapshot.value?.revision ?? "";
  draft.value = { ...entry, phases: [...entry.phases] };
  pendingDelete.value = "";
}
function changeScope() {
  draft.value.phases = draft.value.phases.filter((p) =>
    allowedPhases(draft.value.scope).includes(p),
  );
  if (!draft.value.phases.length)
    draft.value.phases = ["DAY_DISCUSSION", "DAY_VOTE"];
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const result = await knowledgeApi.get();
    snapshot.value = result.snapshot;
    directory.value = result.directory;
    reset();
  } catch (e) {
    error.value = knowledgeError(e);
  } finally {
    busy.value = false;
  }
}
async function save() {
  if (busy.value || !snapshot.value) return;
  if (
    !draft.value.title.trim() ||
    !draft.value.content.trim() ||
    !draft.value.phases.length ||
    !Number.isInteger(draft.value.priority) ||
    draft.value.priority < 0 ||
    draft.value.priority > 100
  ) {
    error.value = "请填写标题、正文、适用阶段和 0 至 100 的整数优先级。";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    snapshot.value = editing.value
      ? await knowledgeApi.update(draft.value, editRevision.value)
      : await knowledgeApi.add(draft.value);
    scope.value = draft.value.scope;
    notice.value = "知识已保存到本机，新建 AI 对局时可以使用。";
    reset();
  } catch (e) {
    error.value = knowledgeError(e);
  } finally {
    busy.value = false;
  }
}
async function toggle(entry: KnowledgeEntry) {
  if (busy.value || !snapshot.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    snapshot.value = await knowledgeApi.update(
      { ...entry, enabled: !entry.enabled },
      snapshot.value.revision,
    );
    notice.value = entry.enabled
      ? "已停用，新对局不再读取这条知识。"
      : "已启用，将参与新对局的知识筛选。";
  } catch (e) {
    error.value = knowledgeError(e);
  } finally {
    busy.value = false;
  }
}
async function remove(entry: KnowledgeEntry) {
  if (pendingDelete.value !== entry.id) {
    pendingDelete.value = entry.id;
    return;
  }
  if (busy.value || !snapshot.value) return;
  busy.value = true;
  error.value = "";
  try {
    snapshot.value = await knowledgeApi.remove(
      entry.id,
      snapshot.value.revision,
    );
    if (editing.value === entry.id) reset();
    pendingDelete.value = "";
    notice.value = "条目已删除，已有对局的知识快照仍然保留。";
  } catch (e) {
    error.value = knowledgeError(e);
  } finally {
    busy.value = false;
  }
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || busy.value) return;
  if (file.size > 512 * 1024 || file.size === 0) {
    error.value = "请选择非空且不超过 512 KB 的知识库 JSON 文件。";
    return;
  }
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const before = snapshot.value?.entries.length ?? 0;
    snapshot.value = await knowledgeApi.importFile(file);
    reset();
    notice.value = `已追加导入 ${snapshot.value.entries.length - before} 条知识，原有内容已保留。`;
  } catch (e) {
    error.value = knowledgeError(e);
  } finally {
    busy.value = false;
  }
}
async function exportFile() {
  busy.value = true;
  error.value = "";
  try {
    const blob = await knowledgeApi.exportFile();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "werewolf-knowledge.json";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    error.value = knowledgeError(e);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
// 切换角色分类时，新条目默认归入该分类；正在编辑的条目保持其原角色。
watch(scope, (value) => {
  if (!editing.value) {
    draft.value.scope = value;
    changeScope();
  }
});
</script>

<template>
  <div class="page knowledge-page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">LOCAL STRATEGY LIBRARY</div>
        <h1>本地知识库</h1>
        <p>为不同角色编写策略建议，观察知识如何影响 Agent 的决策。</p>
      </div>
      <div class="knowledge-tools">
        <button class="subtle-button" :disabled="busy" @click="load">
          刷新
        </button>
        <button
          class="subtle-button"
          :disabled="busy || !snapshot"
          @click="exportFile"
        >
          导出 JSON
        </button>
        <button
          class="subtle-button"
          :disabled="busy || !snapshot"
          @click="fileInput?.click()"
        >
          导入 JSON
        </button>
        <input
          ref="fileInput"
          type="file"
          accept=".json,application/json"
          hidden
          @change="importFile"
        />
      </div>
    </div>
    <p v-if="error" class="experiment-message error" role="alert">
      {{ error }}
    </p>
    <p v-if="notice" class="experiment-message" role="status">{{ notice }}</p>
    <section class="panel knowledge-intro">
      <strong>通用知识 + 当前角色知识</strong>
      <p>
        Agent 只读取自己角色和当前行动阶段的建议。每步最多筛选 4
        条；优先级越高越先选中。游戏规则与合法行动仍由引擎决定。
      </p>
      <p>
        在总览创建 AI
        对局时选择知识模式。每场对局固定开局快照，编辑内容只影响新对局。本知识库由本机浏览器共享。
      </p>
      <small v-if="snapshot"
        >共 {{ snapshot.entries.length }} / 200 条 · {{ enabledCount }} 条启用 ·
        版本 {{ snapshot.revision.slice(0, 12) }}</small
      >
      <small v-if="directory" class="knowledge-directory"
        >保存位置：{{ directory }}</small
      >
    </section>
    <div class="knowledge-layout">
      <section class="panel knowledge-library">
        <div class="section-head">
          <h2>知识条目</h2>
          <button class="subtle-button" :disabled="busy" @click="reset">
            新增知识
          </button>
        </div>
        <div class="knowledge-tabs" role="group" aria-label="知识所属角色">
          <button
            v-for="item in scopes"
            :key="item"
            :class="{ selected: scope === item }"
            :aria-pressed="scope === item"
            @click="
              scope = item;
              pendingDelete = '';
            "
          >
            {{ scopeName[item] }}
            <small>{{
              snapshot?.entries.filter((e) => e.scope === item).length ?? 0
            }}</small>
          </button>
        </div>
        <input
          v-model="query"
          class="knowledge-search"
          aria-label="搜索知识"
          placeholder="搜索标题或正文…"
        />
        <p v-if="!snapshot" class="muted">
          {{ busy ? "正在读取本地知识库…" : "知识库尚未载入，请刷新重试。" }}
        </p>
        <p v-else-if="!entries.length" class="muted">
          此分类暂无匹配知识，可在右侧添加。
        </p>
        <article
          v-for="entry in entries"
          :key="entry.id"
          class="knowledge-card"
          :class="{ disabled: !entry.enabled, editing: editing === entry.id }"
        >
          <div class="knowledge-card-head">
            <h3>{{ entry.title }}</h3>
            <span>{{ entry.enabled ? "已启用" : "已停用" }}</span>
          </div>
          <div class="knowledge-tags">
            <span v-for="phase in entry.phases" :key="phase">{{
              phaseName[phase]
            }}</span
            ><span>优先级 {{ entry.priority }}</span>
          </div>
          <p class="knowledge-content">{{ entry.content }}</p>
          <div class="knowledge-card-actions">
            <button :disabled="busy" @click="startEdit(entry)">编辑</button>
            <button :disabled="busy" @click="toggle(entry)">
              {{ entry.enabled ? "停用" : "启用" }}
            </button>
            <button :disabled="busy" @click="remove(entry)">
              {{ pendingDelete === entry.id ? "确认删除" : "删除" }}
            </button>
            <button
              v-if="pendingDelete === entry.id"
              @click="pendingDelete = ''"
            >
              取消删除
            </button>
          </div>
        </article>
      </section>
      <section class="panel knowledge-editor">
        <div class="section-head">
          <h2>{{ editing ? "编辑知识" : "添加知识" }}</h2>
          <button
            v-if="editing"
            class="subtle-button"
            :disabled="busy"
            @click="reset"
          >
            取消编辑
          </button>
        </div>
        <form @submit.prevent="save">
          <fieldset :disabled="busy || !snapshot">
            <label
              >标题<input
                v-model="draft.title"
                maxlength="80"
                placeholder="例如：结合票型判断身份声明"
                required
            /></label>
            <div class="knowledge-form-row">
              <label
                >所属角色<select v-model="draft.scope" @change="changeScope">
                  <option v-for="item in scopes" :key="item" :value="item">
                    {{ scopeName[item] }}
                  </option>
                </select></label
              >
              <label
                >优先级<input
                  v-model.number="draft.priority"
                  type="number"
                  min="0"
                  max="100"
                  step="1"
                  required
              /></label>
            </div>
            <div class="knowledge-phase-label">适用阶段</div>
            <div class="knowledge-phase-options">
              <label v-for="phase in phases" :key="phase"
                ><input
                  v-model="draft.phases"
                  type="checkbox"
                  :value="phase"
                />{{ phaseName[phase] }}</label
              >
            </div>
            <label
              >策略建议<textarea
                v-model="draft.content"
                maxlength="1200"
                rows="9"
                placeholder="写下条件、依据与建议行动。请使用一般策略，不要填写本局隐藏身份或其他玩家的私有信息。"
                required
              />
            </label>
            <div class="knowledge-editor-footer">
              <label
                ><input
                  v-model="draft.enabled"
                  type="checkbox"
                />启用此知识</label
              ><small>{{ draft.content.length }} / 1200 字</small>
            </div>
            <button class="primary-button" type="submit">
              {{ busy ? "正在保存…" : editing ? "保存修改" : "添加并保存" }}
            </button>
          </fieldset>
        </form>
        <p class="muted">
          建议写清适用条件，保留不确定性。导入追加条目，重复导入也会新增条目；导入后请检查内容和启用状态。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 沿用实验平台配色；窄屏将编辑区放在条目区下方。 */
.knowledge-tools,
.knowledge-tabs,
.knowledge-tags,
.knowledge-card-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.knowledge-intro {
  margin-bottom: 22px;
}
.knowledge-intro p,
.knowledge-editor > p {
  color: #aeb8b9;
  font-size: 12px;
  line-height: 1.8;
}
.knowledge-intro small {
  color: #c5aa76;
}
.knowledge-directory {
  display: block;
  margin-top: 8px;
  overflow-wrap: anywhere;
}
.knowledge-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(320px, 1fr);
  gap: 22px;
  align-items: start;
}
.knowledge-tabs button,
.knowledge-card-actions button {
  border: 1px solid #ffffff20;
  border-radius: 6px;
  padding: 8px 11px;
  background: #19232e;
  color: #aeb8b9;
}
.knowledge-tabs .selected {
  border-color: #c5aa76;
  color: #f0d39e;
  background: #3b3324;
}
.knowledge-tabs small {
  opacity: 0.7;
}
.knowledge-search {
  margin: 18px 0;
  width: 100%;
}
.knowledge-card {
  padding: 17px;
  border: 1px solid #ffffff15;
  border-radius: 8px;
  margin-top: 12px;
  background: #141e29;
}
.knowledge-card.disabled {
  opacity: 0.65;
}
.knowledge-card.editing {
  border-color: #c5aa76;
}
.knowledge-card-head {
  display: flex;
  gap: 12px;
  justify-content: space-between;
  align-items: baseline;
}
.knowledge-card h3 {
  margin: 0 0 10px;
  font-size: 14px;
  overflow-wrap: anywhere;
}
.knowledge-card-head > span {
  font-size: 10px;
  color: #a7c5b2;
  flex-shrink: 0;
}
.knowledge-tags span {
  padding: 3px 7px;
  color: #a9b5c0;
  background: #ffffff08;
  border-radius: 4px;
  font-size: 10px;
}
.knowledge-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
  color: #d1d5cf;
  font-size: 12px;
}
.knowledge-card-actions button {
  font-size: 11px;
}
.knowledge-editor fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.knowledge-editor label {
  display: block;
  color: #aeb8b9;
  font-size: 12px;
}
.knowledge-editor label > input:not([type="checkbox"]),
.knowledge-editor select,
.knowledge-editor textarea {
  display: block;
  width: 100%;
  margin: 9px 0 18px;
}
.knowledge-editor input,
.knowledge-editor select,
.knowledge-editor textarea,
.knowledge-search {
  background: #141e29;
  border: 1px solid #ffffff25;
  color: #eeeae1;
  padding: 10px;
  border-radius: 6px;
  font: inherit;
  font-size: 12px;
}
.knowledge-editor textarea {
  resize: vertical;
  line-height: 1.8;
}
.knowledge-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.knowledge-phase-label {
  font-size: 12px;
  color: #aeb8b9;
  margin-bottom: 10px;
}
.knowledge-phase-options {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.knowledge-phase-options input,
.knowledge-editor-footer input {
  accent-color: #c5aa76;
}
.knowledge-editor-footer {
  display: flex;
  justify-content: space-between;
  margin-bottom: 18px;
  gap: 8px;
  color: #aeb8b9;
}
.knowledge-editor-footer small {
  font-size: 10px;
}
button:disabled {
  cursor: default;
  opacity: 0.5;
}
@media (max-width: 1050px) {
  .knowledge-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .knowledge-page .page-heading {
    flex-wrap: wrap;
    gap: 16px;
  }
}
</style>
