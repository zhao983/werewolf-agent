import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  // 开发时把同源 /api 请求转发给 Spring Boot，页面无需持有模型服务地址。
  server: { proxy: { "/api": "http://127.0.0.1:8080" } },
});
