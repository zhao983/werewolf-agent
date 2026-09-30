<script setup lang="ts">
// 标签只作为实验元数据保存，不进入模型提示和规则配置。
const name = defineModel<string>("name", { default: "" });
const group = defineModel<string>("group", { default: "" });
const notes = defineModel<string>("notes", { default: "" });
</script>
<template>
  <div class="experiment-label-fields">
    <label
      >实验名称<input
        v-model="name"
        maxlength="80"
        placeholder="留空则自动命名"
    /></label>
    <label
      >实验分组<input
        v-model="group"
        maxlength="60"
        placeholder="例如：无知识基线 / 角色知识组"
    /></label>
    <label
      >实验备注<textarea
        v-model="notes"
        maxlength="2000"
        rows="3"
        placeholder="记录研究目的、控制条件或观察结果"
      />
    </label>
  </div>
</template>
<style scoped>
.experiment-label-fields label {
  display: block;
  margin: 10px 0;
  font-size: 12px;
  color: #aeb8b9;
}
.experiment-label-fields input,
.experiment-label-fields textarea {
  width: 100%;
  display: block;
  margin-top: 8px;
  padding: 10px;
  background: #141e29;
  border: 1px solid #ffffff25;
  color: #eeeae1;
  border-radius: 5px;
  font: inherit;
}
.experiment-label-fields textarea {
  resize: vertical;
  line-height: 1.7;
}
</style>
