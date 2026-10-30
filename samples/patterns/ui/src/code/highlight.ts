import type { Lang } from "./extract";

// A small highlighter for the four languages the tour shows. It colours
// keywords, types, calls, strings, numbers, annotations, keys and comments;
// it does not parse, so it never fails on a snippet.

export type TokenKind = "kw" | "type" | "fn" | "str" | "num" | "com" | "ann" | "key" | "lit" | "punct" | "text";

export interface Token {
  kind: TokenKind;
  text: string;
}

const JAVA_KEYWORDS = new Set(
  (
    "abstract boolean break case catch class continue default do double else enum extends final finally float for if " +
    "implements import instanceof int interface long new package private protected public record return static super " +
    "switch this throw throws try var void while yield"
  ).split(" ")
);

const LITERALS = new Set(["true", "false", "null"]);

/** One line of tokens per line of code. */
export function highlight(code: string, lang: Lang): Token[][] {
  const out: Token[][] = [];
  let inBlockComment = false;
  for (const line of code.split("\n")) {
    if (lang === "java") {
      const [tokens, still] = javaLine(line, inBlockComment);
      inBlockComment = still;
      out.push(tokens);
    } else if (lang === "yaml") out.push(yamlLine(line));
    else if (lang === "hocon") out.push(hoconLine(line));
    else out.push(jsonLine(line));
  }
  return out;
}

function push(tokens: Token[], kind: TokenKind, text: string) {
  if (!text) return;
  const last = tokens[tokens.length - 1];
  if (last && last.kind === kind) last.text += text;
  else tokens.push({ kind, text });
}

const JAVA = /(\/\/.*$)|(\/\*.*?(\*\/|$))|("(?:[^"\\]|\\.)*"?)|('(?:[^'\\]|\\.)*'?)|(@[A-Za-z_][\w.]*)|(\b\d[\d_]*(?:\.\d+)?[LlDdFf]?\b)|([A-Za-z_$][\w$]*)|(\s+)|(.)/g;

function javaLine(line: string, inBlockComment: boolean): [Token[], boolean] {
  const tokens: Token[] = [];
  let rest = line;
  if (inBlockComment) {
    const end = line.indexOf("*/");
    if (end < 0) return [[{ kind: "com", text: line }], true];
    push(tokens, "com", line.slice(0, end + 2));
    rest = line.slice(end + 2);
  }
  let open = false;
  for (const m of rest.matchAll(JAVA)) {
    const [text, lineComment, blockComment, blockEnd, str, chr, ann, num, word] = m;
    if (lineComment) push(tokens, "com", text);
    else if (blockComment) {
      push(tokens, "com", text);
      open = !blockEnd;
    } else if (str || chr) push(tokens, "str", text);
    else if (ann) push(tokens, "ann", text);
    else if (num) push(tokens, "num", text);
    else if (word) {
      const after = rest.slice((m.index ?? 0) + text.length);
      if (JAVA_KEYWORDS.has(word)) push(tokens, "kw", text);
      else if (LITERALS.has(word)) push(tokens, "lit", text);
      else if (/^\s*\(/.test(after)) push(tokens, "fn", text);
      else if (/^[A-Z]/.test(word)) push(tokens, "type", text);
      else push(tokens, "text", text);
    } else if (/^[{}()[\];,.<>:=!&|?+\-*/%]$/.test(text)) push(tokens, "punct", text);
    else push(tokens, "text", text);
  }
  return [tokens, open];
}

function yamlLine(line: string): Token[] {
  const tokens: Token[] = [];
  const comment = line.match(/^(.*?)(\s#.*|^#.*)$/);
  const body = comment ? comment[1] : line;
  const key = body.match(/^(\s*(?:-\s+)?)([\w.-]+)(:)(.*)$/);
  if (key) {
    push(tokens, "punct", key[1]);
    push(tokens, "key", key[2]);
    push(tokens, "punct", key[3]);
    flow(tokens, key[4]);
  } else flow(tokens, body);
  if (comment) push(tokens, "com", comment[2]);
  return tokens;
}

/** A YAML or HOCON value: strings, numbers, literals, inline keys and brackets. */
function flow(tokens: Token[], text: string) {
  const VALUE = /("(?:[^"\\]|\\.)*"?)|('(?:[^'\\]|\\.)*'?)|([\w.-]+)(?=\s*:\s)|(\b(?:true|false|null)\b)|(\b\d[\d.]*\b)|([{}[\],:=])|(\s+)|([^\s{}[\],:="']+)/g;
  for (const m of text.matchAll(VALUE)) {
    const [t, dq, sq, inlineKey, lit, num, punct] = m;
    if (dq || sq) push(tokens, "str", t);
    else if (inlineKey) push(tokens, "key", t);
    else if (lit) push(tokens, "lit", t);
    else if (num) push(tokens, "num", t);
    else if (punct) push(tokens, "punct", t);
    else push(tokens, "text", t);
  }
}

function hoconLine(line: string): Token[] {
  const tokens: Token[] = [];
  const comment = line.match(/^(.*?)(#.*|\/\/.*)$/);
  const body = comment ? comment[1] : line;
  const key = body.match(/^(\s*)("[^"]*"|[\w.-]+)(\s*)([=:{])(.*)$/);
  if (key) {
    push(tokens, "text", key[1]);
    push(tokens, "key", key[2]);
    push(tokens, "text", key[3]);
    push(tokens, "punct", key[4]);
    flow(tokens, key[5]);
  } else flow(tokens, body);
  if (comment) push(tokens, "com", comment[2]);
  return tokens;
}

function jsonLine(line: string): Token[] {
  const tokens: Token[] = [];
  const JSONISH = /("(?:[^"\\]|\\.)*")(\s*:)?|(\b(?:true|false|null)\b)|(-?\b\d[\d.eE+-]*\b)|([{}[\],])|(\s+)|(.)/g;
  for (const m of line.matchAll(JSONISH)) {
    const [t, str, colon, lit, num, punct] = m;
    if (str && colon) {
      push(tokens, "key", str);
      push(tokens, "punct", colon);
    } else if (str) push(tokens, "str", str);
    else if (lit) push(tokens, "lit", t);
    else if (num) push(tokens, "num", t);
    else if (punct) push(tokens, "punct", t);
    else push(tokens, "text", t);
  }
  return tokens;
}
