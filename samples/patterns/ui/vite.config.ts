import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import { tourSources } from "./src/code/plugin";

// The tour calls the patterns sample same-origin. `pnpm dev` forwards those
// calls to the running service; `pnpm build` writes into the directory the
// service serves at `/`, so the service's Maven build needs no Node.
const service = "http://localhost:9164";

/** The sample's routes the tour calls: the oversight contract and each scenario's own routes. */
const ROUTES = [
  "/oversight",
  "/payments",
  "/notices",
  "/rollouts",
  "/handoffs",
  "/waivers",
  "/matches",
  "/decisions",
  "/outputs",
  "/replies",
  "/monitor",
  "/attestations",
  "/quality",
  "/records",
  "/eligibility",
  "/agent-approvals",
  "/tool-calls"
];

export default defineConfig({
  plugins: [react(), tourSources()],
  resolve: { conditions: ["source", "module", "browser", "development|production"] },
  // Tests run in Node, which resolves through the SSR conditions; the kit is read from its source there too.
  ssr: { resolve: { conditions: ["source", "module", "node", "development|production"] } },
  server: {
    port: 5185,
    strictPort: true,
    proxy: Object.fromEntries(ROUTES.map((route) => [route, service]))
  },
  build: {
    outDir: "../src/main/resources/static-resources",
    emptyOutDir: true
  },
  test: {
    include: ["src/**/*.test.ts"]
  }
});
