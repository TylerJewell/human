import {
  DecisionPanel,
  History,
  OutcomeChip,
  readClock,
  serviceNow,
  StateChip,
  useNow,
  useRegistry,
  type HumanEvent,
  type HumanEventRow
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

/** Time left on a pending review, by its service's clock. */
export function DueText({ review, now }: { review: HumanEventRow; now: number }) {
  const clock = readClock(review.sla, review.detectedAt, serviceNow(review, now));
  return <span className={`d-due d-due--${clock.tone}`}>{clock.label}</span>;
}

export function ReviewList({
  title,
  reviews,
  applications,
  selected,
  base,
  empty
}: {
  title: string;
  reviews: HumanEventRow[] | undefined;
  applications: Map<string, ApplicationRow>;
  selected?: string;
  base: string;
  empty: string;
}) {
  const now = useNow(5000);
  const nameOf = useNameOf();
  if (!reviews) return <Reading what={title.toLowerCase()} />;
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
          {reviews.length === 0 ? (
            <tr>
              <td colSpan={4} className="d-empty">
                {empty}
              </td>
            </tr>
          ) : null}
          {reviews.map((r) => {
            const ref = r.subject.ref ?? "";
            const app = applications.get(ref);
            return (
              <tr
                key={r.id}
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
                <td className="hk">{r.pending ? <StateChip state={r.state} /> : <OutcomeChip row={r} />}</td>
                <td>{r.pending ? <DueText review={r} now={now} /> : <span className="d-sub">{[...new Set(r.decidedBy ?? [])].map(nameOf).join(", ") || "—"}</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** One review: what the decision is about on the left, the decision panel on the right, the history beneath. */
export function ReviewLayout({ event, children }: { event?: HumanEvent; children: ReactNode }) {
  const nameOf = useNameOf();
  return (
    <div className="d-review">
      <div className="d-review__grid">
        <div className="d-review__main">{children}</div>
        <div className="d-review__decide">
          {event ? <DecisionPanel event={event} /> : <p className="d-empty">No review is open for this application.</p>}
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
