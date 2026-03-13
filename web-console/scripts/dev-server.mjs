import { createServer } from "vite";
import vue from "@vitejs/plugin-vue";

const server = await createServer({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    port: 5173,
    proxy: {
      "/api": "http://localhost:18081",
      "/ws": {
        target: "ws://localhost:18081",
        ws: true
      }
    }
  }
});

await server.listen();
server.printUrls();
