import { actionLabel, readable, readClock, votesOf, type ClockTone, type HumanEvent, type OverseerRegistry } from "@akka-human/kit";

// What the screens show, worked out from the service's records and events:
// which links wait for a review, which cases are open, the decision clock and
// who may decide. Kept free of React so it can be tested on its own.

export const LINK_REVIEW = "custom-cl0004";
export const CASE_RESOLUTION = "custom-cl0046";

// ---------------------------------------------------------------- links

/** One source record in a link, as `GET /links` gives it. */
export interface Member {
  source: string;
  ref: string;
  loadedBy: string;
  fields: Record<string, string>;
}

/** `status`: SINGLE (one record so far), PENDING (waiting for a review), CONFIRMED or REJECTED. */
export interface LinkView {
  clusterId: string;
  rule: string;
  status: "SINGLE" | "PENDING" | "CONFIRMED" | "REJECTED";
  left: Member | null;
  right: Member | null;
  agree: string[];
  differ: string[];
  reviewedBy: string | null;
  eventId: string | null;
}

export interface Links {
  /** Two records and a review raised for them. */
  toReview: LinkView[];
  /** Confirmed or rejected. */
  decided: LinkView[];
  /** Keys that hold one record so far, so nothing to review yet. */
  single: number;
}

export function sortLinks(links: readonly LinkView[]): Links {
  return {
    toReview: links.filter((l) => l.status === "PENDING" && l.eventId && l.left && l.right),
    decided: links.filter((l) => l.status === "CONFIRMED" || l.status === "REJECTED"),
    single: links.filter((l) => l.status === "SINGLE").length
  };
}

const TITLE_FIELDS = ["name", "fullName", "email", "title", "sku"];

/** What a link is called in a list: the name the records share, or their first field. */
export function linkTitle(link: LinkView): string {
  const fields = { ...link.right?.fields, ...link.left?.fields };
  for (const f of TITLE_FIELDS) if (fields[f]) return fields[f];
  const first = Object.values(fields)[0];
  return first ?? link.clusterId;
}

export interface FieldRow {
  field: string;
  left?: string;
  right?: string;
  same: boolean;
}

/**
 * Every field either record has, in the left record's order, and whether the
 * two agree. The service's `agree` and `differ` lists decide; a field neither
 * names is compared here.
 */
export function fieldRows(link: LinkView): FieldRow[] {
  const left = link.left?.fields ?? {};
  const right = link.right?.fields ?? {};
  const names = [...new Set([...Object.keys(left), ...Object.keys(right)])];
  return names.map((field) => ({
    field,
    left: left[field],
    right: right[field],
    same: link.differ.includes(field) ? false : link.agree.includes(field) ? true : left[field] === right[field]
  }));
}

/** The people who loaded the records, each once. */
export const loadersOf = (link: LinkView) =>
  [...new Set([link.left?.loadedBy, link.right?.loadedBy].filter((s): s is string => Boolean(s)))];

// ---------------------------------------------------------------- cases

/** `status`: OPEN, RESOLVED or DISMISSED. `headline` holds the fields a list shows. */
export interface Case {
  id: string;
  watcherId: string;
  title: string;
  headline: Record<string, string>;
  status: "OPEN" | "RESOLVED" | "DISMISSED";
  openedAt: string;
  readings: number;
  closedBy: string | null;
  reason: string | null;
  eventId: string | null;
}

export const CASE_FILTERS = [
  { id: "OPEN", label: "Open" },
  { id: "RESOLVED", label: "Resolved" },
  { id: "DISMISSED", label: "Dismissed" },
  { id: "ALL", label: "All" }
] as const;

export type CaseFilter = (typeof CASE_FILTERS)[number]["id"];

/** Cases under `filter`, newest first. */
export function casesWith(cases: readonly Case[], filter: CaseFilter): Case[] {
  return cases
    .filter((c) => filter === "ALL" || c.status === filter)
    .sort((a, b) => Date.parse(b.openedAt) - Date.parse(a.openedAt));
}

export function caseCounts(cases: readonly Case[]): Record<CaseFilter, number> {
  const count = (s: string) => cases.filter((c) => c.status === s).length;
  return { OPEN: count("OPEN"), RESOLVED: count("RESOLVED"), DISMISSED: count("DISMISSED"), ALL: cases.length };
}

/** The headline fields a case list shows as columns, in the order they first appear. */
export function headlineColumns(cases: readonly Case[], max = 3): string[] {
  return [...new Set(cases.flatMap((c) => Object.keys(c.headline ?? {})))].slice(0, max);
}

// ---------------------------------------------------------------- the decision clock

/** The service's time: the browser's, moved by how far the service's clock is ahead. */
export const serviceNow = (event: Pick<HumanEvent, "clockOffset">, browserNow: number) =>
  browserNow + (event.clockOffset ?? 0);

export interface DecisionClock {
  tone: ClockTone;
  /** Seconds to the deadline; negative once past it; undefined without one. */
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

/** The deadline and the minimum review time of `event` at `browserNow`, for the reviewer `me`. */
export function decisionClock(event: HumanEvent, browserNow: number, me?: string): DecisionClock {
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

/** A countdown: hours, minutes and seconds, or days and hours past a day. */
export function hms(total: number): string {
  const s = Math.max(0, Math.floor(total));
  const pad = (n: number) => String(n).padStart(2, "0");
  if (s >= 86400) return `${Math.floor(s / 86400)} d ${Math.floor((s % 86400) / 3600)}:${pad(Math.floor((s % 3600) / 60))}`;
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

/** What happens when the deadline passes, as the clock's last line says it. */
export function afterDeadline(event: HumanEvent): string {
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

// ---------------------------------------------------------------- who may decide

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

/** Two letters for a person: from their name, or from the part of their address before `@`. */
export function initialsOf(nameOrSubject: string): string {
  const words = nameOrSubject.split("@")[0].split(/[^A-Za-z]+/).filter(Boolean);
  const letters = words.length > 1 ? words[0][0] + words[1][0] : (words[0] ?? "?").slice(0, 2);
  return letters.toUpperCase();
}

/**
 * The people the event is routed to, and the people it must stay independent
 * of, struck out with why: whoever asked, whoever built it, anyone not
 * qualified or who has already voted. The barred come first, then `me`.
 * `words` says how this service names the proposer and the builder.
 */
export function deciders(
  event: HumanEvent,
  registry: OverseerRegistry | undefined,
  me?: string,
  words: { proposer: string; builder: string } = { proposer: "asked for it", builder: "built it" }
): Decider[] {
  const independentOf = event.routing.independentOf ?? [];
  const voted = new Set(votesOf(event).map((d) => d.reviewer.subject));
  const people = registry?.overseers ?? [];
  const nameOf = (s: string) => people.find((p) => p.subject === s)?.name ?? s;
  const why = (s: string): Pick<Decider, "note" | "barred"> =>
    s === event.proposedBy
      ? { note: `${words.proposer}, so cannot decide`, barred: "requested" }
      : s === event.builtBy
        ? { note: `${words.builder}, so cannot decide`, barred: "built" }
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

// ---------------------------------------------------------------- where an event belongs

/** The screen that shows the record an event is about, when contextlayer has one. */
export function recordOf(event: Pick<HumanEvent, "control" | "subject">): { path: string; label: string } | undefined {
  const ref = event.subject.ref;
  if (!ref) return undefined;
  if (event.control.rowId === LINK_REVIEW) return { path: `/links/${ref}`, label: "The link" };
  if (event.control.rowId === CASE_RESOLUTION) return { path: `/cases/${ref}`, label: "The case" };
  return undefined;
}
