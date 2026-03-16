import { createRouter, createWebHistory } from "vue-router";

const routes = [
  { path: "/", redirect: "/chat" },
  { path: "/chat/:sessionId?", name: "chat" },
  { path: "/search", name: "search" },
  { path: "/approvals", name: "approval" },
  { path: "/memory", name: "memory" },
  { path: "/knowledge", name: "knowledge" },
  { path: "/tools", name: "tools" },
  { path: "/skills", name: "skills" }
];

export const router = createRouter({
  history: createWebHistory(),
  routes
});
