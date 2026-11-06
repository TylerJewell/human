import { formatMoment, type HumanEvent } from "@akka-human/kit";
import { Frame, Mono, Note, Pill, Rows, useAction, type Tone } from "../parts/ui";
import { AppliedLine, NextBar, RaisedBar, StartBar, Waiting } from "../steps/common";
import { awaitRaised, call, newRun, pendingOf } from "../tour/api";
import { useRecord } from "./cases";
import type { Scene, SceneProps } from "./types";

// The patterns a monitor or a schedule raises while the work carries on:
// triage, attestation and oversight quality. The tour sets the sample's timer
// going, then finds the event the timer raised.

/** A sparkline of the referral share: the usual band, the threshold and the last reading. */
function ReferralChart({ reading }: { reading?: number }) {
  const days = [36, 34, 37, 35, 33, 36, 34, 35, 31, 27, 22, reading ?? 18];
  const w = 520;
  const h = 150;
  // The right-hand gutter holds the line labels, clear of the series.
  const x = (i: number) => 24 + (i * (w - 150)) / (days.length - 1);
  const y = (v: number) => h - 18 - ((v - 0) / 45) * (h - 36);
  const path = days.map((v, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
  const last = days.length - 1;
  const shown = reading !== undefined;
  return (
    <svg className="pt-chart" viewBox={`0 0 ${w} ${h}`} role="img" aria-label={`Referral share over twelve days${shown ? `, last reading ${reading}%` : ""}`}>
      <line x1={24} x2={w - 112} y1={y(35)} y2={y(35)} className="pt-chart__base" />
      <text x={w - 104} y={y(35) + 4} className="pt-chart__label">
        baseline 35%
      </text>
      <line x1={24} x2={w - 112} y1={y(20)} y2={y(20)} className="pt-chart__limit" />
      <text x={w - 104} y={y(20) + 4} className="pt-chart__label pt-chart__label--limit">
        threshold 20%
      </text>
      <path d={shown ? path : path.slice(0, path.lastIndexOf(" L"))} className="pt-chart__line" />
      {shown ? <circle cx={x(last)} cy={y(reading!)} r={5} className="pt-chart__dot" /> : null}
      {shown ? (
        <text x={x(last) - 10} y={y(reading!) + 4} className="pt-chart__value" textAnchor="end">
          {reading}%
        </text>
      ) : null}
    </svg>
  );
}

// ---- triage

function TriageFrame({ reading, record }: { reading?: number; record?: { outcome: string } }) {
  const outcome = record?.outcome;
  const [tone, word]: [Tone, string] = outcome
    ? outcome === "resolved"
      ? ["ok", "Resolved"]
      : outcome === "reported"
        ? ["gold", "Reported"]
        : ["idle", "Dismissed"]
    : reading !== undefined
      ? ["wait", "Below threshold"]
      : ["idle", "Within range"];
  return (
    <Frame role="The model monitor" icon="chart" title="Referral share of assessments, last 24 hours" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-monitor">
      <ReferralChart reading={reading} />
      <Note>Assessments keep running while the drift is triaged: the event is not blocking.</Note>
    </Frame>
  );
}

function TriageTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const runIt = () =>
    start(async () => {
      const before = await pendingOf("pt-00010");
      await call("POST", "/monitor/referral-share", { referralShare: 12 });
      const raised = await awaitRaised("pt-00010", before);
      setRun(newRun({ eventId: raised.id, events: [raised.id], data: { reading: 12, occurrence: before.has(raised.id) } }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /monitor/referral-share" label="Send a reading of 12%" onRun={runIt} busy={busy} error={error} ran={!!run}>
        {run?.data.occurrence ? <Note>An event for this drift was already open, so the reading was added to it as another occurrence.</Note> : null}
      </StartBar>
      <RaisedBar event={event} go={go} />
      <TriageFrame reading={run ? 12 : undefined} />
    </div>
  );
}

function TriageEffect({ event, go }: SceneProps) {
  const record = useRecord(event && !event.pending ? event.id : undefined).data;
  return (
    <div className="pt-stack">
      <TriageFrame reading={12} record={record} />
      {record?.outcome ? (
        <Note>
          The handler recorded <Mono>{record.outcome}</Mono>. Nothing was waiting, so nothing resumed.
        </Note>
      ) : null}
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const triage: Scene = { Trigger: TriageTrigger, Effect: TriageEffect };

// ---- attestation

function quarterOf(event?: HumanEvent) {
  return event?.ask.question.match(/\d{4}-Q\d/)?.[0];
}

function ScheduleFrame({ event, record }: { event?: HumanEvent; record?: { outcome: string; fields: Record<string, string> } }) {
  const outcome = record?.outcome;
  const [tone, word]: [Tone, string] = record
    ? outcome === "attested"
      ? ["ok", "Attested"]
      : outcome?.startsWith("attested")
        ? ["gold", "Attested with findings"]
        : ["bad", "Refused"]
    : event
      ? ["wait", "Due"]
      : ["idle", "Scheduled"];
  const quarter = quarterOf(event) ?? "this quarter";
  return (
    <Frame role="The model inventory" icon="calendar" title="Eligibility model v3" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-inventory">
      <Rows
        rows={[
          ["Owner", "The model team"],
          ["Attested", "Every quarter, by a model risk owner"],
          ["Due now", `The attestation for ${quarter}`],
          ["Evidence", `Validation report vr-${quarter}: the back-test is within tolerance`],
          ...(record ? ([["Recorded", `${outcome}${record.fields.rationale ? `: ${record.fields.rationale}` : ""}`]] as [string, string][]) : [])
        ]}
      />
    </Frame>
  );
}

function AttestationTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const runIt = () =>
    start(async () => {
      const before = await pendingOf("pt-00013");
      await call("POST", "/attestations/run");
      const raised = await awaitRaised("pt-00013", before);
      setRun(newRun({ eventId: raised.id, events: [raised.id] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /attestations/run" label="Bring the schedule forward" onRun={runIt} busy={busy} error={error} ran={!!run}>
        <Note>The sample's timer fires on the first day of each quarter. The route fires it now, and the timer sets the next one.</Note>
      </StartBar>
      <RaisedBar event={event} go={go} />
      <ScheduleFrame event={event} />
    </div>
  );
}

function AttestationEffect({ event, go }: SceneProps) {
  const record = useRecord(event && !event.pending ? event.id : undefined).data;
  return (
    <div className="pt-stack">
      <ScheduleFrame event={event} record={record} />
      {!record && event && !event.pending ? <Waiting>The handler is recording the attestation.</Waiting> : null}
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const attestation: Scene = { Trigger: AttestationTrigger, Effect: AttestationEffect };

// ---- oversight quality

/** The finding the hourly quality monitor raises when payment approvals breach their minimum review time. */
const FINDING = {
  control: "pt-00014",
  ask: { question: "The below-minimum-share of pt-00001 is 0.35. Reset, retrain or reassign?", checks: [] },
  subject: {
    kind: "behaviour",
    summary: "below-minimum-share on pt-00001 is 0.35",
    service: "patterns",
    ref: "pt-00001",
    detail: { metric: "below-minimum-share", value: 0.35, threshold: 0.2, comparison: ">", window: "P7D", unit: "share" }
  },
  trigger: {
    detector: "monitor",
    rule: "below-minimum-share > 0.2",
    reason: "The oversight of pt-00001 is not working as it should.",
    values: [{ name: "below-minimum-share", value: 0.35, limit: 0.2 }],
    conditions: []
  },
  dedupeKey: "oq:pt-00014:pt-00001"
};

function QualityFrame({ raised, record }: { raised: boolean; record?: { outcome: string } }) {
  const outcome = record?.outcome;
  return (
    <Frame
      role="Oversight quality"
      icon="eye"
      title="Payment approvals (pt-00001), last 7 days"
      status={record ? <Pill tone="ok">{outcome === "approve" ? "Reset" : outcome === "replan" ? "Retraining" : "Reassigned"}</Pill> : raised ? <Pill tone="wait" pulse>Limit crossed</Pill> : <Pill>Watching</Pill>}
      testId="frame-quality"
    >
      <div className="pt-meter">
        <div className="pt-meter__bar">
          <span className="pt-meter__fill" style={{ width: raised ? "35%" : "6%" }} />
          <span className="pt-meter__limit" style={{ left: "20%" }} />
        </div>
        <div className="pt-meter__legend">
          <span>
            <b>{raised ? "35%" : "6%"}</b> of approvals were made faster than the ten-second minimum review
          </span>
          <span className="pt-muted">limit 20%</span>
        </div>
      </div>
      <Note>
        In this sample, payments enforce their minimum review, so a live run keeps this figure at zero. The tour raises the finding the monitor sends when a control
        only warns, through the development route <Mono>POST /oversight/dev/events</Mono>.
      </Note>
    </Frame>
  );
}

function QualityTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const runIt = () =>
    start(async () => {
      const raised = await call<HumanEvent>("POST", "/oversight/dev/events", FINDING);
      setRun(newRun({ eventId: raised.id, events: [raised.id] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /oversight/dev/events" label="Raise the monitor's finding" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <QualityFrame raised={!!run} />
    </div>
  );
}

function QualityEffect({ event, go }: SceneProps) {
  const record = useRecord(event && !event.pending ? event.id : undefined).data;
  return (
    <div className="pt-stack">
      <QualityFrame raised record={record} />
      {record ? (
        <Note>
          The handler recorded <Mono>{record.outcome}</Mono>
          {event?.closedAt ? `, ${formatMoment(event.closedAt)}` : ""}.
        </Note>
      ) : null}
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const oversightQuality: Scene = { Trigger: QualityTrigger, Effect: QualityEffect };
