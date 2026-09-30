<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useLabStore } from "../store";
import type { AgentType } from "../types";

const store = useLabStore();
const router = useRouter();
const seedText = ref("");
const hasLlm = computed(() => store.agents.includes("LLM"));
const roleSum = computed(
  () =>
    Number(store.config.werewolves) +
    Number(store.config.villagers) +
    Number(store.config.seers) +
    Number(store.config.witches),
);
const configValid = computed(
  () =>
    Number.isInteger(store.config.playerCount) &&
    store.config.playerCount >= 3 &&
    store.config.playerCount <= 20 &&
    [
      store.config.werewolves,
      store.config.villagers,
      store.config.seers,
      store.config.witches,
    ].every(Number.isInteger) &&
    store.config.werewolves >= 1 &&
    store.config.villagers >= 0 &&
    store.config.seers >= 0 &&
    store.config.witches >= 0 &&
    roleSum.value === store.config.playerCount &&
    store.config.werewolves <
      store.config.playerCount - store.config.werewolves,
);
const modeText = computed(() =>
  hasLlm.value ? "AI 逐步推进" : store.manual ? "手动推进" : "自动完成",
);
onMounted(() => store.loadGames());

// 快速预设只改变控制方式，不修改人数和身份配比。
function preset(type: AgentType) {
  if (
    !Number.isInteger(store.config.playerCount) ||
    store.config.playerCount < 3 ||
    store.config.playerCount > 20
  )
    return;
  store.agents = Array<AgentType>(store.config.playerCount).fill(type);
}
function updateCount(event: Event) {
  store.setPlayerCount(Number((event.target as HTMLInputElement).value));
}
async function start() {
  if (!configValid.value) {
    store.error =
      "请检查玩家总数与身份数量：总和必须相等，且开局好人多于狼人。";
    return;
  }
  const seed =
    seedText.value.trim() === "" ? undefined : Number(seedText.value);
  if (seed !== undefined && !Number.isSafeInteger(seed)) {
    store.error = "随机种子必须是整数。";
    return;
  }
  if (hasLlm.value && (!store.llm.baseUrl || !store.llm.model)) {
    router.push("/settings");
    return;
  }
  const id = await store.createGame(seed);
  if (id) router.push(`/game/${id}`);
}
</script>

<template>
  <div class="page home-page">
    <div class="eyebrow">MULTI-AGENT WEREWOLF EXPERIMENT</div>
    <section class="hero">
      <div class="hero-copy">
        <div class="hero-kicker">
          <span class="live-pulse"></span> 规则引擎已就绪
        </div>
        <h1>当夜幕降临，<br /><em>策略开始交锋。</em></h1>
        <p>
          一个用于观察多 Agent 协同与对抗的狼人杀实验环境。规则由 Java
          引擎裁决，智能体只负责决策。
        </p>
        <div class="hero-actions">
          <button
            class="primary-button"
            @click="start"
            :disabled="store.busy || !configValid"
          >
            {{ store.busy ? "正在创建…" : "开始一场对局" }} <span>↗</span>
          </button>
          <RouterLink to="/history" class="text-link"
            >查看历史对局 →</RouterLink
          >
        </div>
      </div>
      <div class="hero-visual">
        <img src="/assets/werewolf.png" alt="狼人身份牌" />
        <div class="hero-visual-shade"></div>
        <span>THE NIGHT IS WATCHING</span>
      </div>
    </section>

    <section class="dashboard-grid">
      <div class="panel setup-panel">
        <div class="section-head">
          <div>
            <span class="section-index">01 / SETUP</span>
            <h2>配置对局</h2>
          </div>
          <span class="muted">{{ modeText }}</span>
        </div>
        <div class="config-grid">
          <label class="config-field"
            >玩家总数
            <input
              :value="store.config.playerCount"
              @input="updateCount"
              type="number"
              min="3"
              max="20"
          /></label>
          <label class="config-field"
            >狼人
            <input
              v-model.number="store.config.werewolves"
              type="number"
              min="1"
              :max="store.config.playerCount"
          /></label>
          <label class="config-field"
            >村民
            <input
              v-model.number="store.config.villagers"
              type="number"
              min="0"
              :max="store.config.playerCount"
          /></label>
          <label class="config-field"
            >预言家
            <input
              v-model.number="store.config.seers"
              type="number"
              min="0"
              :max="store.config.playerCount"
          /></label>
          <label class="config-field"
            >女巫
            <input
              v-model.number="store.config.witches"
              type="number"
              min="0"
              :max="store.config.playerCount"
          /></label>
        </div>
        <div class="config-check" :class="configValid ? 'valid' : 'invalid'">
          已分配 {{ roleSum }} / {{ store.config.playerCount }} 人
          <span>{{
            configValid ? "身份配置有效" : "需满足数量总和及开局阵营条件"
          }}</span>
        </div>
        <div class="preset-row">
          <span>参赛 Agent</span
          ><button @click="preset('RANDOM')">全部 Random</button
          ><button @click="preset('RULE')">全部 Rule</button>
        </div>
        <div class="seat-grid">
          <label
            v-for="(agent, index) in store.agents"
            :key="index"
            class="seat-row"
          >
            <span class="seat-num">{{
              String(index + 1).padStart(2, "0")
            }}</span
            ><span class="seat-title">Player {{ index + 1 }}</span>
            <select v-model="store.agents[index]">
              <option value="RANDOM">Random Agent</option>
              <option value="RULE">Rule Agent</option>
              <option value="LLM">LLM Agent</option>
            </select>
          </label>
        </div>
        <div class="setup-footer">
          <label
            >随机种子
            <input v-model="seedText" type="number" placeholder="留空则随机"
          /></label>
          <label class="manual-option"
            ><input v-model="store.manual" type="checkbox" :disabled="hasLlm" />
            逐步推进{{ hasLlm ? "（AI 必须）" : "" }}</label
          >
          <RouterLink v-if="hasLlm" to="/settings" class="small-link"
            >配置模型参数 →</RouterLink
          >
        </div>
      </div>
      <div class="right-stack">
        <div class="panel rule-panel">
          <span class="section-index">02 / RULES</span>
          <h2>本局规则</h2>
          <div class="rule-line">
            <span>阵营配置</span
            ><strong
              >{{ store.config.werewolves }} 狼人 ·
              {{
                store.config.playerCount - store.config.werewolves
              }}
              好人</strong
            >
          </div>
          <div class="rule-line">
            <span>身份构成</span
            ><strong
              >{{ store.config.villagers }} 村 · {{ store.config.seers }} 预言家
              · {{ store.config.witches }} 女巫</strong
            >
          </div>
          <div class="rule-line">
            <span>狼人胜利</span><strong>存活狼人 ≥ 存活好人</strong>
          </div>
          <div class="rule-line">
            <span>好人胜利</span><strong>全部狼人出局</strong>
          </div>
          <div class="rule-line">
            <span>投票平票</span><strong>最高票者中随机放逐</strong>
          </div>
        </div>
        <div class="panel activity-panel">
          <div class="section-head">
            <div>
              <span class="section-index">03 / ACTIVITY</span>
              <h2>近期对局</h2>
            </div>
            <RouterLink to="/history" class="small-link">全部 →</RouterLink>
          </div>
          <div v-if="!store.games.length" class="empty-small">
            还没有对局。创建第一场实验吧。
          </div>
          <RouterLink
            v-for="game in store.games.slice(0, 3)"
            :key="game.gameId"
            :to="`/game/${game.gameId}`"
            class="recent-row"
          >
            <span
              class="result-icon"
              :class="game.result === 'WEREWOLF_WIN' ? 'wolf' : 'good'"
              >{{
                game.result === "ONGOING"
                  ? "◌"
                  : game.result === "WEREWOLF_WIN"
                    ? "☾"
                    : "✧"
              }}</span
            >
            <span
              >{{
                game.result === "ONGOING"
                  ? "对局进行中"
                  : game.result === "WEREWOLF_WIN"
                    ? "狼人阵营获胜"
                    : "好人阵营获胜"
              }}<small
                >{{ game.playerCount }} 人 · 第 {{ game.days }} 天</small
              ></span
            ><span>↗</span>
          </RouterLink>
        </div>
      </div>
    </section>
  </div>
</template>
