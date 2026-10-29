import {
  DecisionPanel,
  History,
  OutcomeChip,
  readClock,
  StateChip,
  useNow,
  useRegistry,
  type HumanEvent
} from "@akka-human/kit";
import type { ReactNode } from "react";
import { money, type ApplicationRow } from "../model";
import { Reading } from "../parts";

// What every review on the desk shares: the list of reviews beside the one
// open, and the open one laid out as the application on the left, the kit's
// decision panel on the right, and the event's history beneath.

/** A person's name from the registry, or their subject. */
export function useNameOf() {
  const registry = useRegistry().data;
  return (subject: string) => registry?.overseers.find((o) => o.subject === subject)?.name ?? subject;
}

/** Time left on a pending review, by the service's clock. */
export function DueText({ event, now }: { event: HumanEvent; now: number }) {
  const clock = readClock(event.sla, event.trigger.detectedAt, now + (event.clockOffset ?? 0));
  return <span className={`d-due d-due--${clock.tone}`}>{clock.label}</span>;
}

export function ReviewList({
  title,
  events,
  applications,
  selected,
  base,
  empty
}: {
  title: string;
  events: HumanEvent[] | undefined;
  applications: Map<string, ApplicationRow>;
  selected?: string;
  base: string;
  empty: string;
}) {
  const now = useNow(5000);
  const nameOf = useNameOf();
  if (!events) return <Reading what={title.toLowerCase()} />;
  return (
    <div className="d-list">
      <table className="d-table d-table--list">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th>Application</th>
            <th>Applicant</th>
            <th>Status</th>
            <th>Due / by</th>
          </tr>
        </thead>
        <tbody>
          {events.length === 0 ? (
            <tr>
              <td colSpan={4} className="d-empty">
                {empty}
              </td>
            </tr>
          ) : null}
          {events.map((e) => {
            const ref = e.subject.ref ?? "";
            const app = applications.get(ref);
            return (
              <tr
                key={e.id}
                className={ref === selected ? "d-row d-row--on" : "d-row"}
                onClick={() => (window.location.hash = `${base}/${ref}`)}
                data-testid="review-row"
              >
                <td className="d-mono">
                  <a href={`#${base}/${ref}`}>{ref}</a>
                </td>
                <td>
                  {app?.applicant ?? "—"}
                  {app ? <div className="d-sub">{money(app.amount)}</div> : null}
                </td>
                <td className="hk">{e.pending ? <StateChip state={e.state} /> : <OutcomeChip row={e} />}</td>
                <td>{e.pending ? <DueText event={e} now={now} /> : <span className="d-sub">{[...new Set(e.decisions.map((d) => d.reviewer.subject))].map(nameOf).join(", ") || "—"}</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** One review: what the decision is about on the left, the decision panel on the right, the history beneath. */
export function ReviewLayout({ event, onWrite, children }: { event?: HumanEvent; onWrite: () => void; children: ReactNode }) {
  const nameOf = useNameOf();
  return (
    <div className="d-review">
      <div className="d-review__grid">
        <div className="d-review__main">{children}</div>
        <div className="d-review__decide">
          {event ? <DecisionPanel event={event} onWrite={onWrite} /> : <p className="d-empty">No review is open for this application.</p>}
        </div>
      </div>
      {event ? (
        <div className="hk d-history">
          <History event={event} nameOf={nameOf} />
        </div>
      ) : null}
    </div>
  );
}
