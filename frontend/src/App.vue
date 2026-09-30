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
        <RouterLink
          to="/"
          exact-active-class="active"
          aria-label="总览"
          title="总览"
          ><span aria-hidden="true">◈</span
          ><span class="nav-text">总览</span></RouterLink
        >
        <RouterLink
          to="/history"
          active-class="active"
          aria-label="对局历史"
          title="对局历史"
          ><span aria-hidden="true">▤</span
          ><span class="nav-text">对局历史</span></RouterLink
        >
        <RouterLink
          to="/experiments"
          active-class="active"
          aria-label="实验记录"
          title="实验记录"
          ><span aria-hidden="true">▥</span
          ><span class="nav-text">实验记录</span></RouterLink
        >
        <RouterLink
          to="/knowledge"
          active-class="active"
          aria-label="本地知识库"
          title="本地知识库"
          ><span aria-hidden="true">▧</span
          ><span class="nav-text">本地知识库</span></RouterLink
        >
        <RouterLink
          to="/settings"
          active-class="active"
          aria-label="AI 设置"
          title="AI 设置"
          ><span aria-hidden="true">⚙</span
          ><span class="nav-text">AI 设置</span></RouterLink
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
