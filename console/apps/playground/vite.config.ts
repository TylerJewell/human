import type { IncomingMessage, ServerResponse } from "node:http";
import react from "@vitejs/plugin-react";
import type { Connect, Plugin } from "vite";
import { defineConfig } from "vitest/config";

/**
 * Forwards `/proxy/<host:port>/<path>` to `http://<host:port>/<path>`, so the playground can
 * talk to a companion or a service on another port without either serving CORS headers.
 * Only localhost targets are forwarded: this is a development server, not an open proxy.
 */
function targetProxy(): Plugin {
  const handle: Connect.NextHandleFunction = (req: IncomingMessage, res: ServerResponse) => {
    const match = /^\/((?:localhost|127\.0\.0\.1)(?::\d{1,5})?)(\/.*)?$/.exec(req.url ?? "");
    const answer = (status: number, error: string) => {
      res.statusCode = status;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error }));
    };
    if (!match) return answer(400, "Only localhost targets are forwarded.");
    const target = `http://${match[1]}${match[2] ?? "/"}`;
    const chunks: Buffer[] = [];
    req.on("data", (chunk: Buffer) => chunks.push(chunk));
    req.on("end", async () => {
      try {
        const upstream = await fetch(target, {
          method: req.method,
          headers: {
            Accept: req.headers.accept ?? "application/json",
            ...(req.headers["content-type"] ? { "Content-Type": req.headers["content-type"] } : {})
          },
          body: chunks.length && req.method !== "GET" && req.method !== "HEAD" ? Buffer.concat(chunks) : undefined
        });
        res.statusCode = upstream.status;
        res.setHeader("Content-Type", upstream.headers.get("content-type") ?? "application/json");
        res.end(Buffer.from(await upstream.arrayBuffer()));
      } catch {
        answer(502, `${match[1]} could not be reached.`);
      }
    });
  };
  return {
    name: "human-target-proxy",
    configureServer: (server) => void server.middlewares.use("/proxy", handle),
    configurePreviewServer: (server) => void server.middlewares.use("/proxy", handle)
  };
}

export default defineConfig({
  plugins: [react(), targetProxy()],
  resolve: { conditions: ["source", "module", "browser", "development|production"] },
  server: { port: 5181, strictPort: true },
  preview: { port: 5181, strictPort: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"]
  }
});
