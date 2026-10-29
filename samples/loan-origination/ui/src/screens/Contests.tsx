import { formatMoment, readable, serviceNow, SubjectView, useEvent, useNow, useOnSettled, useReviewer, type HumanEvent } from "@akka-human/kit";
import { useApplication, useApplications, useControlRows } from "../api";
import { barredReviewers, byApplication, CONTEST, deskOrder, money } from "../model";
import { Facts, Panel, Pill, Problem, Reading, StatusPill } from "../parts";
import { ReviewLayout, ReviewList, useNameOf } from "./common";

// Contests: a declined applicant's appeal, decided by a senior underwriter who
// did not make the decision under appeal. The open one shows the original
// decision and the appeal beside the decision panel, and says who is barred.

export function Contests({ id }: { id?: string }) {
  const reviews = useControlRows(CONTEST);
  const applications = useApplications();
  const ordered = deskOrder(reviews.data ?? []);
  const rows = new Map((applications.data ?? []).map((a) => [a.id, a]));
  const selected = id ?? ordered[0]?.subject.ref ?? undefined;
  const review = selected ? byApplication(reviews.data ?? [], CONTEST).get(selected) : undefined;
  const event = useEvent(review?.id).data;
  useOnSettled(review?.id, () => void applications.refresh());

  return (
    <div className="d-split">
      <div className="d-split__list">
        <Problem error={reviews.error} />
        <ReviewList title="Contested declines" reviews={reviews.data ? ordered : undefined} applications={rows} selected={selected} base="/contests" empty="No decline is contested." />
      </div>
      <div className="d-split__detail">
        {selected ? (
          <ContestDetail
            key={selected}
            applicationId={selected}
            event={event}
          />
        ) : (
          <p className="d-empty">No decline has been contested.</p>
        )}
      </div>
    </div>
  );
}

function ContestDetail({ applicationId, event }: { applicationId: string; event?: HumanEvent }) {
  const read = useApplication(applicationId);
  useOnSettled(event?.id, () => void read.refresh());
  const me = useReviewer().data;
  const now = useNow(1000);
  const nameOf = useNameOf();
  const app = read.data;
  const barred = event ? barredReviewers(event) : [];
  const decider = barred.find((s) => s === app?.outcome?.by);
  const managers = barred.filter((s) => s !== decider);
  return (
    <ReviewLayout event={event}>
      <Problem error={read.error} />
      {barred.length ? (
        <p className="d-barred" data-testid="barred">
          <b>Barred from this appeal:</b>{" "}
          {decider ? `${nameOf(decider)}, who made the decision under appeal` : ""}
          {decider && managers.length ? ", and " : ""}
          {managers.length ? `${managers.map(nameOf).join(", ")}, in the line above ${decider ? "them" : "the decision"}` : ""}. A senior
          underwriter independent of it decides.
        </p>
      ) : null}
      {!app ? (
        <Reading what="the application" />
      ) : (
        <Panel title="The original decision" aside={<StatusPill status={app.status} />} testId="original-decision">
          <Facts
            rows={[
              ["Application", `${app.id} · ${app.applicant} · ${money(app.amount)} over ${app.termMonths} months`],
              ["Decided by", app.outcome ? nameOf(app.outcome.by) : "—"],
              ["When", app.outcome ? formatMoment(app.outcome.at) : "—"],
              ["Reasons", app.outcome?.reasons.map(readable).join(", ") || "—"],
              ["Rationale", app.outcome?.rationale || "—"],
              ["Notice", app.notice ? `${readable(app.notice.issuedAs)}, issued ${formatMoment(app.notice.at)}` : "—"]
            ]}
          />
        </Panel>
      )}
      {event ? (
        <Panel title="The appeal" aside={event.pending ? <Pill tone="pending">Open</Pill> : <Pill tone="neutral">Closed</Pill>} testId="appeal">
          <div className="hk">
            <SubjectView event={event} now={serviceNow(event, now)} reviewer={me} />
          </div>
        </Panel>
      ) : null}
    </ReviewLayout>
  );
}
