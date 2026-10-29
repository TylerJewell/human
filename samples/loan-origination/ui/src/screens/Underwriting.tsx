import { actionLabel, formatMoment, readable, useEvent, useNow, useOnSettled, type HumanEvent } from "@akka-human/kit";
import { useApplication, useApplications, useControlRows, useUnderwritingQueue } from "../api";
import { byApplication, debtToIncome, deskOrder, factsOf, money, percent, underwritingQueue, UNDERWRITING } from "../model";
import { Facts, Panel, Pill, Problem, Reading, StatusPill } from "../parts";
import { DueText, ReviewLayout, ReviewList, useNameOf } from "./common";

// The underwriting queue: applications the rule did not approve alone, waiting
// for an underwriter, each matched to its review by the review's queue row.
// The open one shows the application and the eligibility assessment beside
// the decision panel of its review, read whole.

export function Underwriting({ id }: { id?: string }) {
  const queue = useUnderwritingQueue();
  const reviews = useControlRows(UNDERWRITING);
  const applications = useApplications();
  const now = useNow(5000);
  const items = underwritingQueue(queue.data ?? [], reviews.data ?? []);
  const decided = deskOrder(reviews.data ?? []).filter((r) => !r.pending);
  const rows = new Map((applications.data ?? []).map((a) => [a.id, a]));
  const selected = id ?? items[0]?.application.id ?? decided[0]?.subject.ref ?? undefined;
  const review = selected ? byApplication(reviews.data ?? [], UNDERWRITING).get(selected) : undefined;
  const event = useEvent(review?.id).data;
  // The application leaves the queue once its review is decided, here or anywhere else.
  useOnSettled(review?.id, () => {
    void queue.refresh();
    void applications.refresh();
  });

  return (
    <div className="d-split">
      <div className="d-split__list">
        <Problem error={queue.error ?? reviews.error} />
        {queue.data === undefined && !queue.error ? (
          <Reading what="the queue" />
        ) : (
          <div className="d-list">
            <table className="d-table d-table--list">
              <caption>Waiting for an underwriter</caption>
              <thead>
                <tr>
                  <th>Application</th>
                  <th>Applicant</th>
                  <th className="d-r">Amount</th>
                  <th>Due</th>
                </tr>
              </thead>
              <tbody>
                {items.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="d-empty">
                      No application is waiting.
                    </td>
                  </tr>
                ) : null}
                {items.map(({ application: a, review: r }) => (
                  <tr
                    key={a.id}
                    className={a.id === selected ? "d-row d-row--on" : "d-row"}
                    onClick={() => (window.location.hash = `/underwriting/${a.id}`)}
                    data-testid="queue-row"
                  >
                    <td className="d-mono">
                      <a href={`#/underwriting/${a.id}`}>{a.id}</a>
                    </td>
                    <td>{a.applicant}</td>
                    <td className="d-r d-num">{money(a.amount)}</td>
                    <td>{r ? <DueText review={r} now={now} /> : <span className="d-sub">—</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <ReviewList title="Decided by underwriters" reviews={decided.slice(0, 20)} applications={rows} selected={selected} base="/underwriting" empty="Nothing decided yet." />
      </div>
      <div className="d-split__detail">
        {selected ? <ReviewDetail key={selected} applicationId={selected} event={event} /> : <p className="d-empty">Choose an application.</p>}
      </div>
    </div>
  );
}

function ReviewDetail({ applicationId, event }: { applicationId: string; event: HumanEvent | undefined }) {
  const read = useApplication(applicationId);
  useOnSettled(event?.id, () => void read.refresh());
  const nameOf = useNameOf();
  const app = read.data;
  const facts = event ? factsOf(event) : undefined;
  const dti = facts ? debtToIncome(facts) : undefined;
  const assessment = app?.assessment;
  const ai = event?.aiRecommendation;

  return (
    <ReviewLayout event={event}>
      <Problem error={read.error} />
      {!app ? (
        <Reading what="the application" />
      ) : (
        <>
          <Panel title={`Application ${app.id}`} aside={<StatusPill status={app.status} />} testId="application-panel">
            <Facts
              rows={[
                ["Applicant", app.applicant],
                ["Amount", <span className="d-num">{money(app.amount)}</span>],
                ["Term", `${app.termMonths} months`],
                ["Monthly income", <span className="d-num">{money(app.monthlyIncome)}</span>],
                ["Monthly debts", <span className="d-num">{money(app.monthlyDebts)}</span>],
                ["New instalment", <span className="d-num">{money(facts?.instalment)}</span>],
                ["Debt-to-income with the loan", <span className="d-num">{percent(dti)}</span>],
                ["Defaults in the credit file", facts?.creditDefaults ?? "—"],
                ["Documents", app.documents.map(readable).join(", ") || "None"]
              ]}
            />
          </Panel>
          <Panel
            title="Eligibility assessment"
            aside={assessment?.recommendation ? <Pill tone={assessment.recommendation === "APPROVE" ? "good" : assessment.recommendation === "DECLINE" ? "bad" : "pending"}>{readable(assessment.recommendation)}</Pill> : <Pill tone="neutral">Not assessed</Pill>}
            testId="assessment-panel"
          >
            {assessment ? (
              <Facts
                rows={[
                  ["Recommendation", assessment.recommendation ? readable(assessment.recommendation) : "None: the automated assessment is halted"],
                  ["Risk score", assessment.assessedAt ? assessment.riskScore.toFixed(2) : "—"],
                  ["Debt-to-income (assessed)", assessment.assessedAt ? percent(assessment.debtToIncome) : "—"],
                  ["Decline reasons found", assessment.reasons.length ? assessment.reasons.map(readable).join(", ") : "None"],
                  ["Referred because", assessment.referredBecause ? readable(assessment.referredBecause) : "—"],
                  ["Assessed", assessment.assessedAt ? formatMoment(assessment.assessedAt) : "—"]
                ]}
              />
            ) : (
              <p className="d-empty">Not assessed yet.</p>
            )}
            {ai ? (
              <p className="d-note" data-testid="ai-recommendation">
                <b>{ai.agent ? readable(ai.agent) : "The assessor"}</b> recommends <b>{actionLabel(ai.action, event?.actionLabels).toLowerCase()}</b>
                {ai.score != null ? ` (${Math.round(ai.score * 100)}% confidence)` : ""}
                {ai.rationale ? `: ${ai.rationale}` : "."} The underwriter decides.
              </p>
            ) : null}
          </Panel>
          {app.outcome ? (
            <Panel title="Decision on file" aside={<StatusPill status={app.outcome.decision} />}>
              <Facts
                rows={[
                  ["Decided by", app.outcome.by.startsWith("rule:") ? readable(app.outcome.by.slice(5)) : nameOf(app.outcome.by)],
                  ["When", formatMoment(app.outcome.at)],
                  ["Rationale", app.outcome.rationale || "—"]
                ]}
              />
            </Panel>
          ) : null}
        </>
      )}
    </ReviewLayout>
  );
}
