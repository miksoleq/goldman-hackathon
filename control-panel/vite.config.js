import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      "/api/engine/snapshot": "http://localhost:8081",
      "/api": {
        target: "http://localhost:8082",
        changeOrigin: true
      }
    }
  }
});
