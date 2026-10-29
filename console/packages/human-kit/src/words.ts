import type {
  Action,
  Conclusion,
  Flavor,
  Mode,
  PatternId,
  ReviewMode,
  SlaState,
  State,
  Sticky,
  SubjectKind,
  TimeoutBehaviour
} from "./types";

// The words every view shares, so a state, action or outcome reads the same on
// every screen, and the small print: times, spans and durations as the console
// writes them. A value this kit does not know is shown by rule rather than
// dropped.

/** Words that keep their capitals inside a readable name. */
const ACRONYMS = new Set(["ai", "api", "eu", "llm", "ml", "sla", "id", "crm"]);

/**
 * An id read by rule: dashes and underscores are spaces, the first word is
 * capitalised and acronyms keep their capitals. "ai-change-board" reads "AI
 * change board".
 */
export function readable(id: string): string {
  const words = id
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((w) => (ACRONYMS.has(w.toLowerCase()) ? w.toUpperCase() : w.toLowerCase()));
  const text = words.join(" ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** A camelCase or dotted key as words: "policyLimitDays" reads "Policy limit days". */
export const keyWords = (key: string) =>
  readable(key.replace(/([a-z0-9])([A-Z])/g, "$1-$2").replace(/\./g, "-"));

/** Names as a sentence lists them: "A", "A and B", "A, B and C". */
export function listNames(names: readonly string[], joiner = "and"): string {
  if (names.length <= 1) return names[0] ?? "";
  return `${names.slice(0, -1).join(", ")} ${joiner} ${names.at(-1)}`;
}

export const capital = (words: string) => words.charAt(0).toUpperCase() + words.slice(1);

/** Text ending in a full stop, unless it already ends a sentence. */
export const sentence = (text: string) => {
  const t = text.trim();
  return /[.!?]$/.test(t) ? t : `${t}.`;
};

export const PATTERN_NAME: Record<PatternId, string> = {
  "action-approval": "Action approval",
  "decision-review": "Decision review before effect",
  "change-authorization": "Change authorization",
  exception: "Exception or waiver",
  "match-adjudication": "Match and adjudication",
  "outcome-reconciliation": "Outcome reconciliation",
  "contest-appeal": "Contest and appeal",
  override: "Override with justification",
  "flag-feedback": "Flag and feedback",
  triage: "Triage of alerts, cases and incidents",
  "hand-off": "Hand-off to a person",
  "halt-restart": "Halt and restart",
  attestation: "Scheduled attestation",
  "oversight-quality": "Oversight quality"
};

export const PATTERNS = Object.keys(PATTERN_NAME) as PatternId[];

export const patternName = (pattern: string, given?: string) =>
  given || PATTERN_NAME[pattern as PatternId] || readable(pattern);

export const FLAVOR_LABEL: Record<Flavor, string> = {
  "human-system-authorization": "Authorization",
  "human-monitoring": "Monitoring",
  "human-halt": "Halt",
  "human-feedback": "Feedback",
  "human-domain-decision": "Domain decision"
};

export const flavorLabel = (flavor: string) => FLAVOR_LABEL[flavor as Flavor] ?? readable(flavor);

/** The loop a mode puts the person in. */
export const MODE_LABEL: Record<Mode, string> = {
  HITL: "In the loop",
  HOTL: "On the loop",
  HALT: "Over the loop"
};

export const modeLabel = (mode: string) => MODE_LABEL[mode as Mode] ?? readable(mode);

export const SUBJECT_KIND_LABEL: Record<SubjectKind, string> = {
  action: "Action",
  behaviour: "Behaviour",
  change: "Change",
  system: "System",
  output: "Output",
  records: "Records",
  appeal: "Appeal",
  exception: "Exception",
  conversation: "Conversation",
  attestation: "Attestation"
};

export const subjectKindLabel = (kind: string) => SUBJECT_KIND_LABEL[kind as SubjectKind] ?? readable(kind);

export const STATE_LABEL: Record<State, string> = {
  DETECTED: "Detected",
  OPEN: "Open",
  CLAIMED: "Claimed",
  IN_REVIEW: "In review",
  AWAITING_SECOND: "Awaiting second",
  ESCALATED: "Escalated",
  CHANGES_REQUESTED: "Sent back",
  DECIDED: "Decided",
  APPLIED: "Applied",
  TIMED_OUT: "Timed out",
  ABANDONED: "Abandoned",
  KILLED: "Killed",
  WITHDRAWN: "Withdrawn",
  CLOSED: "Closed",
  REOPENED: "Reopened"
};

export const stateLabel = (state: string) => STATE_LABEL[state as State] ?? readable(state);

/** An action as a control's default names it, when the event gives no label of its own. */
export const ACTION_LABEL: Record<Action, string> = {
  APPROVE: "Approve",
  DENY: "Deny",
  EDIT: "Edit",
  REDIRECT: "Redirect",
  REPLAN: "Replan",
  ESCALATE: "Escalate",
  HALT: "Halt",
  RECUSE: "Recuse",
  REQUEST_MORE_INFO: "Ask for more",
  SEND_BACK: "Send back",
  DECLINE_SYSTEM: "Take the manual path",
  OVERRIDE: "Override",
  DISREGARD: "Disregard",
  REVERSE: "Reverse"
};

/** An action as the event labels it, else as the contract names it. */
export const actionLabel = (action: string, labels?: Record<string, string>) =>
  labels?.[action] ?? ACTION_LABEL[action as Action] ?? readable(action);

/** An action as done: the word a vote or an outcome is given in. */
export const ACTION_DONE: Record<Action, string> = {
  APPROVE: "Approved",
  DENY: "Denied",
  EDIT: "Edited",
  REDIRECT: "Redirected",
  REPLAN: "Replanned",
  ESCALATE: "Escalated",
  HALT: "Halted",
  RECUSE: "Recused",
  REQUEST_MORE_INFO: "Asked for more",
  SEND_BACK: "Sent back",
  DECLINE_SYSTEM: "Taken over",
  OVERRIDE: "Overridden",
  DISREGARD: "Disregarded",
  REVERSE: "Reversed"
};

export const actionDone = (action: string) => ACTION_DONE[action as Action] ?? readable(action);

export const STICKY_LABEL: Record<Sticky, string> = {
  APPROVE_ONCE: "This once",
  APPROVE_FOR_SESSION: "For this session",
  APPROVE_FOR_PATTERN: "For every match",
  DENY_FOR_PATTERN: "For every match"
};

export const REVIEW_MODE_LABEL: Record<ReviewMode, string> = {
  SINGLE: "One reviewer",
  TWO_PERSON: "Two people",
  N_OF_M: "Several of a group",
  CONSENSUS: "Everyone agrees"
};

export const CONCLUSION_LABEL: Record<Conclusion, string> = {
  upheld: "Upheld",
  breached: "Breached",
  inconclusive: "Inconclusive",
  error: "Error"
};

export const conclusionLabel = (c: string) => CONCLUSION_LABEL[c as Conclusion] ?? readable(c);

/** What happens when an SLA runs out, as the end of "then …". */
export function timeoutSentence(behaviour: TimeoutBehaviour | undefined, fallback?: Action | null): string {
  switch (behaviour) {
    case "RAISE_TIMEOUT":
      return "it times out and is recorded as timed out";
    case "RESUME_WITH_DEFAULT":
      return fallback
        ? `it resumes with the default: ${ACTION_LABEL[fallback].toLowerCase()}`
        : "it resumes with the control's default";
    case "ESCALATE":
      return "it escalates to the next tier";
    case "ABANDON":
      return "it is abandoned";
    default:
      return "the control's timeout applies";
  }
}

const DETECTOR_PHRASE: Record<string, string> = {
  "tool-policy": "a tool policy",
  guardrail: "a guardrail",
  eval: "an evaluation",
  monitor: "a monitor",
  workflow: "a workflow",
  "change-request": "a change request",
  person: "a person",
  schedule: "a schedule"
};

/** What detected an event, as a sentence says it: "a tool policy". */
export const detectorPhrase = (detector: string) =>
  DETECTOR_PHRASE[detector] ?? `${/^[aeiou]/i.test(detector) ? "an" : "a"} ${readable(detector).toLowerCase()}`;

/** A person in the registry by name, else their subject; a role or system by its readable name. */
export type NameOf = (subject: string) => string;

export const actorText = (actor: string, nameOf?: NameOf) =>
  actor.includes("@") ? nameOf?.(actor) ?? actor : actor.includes(":") ? actor : readable(actor);

// ---------------------------------------------------------------- times

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/** A day as prose names it: "October 29, 2026". A date-only value is its own date in every zone. */
export const formatDay = (value: string) =>
  new Intl.DateTimeFormat("en-US", { dateStyle: "long", ...(DATE_ONLY.test(value) ? { timeZone: "UTC" } : {}) }).format(
    new Date(value)
  );

const TIME_OF_DAY = new Intl.DateTimeFormat("en-US", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });

/** A moment as a sentence names it, "October 29, 2026, 14:00", in the reader's time. */
export function formatMoment(value: string | number | Date): string {
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return String(value);
  return `${formatDay(at.toISOString())}, ${TIME_OF_DAY.format(at)}`;
}

/** A moment in a table cell or a tight line: "Oct 29, 14:00". */
export function formatShortMoment(value: string | number | Date): string {
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return String(value);
  return `${new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(at)}, ${TIME_OF_DAY.format(at)}`;
}

/** What a cell shows for a value it does not have. */
export const MISSING = "—";

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** A span of time in the unit that reads best: "8 s", "2 min 5 s", "1 h 20 min", "4 d 6 h". */
export function formatSpan(ms: number): string {
  const abs = Math.abs(ms);
  if (!Number.isFinite(abs)) return MISSING;
  if (abs < 59_500) return `${Math.max(0, Math.round(abs / SECOND))} s`;
  const two = (large: number, lu: string, small: number, su: string) =>
    small === 0 ? `${large} ${lu}` : `${large} ${lu} ${small} ${su}`;
  const seconds = Math.round(abs / SECOND);
  if (seconds < HOUR / SECOND) return two(Math.floor(seconds / 60), "min", seconds % 60, "s");
  const minutes = Math.round(abs / MINUTE);
  if (minutes < DAY / MINUTE) return two(Math.floor(minutes / 60), "h", minutes % 60, "min");
  const hours = Math.round(abs / HOUR);
  return two(Math.floor(hours / 24), "d", hours % 24, "h");
}

const DURATION =
  /^P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)W)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/;

/** An ISO-8601 duration ("PT30M", "P1D") in milliseconds; a year is 365 days and a month 30. */
export function parseDuration(iso: string | null | undefined): number | undefined {
  if (!iso) return undefined;
  const m = DURATION.exec(iso);
  if (!m || m.slice(1).every((part) => part === undefined)) return undefined;
  const [y, mo, w, d, h, mi, s] = m.slice(1).map((part) => Number(part ?? 0));
  return ((((y * 365 + mo * 30 + w * 7 + d) * 24 + h) * 60 + mi) * 60 + s) * 1000;
}

const UNITS = ["year", "month", "week", "day", "hour", "minute", "second"];

/** An ISO-8601 duration in words, in the units it is written in: "PT24H" reads "24 hours". */
export function formatDuration(iso: string | null | undefined): string {
  if (!iso) return MISSING;
  const m = DURATION.exec(iso);
  if (!m || m.slice(1).every((part) => part === undefined)) return iso;
  const words = m
    .slice(1)
    .flatMap((part, i) =>
      part === undefined || Number(part) === 0 ? [] : [`${Number(part)} ${UNITS[i]}${Number(part) === 1 ? "" : "s"}`]
    );
  return words.join(" ") || "0 seconds";
}

/** A share of 1 or a percentage, one way everywhere: "14.4%", "35%". */
export function formatPercent(value: number, of: "percent" | "share" = "share"): string {
  const percent = of === "share" ? value * 100 : value;
  return `${Number(percent.toFixed(1))}%`;
}

/** A value as a fact shows it: text as it is, a share of 1 as a percentage where its name says so, anything else as JSON. */
export function valueText(value: unknown, name = ""): string {
  if (typeof value === "string") return value;
  if (/share|rate|ratio/i.test(name) && typeof value === "number" && value >= 0 && value <= 1) {
    return formatPercent(value);
  }
  if (typeof value === "number") return value.toLocaleString("en-US");
  if (value === null || value === undefined) return MISSING;
  return JSON.stringify(value);
}

// ---------------------------------------------------------------- the SLA clock

export type ClockTone = "none" | "running" | "at-risk" | "overdue" | "paused" | "met" | "missed";

export interface ClockReading {
  tone: ClockTone;
  /** How much of the deadline is spent, 0 to 1. */
  fraction: number;
  /** "12 min left", "3 min over", "SLA kept". */
  label: string;
  /** Milliseconds left; negative when over; undefined without a deadline. */
  left?: number;
}

interface SlaLike {
  state: SlaState | string;
  dueAt?: string | null;
  target?: string | null;
  startedAt?: string | null;
  atRiskAt?: number;
}

/** The share of a deadline spent at which an event is at risk, unless the control says otherwise. */
export const AT_RISK = 0.75;

/**
 * The service's time at this browser's `browserNow`, for an event or row that
 * reported its service's clock. Deadlines, claims and review times count
 * against it, so a service whose clock is moved still shows the right time left.
 */
export const serviceNow = (item: { clockOffset?: number } | undefined, browserNow: number) =>
  browserNow + (item?.clockOffset ?? 0);

/**
 * A deadline at `now`: how much is spent, its tone and the time left or over.
 * Read from the due time, so the ring runs between refreshes. A paused, kept,
 * missed or absent SLA is as the server says.
 */
export function readClock(sla: SlaLike, detectedAt: string, now: number): ClockReading {
  if (sla.state === "met") return { tone: "met", fraction: 1, label: "SLA kept" };
  if (sla.state === "missed") return { tone: "missed", fraction: 1, label: "SLA missed" };
  if (sla.state === "paused") return { tone: "paused", fraction: 0, label: "Clock paused" };
  if (sla.state === "none" || !sla.dueAt) {
    const age = detectedAt ? now - Date.parse(detectedAt) : NaN;
    return { tone: "none", fraction: 0, label: Number.isFinite(age) ? `${formatSpan(age)} old` : "No deadline" };
  }
  const due = Date.parse(sla.dueAt);
  const target = parseDuration(sla.target);
  const start = sla.startedAt
    ? Date.parse(sla.startedAt)
    : target !== undefined
      ? due - target
      : Date.parse(detectedAt);
  const left = due - now;
  const fraction = due > start ? Math.min(1, Math.max(0, (now - start) / (due - start))) : 1;
  if (left <= 0) return { tone: "overdue", fraction: 1, label: `${formatSpan(-left)} over`, left };
  return {
    tone: fraction >= (sla.atRiskAt ?? AT_RISK) ? "at-risk" : "running",
    fraction,
    label: `${formatSpan(left)} left`,
    left
  };
}

/**
 * Pending events, most urgent first as the contract orders them: the overdue,
 * then the least time left, then those without a deadline, oldest first. Rows
 * from services whose clocks differ compare by time left, so each time is
 * taken back to this browser's clock by its row's offset.
 */
export function byUrgency<T extends { sla: { dueAt?: string | null }; detectedAt: string; clockOffset?: number }>(
  rows: readonly T[]
): T[] {
  const local = (r: T, at: string) => Date.parse(at) - (r.clockOffset ?? 0);
  const due = (r: T) => (r.sla.dueAt ? local(r, r.sla.dueAt) : Number.POSITIVE_INFINITY);
  return [...rows].sort((a, b) => due(a) - due(b) || local(a, a.detectedAt) - local(b, b.detectedAt));
}

/** Closed events, newest first. */
export function byClosed<T extends { closedAt?: string | null; detectedAt: string }>(rows: readonly T[]): T[] {
  const closed = (r: T) => Date.parse(r.closedAt ?? r.detectedAt) || 0;
  return [...rows].sort((a, b) => closed(b) - closed(a));
}
