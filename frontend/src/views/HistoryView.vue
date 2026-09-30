<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useLabStore } from "../store";
import {
  experimentApi,
  experimentError,
  type ArchiveItem,
} from "../experiments";
const store = useLabStore();
const archives = ref<ArchiveItem[]>([]);
const error = ref("");
const loading = ref(false);
const page = ref(1);
// 保留进行中对局的继续入口，已结束和重启后的历史从磁盘记录打开。
const rows = computed(() => {
  const recordedLiveIds = new Set(
    archives.value.filter((a) => a.source === "SINGLE").map((a) => a.gameId),
  );
  const saved = archives.value.map((a) => {
    const live =
      a.source === "SINGLE" &&
      store.games.some((g) => g.gameId === a.gameId && g.result === "ONGOING");
    return {
      ...a,
      live,
      link: live
        ? `/game/${a.gameId}`
        : `/experiments/${a.experimentId}/replay/${a.gameId}`,
    };
  });
  const unsaved = store.games
    .filter((g) => !recordedLiveIds.has(g.gameId))
    .map((g) => ({
      ...g,
      experimentId: "",
      experimentName: "当前对局",
      source: "SINGLE",
      experimentStatus: "RUNNING",
      startedAt: "",
      status: "IN_PROGRESS",
      live: g.result === "ONGOING",
      link: `/game/${g.gameId}`,
    }));
  return [...unsaved, ...saved];
});
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / 30)));
const visible = computed(() =>
  rows.value.slice((page.value - 1) * 30, page.value * 30),
);
async function refresh() {
  loading.value = true;
  error.value = "";
  try {
    const [records] = await Promise.all([
      experimentApi.archives(),
      store.loadGames(),
    ]);
    archives.value = records;
    page.value = Math.min(page.value, pages.value);
  } catch (e) {
    error.value = experimentError(e);
  } finally {
    loading.value = false;
  }
}
onMounted(refresh);
</script>
<template>
  <div class="page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">LOCAL ARCHIVE</div>
        <h1>对局历史</h1>
        <p>本地保存的普通对局、批量实验和导入记录，后端重启后仍可回放。</p>
      </div>
      <RouterLink to="/" class="primary-button">创建新对局 ↗</RouterLink>
    </div>
    <p v-if="error" class="experiment-message error" role="alert">
      {{ error }}
    </p>
    <div class="panel history-panel">
      <div class="table-header">
        <span>对局编号 / 来源</span><span>状态</span><span>轮次</span
        ><span>人数</span><span></span>
      </div>
      <RouterLink
        v-for="(game, i) in visible"
        :key="`${game.experimentId}/${game.gameId}`"
        :to="game.link"
        class="history-row"
      >
        <span
          ><b class="row-number">{{
            String((page - 1) * 30 + i + 1).padStart(2, "0")
          }}</b
          ><code>{{ game.gameId.slice(0, 8) }}</code> ·
          {{
            game.source === "IMPORTED"
              ? "导入"
              : game.source === "BATCH"
                ? "批量"
                : "单局"
          }}</span
        >
        <span
          class="result-text"
          :class="game.result === 'WEREWOLF_WIN' ? 'wolf' : 'good'"
          >{{
            game.result === "WEREWOLF_WIN"
              ? "☾ 狼人胜利"
              : game.result === "VILLAGER_WIN"
                ? "✧ 好人胜利"
                : game.live
                  ? "◌ 进行中"
                  : "未结束存档"
          }}</span
        >
        <span>第 {{ game.days }} 天</span><span>{{ game.playerCount }} 人</span
        ><span>{{ game.live ? "继续对局" : "查看回放" }} ↗</span>
      </RouterLink>
      <div v-if="!rows.length" class="empty-state">
        {{
          loading
            ? "正在读取本地记录…"
            : "还没有历史对局，可在实验记录中导入 JSON。"
        }}<RouterLink to="/experiments">打开实验记录 →</RouterLink>
      </div>
    </div>
    <div class="timeline-controls" style="margin-top: 20px">
      <button :disabled="page <= 1" @click="page--">上一页</button
      ><span>{{ page }} / {{ pages }} 页 · 共 {{ rows.length }} 局</span
      ><button :disabled="page >= pages" @click="page++">下一页</button
      ><button :disabled="loading" @click="refresh">刷新</button>
    </div>
  </div>
</template>
