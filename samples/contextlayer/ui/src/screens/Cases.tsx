import {
  DecisionPanel,
  ErrorLine,
  formatMoment,
  formatSpan,
  History,
  readable,
  serviceNow,
  SubjectView,
  useEvent,
  useNow,
  useOnSettled,
  useRegistry,
  useReviewer
} from "@akka-human/kit";
import { useState, type ReactNode } from "react";
import { useCases } from "../api";
import { CASE_FILTERS, caseCounts, casesWith, headlineColumns, type Case, type CaseFilter } from "../model";
import { Chip, Head, Kv, Loading, type Tone } from "../parts/ui";

// Cases: what a watcher found for a person to look into. Nothing waits on a
// case; it closes when a reviewer resolves or dismisses it, with a reason,
// through the decision panel of its triage event.

const STATUS: Record<Case["status"], [Tone, string]> = {
  OPEN: ["warn", "Open"],
  RESOLVED: ["ok", "Resolved"],
  DISMISSED: ["mute", "Dismissed"]
};

const ago = (at: string, now: number) => `${formatSpan(Math.max(0, now - Date.parse(at)))} ago`;

export function Cases({ id, go }: { id?: string; go: (path: string) => void }) {
  const cases = useCases();
  const now = useNow(15_000);
  const [filter, setFilter] = useState<CaseFilter>("OPEN");
  if (id) return <CaseDetail id={id} all={cases.data} error={cases.error} refresh={cases.refresh} />;
  const all = cases.data ?? [];
  const counts = caseCounts(all);
  const shown = casesWith(all, filter);
  const columns = headlineColumns(all);

  return (
    <div className="cl-page">
      <Head
        title="Cases"
        sub="A case is something a watcher found for a person to look into. Nothing waits on it; it closes when someone resolves or dismisses it, with a note."
      />
      <div className="cl-seg" role="radiogroup" aria-label="Status">
        {CASE_FILTERS.map((f) => (
          <button key={f.id} type="button" role="radio" aria-checked={filter === f.id} className={filter === f.id ? "on" : undefined} onClick={() => setFilter(f.id)}>
            {f.label} <span className="cl-num">{counts[f.id]}</span>
          </button>
        ))}
      </div>
      {cases.error ? (
        <div className="hk">
          <ErrorLine error={cases.error} />
        </div>
      ) : null}
      {cases.data === undefined && !cases.error ? (
        <Loading what="the cases" />
      ) : shown.length === 0 ? (
        <p className="cl-empty">No {filter === "ALL" ? "" : `${CASE_FILTERS.find((f) => f.id === filter)?.label.toLowerCase()} `}cases.</p>
      ) : (
        <div className="cl-card cl-card--tight">
          <table className="cl-tbl">
            <thead>
              <tr>
                <th>Case</th>
                <th>What the watcher found</th>
                {columns.map((c) => (
                  <th key={c}>{readable(c)}</th>
                ))}
                <th>Watcher</th>
                <th className="cl-r">Readings</th>
                <th>Opened</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((c) => (
                <tr key={c.id} className="cl-click" onClick={() => go(`/cases/${c.id}`)} data-testid="case-row">
                  <td className="cl-mono">
                    <a href={`#/cases/${c.id}`} onClick={(e) => e.stopPropagation()}>
                      {c.id}
                    </a>
                  </td>
                  <td>{c.title}</td>
                  {columns.map((k) => (
                    <td key={k}>{c.headline?.[k] ?? <span className="cl-faint">—</span>}</td>
                  ))}
                  <td className="cl-muted">{c.watcherId}</td>
                  <td className="cl-r cl-num">{c.readings}</td>
                  <td className="cl-muted">{ago(c.openedAt, now)}</td>
                  <td>
                    <Chip tone={STATUS[c.status][0]}>{STATUS[c.status][1]}</Chip>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function CaseDetail({ id, all, error, refresh }: { id: string; all?: Case[]; error?: Error; refresh: () => Promise<void> }) {
  const found = all?.find((c) => c.id === id);
  const event = useEvent(found?.eventId ?? undefined).data;
  // The case closes when its event is decided, here or anywhere else.
  useOnSettled(found?.eventId ?? undefined, () => void refresh());
  const registry = useRegistry().data;
  const me = useReviewer().data;
  const now = useNow(1000);
  const nameOf = (s: string) => registry?.overseers.find((o) => o.subject === s)?.name ?? s;
  const back: [string, string] = ["Cases", "/cases"];
  if (!found) {
    return (
      <div className="cl-page">
        <Head back={back} title={id} />
        {error ? (
          <div className="hk">
            <ErrorLine error={error} />
          </div>
        ) : all ? (
          <p className="cl-empty">There is no case {id}.</p>
        ) : (
          <Loading what="the case" />
        )}
      </div>
    );
  }

  const evidence: [string, ReactNode][] = Object.entries(found.headline ?? {}).map(([k, v]) => [readable(k), v]);
  evidence.push(["Readings past the threshold", <span className="cl-num">{found.readings}</span>]);
  evidence.push(["Opened", `${formatMoment(found.openedAt)}, by the watcher ${found.watcherId}`]);
  if (found.status !== "OPEN") {
    evidence.push([found.status === "RESOLVED" ? "Resolved by" : "Dismissed by", found.closedBy ? nameOf(found.closedBy) : "—"]);
    if (found.reason) evidence.push(["Note", found.reason]);
  }

  return (
    <div className="cl-page">
      <Head
        back={back}
        title={
          <>
            <span className="cl-mono cl-muted">{found.id}</span> · {found.title}
          </>
        }
        chips={<Chip tone={STATUS[found.status][0]}>{STATUS[found.status][1]}</Chip>}
        sub={`The watcher ${found.watcherId} opened this case when a reading crossed its threshold. Nothing waits on it; it closes when a reviewer resolves or dismisses it, with a note.`}
      />
      <div className="cl-decide">
        <div className="cl-decide__main">
          <h2>Evidence</h2>
          <div className="cl-card">
            <Kv rows={evidence} />
          </div>
          <h2>What the watcher saw</h2>
          <div className="cl-card hk">{event ? <SubjectView event={event} now={serviceNow(event, now)} reviewer={me} /> : <Loading what="the reading" />}</div>
        </div>
        <div className="cl-decide__aside">{event ? <DecisionPanel event={event} /> : null}</div>
      </div>
      {event ? (
        <div className="cl-history hk">
          <History event={event} nameOf={nameOf} />
        </div>
      ) : null}
    </div>
  );
}
