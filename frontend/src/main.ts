import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import "./style.css";
import App from "./App.vue";
import router from "./router";

// 在一个入口安装状态管理、路由和页面组件。
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount("#app");
