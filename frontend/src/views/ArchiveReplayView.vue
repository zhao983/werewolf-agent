<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute } from "vue-router";
import axios from "axios";
import ObserverPanel from "../components/ObserverPanel.vue";
import { restoreLegacyNotes } from "../observer";
import {
  experimentApi,
  experimentError,
  type ExperimentGame,
} from "../experiments";
import {
  phaseName,
  roleImage,
  roleName,
  type GameEvent,
  type ObserverNote,
  type ObserverView,
} from "../types";

const route = useRoute();
const game = ref<ExperimentGame | null>(null);
const cursor = ref(0);
const loading = ref(false);
const error = ref("");
const playing = ref(false);
const reveal = ref(false);
const hovered = ref("");
const selectedPlayerId = ref("all");
const actualNotes = ref<ObserverNote[] | null>(null);
const observerNotice = computed(() =>
  actualNotes.value === null
    ? "旧存档未保存观战私有记录，以下只还原已有线索与行动，无法恢复原始决策说明。"
    : "",
);
const notes = computed(
  () => actualNotes.value ?? (game.value ? restoreLegacyNotes(game.value) : []),
);
let backfillTimer: ReturnType<typeof setTimeout> | undefined;
let timer: ReturnType<typeof setInterval> | undefined;
let requestNumber = 0;
const events = computed(() => game.value?.events ?? []);
const current = computed(() => events.value[cursor.value]);
const visibleEvents = computed(() =>
  events.value
    .slice(Math.max(0, cursor.value - 30), cursor.value + 1)
    .reverse(),
);
const ended = computed(() => current.value?.type === "GAME_OVER");
const showPrivate = computed(() => reveal.value || ended.value);
// 从当前时间线之前的死亡事件重建局势，不能直接使用存档的最终存活状态。
const dead = computed(
  () =>
    new Set(
      events.value
        .slice(0, cursor.value + 1)
        .filter((e) => e.type === "PLAYER_EXILED" || e.type === "PLAYER_DIED")
        .map((e) => e.targetId),
    ),
);
const winner = computed(() =>
  game.value?.result === "WEREWOLF_WIN"
    ? "狼人阵营获胜"
    : game.value?.result === "VILLAGER_WIN"
      ? "好人阵营获胜"
      : "对局未结束",
);
function display(e: GameEvent) {
  if (!showPrivate.value) {
    const hidden: Record<string, string> = {
      ROLE_ASSIGNED: "玩家已收到身份牌",
      WOLF_VOTE: "狼人已提交行动",
      WOLF_TARGET: "狼人已确定目标",
      SEER_CHECK: "预言家已完成查验",
      WITCH_ACTION: "女巫已完成行动",
    };
    if (hidden[e.type]) return hidden[e.type];
  }
  if (e.type === "GAME_START")
    return `${game.value?.seats.length ?? 7} 人对局开始`;
  if (e.type === "GAME_OVER") return winner.value;
  if (e.type === "PLAYER_DIED") return `${e.targetId} 昨夜出局`;
  if (e.type === "PLAYER_EXILED") return `${e.targetId} 被放逐`;
  return e.text;
}
function stop() {
  playing.value = false;
  if (timer) clearInterval(timer);
  timer = undefined;
}
function step(delta: number) {
  stop();
  cursor.value = Math.max(
    0,
    Math.min(events.value.length - 1, cursor.value + delta),
  );
}
function toggle() {
  if (playing.value) {
    stop();
    return;
  }
  if (!events.value.length) return;
  if (cursor.value >= events.value.length - 1) cursor.value = 0;
  playing.value = true;
  timer = setInterval(() => {
    if (cursor.value < events.value.length - 1) cursor.value++;
    else stop();
  }, 750);
}
/** 从原会话补救旧记录。临时缓存只在成功授权读取存档后使用，成功回填后删除。 */
async function loadObserver(
  id: string,
  gameId: string,
  snapshot: ExperimentGame,
  request: number,
) {
  if (snapshot.observerNotes != null) {
    actualNotes.value = snapshot.observerNotes;
    return;
  }
  if (id !== gameId) return;
  const cacheKey = `werewolf-observer-backfill:${id}`;
  let recovered: ObserverNote[] | null = null;
  try {
    const result = (
      await axios.get<ObserverView>(`/api/games/${gameId}/observer`)
    ).data;
    recovered = result.notes;
    // 更新旧后端前保留原始摘要，避免重启把仍在内存中的内容清空。
    try {
      localStorage.setItem(cacheKey, JSON.stringify(recovered));
    } catch {
      /* 无法缓存时仍展示原始内容并尝试回填。 */
    }
  } catch {
    try {
      const saved = JSON.parse(localStorage.getItem(cacheKey) ?? "null");
      if (
        Array.isArray(saved) &&
        saved.length <= 20_000 &&
        saved.every(
          (note) =>
            note &&
            typeof note.text === "string" &&
            typeof note.playerId === "string" &&
            snapshot.seats.some((seat) => seat.playerId === note.playerId) &&
            typeof note.phase === "string" &&
            (note.kind === "CLUE" || note.kind === "DECISION") &&
            Number.isInteger(note.day) &&
            Number.isInteger(note.eventIndex) &&
            note.eventIndex >= 0 &&
            note.eventIndex <= snapshot.events.length,
        )
      )
        recovered = saved;
    } catch {
      /* 无效缓存不阻止旧存档回放。 */
    }
  }
  if (request !== requestNumber || recovered === null) return;
  actualNotes.value = recovered;
  const save = async () => {
    if (request !== requestNumber) return;
    try {
      const saved = await experimentApi.backfillObserver(
        id,
        gameId,
        recovered!,
      );
      if (request === requestNumber)
        actualNotes.value = saved.observerNotes ?? recovered;
      try {
        localStorage.removeItem(cacheKey);
      } catch {
        /* 缓存清理失败不影响已经保存的存档。 */
      }
    } catch (e) {
      // 旧后端暂时没有此接口；升级过程中重试有限次数，避免不断产生失败请求。
      if (
        axios.isAxiosError(e) &&
        e.response?.status === 404 &&
        attempts++ < 30 &&
        request === requestNumber
      )
        backfillTimer = setTimeout(save, 2000);
    }
  };
  let attempts = 0;
  await save();
}
// 除旧观战记录的一次性回填外，回放只读取存档，播放不会向游戏引擎发送推进请求。
watch(
  () => [route.params.experimentId, route.params.gameId],
  async ([id, gameId]) => {
    const request = ++requestNumber;
    stop();
    game.value = null;
    error.value = "";
    cursor.value = 0;
    reveal.value = false;
    selectedPlayerId.value = "all";
    actualNotes.value = null;
    if (backfillTimer) clearTimeout(backfillTimer);
    loading.value = true;
    try {
      const result = await experimentApi.replay(String(id), String(gameId));
      if (request === requestNumber) {
        game.value = result;
        await loadObserver(String(id), String(gameId), result, request);
      }
    } catch (e) {
      if (request === requestNumber) error.value = experimentError(e);
    } finally {
      if (request === requestNumber) loading.value = false;
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++requestNumber;
  stop();
  if (backfillTimer) clearTimeout(backfillTimer);
});
</script>

<template>
  <div class="page game-page">
    <div class="page-heading">
      <div>
        <RouterLink
          :to="`/experiments?id=${route.params.experimentId}`"
          class="back-link"
          >← 返回实验记录</RouterLink
        >
        <div class="eyebrow">LOCAL ARCHIVE / READ ONLY</div>
        <h1>存档对局回放</h1>
        <p>
          从本地保存的事件回放对局，可在后端重启后或导入其他电脑的记录后观看。
        </p>
      </div>
      <div
        v-if="game && ended"
        class="winner-badge"
        :class="game.result === 'WEREWOLF_WIN' ? 'wolf' : 'good'"
      >
        {{ winner }}
      </div>
    </div>
    <p v-if="error" class="experiment-message error" role="alert">
      {{ error }}
    </p>
    <div v-if="loading" class="empty-state">正在读取本地存档…</div>
    <template v-if="game">
      <p class="muted">
        种子 {{ game.seed }} · {{ game.seats.length }} 人 ·
        {{ game.events.length }} 条事件
      </p>
      <p v-if="game.result === 'ONGOING'" class="experiment-message">
        此对局未完整结束，只能回放已保存的过程，不能从存档继续运行。
      </p>
      <div
        class="replay-banner"
        :class="current?.phase.startsWith('NIGHT') ? 'night' : 'day'"
      >
        <div class="moon-symbol">
          {{ current?.phase.startsWith("NIGHT") ? "☾" : "☼" }}
        </div>
        <div>
          <h2>
            第 {{ current?.day ?? 1 }} 天 ·
            {{ phaseName[current?.phase ?? "GAME_START"] ?? current?.phase }}
          </h2>
          <p>{{ current ? display(current) : "暂无回放事件" }}</p>
        </div>
        <div class="banner-counter">
          {{ events.length ? cursor + 1 : 0 }}
          <span>/ {{ events.length }}</span>
        </div>
      </div>
      <div class="replay-layout">
        <div class="replay-main">
          <section class="panel player-panel">
            <div class="section-head">
              <h2>参赛玩家</h2>
              <button class="subtle-button" @click="reveal = !reveal">
                {{ reveal ? "隐藏身份与夜间内容" : "揭晓身份与夜间内容" }}
              </button>
            </div>
            <p class="player-hint">
              鼠标移到身份牌上或用键盘聚焦即可查看身份；这些信息仅供用户观看。
            </p>
            <div class="player-grid">
              <button
                v-for="seat in game.seats"
                :key="seat.playerId"
                class="player-card"
                :class="{
                  eliminated: dead.has(seat.playerId),
                  selected: selectedPlayerId === seat.playerId,
                }"
                @mouseenter="hovered = seat.playerId"
                @mouseleave="hovered = ''"
                @focus="hovered = seat.playerId"
                @blur="hovered = ''"
                @click="selectedPlayerId = seat.playerId"
              >
                <img
                  :src="
                    showPrivate || hovered === seat.playerId
                      ? roleImage[seat.role]
                      : '/assets/player.png'
                  "
                  :alt="
                    showPrivate || hovered === seat.playerId
                      ? roleName[seat.role]
                      : '隐藏身份'
                  "
                />
                <div class="player-meta">
                  <strong>{{ seat.playerId }}</strong
                  ><span
                    >{{
                      showPrivate || hovered === seat.playerId
                        ? roleName[seat.role]
                        : "身份隐藏"
                    }}{{ dead.has(seat.playerId) ? " · 已出局" : "" }}</span
                  ><small>{{ seat.agentType }}</small>
                </div>
                <span v-if="dead.has(seat.playerId)" class="dead-mark">✕</span>
              </button>
            </div>
          </section>
          <ObserverPanel
            v-model:selected-player-id="selectedPlayerId"
            :notes="notes"
            :players="game.seats.map((s) => s.playerId)"
            :cursor="cursor"
            :notice="observerNotice"
            :knowledge="game.knowledge"
            :event-count="events.length"
          />
          <section class="panel timeline-panel">
            <div class="section-head">
              <h2>对局时间线</h2>
              <span class="muted">逐步或自动播放</span>
            </div>
            <div class="timeline-controls">
              <button
                :disabled="cursor === 0 || !events.length"
                @click="step(-1)"
              >
                ← 上一步
              </button>
              <button
                class="play-button"
                :disabled="!events.length"
                @click="toggle"
              >
                {{ playing ? "Ⅱ 暂停" : "▶ 播放" }}
              </button>
              <button :disabled="cursor >= events.length - 1" @click="step(1)">
                下一步 →
              </button>
            </div>
            <input
              v-model.number="cursor"
              class="timeline-range"
              type="range"
              min="0"
              :max="Math.max(0, events.length - 1)"
              :disabled="!events.length"
              aria-label="回放进度"
              @input="stop"
            />
            <div class="timeline-endpoints">
              <span>开局</span
              ><span>{{
                game.result === "ONGOING" ? "最后保存位置" : "胜负判定"
              }}</span>
            </div>
          </section>
        </div>
        <section class="panel event-panel">
          <div class="section-head"><h2>行动记录</h2></div>
          <div class="event-list">
            <button
              v-for="(event, index) in visibleEvents"
              :key="cursor - index"
              class="event-row"
              :class="{ selected: event === current }"
              @click="
                stop();
                cursor -= index;
              "
            >
              <span class="event-dot"></span
              ><span class="event-text"
                ><small
                  >第 {{ event.day }} 天 ·
                  {{ phaseName[event.phase] ?? event.phase }}</small
                ><strong>{{ display(event) }}</strong></span
              >
            </button>
          </div>
        </section>
      </div>
      <p class="game-note">
        存档不包含模型原始思考、提示词和 API Key；逐行动指标可返回实验记录查看。
      </p>
    </template>
  </div>
</template>
