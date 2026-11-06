import { Fragment, useState } from "react";
import { verdictDeciders } from "../parse";
import type { Decision, HumanEvent } from "../types";
import {
  actionLabel,
  actorText,
  conclusionLabel,
  detectorPhrase,
  formatMoment,
  formatShortMoment,
  formatSpan,
  readable,
  serviceNow,
  stateLabel,
  valueText,
  type NameOf
} from "../words";
import { ConclusionChip, ConditionList, StateChip } from "./status";
import { Chip, Facts, Section, type Fact } from "./ui";

// One event's journey from detection to verdict: the steps taken as a line of
// nodes, each with what it did, who and when; an escalation or a send-back
// branching under the line; the steps still to come as one Next line. Colour
// means effect: brand when a person changed what the system asked for, accent
// when it was approved or applied, success when the system was kept safe,
// danger when it was stopped or the oversight breached, a dashed ghost for a
// step skipped. Under it, every step of the history, and the proof: the
// decisions as recorded and the verdict they gave.

type Effect = "changed" | "applied" | "saved" | "stopped" | "none" | "neutral";

export interface JourneyStep {
  key: string;
  label: string;
  status: "done" | "pending" | "skipped";
  effect: Effect;
  at?: string;
  actor?: string;
  detail?: string;
  note?: string;
  sinceMs?: number;
}

interface Branch {
  kind: "escalation" | "sent-back";
  /** The index of the main step the branch leaves from; it rejoins at the next. */
  after: number;
  steps: JourneyStep[];
}

export interface Journey {
  main: JourneyStep[];
  branches: Branch[];
}

const DECISION_EFFECT: Record<string, Effect> = {
  APPROVE: "applied",
  EDIT: "changed",
  REDIRECT: "changed",
  REPLAN: "changed",
  OVERRIDE: "changed",
  DENY: "saved",
  HALT: "saved",
  DECLINE_SYSTEM: "saved",
  REVERSE: "saved"
};

const CONCLUSION_EFFECT: Record<string, Effect> = {
  upheld: "saved",
  breached: "stopped",
  error: "stopped",
  inconclusive: "neutral"
};

function detectedLine(event: HumanEvent): string {
  const value = event.trigger.values?.[0];
  if (!value) return `By ${detectorPhrase(event.trigger.detector)}`;
  return value.limit == null
    ? `${value.name} ${valueText(value.value, value.name)}`
    : `${value.name} ${valueText(value.value, value.name)} against ${valueText(value.limit, value.name)}`;
}

const decisionStep = (event: HumanEvent, decision: Decision | undefined, fallback: string) => {
  if (!decision) return { detail: fallback || undefined, effect: "neutral" as Effect };
  return {
    detail: `${actionLabel(decision.action, event.actionLabels)}${decision.edits ? `: ${decision.edits.summary}` : ""}${decision.belowMinimum ? ", below the minimum review time" : ""}`,
    effect: decision.belowMinimum ? ("stopped" as Effect) : DECISION_EFFECT[decision.action] ?? "neutral"
  };
};

function timeoutStep(event: HumanEvent): Pick<JourneyStep, "detail" | "effect"> {
  const { timeoutBehaviour, default: fallback } = event.sla;
  if (timeoutBehaviour === "RESUME_WITH_DEFAULT" && fallback) {
    return {
      detail: `Resumed with the default: ${actionLabel(fallback, event.actionLabels)}`,
      // A default that lets the subject through is a fail-open timeout.
      effect: fallback === "APPROVE" ? "stopped" : DECISION_EFFECT[fallback] ?? "neutral"
    };
  }
  const words: Record<string, string> = {
    RAISE_TIMEOUT: "Raised a timeout",
    ESCALATE: "Escalated",
    ABANDON: "Abandoned",
    RESUME_WITH_DEFAULT: "Resumed with the default"
  };
  return { detail: timeoutBehaviour ? words[timeoutBehaviour] : undefined, effect: "neutral" };
}

/**
 * The journey of `event`, walked from its history: each state a step on the
 * main line, an escalation or a send-back a branch off it until the event is
 * decided, and a reopening the start of a new round. The steps the current
 * round has still to reach follow as pending.
 */
export function journeyOf(event: HumanEvent): Journey {
  const main: JourneyStep[] = [];
  const branches: Branch[] = [];
  const multi = event.routing.required > 1;
  let branch: Branch | undefined;
  let round = 0;
  let escalations = 0;
  let decided = 0;
  let reached = new Set<string>();
  let terminal: "applies" | "ends" | undefined;
  let closed = false;
  let lastAt: number | undefined;

  const step = (key: string, label: string, h: HumanEvent["history"][number], more: Partial<JourneyStep> = {}): JourneyStep => {
    const time = Date.parse(h.at);
    const sinceMs = lastAt === undefined ? undefined : time - lastAt;
    lastAt = time;
    return {
      key: `${key}-${round}-${main.length}-${branches.length}`,
      label,
      status: "done",
      effect: "neutral",
      at: h.at,
      actor: h.actor,
      note: h.note || undefined,
      sinceMs,
      ...more
    };
  };
  const onMain = (s: JourneyStep, reach: string) => {
    branch = undefined;
    main.push(s);
    reached.add(reach);
  };
  const skippedClaim = () => {
    if (!reached.has("claimed")) {
      main.push({ key: `claimed-${round}-skipped`, label: "Claimed", status: "skipped", effect: "none", detail: "Nobody claimed it" });
      reached.add("claimed");
    }
  };

  event.history.forEach((h, i) => {
    const previous = event.history[i - 1]?.state;
    switch (h.state) {
      case "DETECTED":
        onMain(step("detected", "Detected", h, { detail: detectedLine(event) }), "detected");
        break;
      case "OPEN":
        if (branch && previous === "CHANGES_REQUESTED") branch.steps.push(step("resubmitted", "Resubmitted", h));
        else if (!branch && !reached.has("routed") && round === 0)
          onMain(step("routed", "Routed", h, { detail: readable(event.escalation?.chain[0] ?? event.routing.queue) }), "routed");
        break;
      case "CLAIMED":
      case "IN_REVIEW":
        if (branch) {
          branch.steps.push(step("claimed", "Claimed", h));
          reached.add("claimed");
        } else if (!reached.has("claimed")) onMain(step("claimed", "Claimed", h), "claimed");
        break;
      case "ESCALATED": {
        escalations += 1;
        const tier = escalations + 1;
        branch = {
          kind: "escalation",
          after: main.length - 1,
          steps: [step("escalated", `Escalated to tier ${tier}`, h, { detail: readable(event.escalation?.chain[tier - 1] ?? event.routing.queue) })]
        };
        branches.push(branch);
        break;
      }
      case "CHANGES_REQUESTED":
        branch = { kind: "sent-back", after: main.length - 1, steps: [step("sent-back", "Sent back", h, { effect: "changed" })] };
        branches.push(branch);
        break;
      case "AWAITING_SECOND":
      case "DECIDED": {
        const first = !reached.has("decided");
        const decision = event.decisions[decided];
        decided += 1;
        onMain(
          step(first ? "decided" : "second", first ? "Decided" : "Next reviewer", h, decisionStep(event, decision, h.note)),
          first ? "decided" : "second"
        );
        break;
      }
      case "TIMED_OUT":
        skippedClaim();
        onMain(step("timed-out", "Timed out", h, timeoutStep(event)), "decided");
        terminal = event.sla.timeoutBehaviour === "RESUME_WITH_DEFAULT" ? "applies" : "ends";
        break;
      case "ABANDONED":
      case "KILLED":
      case "WITHDRAWN":
        skippedClaim();
        onMain(step(h.state.toLowerCase(), stateLabel(h.state), h, { effect: h.state === "KILLED" ? "stopped" : "none" }), "decided");
        terminal = "ends";
        break;
      case "APPLIED": {
        const error = event.outcome?.applyError;
        onMain(
          step("applied", "Applied", h, {
            detail: error ? "It could not be applied" : undefined,
            note: error ?? (h.note || event.outcome?.applied || undefined),
            effect: error ? "stopped" : "applied"
          }),
          "applied"
        );
        break;
      }
      case "CLOSED": {
        const conclusion = !event.pending && event.conclusion ? event.conclusion : undefined;
        onMain(
          step("verdict", "Verdict", h, {
            detail: conclusion ? conclusionLabel(conclusion) : undefined,
            effect: conclusion ? CONCLUSION_EFFECT[conclusion] ?? "neutral" : "neutral"
          }),
          "verdict"
        );
        closed = true;
        break;
      }
      case "REOPENED":
        onMain(step("reopened", "Reopened", h, { effect: "changed" }), "reopened");
        round += 1;
        reached = new Set(["routed"]);
        decided = event.decisions.length;
        terminal = undefined;
        closed = false;
        break;
      default:
        break;
    }
  });

  // A closed event with a verdict but no CLOSED step still ends in its verdict.
  if (!event.pending && !closed && event.conclusion) {
    const at = event.verdict?.observedAt ?? event.closedAt ?? undefined;
    main.push({
      key: `verdict-${round}-final`,
      label: "Verdict",
      status: "done",
      effect: CONCLUSION_EFFECT[event.conclusion] ?? "neutral",
      detail: conclusionLabel(event.conclusion),
      at,
      actor: event.verdict?.source.kind === "rehearsal" ? "rehearsal" : undefined
    });
    closed = true;
  }

  const pending = (key: string, label: string) =>
    main.push({ key: `${key}-${round}-pending`, label, status: "pending", effect: "none" });
  if (!closed && event.pending) {
    if (!reached.has("routed")) pending("routed", "Routed");
    if (!terminal) {
      if (!reached.has("claimed")) pending("claimed", "Claimed");
      if (!reached.has("decided")) pending("decided", "Decided");
      if (multi && !reached.has("second")) pending("second", "Next reviewer");
    }
    if (terminal !== "ends" && !reached.has("applied")) pending("applied", "Applied");
    pending("verdict", "Verdict");
  }
  return { main, branches };
}

const LEGEND: Record<Effect, string> = {
  changed: "a person changed it",
  applied: "approved or applied",
  saved: "kept safe",
  stopped: "stopped or breached",
  none: "not yet",
  neutral: "done"
};

const LEGEND_ORDER: Effect[] = ["changed", "applied", "saved", "stopped"];

const NEXT_WORD: Record<string, string> = {
  Routed: "route",
  Claimed: "claim",
  Decided: "decide",
  "Next reviewer": "next review",
  Applied: "apply",
  Verdict: "verdict"
};

function Node({ s, nameOf, small = false }: { s: JourneyStep; nameOf?: NameOf; small?: boolean }) {
  const [open, setOpen] = useState(false);
  const actor = s.actor ? actorText(s.actor, nameOf) : undefined;
  const note = s.note && s.note !== s.detail ? s.note : undefined;
  const title = [s.label, s.detail, actor, s.at ? formatMoment(s.at) : undefined, s.sinceMs ? `+${formatSpan(s.sinceMs)} after the step before` : undefined, note]
    .filter(Boolean)
    .join("\n");
  const content = (
    <>
      <span className="hk-node__label">
        <strong>{s.label}</strong>
        {s.detail ? <span data-testid="journey-detail"> · {s.detail}</span> : null}
      </span>
      {actor || s.at ? (
        <span className="hk-node__meta">
          {actor ? <span className="hk-node__actor">{actor}</span> : null}
          {s.at ? <span className="hk-nums">{formatShortMoment(s.at)}</span> : null}
        </span>
      ) : null}
      {open && note ? <span className="hk-node__note">{note}</span> : null}
    </>
  );
  const className = `hk-node hk-node--${s.effect}${small ? " hk-node--small" : ""}`;
  return note ? (
    <button
      type="button"
      className={className}
      title={title}
      aria-expanded={open}
      onClick={() => setOpen(!open)}
      data-testid="journey-node"
      data-status={s.status}
      data-effect={s.effect}
    >
      {content}
    </button>
  ) : (
    <div className={className} title={title} data-testid="journey-node" data-status={s.status} data-effect={s.effect}>
      {content}
    </div>
  );
}

function Arrow({ dashed }: { dashed: boolean }) {
  return (
    <span className={`hk-arrow${dashed ? " hk-arrow--dashed" : ""}`} aria-hidden="true">
      <span className="hk-arrow__shaft" />
      <svg viewBox="0 0 6 8" className="hk-arrow__head">
        <path d="M0 0 6 4 0 8z" />
      </svg>
    </span>
  );
}

const NODE = "minmax(8.5rem, 11rem)";
const LONG_NODE = "minmax(6rem, 11rem)";
const ARROW = "1.25rem";

/** The journey on one line: the steps taken as nodes, the branches under them, and what comes next. */
export function JourneyView({ event, nameOf }: { event: HumanEvent; nameOf?: NameOf }) {
  const { main, branches } = journeyOf(event);
  const taken = main.filter((s) => s.status !== "pending");
  const next = main.filter((s) => s.status === "pending");
  const long = taken.length >= 6;
  const node = long ? LONG_NODE : NODE;
  const columns = [
    ...taken.flatMap((_, i) => (i === 0 ? [node] : [ARROW, node])),
    ...(next.length ? [...(taken.length ? [ARROW] : []), "auto"] : [])
  ].join(" ");
  const nextColumn = 2 * taken.length + 1;
  const present = new Set([...taken, ...branches.flatMap((b) => b.steps)].map((s) => s.effect));
  const legend = LEGEND_ORDER.filter((e) => present.has(e));
  return (
    <div className="hk-journey" data-testid="journey">
      <div className="hk-journey__scroll">
        <ol className="hk-journey__grid" style={{ gridTemplateColumns: columns }} aria-label="Journey">
          {taken.map((s, i) => (
            <Fragment key={s.key}>
              <li className="hk-journey__cell" style={{ gridColumn: 2 * i + 1, gridRow: 1 }}>
                <Node s={s} nameOf={nameOf} small={long} />
              </li>
              {i < taken.length - 1 ? (
                <li className="hk-journey__cell" style={{ gridColumn: 2 * i + 2, gridRow: 1 }} aria-hidden="true">
                  <Arrow dashed={taken[i + 1].status !== "done"} />
                </li>
              ) : null}
            </Fragment>
          ))}
          {next.length ? (
            <>
              {taken.length ? (
                <li className="hk-journey__cell" style={{ gridColumn: nextColumn - 1, gridRow: 1 }} aria-hidden="true">
                  <Arrow dashed />
                </li>
              ) : null}
              <li className="hk-journey__next" style={{ gridColumn: nextColumn, gridRow: 1 }} data-testid="journey-next">
                Next: {next.map((s) => NEXT_WORD[s.label] ?? s.label.toLowerCase()).join(" → ")}
              </li>
            </>
          ) : null}
          {branches.map((b) => (
            <li
              key={b.steps[0].key}
              className="hk-branch"
              style={{ gridColumn: `${2 * b.after + 1} / ${2 * b.after + 4}`, gridRow: 2 }}
              data-testid="journey-branch"
              data-kind={b.kind}
            >
              <span className="hk-branch__bracket" aria-hidden="true" />
              <span className="hk-branch__steps">
                {b.steps.map((s, i) => (
                  <Fragment key={s.key}>
                    {i > 0 ? <Arrow dashed={false} /> : null}
                    <Node s={s} nameOf={nameOf} small />
                  </Fragment>
                ))}
              </span>
            </li>
          ))}
        </ol>
      </div>
      {legend.length ? (
        <p className="hk-legend" data-testid="journey-legend">
          {legend.map((e) => (
            <span key={e} className="hk-legend__item">
              <span className={`hk-legend__dot hk-legend__dot--${e}`} aria-hidden="true" />
              {LEGEND[e]}
            </span>
          ))}
        </p>
      ) : null}
    </div>
  );
}

/** The journey compressed to dots and words, for the event's header. */
export function JourneyLine({ event, nameOf }: { event: HumanEvent; nameOf?: NameOf }) {
  const { main } = journeyOf(event);
  const next = main.findIndex((s) => s.status === "pending");
  return (
    <ol className="hk-journey-line" aria-label="Journey" data-testid="journey-line">
      {main.map((s, i) => {
        const taken = s.status !== "pending";
        const who = taken ? [s.actor ? actorText(s.actor, nameOf) : undefined].filter(Boolean).join("") : "";
        return (
          <li key={s.key} className="hk-journey-line__step" data-status={s.status} data-next={i === next}>
            {i > 0 ? <span className="hk-journey-line__rule" aria-hidden="true" /> : null}
            <span className={`hk-journey-line__dot hk-journey-line__dot--${taken ? s.effect : i === next ? "next" : "todo"}`} aria-hidden="true" />
            <span className="hk-journey-line__label">{s.label}</span>
            {who ? <span className="hk-muted">{who}</span> : null}
            {!taken ? <span className="hk-sr-only">(to come)</span> : null}
          </li>
        );
      })}
    </ol>
  );
}

/** Every step of the history, oldest first. */
function Timeline({ event, nameOf }: { event: HumanEvent; nameOf?: NameOf }) {
  return (
    <table className="hk-table hk-timeline" data-testid="history-table">
      <caption className="hk-sr-only">History</caption>
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">State</th>
          <th scope="col">By</th>
          <th scope="col">Note</th>
        </tr>
      </thead>
      <tbody>
        {event.history.map((h, i) => (
          <tr key={i} data-testid="history-entry">
            <td className="hk-nums hk-nowrap">{formatShortMoment(h.at)}</td>
            <td>
              <StateChip state={h.state} />
            </td>
            <td>{actorText(h.actor, nameOf)}</td>
            <td>{h.note || <span className="hk-muted">—</span>}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The verdict and the record of who decided: what the oversight proves. */
function Proof({ event, nameOf }: { event: HumanEvent; nameOf?: NameOf }) {
  const verdict = event.verdict;
  const facts: Fact[] = [];
  if (event.conclusion) facts.push({ label: "Verdict", value: <ConclusionChip conclusion={event.conclusion} /> });
  if (verdict?.reason) facts.push({ label: "Reason", value: verdict.reason.text });
  if (verdict?.source)
    facts.push({
      label: "Source",
      value:
        verdict.source.kind === "rehearsal" ? (
          <span className="hk-inline-list">
            Rehearsal <Chip tone="quiet" icon={false}>Not counted as evidence</Chip>
          </span>
        ) : (
          readable(verdict.source.kind)
        )
    });
  const deciders = verdict ? verdictDeciders(verdict) : [];
  if (deciders.length) facts.push({ label: "Decided by", value: deciders.map((d) => actorText(d, nameOf)).join(", ") });
  if (event.outcome?.slaOutcome) facts.push({ label: "Deadline", value: readable(event.outcome.slaOutcome) });
  if (event.outcome?.applied) facts.push({ label: "Applied", value: event.outcome.applied });
  if (event.outcome?.applyError) facts.push({ label: "Not applied", value: event.outcome.applyError });
  if (event.outcome?.conditions?.length)
    facts.push({
      label: "Conditions",
      value: <ConditionList conditions={event.outcome.conditions} now={serviceNow(event, Date.now())} />
    });
  if (event.outcome?.standingDecisionId)
    facts.push({ label: "Standing decision", value: <code className="hk-code">{event.outcome.standingDecisionId}</code> });
  if (verdict?.detail.workItem?.queue) facts.push({ label: "Queue", value: readable(verdict.detail.workItem.queue) });
  if (event.verdictId) facts.push({ label: "Verdict id", value: <code className="hk-code">{event.verdictId}</code> });
  if (event.evidence?.length)
    facts.push({
      label: "Evidence",
      value: (
        <span className="hk-inline-list">
          {event.evidence.map((e) => (
            <code key={`${e.kind}-${e.ref}`} className="hk-code">
              {e.kind}:{e.ref}
            </code>
          ))}
        </span>
      )
    });
  const decisions = event.decisions;
  if (!facts.length && !decisions.length) return null;
  return (
    <div className="hk-stack hk-stack--tight" data-testid="history-proof">
      <h3 className="hk-subheading">Proof</h3>
      {facts.length ? <Facts facts={facts} testId="proof-facts" /> : null}
      {decisions.length ? (
        <table className="hk-table" data-testid="proof-decisions">
          <caption className="hk-sr-only">Decisions recorded</caption>
          <thead>
            <tr>
              <th scope="col">Decision</th>
              <th scope="col">By</th>
              <th scope="col">Submitted</th>
              <th scope="col">Review</th>
              <th scope="col">Why</th>
            </tr>
          </thead>
          <tbody>
            {decisions.map((d, i) => (
              <tr key={i}>
                <td>
                  <strong>{actionLabel(d.action, event.actionLabels)}</strong>
                  {d.edits ? <span className="hk-muted">: {d.edits.summary}</span> : null}
                </td>
                <td>
                  {actorText(d.reviewer.subject, nameOf)}
                  {d.reviewer.roles?.length ? <span className="hk-muted"> as {d.reviewer.roles.map(readable).join(", ")}</span> : null}
                </td>
                <td className="hk-nums hk-nowrap">{d.submittedAt ? formatShortMoment(d.submittedAt) : "—"}</td>
                <td className="hk-nums hk-nowrap">
                  {d.reviewSeconds !== undefined ? formatSpan(d.reviewSeconds * 1000) : "—"}
                  {d.belowMinimum ? <Chip tone="danger">Below minimum</Chip> : null}
                  {d.late ? <Chip tone="warning">Late</Chip> : null}
                </td>
                <td>{d.rationale ?? <span className="hk-muted">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </div>
  );
}

/** The history of an event: its journey, every step, and the proof. */
export function History({ event, nameOf }: { event: HumanEvent; nameOf?: NameOf }) {
  return (
    <Section title="History" testId="history">
      <div className="hk-stack">
        <JourneyView event={event} nameOf={nameOf} />
        <Timeline event={event} nameOf={nameOf} />
        <Proof event={event} nameOf={nameOf} />
      </div>
    </Section>
  );
}
