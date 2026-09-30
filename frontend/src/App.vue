<script setup lang="ts">
import { useLabStore } from "./store";
const store = useLabStore();
// 左侧摘要跟随首页当前正在编辑的身份配置变化。
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand">
        <span class="brand-mark">☽</span
        ><span><strong>月下议会</strong><small>WEREWOLF LAB</small></span>
      </RouterLink>
      <div class="nav-label">实验空间</div>
      <nav class="main-nav">
        <RouterLink to="/" exact-active-class="active"
          ><span>◈</span> 总览</RouterLink
        >
        <RouterLink to="/history" active-class="active"
          ><span>▤</span> 对局历史</RouterLink
        >
        <RouterLink to="/stats" active-class="active"
          ><span>▥</span> 实验统计</RouterLink
        >
        <RouterLink to="/settings" active-class="active"
          ><span>⚙</span> AI 设置</RouterLink
        >
      </nav>
      <div class="sidebar-bottom">
        <div class="status-dot"></div>
        <span>本地实验环境</span>
        <small
          >{{ store.config.playerCount }} 人 · {{ store.config.werewolves }} 狼
          · {{ store.config.villagers }} 村 · {{ store.config.seers }} 预言家 ·
          {{ store.config.witches }} 女巫</small
        >
      </div>
    </aside>
    <main class="main-content">
      <header class="topbar">
        <span>多 Agent 协同与对抗研究平台</span
        ><span class="topbar-right">本科毕业设计 · 演示版</span>
      </header>
      <div v-if="store.error" class="global-error">
        {{ store.error }} <button @click="store.error = ''">×</button>
      </div>
      <RouterView />
    </main>
  </div>
</template>
