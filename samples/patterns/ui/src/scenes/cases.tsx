import { formatMoment, useRegistry } from "@akka-human/kit";
import { nameIn } from "../parts/people";
import { Frame, Mono, Note, Pill, Rows, useAction, type Tone } from "../parts/ui";
import { AppliedLine, NextBar, RaisedBar, StartBar } from "../steps/common";
import { call, newRun, useDomain } from "../tour/api";
import type { Scene, SceneProps } from "./types";

// The patterns where the service raises the event itself and an apply handler
// carries the decision out: exception and match adjudication. The handler
// writes to the sample's records, which is what the requester sees.

export interface RecordEntry {
  kind: string;
  id: string;
  outcome: string;
  fields: Record<string, string>;
  at: string;
}

/** The record an apply handler wrote, once it has. */
export const useRecord = (id: string | undefined) => useDomain<RecordEntry>(id ? `/records/${encodeURIComponent(id)}` : undefined, 1500);

// ---- exception

const UNTIL = () => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 1);
  d.setUTCHours(18, 0, 0, 0);
  return d.toISOString().replace(/\.\d{3}Z$/, "Z");
};

function Calendar({ requested, record, until }: { requested: boolean; record?: RecordEntry; until?: string }) {
  const granted = record?.outcome === "granted";
  const [tone, word]: [Tone, string] = record ? (granted ? ["ok", "Waived until expiry"] : ["bad", "Refused"]) : requested ? ["wait", "Waiver requested"] : ["idle", "Rule in force"];
  return (
    <Frame role="The change calendar" icon="rule" title="Rule R-12: no production deployments on Fridays" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-calendar">
      <Rows
        rows={[
          ["Requested by", "J. Lind, head of operations"],
          ["Scope", "billing-service: the security fix for CVE-2026-4411"],
          ["Until", until ? formatMoment(until) : "tomorrow at 18:00 UTC"],
          ["Why", "The vulnerability is being exploited elsewhere; waiting until Monday leaves billing exposed for three days."],
          ["In its place", "A second engineer reviews the change, and the rollback is rehearsed before release."]
        ]}
      />
      {record ? (
        <Note>
          The handler recorded: <Mono>{record.outcome}</Mono>
          {record.fields.rationale ? <>, because "{record.fields.rationale}"</> : null}.
        </Note>
      ) : null}
    </Frame>
  );
}

function ExceptionTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const runIt = () =>
    start(async () => {
      const until = UNTIL();
      const raised = await call<{ eventId: string }>("POST", "/waivers", {
        ruleId: "R-12",
        ruleText: "No production deployments on Fridays.",
        scope: "billing-service security fix",
        until,
        requestedBy: "j.lind@example.com",
        justification: "CVE-2026-4411 is being exploited; waiting until Monday leaves billing exposed for three days.",
        compensating: ["A second engineer reviews the change.", "The rollback is rehearsed before release."]
      });
      setRun(newRun({ eventId: raised.eventId, events: [raised.eventId], data: { until } }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /waivers" label="Request the waiver" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <Calendar requested={!!run} until={run?.data.until as string | undefined} />
    </div>
  );
}

function ExceptionEffect({ run, event, go }: SceneProps) {
  const record = useRecord(event?.subject.ref ?? undefined).data;
  return (
    <div className="pt-stack">
      <Calendar requested record={record} until={run?.data.until as string | undefined} />
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const exception: Scene = { Trigger: ExceptionTrigger, Effect: ExceptionEffect };

// ---- match adjudication

const LEFT = { name: "Ana Ruiz", born: "1988-04-12", address: "Calle Mayor 14, 28013 Madrid", email: "ana.ruiz@example.es" };
const RIGHT = { name: "Ana M. Ruiz Ortega", born: "1988-04-12", address: "C/ Mayor 14, 2º, 28013 Madrid", email: "aruiz88@example.com" };

function Records({ record, started }: { record?: RecordEntry; started: boolean }) {
  const verdict = record?.outcome;
  const [tone, word]: [Tone, string] = verdict
    ? verdict === "same"
      ? ["ok", "Same customer"]
      : verdict === "relabelled"
        ? ["gold", "Relabelled"]
        : ["bad", "Different customers"]
    : started
      ? ["wait", "Waiting for a steward"]
      : ["idle", "Score 0.78"];
  return (
    <Frame role="The customer matcher" icon="records" title="Possible duplicate customer" status={<Pill tone={tone} pulse={tone === "wait"}>{word}</Pill>} testId="frame-records">
      <div className="pt-compare">
        <div className="pt-compare__head">
          <span />
          <span>
            CRM <Mono>c-1042</Mono>
          </span>
          <span>
            Claims <Mono>k-0917</Mono>
          </span>
        </div>
        {(Object.keys(LEFT) as (keyof typeof LEFT)[]).map((k) => (
          <div key={k} className={`pt-compare__row${LEFT[k] === RIGHT[k] ? "" : " differs"}`}>
            <span className="pt-muted">{k === "born" ? "Date of birth" : k.charAt(0).toUpperCase() + k.slice(1)}</span>
            <span>{LEFT[k]}</span>
            <span>{RIGHT[k]}</span>
          </div>
        ))}
      </div>
      <Note>The matcher merges above 0.9 and keeps records apart below 0.6. Between them, a data steward decides.</Note>
    </Frame>
  );
}

function MatchTrigger({ run, setRun, event, go }: SceneProps) {
  const [start, busy, error] = useAction();
  const runIt = () =>
    start(async () => {
      const raised = await call<{ eventId: string }>("POST", "/matches", {
        entityType: "customer",
        left: { source: "crm", ref: "c-1042", fields: LEFT },
        right: { source: "claims", ref: "k-0917", fields: RIGHT },
        loadedBy: "loader:crm",
        score: 0.78
      });
      setRun(newRun({ eventId: raised.eventId, events: [raised.eventId] }));
    });
  return (
    <div className="pt-stack">
      <StartBar call="POST /matches" label="Run the matcher" onRun={runIt} busy={busy} error={error} ran={!!run} />
      <RaisedBar event={event} go={go} />
      <Records started={!!run} />
    </div>
  );
}

function MatchEffect({ event, go }: SceneProps) {
  const record = useRecord(event?.subject.ref ?? undefined).data;
  const registry = useRegistry().data;
  return (
    <div className="pt-stack">
      <Records record={record} started />
      {record ? (
        <Note>
          {nameIn(registry, record.fields.deciders?.split(", ").pop())} decided; the handler recorded <Mono>{record.outcome}</Mono>.
        </Note>
      ) : null}
      <AppliedLine event={event} />
      {event && !event.pending ? <NextBar go={go} to="record" label="See the record" /> : null}
    </div>
  );
}

export const matchAdjudication: Scene = { Trigger: MatchTrigger, Effect: MatchEffect };
