declare module "virtual:tour-sources" {
  import type { Snippet } from "./code/extract";
  const snippets: Record<import("./code/sources").SourceKey, Snippet>;
  export default snippets;
}
