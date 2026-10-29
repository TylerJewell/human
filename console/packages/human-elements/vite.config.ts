import { defineConfig } from "vitest/config";

// One self-contained module: React and the kit are bundled in, so a console that is not React
// loads the elements with a single script tag. The bundle is React's production build; the
// tests run the development build, which `act` needs.
export default defineConfig(({ command }) => ({
  resolve: { conditions: ["source", "module", "browser", "development|production"] },
  ...(command === "build" ? { define: { "process.env.NODE_ENV": JSON.stringify("production") } } : {}),
  build: {
    lib: { entry: "src/index.tsx", formats: ["es"], fileName: () => "human-elements.js" },
    target: "es2022"
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"]
  }
}));
