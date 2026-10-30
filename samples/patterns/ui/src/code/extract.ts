// Cuts the part of a source file that the tour shows: a Java method with its
// Javadoc, a builder call, a configuration block, or one control of the YAML
// catalog. The cut is made at build time from the files in the repository, so
// the code beside each step is the code the service runs.

export type Lang = "java" | "yaml" | "hocon" | "json";

export type SourceRef = { file: string; lang: Lang } & (
  | { method: string }
  | { call: string }
  | { block: string }
  | { yamlItem: string }
);

export interface Snippet {
  file: string;
  /** The first line's number in the file, from 1. */
  line: number;
  lang: Lang;
  code: string;
}

export function extract(text: string, ref: SourceRef): Snippet {
  const lines = text.split(/\r?\n/);
  const [from, to] =
    "method" in ref
      ? methodRange(lines, ref.method)
      : "call" in ref
        ? balancedRange(lines, find(lines, ref.call, ref.file), "(", ")", ref.call)
        : "block" in ref
          ? withComments(lines, balancedRange(lines, find(lines, ref.block, ref.file), "{", "}"), "#")
          : yamlRange(lines, ref.yamlItem, ref.file);
  return { file: ref.file, line: from + 1, lang: ref.lang, code: dedent(lines.slice(from, to + 1)).join("\n") };
}

function find(lines: string[], needle: string, file: string): number {
  const at = lines.findIndex((l) => l.includes(needle));
  if (at < 0) throw new Error(`${file} has no line containing ${JSON.stringify(needle)}`);
  return at;
}

/** A method from its signature to its closing brace, with the Javadoc and annotations above it. */
function methodRange(lines: string[], signature: string): [number, number] {
  const at = lines.findIndex((l) => l.includes(signature));
  if (at < 0) throw new Error(`no method ${JSON.stringify(signature)}`);
  const [, end] = balancedRange(lines, at, "{", "}");
  let start = at;
  while (start > 0 && /^\s*(\/\*\*|\*|\/\/|@)/.test(lines[start - 1])) start--;
  return [start, end];
}

/**
 * From `start` to the line where the first `open` after `from` is closed again. Brackets inside
 * string literals and line comments do not count.
 */
function balancedRange(lines: string[], start: number, open: string, close: string, from?: string): [number, number] {
  let depth = 0;
  let opened = false;
  for (let i = start; i < lines.length; i++) {
    const line = lines[i];
    let quote: string | null = null;
    const first = i === start && from ? line.indexOf(from) : 0;
    for (let j = first; j < line.length; j++) {
      const c = line[j];
      if (quote) {
        if (c === "\\") j++;
        else if (c === quote) quote = null;
        continue;
      }
      if (c === '"' || c === "'") quote = c;
      else if (c === "/" && line[j + 1] === "/") break;
      else if (c === open) {
        depth++;
        opened = true;
      } else if (c === close) {
        depth--;
        if (opened && depth === 0) return [start, i];
      }
    }
  }
  throw new Error(`unbalanced ${open}${close} from line ${start + 1}`);
}

/** Widens a range upwards over the comment lines directly above it. */
function withComments(lines: string[], [start, end]: [number, number], marker: string): [number, number] {
  while (start > 0 && lines[start - 1].trim().startsWith(marker)) start--;
  return [start, end];
}

/** One list item `- id: <id>`, to the next item at the same indent or the end of the list. */
function yamlRange(lines: string[], id: string, file: string): [number, number] {
  const head = new RegExp(`^(\\s*)- id: ${id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*$`);
  const start = lines.findIndex((l) => head.test(l));
  if (start < 0) throw new Error(`${file} has no item with id ${id}`);
  const indent = head.exec(lines[start])![1].length;
  let end = start;
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i];
    if (line.trim() === "") continue;
    const lead = line.length - line.trimStart().length;
    if (lead < indent || (lead === indent && line.trimStart().startsWith("- "))) break;
    end = i;
  }
  return [start, end];
}

function dedent(lines: string[]): string[] {
  const widths = lines.filter((l) => l.trim() !== "").map((l) => l.length - l.trimStart().length);
  const cut = widths.length ? Math.min(...widths) : 0;
  return lines.map((l) => l.slice(Math.min(cut, l.length - l.trimStart().length)));
}
