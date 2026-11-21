import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  resolve: { dedupe: ["react", "react-dom"] },
  plugins: [react()],
  server: {
    fs: { allow: [".", "../web-shared"] },
    port: 5174,
    proxy: {
      "/api/engine/snapshot": process.env.ENGINE_URL || "http://localhost:8081",
      "/api": {
        target: process.env.CONTROL_URL || "http://localhost:8082",
        changeOrigin: true
      }
    }
  }
});
