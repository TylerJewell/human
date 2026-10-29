import { votesOf } from "./parse";
import type { HumanEvent, OverseerRegistry } from "./types";
import { actionLabel, readable, readClock, serviceNow, type ClockTone } from "./words";

// The decision clock and who may decide, worked out from one event: the time
// left to its deadline and the review time counted from the acting reviewer's
// claim, both on the service's clock; and the people it is routed to, with
// anyone the control keeps out struck through and why.

export interface DecisionClockReading {
  tone: ClockTone;
  /** Seconds to the deadline; negative once past it; undefined without one or once settled. */
  left?: number;
  /** The share of the deadline still to run, 0 to 1. */
  remaining: number;
  minimum: {
    seconds: number;
    /** Whether the review time is counting: it counts from the acting reviewer's claim. */
    started: boolean;
    elapsed: number;
    met: boolean;
  };
}

/** The deadline and the minimum review time of `event` at this browser's `browserNow`, for the reviewer `me`. */
export function decisionClock(event: HumanEvent, browserNow: number, me?: string): DecisionClockReading {
  const now = serviceNow(event, browserNow);
  const reading = readClock(event.sla, event.trigger.detectedAt, now);
  const due = event.sla.dueAt ? Date.parse(event.sla.dueAt) : NaN;
  const left = Number.isFinite(due) && event.pending ? Math.floor((due - now) / 1000) : undefined;
  const seconds = event.minimumReviewSeconds ?? 0;
  const since = event.holder && me && event.holder.subject === me ? Date.parse(event.holder.since) : NaN;
  const started = Number.isFinite(since);
  const elapsed = started ? Math.max(0, Math.floor((now - since) / 1000)) : 0;
  return {
    tone: reading.tone,
    left,
    remaining: left === undefined ? 0 : Math.max(0, 1 - reading.fraction),
    minimum: { seconds, started, elapsed: Math.min(elapsed, seconds), met: seconds === 0 || elapsed >= seconds }
  };
}

/** A countdown in seconds as a clock shows it: "0:25:00", or days and hours past a day, "1 d 1:01". */
export function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const pad = (n: number) => String(n).padStart(2, "0");
  if (s >= 86400) return `${Math.floor(s / 86400)} d ${Math.floor((s % 86400) / 3600)}:${pad(Math.floor((s % 3600) / 60))}`;
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

/** What happens when an event's deadline passes, as a short clause: "then “Deny” by default", "then to head of operations". */
export function afterDeadline(event: Pick<HumanEvent, "sla" | "escalation" | "actionLabels">): string {
  const { sla, escalation } = event;
  switch (sla.timeoutBehaviour) {
    case "RESUME_WITH_DEFAULT":
      return sla.default ? `then “${actionLabel(sla.default, event.actionLabels)}” by default` : "then it goes ahead";
    case "ESCALATE": {
      const next = escalation?.chain[escalation.level + 1];
      return next ? `then to ${readable(next).toLowerCase()}` : "then escalated";
    }
    case "ABANDON":
      return "then abandoned";
    default:
      return "then it times out";
  }
}

export interface Decider {
  subject: string;
  name: string;
  initials: string;
  note: string;
  /** Whether this person may decide the event now. */
  may: boolean;
  /** Why not, in a word, when they may not. */
  barred?: "requested" | "built" | "independent" | "unqualified" | "voted";
  me: boolean;
}

/** How a service names the people a control keeps from deciding, in a note and on a chip. */
export interface DeciderWords {
  proposer?: string;
  builder?: string;
  proposerChip?: string;
  builderChip?: string;
}

const DEFAULT_WORDS: Required<DeciderWords> = {
  proposer: "asked for it",
  builder: "built it",
  proposerChip: "Requested",
  builderChip: "Built it"
};

/** Two letters for a person: from their name, or from the part of their address before `@`. */
export function initialsOf(nameOrSubject: string): string {
  const words = nameOrSubject.split("@")[0].split(/[^A-Za-z]+/).filter(Boolean);
  const letters = words.length > 1 ? words[0][0] + words[1][0] : (words[0] ?? "?").slice(0, 2);
  return letters.toUpperCase();
}

/**
 * The people the event is routed to, and the people it must stay independent
 * of, struck out with why: whoever asked, whoever built it, anyone not
 * qualified or who has already voted. The barred come first, then `me`, then
 * the others who may decide.
 */
export function deciders(event: HumanEvent, registry: OverseerRegistry | undefined, me?: string, words: DeciderWords = {}): Decider[] {
  const w = { ...DEFAULT_WORDS, ...words };
  const independentOf = event.routing.independentOf ?? [];
  const voted = new Set(votesOf(event).map((d) => d.reviewer.subject));
  const people = registry?.overseers ?? [];
  const nameOf = (s: string) => people.find((p) => p.subject === s)?.name ?? s;
  const why = (s: string): Pick<Decider, "note" | "barred"> =>
    s === event.proposedBy
      ? { note: `${w.proposer}, so cannot decide`, barred: "requested" }
      : s === event.builtBy
        ? { note: `${w.builder}, so cannot decide`, barred: "built" }
        : { note: "must stay independent of it", barred: "independent" };
  const person = (subject: string, rest: Pick<Decider, "note" | "may" | "barred">): Decider => ({
    subject,
    name: nameOf(subject),
    initials: initialsOf(nameOf(subject)),
    me: subject === me,
    ...rest
  });

  const barred = independentOf.map((s) => person(s, { may: false, ...why(s) }));
  const routed = people
    .filter((p) => p.roles.some((r) => event.routing.roles.includes(r)) && !independentOf.includes(p.subject))
    .map((p) => {
      if (!p.qualified)
        return person(p.subject, { may: false, barred: "unqualified", note: `not qualified${p.qualifiedReason ? `: ${p.qualifiedReason}` : ""}` });
      if (voted.has(p.subject)) return person(p.subject, { may: false, barred: "voted", note: "has voted" });
      const roles = p.roles.map(readable).join(" · ");
      const holds = event.holder?.subject === p.subject;
      return person(p.subject, { may: event.pending, note: holds ? `${roles} · holds it now` : roles });
    });
  const rank = (d: Decider) => (d.barred === "requested" || d.barred === "built" || d.barred === "independent" ? 0 : d.me ? 1 : d.may ? 2 : 3);
  return [...barred, ...routed].sort((a, b) => rank(a) - rank(b));
}

/** The chip a barred person's row carries, in the service's words. */
export function barredWord(d: Decider, words: DeciderWords = {}): string {
  const w = { ...DEFAULT_WORDS, ...words };
  switch (d.barred) {
    case "requested":
      return w.proposerChip;
    case "built":
      return w.builderChip;
    case "independent":
      return "Barred";
    case "unqualified":
      return "Not qualified";
    case "voted":
      return "Voted";
    default:
      return "Closed";
  }
}

/**
 * How far an event has settled: 0 while it waits for a decision, 1 once
 * decided, 2 once applied or otherwise closed. A rise means a person or the
 * system has acted on it.
 */
export const settleStage = (event: Pick<HumanEvent, "pending" | "state">): 0 | 1 | 2 =>
  event.pending ? 0 : event.state === "DECIDED" ? 1 : 2;
