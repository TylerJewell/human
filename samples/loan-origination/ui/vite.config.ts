import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The desk calls the service same-origin. `pnpm dev` forwards those calls to
// the running service; `pnpm build` writes into the directory the service
// serves at `/`, so the service's Maven build needs no Node.
const service = "http://localhost:9166";

export default defineConfig({
  plugins: [react()],
  resolve: { conditions: ["source", "module", "browser", "development|production"] },
  server: {
    port: 5184,
    strictPort: true,
    proxy: { "/oversight": service, "/applications": service, "/underwriting": service }
  },
  build: {
    outDir: "../src/main/resources/static-resources",
    emptyOutDir: true
  },
  test: {
    include: ["src/**/*.test.ts"]
  }
});
