import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import type { EventWrite } from "../client";
import { useEvent, useEventWrite, useNow, useRegistry, useReviewer } from "../hooks";
import { readSubject, votesOf } from "../parse";
import type {
  Action,
  Decision,
  DecisionInput,
  Edits,
  HumanEvent,
  OverseerRegistry,
  Reviewer,
  Sticky
} from "../types";
import {
  actionDone,
  actionLabel,
  formatDuration,
  formatMoment,
  formatSpan,
  listNames,
  readable,
  readClock,
  serviceNow,
  STICKY_LABEL,
  valueText
} from "../words";
import { ConclusionChip, ConditionList } from "./status";
import { Button, Chip, Confirm, cx, ErrorLine, Field, Icon } from "./ui";

// The person's decision on one event. Only the actions the event allows are
// offered, by the labels and effects it gives. An event that must be claimed
// first offers the claim, its steps shown but closed until it is taken. Then
// numbered steps: the answer (with its edits, replacement or scope), the
// checks, why, and Submit, held until the minimum review time has passed.
// Apart from the answer, the moves that are not a decision: release,
// escalate, send back, ask for more, recuse, extend, resubmit, withdraw,
// reopen. Every write but the claim is confirmed in place, since Release
// undoes a claim; a refused write says why where it was asked for.

/** Actions with a move of their own, so never offered as the answer. */
const MOVES: ReadonlySet<string> = new Set(["ESCALATE", "SEND_BACK", "REQUEST_MORE_INFO", "RECUSE"]);

/** Answers that let the subject go ahead, so the checks must be ticked first. */
const APPROVING: ReadonlySet<string> = new Set(["APPROVE", "EDIT"]);

const CLAIMABLE = new Set(["OPEN", "ESCALATED", "REOPENED"]);

/** An answer as Submit names it: "Submit approval". */
const ACTION_NOUN: Record<string, string> = {
  APPROVE: "approval",
  DENY: "denial",
  EDIT: "edit",
  REDIRECT: "redirect",
  REPLAN: "replan",
  HALT: "halt",
  DECLINE_SYSTEM: "take-over",
  OVERRIDE: "override",
  DISREGARD: "dismissal",
  REVERSE: "reversal"
};

const NUMBER_WORD = ["no", "one", "two", "three", "four", "five", "six", "seven"];

const EXTENSIONS = [
  { value: "PT15M", label: "15 minutes" },
  { value: "PT30M", label: "30 minutes" },
  { value: "PT1H", label: "1 hour" },
  { value: "PT4H", label: "4 hours" },
  { value: "P1D", label: "1 day" },
  { value: "P7D", label: "7 days" }
];

export interface Rules {
  /** Why this reviewer may not decide, in one sentence; undefined when they may, or when it is not known. */
  refusal?: string;
  /** The actions offered as the answer. */
  choices: Action[];
  /** Answers this reviewer may not give here, and why. */
  actionRefusal: Partial<Record<string, string>>;
  /** The event must be claimed before it is decided. */
  needsClaim: boolean;
  heldByMe: boolean;
  /** The next queue of the escalation chain. */
  nextTier?: string;
  canClaim: boolean;
  canRelease: boolean;
  canEscalate: boolean;
  canSendBack: boolean;
  canRequestInfo: boolean;
  canRecuse: boolean;
  canExtend: boolean;
  canResubmit: boolean;
  canWithdraw: boolean;
  canReopen: boolean;
}

/** Whether an overseer's authority entry covers a control: by its row id, or `*`. */
const covers = (controls: string[], rowId: string) => controls.includes("*") || controls.includes(rowId);

/**
 * What `reviewer` may do with `event` now, and why not when they may not.
 * `now` is the service's time, when known, so a hold reads as time left.
 */
export function rulesOf(
  event: HumanEvent,
  reviewer: Reviewer | null | undefined,
  registry?: OverseerRegistry,
  now?: number
): Rules {
  const me = reviewer?.subject;
  const overseer = me ? registry?.overseers.find((o) => o.subject === me) : undefined;
  const nameOf = (subject: string) => registry?.overseers.find((o) => o.subject === subject)?.name ?? subject;
  const votes = votesOf(event);
  const held = event.holder ?? undefined;
  const heldByMe = Boolean(me && held?.subject === me);
  const routable = !reviewer || reviewer.roles.some((r) => event.routing.roles.includes(r));
  const independentOf = event.routing.independentOf ?? [];

  const refusal = (() => {
    if (!event.pending) return "This event is closed.";
    if (event.shadow) return "A shadow-mode event is recorded, not routed, so nobody decides it.";
    if (!reviewer) return undefined;
    const name = overseer?.name ?? reviewer.name ?? reviewer.subject;
    if (registry && !overseer) return `${name} is not in the overseer registry.`;
    if (overseer && !overseer.qualified)
      return `${name} is not qualified to decide${overseer.qualifiedReason ? `: ${overseer.qualifiedReason}` : ""}.`;
    if (!routable)
      return `Acting as ${listNames(reviewer.roles.map(readable)) || "no role"}, you cannot decide events routed to ${listNames(event.routing.roles.map(readable), "or")}.`;
    if (me && independentOf.includes(me)) return "You proposed or built this, so someone independent of you must decide it.";
    if (me && (event.recused ?? []).includes(me)) return "You recused yourself from this event.";
    if (me && votes.some((d) => d.reviewer.subject === me))
      return "You have already voted on this event; the next vote must be someone else's.";
    if (event.state === "CHANGES_REQUESTED") return "It is back with its proposer, and can be decided once it is resubmitted.";
    if (event.state === "DETECTED") return "The event has not been routed yet.";
    if (held && !heldByMe && event.state !== "AWAITING_SECOND") {
      const until = held.expiresAt ? Date.parse(held.expiresAt) : NaN;
      const left = !Number.isFinite(until)
        ? ""
        : now === undefined
          ? ` until ${formatMoment(held.expiresAt)}`
          : until > now
            ? ` for another ${formatSpan(until - now)}`
            : "; the claim has lapsed";
      return `${nameOf(held.subject)} holds this event${left}.`;
    }
    return undefined;
  })();

  const choices = event.allowedActions.filter((a) => !MOVES.has(a));
  const actionRefusal: Partial<Record<string, string>> = {};
  const covering = (overseer?.authority ?? []).filter((a) => covers(a.controls, event.control.rowId));
  if (covering.length > 0 && !covering.some((a) => !a.actions)) {
    for (const action of choices) {
      if (!covering.some((a) => a.actions?.includes(action)))
        actionRefusal[action] = `Your authority for this control does not include “${actionLabel(action, event.actionLabels)}”.`;
    }
  }
  const subject = readSubject(event.subject);
  if (subject.kind === "system" && subject.detail.lift === true && event.allowedActions.includes("APPROVE")) {
    const open = subject.detail.safeStateChecks.filter((c) => c.passed !== true).length;
    if (open > 0)
      actionRefusal.APPROVE = `A restart can be approved only once every safe-state check passes; ${open} of ${subject.detail.safeStateChecks.length} ${open === 1 ? "has" : "have"} not.`;
  }

  const mayDecide = event.pending && refusal === undefined;
  const claimable = mayDecide && !held && CLAIMABLE.has(event.state);
  const needsClaim = claimable && (event.minimumReviewSeconds ?? 0) > 0;
  const open = mayDecide && !needsClaim;
  const nextTier = event.escalation?.chain[event.escalation.level + 1];
  const mayAct = event.pending && !event.shadow && routable && !(me && independentOf.includes(me));
  const isProposer = !reviewer || !event.proposedBy || event.proposedBy === me;
  const isOwner =
    !reviewer || !event.accountableOwner || event.accountableOwner === me || reviewer.roles.includes(event.accountableOwner);
  return {
    refusal,
    choices,
    actionRefusal,
    needsClaim,
    heldByMe,
    nextTier,
    canClaim: claimable,
    canRelease: event.pending && heldByMe,
    canEscalate: open && event.allowedActions.includes("ESCALATE"),
    canSendBack: open && event.allowedActions.includes("SEND_BACK"),
    canRequestInfo: open && event.allowedActions.includes("REQUEST_MORE_INFO"),
    canRecuse: open && event.allowedActions.includes("RECUSE"),
    canExtend: mayAct && Boolean(event.sla.dueAt) && (event.slaExtensions ?? []).length === 0,
    canResubmit: event.pending && event.state === "CHANGES_REQUESTED" && isProposer,
    canWithdraw: event.pending && event.state === "CHANGES_REQUESTED" && isProposer,
    canReopen: !event.pending && isOwner
  };
}

/** How many votes the event needs and has, as a line says it: "1 of 2 votes". */
export function votesText(event: HumanEvent): string {
  const required = event.routing.required;
  if (required <= 1) return "One reviewer";
  const votes = votesOf(event).length;
  return `${Math.min(votes, required)} of ${required} votes`;
}

// When each reviewer could first decide each event in this page, so the
// minimum review time counts from then however often the panel is redrawn.
const firstShown = new Map<string, number>();

/** The values an edit may change, by subject kind; undefined when the edit is said in words. */
function editableOf(event: HumanEvent): Record<string, unknown> | undefined {
  const subject = readSubject(event.subject);
  switch (subject.kind) {
    case "action":
      return subject.detail.arguments;
    case "change":
      return subject.detail.after;
    case "behaviour":
      return subject.detail.threshold !== undefined ? { threshold: subject.detail.threshold } : undefined;
    case "output":
      return { output: subject.detail.output };
    case "appeal":
      return { decision: subject.detail.original.decision };
    default:
      return undefined;
  }
}

/** A field's text as a value of the original's type. */
function typed(original: unknown, text: string): unknown {
  if (typeof original === "number") {
    const n = Number(text);
    return text.trim() === "" || Number.isNaN(n) ? text : n;
  }
  if (typeof original === "boolean") return text === "true";
  if (typeof original === "string") return text;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

const fieldText = (value: unknown) => (typeof value === "object" && value !== null ? JSON.stringify(value) : String(value ?? ""));

/** The edits as a JSON patch and a one-line summary; undefined when nothing changed. */
export function patchOf(original: Record<string, unknown>, fields: Record<string, string>): Edits | undefined {
  const changed = Object.entries(fields)
    .map(([key, text]) => ({ key, was: original[key], value: typed(original[key], text) }))
    .filter((c) => JSON.stringify(c.value) !== JSON.stringify(c.was));
  if (changed.length === 0) return undefined;
  return {
    summary: changed
      .map(({ key, was, value }) => {
        const before = valueText(was, key);
        const after = valueText(value, key);
        return before.length > 40 || after.length > 40 ? `${key} rewritten` : `${key} ${before} → ${after}`;
      })
      .join(", "),
    patch: changed.map(({ key, value }) => ({ op: "replace", path: `/${key.replaceAll("~", "~0").replaceAll("/", "~1")}`, value }))
  };
}

/** One numbered step of the decision; closed while the review is not started. */
function Step({ n, title, closed = false, children }: { n: number; title: string; closed?: boolean; children?: ReactNode }) {
  const id = useId();
  return (
    <section className={cx("hk-step", closed && "hk-step--closed")} aria-labelledby={id} data-testid="decision-step">
      <h3 id={id} className="hk-step__title">
        <span className="hk-step__n" aria-hidden="true">
          {n}
        </span>
        {title}
      </h3>
      {children}
    </section>
  );
}

function Vote({ d, name, labels }: { d: Decision; name: string; labels: Record<string, string> }) {
  return (
    <li className="hk-vote" data-testid="vote">
      <span className="hk-vote__line">
        <strong>{labels[d.action] ?? actionDone(d.action)}</strong>
        {d.edits ? <span>: {d.edits.summary}</span> : null}
        <span className="hk-muted">by {name}</span>
        {d.submittedAt ? <span className="hk-muted hk-nums">{formatMoment(d.submittedAt)}</span> : null}
        {d.reviewSeconds !== undefined ? <span className="hk-muted hk-nums">after {formatSpan(d.reviewSeconds * 1000)}</span> : null}
        {d.reviewer.rehearsal ? <Chip tone="quiet" icon={false}>Rehearsal</Chip> : null}
        {d.belowMinimum ? <Chip tone="danger">Below the minimum review time</Chip> : null}
        {d.late ? <Chip tone="warning">Late</Chip> : null}
        {d.independent === false ? <Chip tone="danger">Not independent</Chip> : null}
      </span>
      {d.rationale ? <span className="hk-vote__why">{d.rationale}</span> : null}
      {d.checks?.length ? (
        <ul className="hk-ticks" aria-label="Checked">
          {d.checks.map((c) => (
            <li key={c}>
              <Icon name="check" className="hk-ticks__icon" />
              {c}
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/** Votes so far against those needed, as segments. */
function VoteProgress({ have, need }: { have: number; need: number }) {
  return (
    <span
      className="hk-votes"
      role="meter"
      aria-label={`${have} of ${need} votes`}
      aria-valuemin={0}
      aria-valuemax={need}
      aria-valuenow={have}
      data-testid="vote-progress"
    >
      <span className="hk-votes__segments" aria-hidden="true">
        {Array.from({ length: need }, (_, i) => (
          <span key={i} className={cx("hk-votes__segment", i < have && "hk-votes__segment--on")} />
        ))}
      </span>
      <span className="hk-votes__text">
        {have} of {need} votes
      </span>
    </span>
  );
}

type Move =
  | "decision"
  | "release"
  | "escalate"
  | "send-back"
  | "request-info"
  | "recuse"
  | "extend"
  | "resubmit"
  | "withdraw"
  | "reopen";

export interface DecisionPanelProps {
  event: HumanEvent;
  /** Who decides; read from the client when left out. */
  reviewer?: Reviewer | null;
  /** The overseer registry; read from the client when left out. */
  registry?: OverseerRegistry;
  /** A fixed time, for tests and pictures; the panel's clock runs otherwise. */
  now?: number;
  /** A view's request to start an answer, as a conversation's take-over; a new `at` asks again. */
  requested?: { action: string; at: number };
  /** Called with the event as it stands after each write. */
  onWrite?: (event: HumanEvent, write: EventWrite) => void;
}

/** The decision on one event, for the acting reviewer. */
export function DecisionPanel(props: DecisionPanelProps) {
  const readReviewer = useReviewer({ enabled: props.reviewer === undefined });
  const readRegistry = useRegistry({ enabled: props.registry === undefined });
  const reviewer = props.reviewer !== undefined ? props.reviewer : readReviewer.data;
  const registry = props.registry ?? readRegistry.data;
  const event = useEvent(props.event.id, { initialData: props.event, pollMs: 0 }).data ?? props.event;
  return (
    <DecisionPanelFor
      // A new reviewer starts a fresh answer.
      key={`${event.id}|${reviewer?.subject ?? ""}`}
      {...props}
      event={event}
      reviewer={reviewer}
      registry={registry}
    />
  );
}

function DecisionPanelFor({ event, reviewer, registry, now: fixedNow, requested, onWrite }: DecisionPanelProps) {
  // Every time below is the service's: deadlines, claims and review times count against its clock.
  const now = serviceNow(event, useNow(1000, fixedNow));
  const write = useEventWrite(event.id);
  const rules = rulesOf(event, reviewer, registry, now);
  const nameOf = (subject: string) => registry?.overseers.find((o) => o.subject === subject)?.name ?? subject;
  const labels = event.actionLabels ?? {};
  const label = (a: string) => actionLabel(a, labels);

  const [choice, setChoice] = useState<string>();
  const [rationale, setRationale] = useState("");
  const [ticked, setTicked] = useState<string[]>([]);
  const [sticky, setSticky] = useState<Sticky | undefined>();
  const [fields, setFields] = useState<Record<string, string>>({});
  const [editWords, setEditWords] = useState("");
  const [replacement, setReplacement] = useState("");
  const [conditions, setConditions] = useState<{ text: string; due: string }[]>([]);
  const [move, setMove] = useState<Move>();
  const [reason, setReason] = useState("");
  const [extra, setExtra] = useState("PT30M");
  const [said, setSaid] = useState<string>();
  const answerRef = useRef<HTMLFieldSetElement>(null);

  const mayDecide = event.pending && rules.refusal === undefined;
  const claimFirst = rules.needsClaim;
  const minimum = event.minimumReviewSeconds ?? 0;
  const enforce = (event.minimumReview ?? "ENFORCE") === "ENFORCE";

  // When the review started: the claim when this reviewer holds it, else when
  // the panel first let them decide. Without the service's clock, a claim
  // stamped ahead of this one counts from when the panel first showed it.
  const shownKey = `${event.id}|${reviewer?.subject ?? ""}|${event.holder?.since ?? ""}`;
  if (mayDecide && !claimFirst && !firstShown.has(shownKey)) firstShown.set(shownKey, now);
  const since = rules.heldByMe && event.holder?.since ? Date.parse(event.holder.since) : NaN;
  const startedAt =
    Number.isFinite(since) && (event.clockOffset !== undefined || since <= now) ? since : firstShown.get(shownKey);
  const claimLeft = rules.heldByMe && event.holder?.expiresAt ? Date.parse(event.holder.expiresAt) - now : undefined;
  const minimumLeft =
    startedAt === undefined ? minimum : Math.max(0, Math.ceil(minimum - Math.max(0, now - startedAt) / 1000));

  const original = useMemo(() => editableOf(event), [event]);
  const edits: Edits | undefined =
    choice !== "EDIT"
      ? undefined
      : original
        ? patchOf(original, fields)
        : editWords.trim()
          ? { summary: editWords.trim(), patch: [] }
          : undefined;
  const checks = event.ask.checks;
  const approving = choice !== undefined && APPROVING.has(choice);
  const stickies = (event.sticky ?? []).filter((s) =>
    approving ? s.startsWith("APPROVE_") : choice === "DENY" ? s === "DENY_FOR_PATTERN" : false
  );

  const blocked = !mayDecide
    ? undefined
    : !choice
      ? "Choose an answer."
      : rules.actionRefusal[choice]
        ? rules.actionRefusal[choice]
        : approving && ticked.length < checks.length
          ? `Tick every check before you ${label(choice).toLowerCase()}: ${ticked.length} of ${checks.length} ticked.`
          : event.rationaleRequired && rationale.trim() === ""
            ? "Say why; this control asks for a rationale."
            : choice === "EDIT" && !edits
              ? original
                ? "Change at least one value to submit an edit."
                : "Say what changes to submit an edit."
              : choice === "OVERRIDE" && replacement.trim() === ""
                ? "Write the output that replaces the system's."
                : undefined;
  const heldByMinimum = enforce && minimumLeft > 0;
  const canSubmit = mayDecide && !claimFirst && !blocked && !heldByMinimum && !write.pending;

  const pick = (action: string) => {
    if (!mayDecide || rules.actionRefusal[action]) return;
    setChoice(action);
    const options = (event.sticky ?? []).filter((s) => (APPROVING.has(action) ? s.startsWith("APPROVE_") : false));
    setSticky(options.includes("APPROVE_ONCE") ? "APPROVE_ONCE" : options[0]);
    if (action === "OVERRIDE" && !replacement) {
      const subject = readSubject(event.subject);
      if (subject.kind === "output") setReplacement(subject.detail.replacement ?? subject.detail.output);
    }
  };

  const send = async (w: EventWrite, done: string) => {
    try {
      const after = await write.run(w);
      setMove(undefined);
      setSaid(done);
      if (w.kind === "decision") {
        setChoice(undefined);
        setRationale("");
        setTicked([]);
        setFields({});
        setEditWords("");
        setReplacement("");
        setConditions([]);
      }
      onWrite?.(after, w);
    } catch {
      // The error is shown where the write was asked for.
    }
  };

  const start = () => {
    write.reset();
    setSaid(undefined);
    void send({ kind: "claim" }, "You hold this event now; nobody else can take it while it is yours.");
  };

  // A view asked to start an answer: take the event first when it must be taken.
  const asked = useRef<number>(undefined);
  useEffect(() => {
    if (!requested || asked.current === requested.at) return;
    asked.current = requested.at;
    if (!event.allowedActions.includes(requested.action as Action)) return;
    setChoice(requested.action);
    if (claimFirst && rules.canClaim) start();
    answerRef.current?.scrollIntoView?.({ block: "nearest", behavior: "smooth" });
    answerRef.current?.querySelector<HTMLInputElement>(`input[value="${requested.action}"]`)?.focus();
  });

  const open = (m: Move) => {
    write.reset();
    setReason("");
    setSaid(undefined);
    setMove(m);
  };

  const presentedAt = startedAt !== undefined ? new Date(startedAt).toISOString() : undefined;

  const body = (): DecisionInput | undefined => {
    if (!choice) return undefined;
    const listed = ticked.filter((t) => checks.some((c) => c.text === t));
    const kept = APPROVING.has(choice)
      ? conditions
          .filter((c) => c.text.trim())
          .map((c) => ({ text: c.text.trim(), ...(c.due ? { dueAt: new Date(`${c.due}T23:59:59Z`).toISOString() } : {}) }))
      : [];
    return {
      action: choice as Action,
      ...(rationale.trim() ? { rationale: rationale.trim() } : {}),
      ...(edits ? { edits } : {}),
      ...(sticky && stickies.includes(sticky) ? { sticky } : {}),
      ...(choice === "OVERRIDE" ? { replacement: replacement.trim() } : {}),
      ...(presentedAt ? { presentedAt } : {}),
      ...(listed.length ? { checks: listed } : {}),
      ...(kept.length ? { conditions: kept } : {})
    };
  };

  const effect = (action: string) =>
    event.effects?.[action] ?? `The decision goes to ${readable(event.owner.service || event.owner.kind)}, which applies it.`;

  const votes = votesOf(event);
  const required = event.routing.required;
  const firstOfMany = required > 1 && votes.length + 1 < required;

  const reasonField = (fieldLabel: string, hint?: string, optional = false) => (
    <Field label={fieldLabel} hint={hint} required={!optional}>
      {(id, describedBy) => (
        <textarea
          id={id}
          rows={3}
          value={reason}
          aria-describedby={describedBy}
          onChange={(e) => setReason(e.target.value)}
          className="hk-input"
          data-testid="write-reason"
        />
      )}
    </Field>
  );
  const needReason = (problem: string) => (reason.trim() ? undefined : problem);

  const confirm = (() => {
    switch (move) {
      case "decision": {
        const b = body();
        if (!b) return undefined;
        return {
          title: `${label(b.action)}: ${event.subject.summary || event.ask.question}`,
          verb: label(b.action),
          danger: b.action === "HALT",
          changes: (
            <ul className="hk-confirm__list">
              <li>{effect(b.action)}</li>
              {b.edits ? <li>Edits: {b.edits.summary}</li> : null}
              {b.replacement ? <li>Replacement: {b.replacement}</li> : null}
              {b.sticky ? <li>Scope: {STICKY_LABEL[b.sticky].toLowerCase()}</li> : null}
              {b.conditions?.length ? <li>Conditions: {b.conditions.map((c) => c.text).join("; ")}</li> : null}
              {b.checks?.length ? <li>Checked: {b.checks.length} of {checks.length}</li> : null}
              {firstOfMany ? (
                <li>
                  Your vote is the {votes.length === 0 ? "first" : "next"} of {NUMBER_WORD[required] ?? required}; nothing
                  changes until {required - votes.length - 1 === 1 ? "another reviewer agrees" : "the others vote"}.
                </li>
              ) : null}
              {!enforce && minimumLeft > 0 ? (
                <li>This is under the minimum review time, so the decision is recorded as breached.</li>
              ) : null}
            </ul>
          ),
          run: () => void send({ kind: "decision", body: b }, `Your ${ACTION_NOUN[b.action] ?? "decision"} was recorded.`)
        };
      }
      case "release":
        return {
          title: "Release this event",
          verb: "Release",
          changes: "It goes back to its queue for anyone who can take it.",
          run: () => void send({ kind: "release" }, "Released to the queue.")
        };
      case "escalate":
        return {
          title: `${label("ESCALATE")}${rules.nextTier ? ` to ${readable(rules.nextTier)}` : ""}`,
          verb: label("ESCALATE"),
          changes:
            event.effects?.ESCALATE ??
            `It goes to ${rules.nextTier ? readable(rules.nextTier) : "the next tier"}, the next queue of the control's chain.`,
          fields: reasonField("Reason"),
          problem: needReason("Say why it needs the next tier."),
          run: () => void send({ kind: "escalate", reason: reason.trim() }, "Escalated.")
        };
      case "send-back":
        return {
          title: label("SEND_BACK"),
          verb: label("SEND_BACK"),
          changes: event.effects?.SEND_BACK ?? "It goes back to its proposer, who resubmits or withdraws it.",
          fields: reasonField("What should change"),
          problem: needReason("Say what the proposer should change."),
          run: () => void send({ kind: "send-back", requested: reason.trim() }, "Sent back to the proposer.")
        };
      case "request-info":
        return {
          title: label("REQUEST_MORE_INFO"),
          verb: label("REQUEST_MORE_INFO"),
          changes: event.effects?.REQUEST_MORE_INFO ?? "The event stays open, with your question on it.",
          fields: reasonField("What you need"),
          problem: needReason("Say what you need."),
          run: () =>
            void send(
              {
                kind: "decision",
                body: { action: "REQUEST_MORE_INFO", rationale: reason.trim(), ...(presentedAt ? { presentedAt } : {}) }
              },
              "Your question is on the event."
            )
        };
      case "recuse":
        return {
          title: label("RECUSE"),
          verb: label("RECUSE"),
          changes: "The event is released and you are barred from deciding it.",
          fields: reasonField("Reason"),
          problem: needReason("Say why you step aside."),
          run: () =>
            void send(
              { kind: "decision", body: { action: "RECUSE", rationale: reason.trim(), ...(presentedAt ? { presentedAt } : {}) } },
              "You stepped aside."
            )
        };
      case "extend":
        return {
          title: "Extend the deadline",
          verb: "Extend",
          changes: `A deadline can be extended once, by at most 7 days. It is due ${event.sla.dueAt ? formatMoment(event.sla.dueAt) : "now"}.`,
          fields: (
            <>
              <Field label="By">
                {(id) => (
                  <select id={id} value={extra} onChange={(e) => setExtra(e.target.value)} className="hk-input" data-testid="write-extra">
                    {EXTENSIONS.map((x) => (
                      <option key={x.value} value={x.value}>
                        {x.label}
                      </option>
                    ))}
                  </select>
                )}
              </Field>
              {reasonField("Reason")}
            </>
          ),
          problem: needReason("Say why it needs more time."),
          run: () => void send({ kind: "extend", extra, reason: reason.trim() }, "The deadline was extended.")
        };
      case "resubmit":
        return {
          title: "Resubmit",
          verb: "Resubmit",
          changes: "It returns to its queue to be reviewed again.",
          fields: reasonField("Note", "What you changed.", true),
          run: () => void send({ kind: "resubmit", note: reason.trim() || undefined }, "Resubmitted.")
        };
      case "withdraw":
        return {
          title: "Withdraw",
          verb: "Withdraw",
          danger: true,
          changes: "It is withdrawn, and the event closes with its verdict.",
          fields: reasonField("Reason"),
          problem: needReason("Say why it is withdrawn."),
          run: () => void send({ kind: "withdraw", reason: reason.trim() }, "Withdrawn.")
        };
      case "reopen":
        return {
          title: "Reopen this event",
          verb: "Reopen",
          changes: "It opens for a new decision. Its first verdict stays, and a new one is added when it closes again.",
          fields: reasonField("New evidence"),
          problem: needReason("Say what new evidence reopens it."),
          run: () => void send({ kind: "reopen", reason: reason.trim() }, "Reopened.")
        };
      default:
        return undefined;
    }
  })();

  const moves: { id: Move; label: string; show: boolean }[] = [
    { id: "release", label: "Release to the queue", show: rules.canRelease },
    { id: "escalate", label: label("ESCALATE"), show: rules.canEscalate },
    { id: "send-back", label: label("SEND_BACK"), show: rules.canSendBack },
    { id: "request-info", label: label("REQUEST_MORE_INFO"), show: rules.canRequestInfo },
    { id: "recuse", label: label("RECUSE"), show: rules.canRecuse },
    { id: "extend", label: "Extend the deadline", show: rules.canExtend },
    { id: "resubmit", label: "Resubmit", show: rules.canResubmit },
    { id: "withdraw", label: "Withdraw", show: rules.canWithdraw },
    { id: "reopen", label: "Reopen", show: rules.canReopen }
  ];
  const shownMoves = moves.filter((m) => m.show);
  const deciding = event.pending && rules.choices.length > 0 && mayDecide;
  let n = 0;
  const why = event.rationaleRequired ? "Why" : "Why (optional)";
  const clock = readClock(event.sla, event.trigger.detectedAt, now);
  const name = useId();

  return (
    <aside className="hk hk-decision" aria-label="Decision" data-testid="decision-panel" data-state={event.state}>
      <header className="hk-decision__head">
        <h2 className="hk-decision__title">{event.pending ? "Your decision" : "The decision"}</h2>
        {required > 1 ? <VoteProgress have={Math.min(votes.length, required)} need={required} /> : null}
      </header>

      {rules.canClaim ? (
        <div className="hk-claim" data-testid="decision-claim">
          <p>
            {claimFirst ? "Start the review to answer it. " : "Take it so nobody else decides it while you look. "}
            <span className="hk-muted">Nobody else can take it while it is yours.</span>
          </p>
          <Button variant={claimFirst ? "primary" : "secondary"} disabled={write.pending} onClick={start} testId="action-claim">
            {claimFirst ? "Start review" : "Claim"}
          </Button>
          {!move ? <ErrorLine error={write.error} testId="claim-error" /> : null}
        </div>
      ) : null}

      {event.pending && claimLeft !== undefined ? (
        <p className={claimLeft > 0 ? "hk-muted hk-nums" : "hk-note hk-note--warning"} data-testid="claim-expiry">
          {claimLeft > 0
            ? `You hold this event; your claim lapses in ${formatSpan(claimLeft)}.`
            : "Your claim has lapsed; anyone routed to it can take it now."}
        </p>
      ) : null}

      {!event.pending ? (
        <div className="hk-stack hk-stack--tight" data-testid="decision-closed">
          {event.outcome?.applied || event.outcome?.applyError ? (
            <p className={event.outcome.applyError ? "hk-note hk-note--danger" : "hk-note"} data-testid="decision-applied">
              {event.outcome.applyError ?? event.outcome.applied}
            </p>
          ) : null}
          {event.outcome?.conditions?.length ? <ConditionList conditions={event.outcome.conditions} now={now} /> : null}
          {event.conclusion ? (
            <span className="hk-inline-list">
              <span className="hk-muted">Verdict</span>
              <ConclusionChip conclusion={event.conclusion} />
              {event.verdict?.source.kind === "rehearsal" ? (
                <Chip tone="quiet" icon={false}>
                  Rehearsal: not evidence
                </Chip>
              ) : null}
            </span>
          ) : null}
        </div>
      ) : null}

      {rules.refusal && event.pending ? (
        <p className="hk-refusal" role="note" data-testid="decision-refusal">
          <Icon name="info" className="hk-refusal__icon" />
          {rules.refusal}
        </p>
      ) : null}

      {event.infoRequests?.length ? (
        <div className="hk-stack hk-stack--tight" data-testid="decision-questions">
          <h3 className="hk-band">Questions asked</h3>
          <ul className="hk-votes-list">
            {event.infoRequests.map((q, i) => (
              <li key={i} className="hk-vote">
                <span className="hk-vote__why">{q.question}</span>
                <span className="hk-muted">
                  {nameOf(q.by)}, {formatMoment(q.at)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {(event.pending ? votes : event.decisions).length ? (
        <div className="hk-stack hk-stack--tight">
          <h3 className="hk-band">{event.pending ? "Votes so far" : "Who decided"}</h3>
          <ul className="hk-votes-list">
            {(event.pending ? votes : event.decisions).map((d, i) => (
              <Vote key={i} d={d} name={nameOf(d.reviewer.subject)} labels={labels} />
            ))}
          </ul>
        </div>
      ) : null}

      {deciding ? (
        <Step n={++n} title="Your answer" closed={claimFirst}>
          {claimFirst ? (
            <p className="hk-muted" data-testid="decision-after-claim">
              These open once you start the review.
            </p>
          ) : null}
          <fieldset className="hk-choices" ref={answerRef} disabled={claimFirst}>
            <legend className="hk-sr-only">Your answer</legend>
            {rules.choices.map((action) => {
              const refused = rules.actionRefusal[action];
              return (
                <label
                  key={action}
                  className={cx("hk-choice", choice === action && "hk-choice--on", refused && "hk-choice--refused")}
                  data-testid={`choice-${action}`}
                >
                  <input
                    type="radio"
                    name={name}
                    value={action}
                    checked={choice === action}
                    disabled={Boolean(refused)}
                    onChange={() => pick(action)}
                    className="hk-choice__input"
                  />
                  <span className="hk-choice__label">{label(action)}</span>
                  <span className="hk-choice__effect" data-testid="choice-effect">
                    {refused ?? effect(action)}
                  </span>
                </label>
              );
            })}
          </fieldset>

          {choice === "EDIT" ? (
            <div className="hk-stack hk-stack--tight" data-testid="decision-edits">
              <h4 className="hk-band">{label("EDIT")}</h4>
              {original ? (
                <>
                  {Object.entries(original).map(([key, value]) => {
                    const text = fields[key] ?? fieldText(value);
                    const set = (next: string) => setFields({ ...fields, [key]: next });
                    return (
                      <Field key={key} label={key}>
                        {(id) =>
                          text.length > 60 ? (
                            <textarea id={id} rows={4} value={text} onChange={(e) => set(e.target.value)} className="hk-input" data-testid={`edit-${key}`} />
                          ) : (
                            <input id={id} value={text} onChange={(e) => set(e.target.value)} className="hk-input" data-testid={`edit-${key}`} />
                          )
                        }
                      </Field>
                    );
                  })}
                  <p className="hk-muted" data-testid="edit-summary">
                    {edits ? edits.summary : "Nothing changed yet."}
                  </p>
                </>
              ) : (
                <Field label="What changes">
                  {(id) => (
                    <textarea id={id} rows={3} value={editWords} onChange={(e) => setEditWords(e.target.value)} className="hk-input" data-testid="edit-words" />
                  )}
                </Field>
              )}
            </div>
          ) : null}

          {choice === "OVERRIDE" ? (
            <Field label="Replacement output" required hint="What the system's output is replaced with.">
              {(id, describedBy) => (
                <textarea
                  id={id}
                  rows={4}
                  value={replacement}
                  aria-describedby={describedBy}
                  onChange={(e) => setReplacement(e.target.value)}
                  className="hk-input"
                  data-testid="decision-replacement"
                />
              )}
            </Field>
          ) : null}

          {stickies.length ? (
            <fieldset className="hk-segmented" data-testid="decision-sticky">
              <legend className="hk-band">Scope</legend>
              {choice === "DENY" ? (
                <label className={cx("hk-segment", sticky === undefined && "hk-segment--on")}>
                  <input type="radio" name={`${name}-sticky`} checked={sticky === undefined} onChange={() => setSticky(undefined)} />
                  This once
                </label>
              ) : null}
              {stickies.map((s) => (
                <label key={s} className={cx("hk-segment", sticky === s && "hk-segment--on")} data-testid={`sticky-${s}`}>
                  <input type="radio" name={`${name}-sticky`} checked={sticky === s} onChange={() => setSticky(s)} />
                  {STICKY_LABEL[s]}
                </label>
              ))}
              {sticky === "APPROVE_FOR_PATTERN" || sticky === "DENY_FOR_PATTERN" ? (
                <p className="hk-muted hk-segmented__note">
                  It becomes a standing decision for 30 days: later events that match are settled by it until it is revoked.
                </p>
              ) : null}
            </fieldset>
          ) : null}

          {approving ? (
            <div className="hk-stack hk-stack--tight" data-testid="decision-conditions">
              <h4 className="hk-band">Conditions</h4>
              {conditions.map((c, i) => (
                <div key={i} className="hk-condition-row">
                  <input
                    value={c.text}
                    placeholder="What must hold"
                    aria-label={`Condition ${i + 1}`}
                    onChange={(e) => setConditions(conditions.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)))}
                    className="hk-input"
                    data-testid="condition-text"
                  />
                  <input
                    type="date"
                    value={c.due}
                    aria-label={`Condition ${i + 1} due`}
                    onChange={(e) => setConditions(conditions.map((x, j) => (j === i ? { ...x, due: e.target.value } : x)))}
                    className="hk-input"
                    data-testid="condition-due"
                  />
                  <button
                    type="button"
                    className="hk-link"
                    aria-label={`Remove condition ${i + 1}`}
                    onClick={() => setConditions(conditions.filter((_, j) => j !== i))}
                  >
                    Remove
                  </button>
                </div>
              ))}
              <button
                type="button"
                className="hk-link"
                onClick={() => setConditions([...conditions, { text: "", due: "" }])}
                data-testid="condition-add"
              >
                Add a condition
              </button>
              {conditions.length ? (
                <p className="hk-muted">The approval carries them; they are kept with its outcome.</p>
              ) : null}
            </div>
          ) : null}
        </Step>
      ) : null}

      {deciding && checks.length ? (
        <Step n={++n} title="What you checked" closed={claimFirst}>
          <fieldset className="hk-checks" disabled={claimFirst} data-testid="decision-checks">
            <legend className="hk-sr-only">What you checked</legend>
            {checks.map((c) => (
              <label key={c.text} className="hk-check">
                <input
                  type="checkbox"
                  checked={ticked.includes(c.text)}
                  onChange={() => setTicked(ticked.includes(c.text) ? ticked.filter((t) => t !== c.text) : [...ticked, c.text])}
                />
                <span>{c.text}</span>
              </label>
            ))}
          </fieldset>
        </Step>
      ) : null}

      {deciding ? (
        <Step n={++n} title={why} closed={claimFirst}>
          {claimFirst ? null : (
            <textarea
              rows={3}
              value={rationale}
              aria-label={event.rationaleRequired ? "Why (required)" : "Why"}
              onChange={(e) => setRationale(e.target.value)}
              placeholder={approving ? "What did you check, and why is this safe?" : "What did you check, and why?"}
              className="hk-input"
              data-testid="decision-rationale"
            />
          )}
        </Step>
      ) : null}

      {deciding ? (
        <Step n={++n} title="Submit" closed={claimFirst}>
          {claimFirst ? null : (
            <div className="hk-submit">
              <Button variant="primary" disabled={!canSubmit} onClick={() => open("decision")} testId="decision-submit">
                {choice ? `Submit ${ACTION_NOUN[choice] ?? "decision"}` : "Submit"}
              </Button>
              {minimum > 0 ? (
                <p className="hk-muted hk-nums" aria-live="polite" data-testid="decision-minimum">
                  {minimumLeft > 0
                    ? enforce
                      ? `You can submit in ${minimumLeft} s; this control asks for at least ${formatSpan(minimum * 1000)} of review.`
                      : `${minimumLeft} s to the minimum review time; a decision before then is recorded as breached.`
                    : `The minimum review time of ${formatSpan(minimum * 1000)} has passed.`}
                </p>
              ) : null}
              {choice && firstOfMany ? (
                <p className="hk-muted" data-testid="decision-next">
                  Your vote is the {votes.length === 0 ? "first" : "next"} of {NUMBER_WORD[required] ?? required}. Nothing changes until{" "}
                  {required - votes.length - 1 === 1 ? "another reviewer agrees" : "the others vote"}.
                </p>
              ) : null}
              {blocked ? (
                <p className="hk-blocked" data-testid="decision-blocked">
                  {blocked}
                </p>
              ) : null}
            </div>
          )}
        </Step>
      ) : null}

      {confirm ? (
        <Confirm
          key={move}
          title={confirm.title}
          changes={confirm.changes}
          verb={confirm.verb}
          danger={"danger" in confirm ? confirm.danger : false}
          pending={write.pending}
          problem={"problem" in confirm ? confirm.problem : undefined}
          error={write.error}
          onConfirm={confirm.run}
          onCancel={() => setMove(undefined)}
        >
          {"fields" in confirm ? confirm.fields : null}
        </Confirm>
      ) : null}

      {shownMoves.length ? (
        <div className="hk-moves" data-testid="decision-moves">
          {event.pending ? <span className="hk-muted">Not ready to decide?</span> : null}
          <div className="hk-moves__list">
            {shownMoves.map((m) => (
              <button
                key={m.id}
                type="button"
                className="hk-link"
                onClick={() => open(m.id)}
                aria-expanded={move === m.id}
                data-testid={`action-${m.id}`}
              >
                {m.label}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {event.slaExtensions?.length ? (
        <p className="hk-muted" data-testid="sla-extended">
          The deadline was extended by {formatDuration(event.slaExtensions[0].extra)}
          {event.slaExtensions[0].by ? ` by ${nameOf(event.slaExtensions[0].by)}` : ""}
          {event.slaExtensions[0].reason ? `: ${event.slaExtensions[0].reason}` : "."}
        </p>
      ) : null}

      {event.pending && clock.tone === "overdue" && event.sla.lateDecision === "REFUSE" ? (
        <p className="hk-note hk-note--danger">The deadline has passed, and this control refuses late decisions.</p>
      ) : null}

      <p className="hk-sr-only" role="status" data-testid="decision-status">
        {said ?? ""}
      </p>
      {said && !write.error ? (
        <p className="hk-said" aria-hidden="true">
          <Icon name="check" className="hk-said__icon" />
          {said}
        </p>
      ) : null}
    </aside>
  );
}
