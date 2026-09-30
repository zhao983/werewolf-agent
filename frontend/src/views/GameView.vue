<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { experimentApi } from "../experiments";
import ObserverPanel from "../components/ObserverPanel.vue";
import { useLabStore } from "../store";
import {
  phaseName,
  roleImage,
  roleName,
  type AdvanceCommand,
  type GameEvent,
  type Role,
} from "../types";

const store = useLabStore();
const route = useRoute();
const router = useRouter();
const cursor = ref(0);
const playing = ref(false);
const reveal = ref(false);
const hoveredPlayerId = ref<string | null>(null);
const selectedPlayerId = ref("all");
let timer: ReturnType<typeof setInterval> | undefined;
const game = computed(() => store.current);
const observer = computed(() => store.observer);
const events = computed(() => game.value?.events ?? []);
const live = computed(() => game.value?.summary.result === "ONGOING");
const current = computed(() => events.value[cursor.value]);
const visibleEvents = computed(() =>
  events.value
    .slice(Math.max(0, cursor.value - 12), cursor.value + 1)
    .reverse(),
);
const isNight = computed(
  () => current.value?.phase.startsWith("NIGHT") ?? false,
);
const finished = computed(() => current.value?.type === "GAME_OVER");
const secretsVisible = computed(
  () => !live.value && (reveal.value || finished.value),
);
const winnerText = computed(() =>
  game.value?.summary.result === "WEREWOLF_WIN"
    ? "狼人阵营获胜"
    : "好人阵营获胜",
);

// 回放时由事件重建当时的存活状态；进行中的对局始终停在最新事件。
const dead = computed(() => {
  const ids = new Set<string>();
  for (const event of events.value.slice(0, cursor.value + 1)) {
    if (
      (event.type === "PLAYER_EXILED" || event.type === "PLAYER_DIED") &&
      event.targetId
    )
      ids.add(event.targetId);
  }
  return ids;
});
const actionLabel = computed(() => {
  const phase = game.value?.summary.phase;
  if (phase === "NIGHT_WEREWOLF") return "下一名狼人行动";
  if (phase === "NIGHT_SEER") return "下一名预言家行动";
  if (phase === "NIGHT_WITCH") return "下一名女巫行动";
  if (phase === "DAY_DISCUSSION") return "下一名玩家发言";
  if (phase === "DAY_VOTE") return "下一名玩家投票";
  return "下一名玩家行动";
});

// 身份仅从观战接口取出并显示在用户页面；Agent 决策仍使用独立的 AgentContext。
function observerRole(id: string): Role | null {
  return (
    observer.value?.players.find((player) => player.id === id)?.role ??
    game.value?.players.find((player) => player.id === id)?.role ??
    null
  );
}
function showRole(id: string) {
  return secretsVisible.value || hoveredPlayerId.value === id;
}
function onPlayerFocus(event: FocusEvent, id: string) {
  // 鼠标点击造成的焦点不应让身份在鼠标移出后仍保持显示；键盘焦点仍可查看。
  if (
    event.target instanceof HTMLElement &&
    event.target.matches(":focus-visible")
  )
    hoveredPlayerId.value = id;
}
function displayEvent(event: GameEvent) {
  if (!secretsVisible.value) {
    if (event.type === "ROLE_ASSIGNED") return `${event.actorId} 已收到身份牌`;
    if (event.type === "WOLF_VOTE") return "一名狼人已提交夜间行动";
    if (event.type === "WOLF_TARGET") return "狼人目标已确定";
    if (event.type === "SEER_CHECK") return "预言家已完成查验";
    if (event.type === "WITCH_ACTION") return "女巫已完成夜间行动";
  }
  if (event.type === "GAME_START")
    return `${game.value?.config.playerCount ?? 7} 人对局开始`;
  if (event.type === "GAME_OVER") return winnerText.value;
  if (event.type === "PLAYER_DIED") return `${event.targetId} 昨夜出局`;
  if (event.type === "PLAYER_EXILED") return `${event.targetId} 被放逐`;
  return event.text;
}
function highlight(id: string) {
  if (!current.value) return false;
  if (
    !secretsVisible.value &&
    ["WOLF_VOTE", "WOLF_TARGET", "SEER_CHECK", "WITCH_ACTION"].includes(
      current.value.type,
    )
  )
    return false;
  return current.value.actorId === id || current.value.targetId === id;
}
function stop() {
  playing.value = false;
  if (timer) clearInterval(timer);
  timer = undefined;
}
function toggle() {
  if (playing.value) {
    stop();
    return;
  }
  if (cursor.value >= events.value.length - 1) cursor.value = 0;
  playing.value = true;
  timer = setInterval(() => {
    if (cursor.value < events.value.length - 1) cursor.value++;
    else stop();
  }, 750);
}
async function advance(command: AdvanceCommand) {
  if (!game.value || store.busy) return;
  const ok = await store.advanceGame(game.value.summary.gameId, command);
  if (ok) {
    stop();
    cursor.value = (store.current?.events.length ?? 1) - 1;
  }
}
function resetCursor() {
  cursor.value = live.value ? Math.max(0, events.value.length - 1) : 0;
}
// 原对局链接在后端重启后转到持久化回放；仍需本浏览器的存档访问凭据。
async function loadOrReplay(id: string) {
  await store.loadGame(id);
  if (route.params.id !== id || store.current) return;
  try {
    const archives = await experimentApi.archives();
    const saved = archives.find(
      (a) => a.gameId === id && a.source === "SINGLE",
    );
    if (saved && route.params.id === id) {
      store.error = "";
      await router.replace(
        `/experiments/${saved.experimentId}/replay/${saved.gameId}`,
      );
    }
  } catch {
    /* 保留原有的载入错误，存档读取失败不能伪装成成功。 */
  }
}
watch(
  () => route.params.id,
  async (id) => {
    stop();
    reveal.value = false;
    hoveredPlayerId.value = null;
    selectedPlayerId.value = "all";
    if (id) {
      await loadOrReplay(String(id));
      if (route.params.id === id) resetCursor();
    }
  },
);
onMounted(async () => {
  const id = String(route.params.id);
  await loadOrReplay(id);
  if (route.params.id === id) resetCursor();
});
onBeforeUnmount(stop);
</script>

<template>
  <div v-if="game" class="page game-page">
    <div class="page-heading">
      <div>
        <RouterLink to="/history" class="back-link">← 返回对局列表</RouterLink>
        <div class="eyebrow">
          {{ live ? "LIVE GAME" : "GAME REPLAY" }} /
          {{ game.summary.gameId.slice(0, 8).toUpperCase() }}
        </div>
        <h1>{{ live ? "对局进行中" : "对局回放" }}</h1>
        <RouterLink
          :to="`/experiments?id=${game.summary.gameId}`"
          class="small-link"
          >查看实验记录与导出 →</RouterLink
        >
        <p>
          {{
            live
              ? "每次点击只推进一名 Agent 或一个阶段。"
              : "沿时间线查看每一步行动与局势变化。"
          }}
        </p>
      </div>
      <div
        v-if="!live && (finished || reveal)"
        class="winner-badge"
        :class="game.summary.result === 'WEREWOLF_WIN' ? 'wolf' : 'good'"
      >
        {{ winnerText }}
      </div>
    </div>
    <div class="replay-banner" :class="isNight ? 'night' : 'day'">
      <div class="moon-symbol">{{ isNight ? "☾" : "☼" }}</div>
      <div>
        <span class="section-index"
          >{{ isNight ? "NIGHT PHASE" : "DAY PHASE" }} · 第
          {{ current?.day ?? 1 }} 天</span
        >
        <h2>
          {{
            phaseName[
              live ? game.summary.phase : (current?.phase ?? "GAME_START")
            ]
          }}
        </h2>
        <p>{{ current ? displayEvent(current) : "" }}</p>
      </div>
      <div class="banner-counter">
        {{ String(cursor + 1).padStart(2, "0") }}
        <span>/ {{ String(events.length).padStart(2, "0") }}</span>
      </div>
    </div>

    <div v-if="live" class="manual-progress">
      <span class="section-index"
        >MANUAL CONTROL / 第 {{ game.summary.days }} 天</span
      >
      <strong>{{ phaseName[game.summary.phase] ?? game.summary.phase }}</strong>
      <p v-if="game.nextCommand === 'NEXT_ACTION'">
        {{
          game.nextActorId
            ? `等待 ${game.nextActorId} 行动。`
            : "等待本阶段下一名特殊身份玩家行动。"
        }}点击一次只调用这一名 Agent。
      </p>
      <p v-else>本阶段玩家行动已完成，请确认进入下一阶段。</p>
      <button
        v-if="game.nextCommand === 'NEXT_ACTION'"
        class="primary-button"
        :disabled="store.busy"
        @click="advance('NEXT_ACTION')"
      >
        {{ store.busy ? "正在等待 Agent…" : actionLabel }} →
      </button>
      <button
        v-else-if="game.nextCommand === 'COMPLETE_PHASE'"
        class="primary-button"
        :disabled="store.busy"
        @click="advance('COMPLETE_PHASE')"
      >
        完成当前阶段 →
      </button>
      <button
        v-else-if="game.nextCommand === 'END_DAY'"
        class="primary-button"
        :disabled="store.busy"
        @click="advance('END_DAY')"
      >
        结束白天并进入下一轮 →
      </button>
    </div>

    <div class="replay-layout">
      <div class="replay-main">
        <div class="panel player-panel">
          <div class="section-head">
            <div>
              <span class="section-index"
                >PLAYERS /
                {{ String(game.config.playerCount).padStart(2, "0") }}</span
              >
              <h2>参赛玩家</h2>
            </div>
            <button
              v-if="!live"
              class="subtle-button"
              :disabled="finished"
              @click="reveal = !reveal"
            >
              {{ finished ? "身份已揭晓" : reveal ? "隐藏身份" : "揭晓身份" }}
            </button>
          </div>
          <p class="player-hint">
            将鼠标移到身份牌上查看身份；点击玩家筛选下方的私有记录。
          </p>
          <div class="player-grid">
            <button
              v-for="player in game.players"
              :key="player.id"
              type="button"
              class="player-card"
              :class="{
                eliminated: dead.has(player.id),
                focused: highlight(player.id),
                selected: selectedPlayerId === player.id,
              }"
              :aria-label="`查看 ${player.name} 的观战信息`"
              @mouseenter="hoveredPlayerId = player.id"
              @mouseleave="hoveredPlayerId = null"
              @focus="onPlayerFocus($event, player.id)"
              @blur="hoveredPlayerId = null"
              @click="selectedPlayerId = player.id"
            >
              <img
                :src="
                  showRole(player.id) && observerRole(player.id)
                    ? roleImage[observerRole(player.id)!]
                    : '/assets/player.png'
                "
                :alt="
                  showRole(player.id) && observerRole(player.id)
                    ? roleName[observerRole(player.id)!]
                    : '隐藏身份'
                "
              />
              <div class="player-meta">
                <strong>{{ player.name }}</strong
                ><span>{{
                  showRole(player.id) && observerRole(player.id)
                    ? `${roleName[observerRole(player.id)!]}${dead.has(player.id) ? " · 已出局" : ""}`
                    : dead.has(player.id)
                      ? "已出局"
                      : "身份隐藏"
                }}</span>
              </div>
              <span v-if="dead.has(player.id)" class="dead-mark">✕</span>
            </button>
          </div>
        </div>
        <ObserverPanel
          v-model:selected-player-id="selectedPlayerId"
          :notes="observer?.notes ?? null"
          :players="game.players.map((p) => p.id)"
          :cursor="cursor"
          :live="live"
          :knowledge="observer?.knowledge"
          :event-count="events.length"
        />
        <div v-if="!live" class="panel timeline-panel">
          <div class="section-head">
            <div>
              <span class="section-index">TIMELINE</span>
              <h2>对局时间线</h2>
            </div>
            <span class="muted">共 {{ events.length }} 条事件</span>
          </div>
          <div class="timeline-controls">
            <button
              @click="
                cursor = Math.max(0, cursor - 1);
                stop();
              "
              :disabled="cursor === 0"
            >
              ← 上一步
            </button>
            <button class="play-button" @click="toggle">
              {{ playing ? "Ⅱ 暂停" : "▶ 播放" }}
            </button>
            <button
              @click="
                cursor = Math.min(events.length - 1, cursor + 1);
                stop();
              "
              :disabled="cursor === events.length - 1"
            >
              下一步 →
            </button>
          </div>
          <input
            class="timeline-range"
            type="range"
            min="0"
            :max="Math.max(0, events.length - 1)"
            v-model.number="cursor"
            @input="stop"
          />
          <div class="timeline-endpoints">
            <span>开局</span><span>胜负判定</span>
          </div>
        </div>
      </div>
      <div class="panel event-panel">
        <div class="section-head">
          <div>
            <span class="section-index">EVENT LOG</span>
            <h2>行动记录</h2>
          </div>
        </div>
        <div class="event-list">
          <button
            v-for="(event, offset) in visibleEvents"
            :key="events.length - offset"
            class="event-row"
            :class="{ selected: event === current }"
            :disabled="live"
            @click="
              cursor = events.indexOf(event);
              stop();
            "
          >
            <span class="event-dot"></span
            ><span class="event-text"
              ><small
                >DAY {{ event.day }} ·
                {{ phaseName[event.phase] || event.phase }}</small
              ><strong>{{ displayEvent(event) }}</strong></span
            >
          </button>
        </div>
      </div>
    </div>
    <div class="game-note">
      {{
        live
          ? "公共行动记录继续隐藏真实身份和夜间目标；观战私有信息仅供本地用户查看。"
          : "身份牌在回放结束时自动揭晓，也可将鼠标移至卡片查看。"
      }}
    </div>
  </div>
  <div v-else class="page empty-state">
    {{ store.busy ? "正在载入对局…" : "暂无可展示的对局。" }}
    <RouterLink to="/">返回首页</RouterLink>
  </div>
</template>
