import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The test SPA calls only the public MicroCloud API (same origin as it will be served from, under
// /microcloud). In dev we proxy /microcloud to the local backend so there is no separate dev API.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/microcloud": {
        target: "http://localhost:8080",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/microcloud/, ""),
      },
    },
  },
});
