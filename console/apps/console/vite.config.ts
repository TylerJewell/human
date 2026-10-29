import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The console talks only to the companion, on the same origin it is served from. The dev server
// forwards the companion's routes so `pnpm dev` behaves as the companion's own copy does.
export default defineConfig({
  plugins: [react()],
  resolve: { conditions: ["source", "module", "browser", "development|production"] },
  server: {
    port: 5182,
    strictPort: true,
    proxy: { "/api/companion": "http://localhost:9167" }
  },
  preview: { port: 5182, strictPort: true, proxy: { "/api/companion": "http://localhost:9167" } },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"]
  }
});
