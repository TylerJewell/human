import {
  actionLabel,
  Button,
  Chip,
  ConclusionChip,
  ErrorLine,
  Facts,
  formatDuration,
  formatMoment,
  patternName,
  readable,
  Section,
  subjectKindLabel,
  Tag,
  type Fact
} from "@akka-human/kit";
import { Fragment, useState } from "react";
import { runConformance, useControls, useServices, useVerdicts } from "../api";
import {
  controlsByService,
  reportTotals,
  scenarioOrder,
  verdictsOf,
  type ConformanceReport,
  type ServiceControl,
  type ServiceVerdict
} from "../logic";

// What every service declares and has proved: its human controls with their
// settings resolved, the verdicts its events gave, and the services
// themselves with a conformance run against each.

const FLAVOR: Record<string, string> = {
  "human-domain-decision": "Domain decision",
  "human-system-authorization": "Authorization",
  "human-monitoring": "Monitoring",
  "human-feedback": "Feedback",
  "human-halt": "Halt"
};

function controlFacts(c: ServiceControl): Fact[] {
  const facts: Fact[] = [
    { label: "Allowed", value: c.allowedActions.map((a) => actionLabel(a, c.actionLabels)).join(", ") },
    { label: "Routed to", value: `${readable(c.routing.queue)} (${c.routing.roles.map(readable).join(", ")})` },
    {
      label: "Review",
      value: c.routing.required > 1 ? `${c.routing.required} people${c.routing.of ? ` of ${c.routing.of}` : ""}` : "One reviewer"
    }
  ];
  if (c.routing.independence?.length) facts.push({ label: "Independent of", value: c.routing.independence.map(readable).join(", ") });
  if (c.slaTarget)
    facts.push({ label: "Deadline", value: `${formatDuration(c.slaTarget)}${c.timeoutBehaviour ? `, then ${readable(c.timeoutBehaviour).toLowerCase()}` : ""}${c.timeoutDefault ? ` (${actionLabel(c.timeoutDefault, c.actionLabels).toLowerCase()})` : ""}` });
  if (c.escalationChain?.length) facts.push({ label: "Escalation", value: c.escalationChain.map((e) => readable(e.queue)).join(" → ") });
  if (c.minimumReviewSeconds)
    facts.push({ label: "Minimum review", value: `${formatDuration(`PT${c.minimumReviewSeconds}S`)}, ${(c.minimumReview ?? "ENFORCE").toLowerCase()}d` });
  if (c.accountableOwner) facts.push({ label: "Accountable owner", value: readable(c.accountableOwner) });
  if (c.sticky?.length) facts.push({ label: "Standing", value: c.sticky.map(readable).join(", ") });
  if (c.aiRecommendation) facts.push({ label: "AI recommendation", value: readable(c.aiRecommendation) });
  if (c.strength) facts.push({ label: "Strength", value: Object.entries(c.strength).map(([env, s]) => `${s} in ${env}`).join(", ") });
  facts.push({ label: "Proof", value: c.proofEvent ?? "HumanOversightDecisionRecordedV2" });
  return facts;
}

/** Each service's human controls, settings resolved, with the pattern and flavor of each. */
export function ControlsPage() {
  const read = useControls();
  const [open, setOpen] = useState<string>();
  if (!read.data) return read.error ? <ErrorLine error={read.error} /> : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />;
  const groups = controlsByService(read.data);
  if (!groups.length) return <p className="hk-empty">No service declares a human control.</p>;
  return (
    <div className="hk-stack" data-testid="controls">
      {groups.map(([service, controls]) => (
        <Section key={service} title={service} status={<Tag>{controls.length} controls</Tag>} flush testId="controls-service">
          <table className="hk-table cs-table">
            <thead>
              <tr>
                <th scope="col">Control</th>
                <th scope="col">Pattern</th>
                <th scope="col">Flavor</th>
                <th scope="col">Subject</th>
                <th scope="col">Routed to</th>
                <th scope="col">Deadline</th>
                <th scope="col">
                  <span className="hk-sr-only">Settings</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {controls.map((c) => {
                const key = `${service}:${c.control.rowId}`;
                return (
                  <Fragment key={key}>
                    <tr data-testid="control-row">
                      <th scope="row">
                        <span className="cs-control">
                          {c.control.name}
                          <span className="hk-muted cs-mono">{c.control.rowId}</span>
                        </span>
                      </th>
                      <td>
                        <Tag>{patternName(c.pattern)}</Tag>
                      </td>
                      <td>{FLAVOR[c.flavor] ?? readable(c.flavor)}</td>
                      <td>{subjectKindLabel(c.subjectKind)}</td>
                      <td>{c.routing.roles.map(readable).join(", ")}</td>
                      <td className="hk-nowrap">{c.slaTarget ? formatDuration(c.slaTarget) : "—"}</td>
                      <td className="hk-num">
                        <button type="button" className="hk-link" aria-expanded={open === key} onClick={() => setOpen(open === key ? undefined : key)}>
                          {open === key ? "Hide" : "Settings"}
                        </button>
                      </td>
                    </tr>
                    {open === key ? (
                      <tr className="cs-detail-row">
                        <td colSpan={7}>
                          <Facts facts={controlFacts(c)} testId="control-settings" />
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </Section>
      ))}
    </div>
  );
}

function VerdictProof({ v }: { v: ServiceVerdict }) {
  const facts: Fact[] = [
    { label: "Boundary", value: v.boundary.ref },
    { label: "Source", value: v.source.kind === "rehearsal" ? "Rehearsal: not counted as evidence" : readable(v.source.kind) },
    { label: "Reasons", value: v.reasons?.length ? v.reasons.map(readable).join(", ") : "None" },
    { label: "Applied", value: v.appliedAt ? formatMoment(v.appliedAt) : "Not applied" },
    { label: "Verdict", value: <span className="cs-mono">{v.id}</span> },
    {
      label: "Event",
      value: (
        <a className="hk-link" href={`#/events/${encodeURIComponent(v.eventId)}`}>
          {v.eventId}: its history and proof
        </a>
      )
    }
  ];
  return <Facts facts={facts} testId="verdict-proof" />;
}

/** Every verdict the services gave, newest first, each opening onto its proof. */
export function VerdictsPage() {
  const read = useVerdicts();
  const [conclusion, setConclusion] = useState<string>();
  const [service, setService] = useState<string>();
  const [open, setOpen] = useState<string>();
  const all = read.data ?? [];
  const services = [...new Set(all.map((v) => v.service ?? v.source.service).filter((s): s is string => Boolean(s)))].sort();
  const shown = verdictsOf(all, { conclusion, service });
  return (
    <div className="hk-stack" data-testid="verdicts">
      <div className="hk-queue__bar" role="toolbar" aria-label="Filters">
        <select className="hk-input hk-input--sm cs-select" aria-label="Conclusion" value={conclusion ?? ""} onChange={(e) => setConclusion(e.target.value || undefined)}>
          <option value="">Every conclusion</option>
          {["upheld", "breached", "inconclusive", "error"].map((c) => (
            <option key={c} value={c}>
              {readable(c)}
            </option>
          ))}
        </select>
        <select className="hk-input hk-input--sm cs-select" aria-label="Service" value={service ?? ""} onChange={(e) => setService(e.target.value || undefined)}>
          <option value="">Every service</option>
          {services.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <span className="hk-muted hk-nums">
          {shown.length} of {all.length} verdicts
        </span>
      </div>
      <ErrorLine error={read.error} />
      {!read.data && !read.error ? <div className="hk-skeleton hk-skeleton--block" aria-busy="true" /> : null}
      {read.data && shown.length === 0 ? <p className="hk-empty">No verdict matches.</p> : null}
      {shown.length ? (
        <div className="hk-panel hk-panel--flush">
          <table className="hk-table cs-table">
            <thead>
              <tr>
                <th scope="col">Verdict</th>
                <th scope="col">Control</th>
                <th scope="col">Pattern</th>
                <th scope="col">Decision</th>
                <th scope="col">Decided by</th>
                <th scope="col">Service</th>
                <th scope="col">At</th>
                <th scope="col">
                  <span className="hk-sr-only">Proof</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((v) => (
                <Fragment key={v.id}>
                  <tr data-testid="verdict-row">
                    <td>
                      <span className="hk-inline-list">
                        <ConclusionChip conclusion={v.conclusion} />
                        {v.source.kind === "rehearsal" ? <Chip tone="quiet" icon={false}>Rehearsal</Chip> : null}
                      </span>
                    </td>
                    <td>{v.control?.name ?? "—"}</td>
                    <td>{v.pattern ? patternName(v.pattern) : "—"}</td>
                    <td>{v.action ? readable(v.action) : "—"}</td>
                    <td>{v.deciders?.join(", ") || "—"}</td>
                    <td>{v.service ?? v.source.service ?? "—"}</td>
                    <td className="hk-nums hk-nowrap">{formatMoment(v.at)}</td>
                    <td className="hk-num">
                      <button type="button" className="hk-link" aria-expanded={open === v.id} onClick={() => setOpen(open === v.id ? undefined : v.id)}>
                        {open === v.id ? "Hide" : "Proof"}
                      </button>
                    </td>
                  </tr>
                  {open === v.id ? (
                    <tr className="cs-detail-row">
                      <td colSpan={8}>
                        <VerdictProof v={v} />
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  );
}

const STATUS_TONE: Record<string, "success" | "danger" | "default"> = { passed: "success", failed: "danger", skipped: "default" };

/** A conformance report: its totals, then each scenario, failed first, its steps on demand. */
export function ConformanceView({ report }: { report: ConformanceReport }) {
  const totals = reportTotals(report);
  return (
    <div className="hk-stack hk-stack--tight" data-testid="conformance-report">
      <p className="hk-inline-list">
        <Chip tone={totals.ok ? "success" : "danger"}>{totals.ok ? "Conforms" : "Does not conform"}</Chip>
        <span className="hk-nums">
          {totals.passed} passed, {totals.failed} failed, {totals.skipped} skipped of {totals.total} scenarios
        </span>
        {report.startedAt && report.finishedAt ? (
          <span className="hk-muted hk-nums">in {Math.round((Date.parse(report.finishedAt) - Date.parse(report.startedAt)) / 1000)} s</span>
        ) : null}
      </p>
      <ul className="cs-scenarios">
        {scenarioOrder(report.scenarios).map((s, i) => (
          <li key={i} data-testid="conformance-scenario" data-status={s.status}>
            <details open={s.status === "failed"}>
              <summary>
                <Chip tone={STATUS_TONE[s.status] ?? "default"} icon={false}>
                  {readable(s.status)}
                </Chip>
                <span className="cs-scenario__name">
                  {patternName(s.pattern)} · {s.scenario}
                </span>
                {s.control ? <span className="hk-muted cs-mono">{s.control}</span> : null}
                {s.detail ? <span className="hk-muted cs-scenario__detail">{s.detail}</span> : null}
              </summary>
              <ol className="cs-steps">
                {s.steps.map((step, j) => (
                  <li key={j} data-status={step.status}>
                    <span className={`cs-dot cs-dot--${step.status === "passed" ? "up" : step.status === "failed" ? "down" : "idle"}`} aria-hidden="true" />
                    <span>{step.name}</span>
                    {step.request ? <span className="hk-muted cs-mono">{step.request}</span> : null}
                    {step.http ? <span className="hk-muted hk-nums">{step.http}</span> : null}
                    {step.detail ? <span className="cs-step__detail">{step.detail}</span> : null}
                  </li>
                ))}
              </ol>
            </details>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ServiceCard({ name }: { name: string }) {
  const services = useServices();
  const s = services.data?.find((x) => x.service === name);
  const [running, setRunning] = useState(false);
  const [report, setReport] = useState<ConformanceReport>();
  const [error, setError] = useState<Error>();
  if (!s) return null;
  const facts: Fact[] = [{ label: "Address", value: <span className="cs-mono">{s.url}</span> }];
  if (s.info) {
    facts.push(
      { label: "Contract", value: `Version ${s.info.contract}` },
      { label: "Library", value: s.info.library ?? "—" },
      { label: "Patterns", value: s.info.patterns.map((p) => patternName(p)).join(", ") },
      { label: "Controls", value: String(s.info.controls ?? "—") },
      { label: "Environment", value: s.info.environment ? readable(s.info.environment) : "—" },
      { label: "Development routes", value: s.info.devRoutes ? "On" : "Off" }
    );
  }
  const canRun = s.up && Boolean(s.info?.devRoutes);
  const run = () => {
    setRunning(true);
    setError(undefined);
    runConformance(s.service)
      .then(setReport, (e: Error) => setError(e))
      .finally(() => setRunning(false));
  };
  return (
    <Section
      title={s.service}
      status={<Chip tone={s.up ? "success" : "danger"}>{s.up ? "Answers" : "Does not answer"}</Chip>}
      controls={
        <Button
          size="sm"
          variant="secondary"
          disabled={!canRun || running}
          onClick={run}
          testId={`conformance-${s.service}`}
          title={canRun ? "Runs every scenario the service's patterns have, against its development routes" : "Needs a running service with development routes on"}
        >
          {running ? "Running the suite…" : "Run conformance"}
        </Button>
      }
      testId="service-card"
    >
      <div className="hk-stack">
        <Facts facts={facts} />
        {running ? <p className="hk-muted" role="status">The suite raises and decides events on {s.service}; this takes a few minutes.</p> : null}
        <ErrorLine error={error} testId="conformance-error" />
        {report ? <ConformanceView report={report} /> : null}
      </div>
    </Section>
  );
}

/** Every service the companion has found, whether it answers, and its conformance. */
export function ServicesPage() {
  const services = useServices();
  if (!services.data) return services.error ? <ErrorLine error={services.error} /> : <div className="hk-skeleton hk-skeleton--block" aria-busy="true" />;
  if (!services.data.length)
    return <p className="hk-empty">No service is registered. A service writes its registration file under ~/.akka/human/services when it starts.</p>;
  return (
    <div className="hk-stack" data-testid="services">
      {services.data.map((s) => (
        <ServiceCard key={s.service} name={s.service} />
      ))}
    </div>
  );
}
