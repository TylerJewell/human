import type { ReactNode } from "react";

// The console's small parts: icons, chips, page heads and key-value lists, in
// contextlayer's own look (see theme.css).

const ICONS: Record<string, ReactNode> = {
  links: (
    <>
      <path d="M10 13.5a4.5 4.5 0 0 0 6.6.4l2.8-2.8a4.5 4.5 0 0 0-6.4-6.4l-1.5 1.5" />
      <path d="M14 10.5a4.5 4.5 0 0 0-6.6-.4l-2.8 2.8a4.5 4.5 0 0 0 6.4 6.4l1.5-1.5" />
    </>
  ),
  cases: (
    <>
      <rect x="3.5" y="7" width="17" height="12.5" rx="1.5" />
      <path d="M8.5 7V5.3a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1V7" />
      <path d="M3.5 12.5h17" />
    </>
  ),
  decisions: (
    <>
      <circle cx="9" cy="8" r="3.8" />
      <path d="M2.5 20.5a6.5 6.5 0 0 1 13 0" />
      <path d="M15.5 11l2 2 4-4.2" />
    </>
  ),
  back: (
    <>
      <path d="M19 12H5.5" />
      <path d="M11 6l-6 6 6 6" />
    </>
  ),
  shield: <path d="M12 3l7.5 2.8v5.7c0 4.4-3.1 8.1-7.5 9.5-4.4-1.4-7.5-5.1-7.5-9.5V5.8z" />,
  info: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11v5.5M12 7.8v.1" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8.5" r="3.8" />
      <path d="M4.5 20.5a7.5 7.5 0 0 1 15 0" />
    </>
  ),
  folder: <path d="M3.5 6.5a1 1 0 0 1 1-1h4.8l2 2h8.2a1 1 0 0 1 1 1v9.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1z" />,
  pin: (
    <>
      <path d="M12 20.5s-6.5-5.7-6.5-10.3a6.5 6.5 0 0 1 13 0c0 4.6-6.5 10.3-6.5 10.3z" />
      <circle cx="12" cy="10.2" r="2.3" />
    </>
  ),
  sources: (
    <>
      <ellipse cx="12" cy="5.5" rx="7.5" ry="2.8" />
      <path d="M4.5 5.5v6.2c0 1.6 3.4 2.8 7.5 2.8s7.5-1.2 7.5-2.8V5.5" />
      <path d="M4.5 11.7v6.5c0 1.6 3.4 2.8 7.5 2.8s7.5-1.2 7.5-2.8v-6.5" />
    </>
  ),
  arrowR: (
    <>
      <path d="M5 12h13.5" />
      <path d="M13 6l6 6-6 6" />
    </>
  )
};

export function Icon({ name, size = 16 }: { name: keyof typeof ICONS; size?: number }) {
  return (
    <svg
      className="cl-i"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {ICONS[name]}
    </svg>
  );
}

export type Tone = "ok" | "warn" | "info" | "bad" | "mute" | "teal" | "line";

export function Chip({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`cl-chip cl-chip--${tone}`}>{children}</span>;
}

export function Head({ title, sub, chips, back }: { title: ReactNode; sub?: ReactNode; chips?: ReactNode; back?: [string, string] }) {
  return (
    <>
      {back ? (
        <a className="cl-back" href={`#${back[1]}`}>
          <Icon name="back" />
          {back[0]}
        </a>
      ) : null}
      <div className="cl-head">
        <div className="cl-head__t">
          <h1>{title}</h1>
          {chips}
        </div>
        {sub ? <div className="cl-head__sub">{sub}</div> : null}
      </div>
    </>
  );
}

export function Kv({ rows }: { rows: [ReactNode, ReactNode, ReactNode?][] }) {
  return (
    <dl className="cl-kv">
      {rows.map(([k, v, x], i) => (
        <div key={i} className="cl-kv__row">
          <dt>{k}</dt>
          <dd>{v}</dd>
          <dd className="cl-kv__x">{x}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Loading({ what }: { what: string }) {
  return <p className="cl-empty">Reading {what}…</p>;
}
