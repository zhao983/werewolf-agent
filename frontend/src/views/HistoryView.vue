<script setup lang="ts">
import { onMounted } from "vue";
import { useLabStore } from "../store";
const store = useLabStore();
// 同一列表显示进行中和已结束的对局，点击后进入操作页或回放页。
onMounted(() => store.loadGames());
</script>
<template>
  <div class="page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">ARCHIVE</div>
        <h1>对局历史</h1>
        <p>本次后端运行期间的对局记录，最多保留最近 30 场。</p>
      </div>
      <RouterLink to="/" class="primary-button">创建新对局 ↗</RouterLink>
    </div>
    <div class="panel history-panel">
      <div class="table-header">
        <span>对局编号</span><span>状态</span><span>轮次</span><span>人数</span
        ><span></span>
      </div>
      <RouterLink
        v-for="(game, i) in store.games"
        :key="game.gameId"
        :to="`/game/${game.gameId}`"
        class="history-row"
        ><span
          ><b class="row-number">{{ String(i + 1).padStart(2, "0") }}</b
          ><code>{{ game.gameId.slice(0, 8) }}</code></span
        ><span
          class="result-text"
          :class="game.result === 'WEREWOLF_WIN' ? 'wolf' : 'good'"
          >{{
            game.result === "ONGOING"
              ? "◌ 进行中"
              : game.result === "WEREWOLF_WIN"
                ? "☾ 狼人胜利"
                : "✧ 好人胜利"
          }}</span
        ><span>第 {{ game.days }} 天</span><span>{{ game.playerCount }} 人</span
        ><span
          >{{ game.result === "ONGOING" ? "继续对局" : "查看回放" }} ↗</span
        ></RouterLink
      >
      <div v-if="!store.games.length" class="empty-state">
        还没有历史对局。<RouterLink to="/">开始第一场 →</RouterLink>
      </div>
    </div>
  </div>
</template>
