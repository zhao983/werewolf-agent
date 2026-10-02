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
        <div class="settings-double">
          <label
            >单次请求超时（秒）<input
              v-model.number="store.llm.requestTimeoutSeconds"
              type="number"
              min="10"
              max="180"
              step="10"
          /></label>
          <label
            >输出额度参数<select v-model="store.llm.tokenLimitParameter">
              <option value="MAX_TOKENS">max_tokens（兼容默认）</option>
              <option value="MAX_COMPLETION_TOKENS">
                max_completion_tokens
              </option>
            </select></label
          >
        </div>
        <p class="muted">
          推理较慢的模型可延长超时。额度参数请按模型服务文档选择；部分服务使用
          max_completion_tokens 计入推理
          Token。服务忽略额度或用量超过设置时，实验诊断会显示提示。纠错最多再请求一次。
        </p>
        <label
          >行动决策方式
          <select v-model="store.llm.decisionMode">
            <option value="JSON">JSON 回复（兼容模式）</option>
            <option value="TOOLS">工具调用（需模型服务支持）</option>
            <option value="TOOLS_STRICT">
              工具调用 + 严格参数（需支持 strict）
            </option>
          </select>
        </label>
        <p class="muted" v-if="store.llm.decisionMode === 'JSON'">
          通过 JSON 选择行动；格式或动作错误时自动纠正一次。
        </p>
        <p class="muted" v-else>
          模型通过袭击、投票、查验、用药或发言工具提交行动。后端只提供本阶段合法工具与目标，每次仅接受一个工具调用。{{
            store.llm.decisionMode === "TOOLS_STRICT"
              ? "严格模式还要求服务按参数结构生成调用。"
              : "若服务支持 strict，可选择严格参数模式进一步约束输出。"
          }}
        </p>
        <p class="muted">
          此设置在创建对局时生效。工具调用仍需后端校验；不支持工具的接口请选择
          JSON，新建对局后生效。
        </p>
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
        <p>
          带 API Key 的远程模型地址必须使用 HTTPS；本机模型地址可使用 HTTP。
        </p>
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
