import { useEffect, useMemo, useState } from "react";
import snippets from "virtual:tour-sources";
import { Icon } from "../parts/ui";
import type { CodeTab } from "../tour/model";
import type { Lang } from "./extract";
import { highlight } from "./highlight";

// The code beside a step: one tab per piece of code, the file and line it was
// cut from, and the lines the step is about lit while the rest step back. A
// live tab (the proof record, the verdict) shows JSON the service answered.

export interface LiveTab {
  label: string;
  caption: string;
  code: string;
  lang: Lang;
}

type Shown = { label: string; caption: string; line?: number; code: string; lang: Lang; focus: string[] };

const fileName = (path: string) => path.slice(path.lastIndexOf("/") + 1);

export function CodePanel({ tabs, live = [], title }: { tabs: CodeTab[]; live?: LiveTab[]; title: string }) {
  const shown: Shown[] = useMemo(
    () => [
      ...tabs.map((t) => {
        const s = snippets[t.key];
        return { label: t.label, caption: s.file, line: s.line, code: s.code, lang: s.lang, focus: t.focus ?? [] };
      }),
      ...live.map((l) => ({ label: l.label, caption: l.caption, code: l.code, lang: l.lang, focus: [] }))
    ],
    [tabs, live]
  );
  const [index, setIndex] = useState(0);
  const tabKey = tabs.map((t) => t.key).join(",");
  useEffect(() => setIndex(0), [tabKey]);
  const current = shown[Math.min(index, shown.length - 1)];

  if (!current) {
    return (
      <aside className="pt-code pt-code--empty" aria-label="Code">
        <div className="pt-code__head">
          <Icon name="code" size={15} />
          <span>{title}</span>
        </div>
        <p className="pt-code__empty">The record step reads what the service wrote. Its proof record appears here once the event has closed.</p>
      </aside>
    );
  }

  return (
    <aside className="pt-code" aria-label="Code" data-testid="code-panel">
      <div className="pt-code__head">
        <Icon name="code" size={15} />
        <span>{title}</span>
      </div>
      {shown.length > 1 ? (
        <div className="pt-code__tabs" role="tablist">
          {shown.map((s, i) => (
            <button
              key={i}
              role="tab"
              aria-selected={i === index}
              className={i === index ? "on" : undefined}
              onClick={() => setIndex(i)}
              type="button"
            >
              {s.label}
            </button>
          ))}
        </div>
      ) : null}
      <div className="pt-code__file">
        <span className="pt-mono">{current.line ? fileName(current.caption) : current.caption}</span>
        {current.line ? <span className="pt-code__where">{dirOf(current.caption)} · line {current.line}</span> : null}
      </div>
      <Code code={current.code} lang={current.lang} focus={current.focus} first={current.line ?? 1} />
    </aside>
  );
}

const dirOf = (path: string) => {
  const parts = path.split("/");
  return parts.length > 3 ? parts.slice(0, 2).join("/") + "/…" : parts.slice(0, -1).join("/");
};

export function Code({ code, lang, focus = [], first = 1 }: { code: string; lang: Lang; focus?: string[]; first?: number }) {
  const lines = useMemo(() => highlight(code, lang), [code, lang]);
  const raw = code.split("\n");
  const lit = raw.map((l) => focus.some((f) => l.includes(f)));
  const anyLit = lit.some(Boolean);
  return (
    <pre className={`pt-src${anyLit ? " pt-src--focus" : ""}`} data-testid="code">
      <code>
        {lines.map((tokens, i) => (
          <span key={i} className={`pt-src__line${lit[i] ? " on" : ""}`}>
            <span className="pt-src__n" aria-hidden="true">
              {first + i}
            </span>
            <span className="pt-src__text">
              {tokens.length === 0 ? " " : tokens.map((t, j) => (
                <span key={j} className={`t-${t.kind}`}>
                  {t.text}
                </span>
              ))}
            </span>
          </span>
        ))}
      </code>
    </pre>
  );
}
