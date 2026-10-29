import { formatMoment, readable } from "@akka-human/kit";
import { useState } from "react";
import { useApplication, useApplications } from "../api";
import { money, percent, statusCounts, STATUS_FILTERS, statusOf, withStatus } from "../model";
import { Facts, Panel, Problem, Reading, StatusPill } from "../parts";
import { useNameOf } from "./common";

// Every application with its status; one opens to its file: the application,
// the assessment, the decision, the notice and its history, with a link to
// the review that is open on it.

export function Applications({ id }: { id?: string }) {
  const applications = useApplications();
  const [status, setStatus] = useState<string>("ALL");
  if (id) return <ApplicationFile id={id} />;
  const all = applications.data ?? [];
  const counts = statusCounts(all);
  const shown = withStatus(all, status);
  return (
    <div className="d-page">
      <div className="d-toolbar">
        <h1>Applications</h1>
        <label className="d-filter">
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)} data-testid="status-filter">
            {STATUS_FILTERS.map((s) => (
              <option key={s} value={s}>
                {s === "ALL" ? "All" : statusOf(s).label} ({counts[s] ?? 0})
              </option>
            ))}
          </select>
        </label>
      </div>
      <Problem error={applications.error} />
      {applications.data === undefined && !applications.error ? (
        <Reading what="the applications" />
      ) : (
        <table className="d-table">
          <thead>
            <tr>
              <th>Application</th>
              <th>Applicant</th>
              <th className="d-r">Amount</th>
              <th>Status</th>
              <th>Assessed</th>
              <th>Referred</th>
              <th>Notice held</th>
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 ? (
              <tr>
                <td colSpan={7} className="d-empty">
                  No application{status === "ALL" ? "" : ` is ${statusOf(status).label.toLowerCase()}`}.
                </td>
              </tr>
            ) : null}
            {shown.map((a) => (
              <tr key={a.id} className="d-row" onClick={() => (window.location.hash = `/applications/${a.id}`)} data-testid="application-row">
                <td className="d-mono">
                  <a href={`#/applications/${a.id}`}>{a.id}</a>
                </td>
                <td>{a.applicant}</td>
                <td className="d-r d-num">{money(a.amount)}</td>
                <td>
                  <StatusPill status={a.status} />
                </td>
                <td>{a.assessedAt ? formatMoment(a.assessedAt) : "—"}</td>
                <td>{a.referred ? "Yes" : "No"}</td>
                <td>{a.noticeBlockedAt ? formatMoment(a.noticeBlockedAt) : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function ApplicationFile({ id }: { id: string }) {
  const read = useApplication(id);
  const nameOf = useNameOf();
  const app = read.data;
  return (
    <div className="d-page">
      <p>
        <a href="#/applications">← All applications</a>
      </p>
      <Problem error={read.error} />
      {!app ? (
        <Reading what="the application" />
      ) : (
        <div className="d-file">
          <div>
            <Panel title={`Application ${app.id}`} aside={<StatusPill status={app.status} />}>
              <Facts
                rows={[
                  ["Applicant", app.applicant],
                  ["Amount", <span className="d-num">{money(app.amount)}</span>],
                  ["Term", `${app.termMonths} months`],
                  ["Monthly income", <span className="d-num">{money(app.monthlyIncome)}</span>],
                  ["Monthly debts", <span className="d-num">{money(app.monthlyDebts)}</span>],
                  ["Documents", app.documents.map(readable).join(", ") || "None yet"],
                  ["Documents due", formatMoment(app.documentsDue)]
                ]}
              />
            </Panel>
            {app.assessment ? (
              <Panel title="Assessment">
                <Facts
                  rows={[
                    ["Recommendation", app.assessment.recommendation ? readable(app.assessment.recommendation) : "None: the assessment was halted"],
                    ["Risk score", app.assessment.assessedAt ? app.assessment.riskScore.toFixed(2) : "—"],
                    ["Debt-to-income", app.assessment.assessedAt ? percent(app.assessment.debtToIncome) : "—"],
                    ["Referred", app.assessment.referred ? `Yes: ${readable(app.assessment.referredBecause ?? "")}` : "No"]
                  ]}
                />
                {app.status === "AWAITING_HUMAN_REVIEW" ? (
                  <p className="d-note">
                    <a href={`#/underwriting/${app.id}`}>Open the underwriting review →</a>
                  </p>
                ) : null}
              </Panel>
            ) : null}
            {app.outcome ? (
              <Panel title="Decision" aside={<StatusPill status={app.outcome.decision} />}>
                <Facts
                  rows={[
                    ["Decided by", app.outcome.by.startsWith("rule:") ? readable(app.outcome.by.slice(5)) : nameOf(app.outcome.by)],
                    ["When", formatMoment(app.outcome.at)],
                    ["Reasons", app.outcome.reasons.map(readable).join(", ") || "—"],
                    ["Rationale", app.outcome.rationale || "—"]
                  ]}
                />
              </Panel>
            ) : null}
            {app.notice ? (
              <Panel title="Adverse-action notice" aside={readable(app.notice.issuedAs)}>
                <blockquote className="d-letter">{app.notice.text}</blockquote>
              </Panel>
            ) : app.noticeBlockedAt ? (
              <Panel title="Adverse-action notice">
                <p className="d-note">
                  Held for review since {formatMoment(app.noticeBlockedAt)}. <a href={`#/notices/${app.id}`}>Open the held notice →</a>
                </p>
              </Panel>
            ) : null}
          </div>
          <Panel title="History">
            <ol className="d-steps">
              {app.history.map((s, i) => (
                <li key={i}>
                  <span className="d-sub">{formatMoment(s.at)}</span>
                  <span>{s.what}</span>
                </li>
              ))}
            </ol>
          </Panel>
        </div>
      )}
    </div>
  );
}
