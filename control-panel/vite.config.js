import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  resolve: { dedupe: ["react", "react-dom"] },
  plugins: [react()],
  server: {
    fs: { allow: [".", "../web-shared"] },
    port: 5174,
    proxy: {
      "/api": {
        target: "http://localhost:8082",
        changeOrigin: true
      }
    }
  }
});
