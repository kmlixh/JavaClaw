import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    port: 5173,
    // 默认 vite 已经允许跨源 (server.cors=true),xmap-ol-front 在 8100 调过来不会被 CORS 拦
    proxy: {
      // /api/* 包括 /api/bridge/invoke-result, /api/llms 等等都转给后端
      "/api": "http://localhost:8080",
      // SDK 直接调 /oauth/token 拿 access_token,这条也得转
      "/oauth": "http://localhost:8080",
      "/ws": {
        target: "ws://localhost:8080",
        ws: true
      }
    }
  },
  preview: {
    host: "0.0.0.0",
    port: 4173
  }
});
