import { formatMoment, readable, serviceNow, SubjectView, useEvent, useNow, useOnSettled, useReviewer, type HumanEvent } from "@akka-human/kit";
import { useApplication, useApplications, useControlRows } from "../api";
import { byApplication, deskOrder, HELD_NOTICE, money } from "../model";
import { Facts, Panel, Pill, Problem, Reading, StatusPill } from "../parts";
import { ReviewLayout, ReviewList, useNameOf } from "./common";

// Held adverse-action notices: a decline's notice whose draft named a
// protected characteristic, held by the guardrail until a reviewer releases
// it as shown, amends it, or sends the standard letter instead.

export function Notices({ id }: { id?: string }) {
  const reviews = useControlRows(HELD_NOTICE);
  const applications = useApplications();
  const ordered = deskOrder(reviews.data ?? []);
  const rows = new Map((applications.data ?? []).map((a) => [a.id, a]));
  const selected = id ?? ordered[0]?.subject.ref ?? undefined;
  const review = selected ? byApplication(reviews.data ?? [], HELD_NOTICE).get(selected) : undefined;
  const event = useEvent(review?.id).data;
  useOnSettled(review?.id, () => void applications.refresh());

  return (
    <div className="d-split">
      <div className="d-split__list">
        <Problem error={reviews.error} />
        <ReviewList
          title="Held adverse-action notices"
          reviews={reviews.data ? ordered : undefined}
          applications={rows}
          selected={selected}
          base="/notices"
          empty="No notice is held."
        />
      </div>
      <div className="d-split__detail">
        {selected ? (
          <NoticeDetail
            key={selected}
            applicationId={selected}
            event={event}
          />
        ) : (
          <p className="d-empty">No notice has been held.</p>
        )}
      </div>
    </div>
  );
}

function NoticeDetail({ applicationId, event }: { applicationId: string; event?: HumanEvent }) {
  const read = useApplication(applicationId);
  useOnSettled(event?.id, () => void read.refresh());
  const me = useReviewer().data;
  const now = useNow(1000);
  const nameOf = useNameOf();
  const app = read.data;
  return (
    <ReviewLayout event={event}>
      <Problem error={read.error} />
      {!app ? (
        <Reading what="the application" />
      ) : (
        <Panel title={`Application ${app.id}`} aside={<StatusPill status={app.status} />}>
          <Facts
            rows={[
              ["Applicant", app.applicant],
              ["Amount", <span className="d-num">{money(app.amount)}</span>],
              ["Declined by", app.outcome ? `${nameOf(app.outcome.by)}, ${formatMoment(app.outcome.at)}` : "—"],
              ["Decline reasons", app.outcome?.reasons.map(readable).join(", ") || "—"],
              ["Underwriter's rationale", app.outcome?.rationale || "—"],
              ["Notice held", app.noticeBlockedAt ? formatMoment(app.noticeBlockedAt) : "—"]
            ]}
          />
        </Panel>
      )}
      {event ? (
        <Panel title="The held draft" aside={<Pill tone="pending">Withheld sentences removed</Pill>} testId="held-draft">
          <div className="hk">
            <SubjectView event={event} now={serviceNow(event, now)} reviewer={me} />
          </div>
        </Panel>
      ) : null}
      {app?.notice ? (
        <Panel title="Notice issued" aside={<Pill tone="good">{readable(app.notice.issuedAs)}</Pill>} testId="issued-notice">
          <p className="d-sub">
            {formatMoment(app.notice.at)} · {app.notice.model}
          </p>
          <blockquote className="d-letter">{app.notice.text}</blockquote>
        </Panel>
      ) : null}
    </ReviewLayout>
  );
}
