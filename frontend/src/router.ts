import { createRouter, createWebHistory } from "vue-router";
import HomeView from "./views/HomeView.vue";
import GameView from "./views/GameView.vue";
import HistoryView from "./views/HistoryView.vue";
import SettingsView from "./views/SettingsView.vue";
import StatsView from "./views/StatsView.vue";

// 对局详情页同时承载进行中操作和已完成回放。
export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: HomeView },
    { path: "/game/:id", component: GameView },
    { path: "/history", component: HistoryView },
    { path: "/settings", component: SettingsView },
    { path: "/stats", component: StatsView },
  ],
});
