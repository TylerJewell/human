import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { Plugin } from "vite";
import { extract, type Snippet } from "./extract";
import { SOURCES } from "./sources";

// The build step behind `virtual:tour-sources`: reads each source the tour
// names from the repository and serves the cut snippets as a module. In
// `pnpm dev` an edit to one of those files reloads the tour.

const ID = "virtual:tour-sources";
const RESOLVED = "\0" + ID;
const ROOT = resolve(fileURLToPath(new URL(".", import.meta.url)), "../../../../..");

export function tourSources(): Plugin {
  return {
    name: "tour-sources",
    resolveId: (id) => (id === ID ? RESOLVED : undefined),
    load(id) {
      if (id !== RESOLVED) return undefined;
      const files = new Map<string, string>();
      const snippets: Record<string, Snippet> = {};
      for (const [key, ref] of Object.entries(SOURCES)) {
        const path = resolve(ROOT, ref.file);
        if (!files.has(path)) {
          files.set(path, readFileSync(path, "utf8"));
          this.addWatchFile(path);
        }
        snippets[key] = extract(files.get(path)!, ref);
      }
      return `export default ${JSON.stringify(snippets)};`;
    }
  };
}
