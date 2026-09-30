<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useLabStore } from "../store";
const store = useLabStore();
onMounted(() => store.loadGames());
// 胜率只使用已经结束的对局，进行中的手动对局不进入分母。
const completed = computed(() =>
  store.games.filter((g) => g.result !== "ONGOING"),
);
const total = computed(() => completed.value.length);
const wolfWins = computed(
  () => completed.value.filter((g) => g.result === "WEREWOLF_WIN").length,
);
const goodWins = computed(
  () => completed.value.filter((g) => g.result === "VILLAGER_WIN").length,
);
const average = computed(() =>
  total.value
    ? (completed.value.reduce((n, g) => n + g.days, 0) / total.value).toFixed(1)
    : "—",
);
</script>
<template>
  <div class="page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">EXPERIMENT OVERVIEW</div>
        <h1>实验统计</h1>
        <p>基于当前内存中的对局结果实时汇总。</p>
      </div>
    </div>
    <div class="stats-grid">
      <div class="panel stat-card">
        <span>总对局数</span><strong>{{ total }}</strong
        ><small>已完成对局</small>
      </div>
      <div class="panel stat-card wolf">
        <span>狼人胜场</span><strong>{{ wolfWins }}</strong
        ><small
          >{{ total ? Math.round((wolfWins / total) * 100) : 0 }}% 胜率</small
        >
      </div>
      <div class="panel stat-card good">
        <span>好人胜场</span><strong>{{ goodWins }}</strong
        ><small
          >{{ total ? Math.round((goodWins / total) * 100) : 0 }}% 胜率</small
        >
      </div>
      <div class="panel stat-card">
        <span>平均轮次</span><strong>{{ average }}</strong
        ><small>天 / 局</small>
      </div>
    </div>
    <div class="panel chart-panel">
      <div class="section-head">
        <div>
          <span class="section-index">WIN DISTRIBUTION</span>
          <h2>阵营胜率</h2>
        </div>
      </div>
      <div class="bar-head">
        <span>狼人阵营</span><strong>{{ wolfWins }}</strong>
      </div>
      <div class="bar-track">
        <div
          class="bar-fill wolf"
          :style="{ width: (total ? (wolfWins / total) * 100 : 0) + '%' }"
        ></div>
      </div>
      <div class="bar-head">
        <span>好人阵营</span><strong>{{ goodWins }}</strong>
      </div>
      <div class="bar-track">
        <div
          class="bar-fill good"
          :style="{ width: (total ? (goodWins / total) * 100 : 0) + '%' }"
        ></div>
      </div>
      <p class="muted">
        当前为演示统计。论文实验阶段可在此基础上加入重复试验、Agent
        分组和显著性分析。
      </p>
    </div>
  </div>
</template>
