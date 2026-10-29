import { ErrorLine, readable, useRegistry, useReviewer, useSetReviewer, type Reviewer } from "@akka-human/kit";
import type { ReactNode } from "react";
import { statusOf, type Tone } from "./model";

// The desk's small parts: panels, fact lists, status pills and the person the
// desk acts as.

export function Pill({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`d-pill d-pill--${tone}`}>{children}</span>;
}

export function StatusPill({ status }: { status: string }) {
  const s = statusOf(status);
  return <Pill tone={s.tone}>{s.label}</Pill>;
}

export function Panel({ title, aside, children, testId }: { title: ReactNode; aside?: ReactNode; children: ReactNode; testId?: string }) {
  return (
    <section className="d-panel" data-testid={testId}>
      <header className="d-panel__head">
        <h2>{title}</h2>
        {aside ? <div className="d-panel__aside">{aside}</div> : null}
      </header>
      <div className="d-panel__body">{children}</div>
    </section>
  );
}

export function Facts({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="d-facts">
      {rows.map(([k, v], i) => (
        <div key={i}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Problem({ error }: { error?: Error }) {
  return error ? (
    <div className="hk d-problem">
      <ErrorLine error={error} />
    </div>
  ) : null;
}

export const Reading = ({ what }: { what: string }) => <p className="d-empty">Reading {what}…</p>;

const ACTING_AS = "loan-desk:acting-as";

export function storedReviewer(): Reviewer | null {
  try {
    const raw = localStorage.getItem(ACTING_AS);
    return raw ? (JSON.parse(raw) as Reviewer) : null;
  } catch {
    return null;
  }
}

function storeReviewer(reviewer: Reviewer) {
  try {
    localStorage.setItem(ACTING_AS, JSON.stringify(reviewer));
  } catch {
    // The choice then lasts until the page is reloaded.
  }
}

/** The signed-in officer: a person from the service's overseer registry, acting in all their roles. */
export function ActingAs() {
  const registry = useRegistry();
  const current = useReviewer();
  const set = useSetReviewer();
  const people = registry.data?.overseers ?? [];
  const choose = (subject: string) => {
    const person = people.find((p) => p.subject === subject);
    if (!person) return;
    const reviewer: Reviewer = { subject: person.subject, name: person.name, roles: person.roles };
    set.run(reviewer).then(() => storeReviewer(reviewer), () => undefined);
  };
  return (
    <div className="d-officer" data-testid="acting-as">
      <label>
        <span>Officer</span>
        <select value={current.data?.subject ?? ""} onChange={(e) => choose(e.target.value)} aria-label="Acting as">
          <option value="" disabled>
            {registry.data ? "Sign in as…" : "Reading the registry…"}
          </option>
          {people.map((p) => (
            <option key={p.subject} value={p.subject}>
              {p.name ?? p.subject}
              {p.qualified ? "" : " (not qualified)"}
            </option>
          ))}
        </select>
      </label>
      {current.data ? <span className="d-officer__roles">{current.data.roles.map(readable).join(", ")}</span> : null}
      <Problem error={set.error ?? registry.error} />
    </div>
  );
}
