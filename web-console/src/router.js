import { createRouter, createWebHistory } from "vue-router";

const RouteShell = { template: "<div />" };

const routes = [
  { path: "/", redirect: "/chat", component: RouteShell },
  // /login 是 App.vue 里通过 route.name 切视图渲染的,不用单独的 component
  { path: "/login", name: "login", component: RouteShell, meta: { public: true } },
  { path: "/chat/:sessionId?", name: "chat", component: RouteShell },
  { path: "/search", name: "search", component: RouteShell },
  { path: "/approvals", name: "approval", component: RouteShell },
  { path: "/memory", name: "memory", component: RouteShell },
  { path: "/usage", name: "usage", component: RouteShell },
  { path: "/lab/:taskId?", name: "lab", component: RouteShell },
  { path: "/admin-sessions", name: "admin-sessions", component: RouteShell },
  { path: "/agents", name: "agents", component: RouteShell },
  { path: "/llms", name: "llms", component: RouteShell },
  { path: "/knowledge", name: "knowledge", component: RouteShell },
  { path: "/tools", name: "tools", component: RouteShell },
  { path: "/skills", name: "skills", component: RouteShell },
  { path: "/datasources", name: "datasources", component: RouteShell },
  // P4-B: 管理员页面
  { path: "/users", name: "users", component: RouteShell },
  { path: "/tenants", name: "tenants", component: RouteShell },
  { path: "/roles", name: "roles", component: RouteShell },
  { path: "/oauth-clients", name: "oauth-clients", component: RouteShell }
];

export const router = createRouter({
  history: createWebHistory(),
  routes
});
