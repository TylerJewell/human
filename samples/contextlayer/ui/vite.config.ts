import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The screens call the service same-origin. `pnpm dev` forwards those calls to
// the running service; `pnpm build` writes into the directory the service
// serves at `/`, so the service's Maven build needs no Node.
const service = "http://localhost:9165";

export default defineConfig({
  plugins: [react()],
  resolve: { conditions: ["source", "module", "browser", "development|production"] },
  // Tests run in Node, which resolves through the SSR conditions; the kit is read from its source there too.
  ssr: { resolve: { conditions: ["source", "module", "node", "development|production"] } },
  server: {
    port: 5183,
    strictPort: true,
    proxy: { "/oversight": service, "/links": service, "/cases": service }
  },
  build: {
    outDir: "../src/main/resources/static-resources",
    emptyOutDir: true
  },
  test: {
    include: ["src/**/*.test.ts"]
  }
});
