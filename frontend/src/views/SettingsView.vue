<script setup lang="ts">
import { useLabStore } from "../store";
const store = useLabStore();
// API Key 仅保存在当前 Pinia 内存状态，不写入 localStorage。
</script>
<template>
  <div class="page">
    <div class="page-heading">
      <div>
        <div class="eyebrow">MODEL CONFIGURATION</div>
        <h1>AI 设置</h1>
        <p>配置兼容 OpenAI Chat Completions 的模型服务，供 LLM Agent 使用。</p>
      </div>
    </div>
    <div class="settings-layout">
      <div class="panel settings-panel">
        <span class="section-index">CONNECTION</span>
        <h2>模型连接</h2>
        <label
          >API Base URL<input
            v-model.trim="store.llm.baseUrl"
            placeholder="https://api.example.com/v1"
            autocomplete="off" /></label
        ><label
          >API Key<input
            v-model="store.llm.apiKey"
            type="password"
            placeholder="仅本次页面会话使用"
            autocomplete="off" /></label
        ><label
          >Model<input
            v-model.trim="store.llm.model"
            placeholder="model-name"
            autocomplete="off"
        /></label>
        <div class="settings-double">
          <label
            >Temperature<input
              v-model.number="store.llm.temperature"
              type="number"
              min="0"
              max="2"
              step="0.1" /></label
          ><label
            >Max Tokens<input
              v-model.number="store.llm.maxTokens"
              type="number"
              min="1"
              max="8192"
              step="100"
          /></label>
        </div>
        <RouterLink to="/" class="primary-button"
          >返回并选择 LLM Agent ↗</RouterLink
        >
      </div>
      <div class="panel security-panel">
        <span class="section-index">SECURITY</span>
        <h2>密钥如何使用</h2>
        <p>
          浏览器只把配置发送给本地 Spring Boot 后端。后端向模型服务发起请求，API
          Key 不会写入对局记录，也不会返回在对局接口中。
        </p>
        <p>带 API Key 的远程模型地址必须使用 HTTPS；本机模型地址可使用 HTTP。</p>
        <div class="security-flow">
          <span>Vue 页面</span><b>→</b><span>Spring Boot</span><b>→</b
          ><span>模型 API</span>
        </div>
        <p class="muted">
          AI 对局创建后停在首夜。每次点击推进按钮只触发一名 Agent
          或一个阶段；模型响应较慢时只需等待本次行动。页面刷新后若要创建新的 AI
          对局，需重新输入密钥；已创建的对局由后端内存继续维护。
        </p>
      </div>
    </div>
  </div>
</template>
