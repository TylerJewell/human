import { useState, type ReactNode } from "react";

// The tour's small parts: icons, eyebrows, frames for the screens a scenario
// shows, pills, buttons and key-value rows, in the tour's look (theme.css).

const ICONS: Record<string, ReactNode> = {
  agent: (
    <>
      <rect x="4.5" y="7.5" width="15" height="11" rx="3" />
      <path d="M12 7.5V4.5M9 12.5v.1M15 12.5v.1M9.5 15.5h5" />
    </>
  ),
  letter: (
    <>
      <rect x="3.5" y="5.5" width="17" height="13" rx="1.5" />
      <path d="M4 6.5l8 6 8-6" />
    </>
  ),
  chat: <path d="M4.5 6.5a2 2 0 0 1 2-2h11a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2H10l-4 3.5v-3.5h0a1.5 1.5 0 0 1-1.5-1.5z" />,
  records: (
    <>
      <rect x="3.5" y="5" width="7.5" height="14" rx="1.2" />
      <rect x="13" y="5" width="7.5" height="14" rx="1.2" />
      <path d="M5.5 9h3.5M5.5 12h3.5M15 9h3.5M15 12h3.5" />
    </>
  ),
  chart: <path d="M4 19.5h16M5.5 15l4-4.5 3.5 3 5.5-7" />,
  calendar: (
    <>
      <rect x="4" y="5.5" width="16" height="14" rx="1.5" />
      <path d="M4 10h16M8.5 3.5v4M15.5 3.5v4" />
    </>
  ),
  halt: (
    <>
      <path d="M8.3 3.5h7.4l5.3 5.3v7.4l-5.3 5.3H8.3L3 16.2V8.8z" />
      <path d="M9.5 9.5v5M14.5 9.5v5" />
    </>
  ),
  rollout: (
    <>
      <path d="M5 18.5l14-13" />
      <path d="M10.5 5.5H19v8.5" />
    </>
  ),
  rule: (
    <>
      <path d="M12 3.5l7.5 2.8v5.7c0 4.4-3.1 8.1-7.5 9.5-4.4-1.4-7.5-5.1-7.5-9.5V6.3z" />
      <path d="M9 12h6" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8.5" r="3.8" />
      <path d="M4.5 20.5a7.5 7.5 0 0 1 15 0" />
    </>
  ),
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  play: <path d="M8 5.5v13l10.5-6.5z" />,
  arrowR: (
    <>
      <path d="M5 12h13.5" />
      <path d="M13 6l6 6-6 6" />
    </>
  ),
  reset: (
    <>
      <path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3" />
      <path d="M4.5 4.5v4h4" />
    </>
  ),
  code: <path d="M9 7l-5 5 5 5M15 7l5 5-5 5" />,
  flag: (
    <>
      <path d="M6 20.5V4.5" />
      <path d="M6 5h11l-2.5 4L17 13H6" />
    </>
  ),
  eye: (
    <>
      <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z" />
      <circle cx="12" cy="12" r="2.8" />
    </>
  ),
  ledger: (
    <>
      <path d="M6 3.5h9.5l3 3v14H6z" />
      <path d="M9 10h6.5M9 13.5h6.5M9 17h4" />
    </>
  ),
  copy: (
    <>
      <rect x="8.5" y="8.5" width="11" height="11" rx="1.5" />
      <path d="M15.5 8.5v-3a1 1 0 0 0-1-1h-9a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3" />
    </>
  )
};

export function Icon({ name, size = 16 }: { name: string; size?: number }) {
  return (
    <svg
      className="pt-icon"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {ICONS[name] ?? null}
    </svg>
  );
}

export function Eyebrow({ children }: { children: ReactNode }) {
  return <div className="pt-eyebrow">{children}</div>;
}

export type Tone = "wait" | "ok" | "bad" | "idle" | "gold";

export function Pill({ tone = "idle", children, pulse }: { tone?: Tone; children: ReactNode; pulse?: boolean }) {
  return (
    <span className={`pt-pill pt-pill--${tone}${pulse ? " pt-pill--pulse" : ""}`}>
      <span className="pt-pill__dot" aria-hidden="true" />
      {children}
    </span>
  );
}

/**
 * A screen the scenario shows: what the agent, the applicant or the service sees. `role` says whose
 * screen it is; `status` is the scenario's state as that screen would show it.
 */
export function Frame({
  role,
  icon,
  title,
  status,
  children,
  testId
}: {
  role: string;
  icon: string;
  title: ReactNode;
  status?: ReactNode;
  children: ReactNode;
  testId?: string;
}) {
  return (
    <section className="pt-frame" data-testid={testId}>
      <header className="pt-frame__bar">
        <span className="pt-frame__icon">
          <Icon name={icon} size={15} />
        </span>
        <span className="pt-frame__role">{role}</span>
        <span className="pt-frame__title">{title}</span>
        {status ? <span className="pt-frame__status">{status}</span> : null}
      </header>
      <div className="pt-frame__body">{children}</div>
    </section>
  );
}

export function Button({
  children,
  onClick,
  variant = "primary",
  icon,
  disabled,
  busy,
  testId
}: {
  children: ReactNode;
  onClick?: () => void;
  variant?: "primary" | "secondary" | "quiet";
  icon?: string;
  disabled?: boolean;
  busy?: boolean;
  testId?: string;
}) {
  return (
    <button
      type="button"
      className={`pt-btn pt-btn--${variant}`}
      onClick={onClick}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      data-testid={testId}
    >
      {busy ? <span className="pt-spin" aria-hidden="true" /> : icon ? <Icon name={icon} size={15} /> : null}
      {children}
    </button>
  );
}

/** Runs an async action and shows its error under the button that started it. */
export function useAction(): [(fn: () => Promise<unknown>) => void, boolean, string | undefined] {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const start = (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(undefined);
    fn().then(
      () => setBusy(false),
      (e: unknown) => {
        setBusy(false);
        setError(e instanceof Error ? e.message : String(e));
      }
    );
  };
  return [start, busy, error];
}

export function ErrorText({ children }: { children?: ReactNode }) {
  return children ? (
    <p className="pt-error" role="alert">
      {children}
    </p>
  ) : null;
}

export function Rows({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="pt-rows">
      {rows.map(([k, v], i) => (
        <div key={i} className="pt-rows__row">
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Mono({ children }: { children: ReactNode }) {
  return <span className="pt-mono">{children}</span>;
}

/** A line under a frame that says what the tour did on the person's behalf. */
export function Note({ children }: { children: ReactNode }) {
  return <p className="pt-note">{children}</p>;
}

export const money = (amount: number, currency = "EUR") =>
  `${new Intl.NumberFormat("en-GB", { maximumFractionDigits: 0 }).format(amount)} ${currency}`;
