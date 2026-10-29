import type { HumanEvent } from "@akka-human/kit";

// The desk's view of the loan service: applications and their status, and the
// three domain reviews an underwriting desk decides, each found from the event
// that asks for it (`subject.ref` is the application id). Kept free of React
// so it can be tested on its own.

/** Referred applications are decided by an underwriter. */
export const UNDERWRITING = "custom-5a1e00000009";
/** Held adverse-action notices are released by a reviewer. */
export const HELD_NOTICE = "custom-5a1e00000008";
/** Declined applicants can contest the decision. */
export const CONTEST = "custom-5a1e00000010";

export const DESK_CONTROLS = [UNDERWRITING, HELD_NOTICE, CONTEST] as const;

/** Where the governance decisions (halts, model changes, monitors, flags) are made. */
export const AKKA_CONSOLE = "http://localhost:9167/";

// ---------------------------------------------------------------- applications

/** One row of `GET /applications` or `GET /underwriting/queue`. */
export interface ApplicationRow {
  id: string;
  applicant: string;
  amount: number;
  status: string;
  assessedAt?: string | null;
  referred: boolean;
  reviewEventId?: string | null;
  noticeBlockedAt?: string | null;
}

/** One application, as `GET /applications/{id}` gives it. */
export interface Application {
  id: string;
  applicant: string;
  nationalId: string;
  amount: number;
  termMonths: number;
  monthlyIncome: number;
  monthlyDebts: number;
  documents: string[];
  status: string;
  documentsDue: string;
  assessment: {
    assessedAt: string | null;
    recommendation: string | null;
    riskScore: number;
    debtToIncome: number;
    reasons: string[];
    referred: boolean;
    referredBecause: string | null;
    reviewEventId: string | null;
  } | null;
  outcome: { decision: string; by: string; rationale: string; reasons: string[]; eventId: string | null; at: string } | null;
  noticeBlockedAt: string | null;
  notice: { text: string; issuedAs: string; model: string; at: string } | null;
  extensions: unknown[];
  history: { at: string; what: string }[];
}

export type Tone = "neutral" | "info" | "pending" | "good" | "bad";

export const STATUS: Record<string, { label: string; tone: Tone }> = {
  DOCUMENTS_REQUESTED: { label: "Documents requested", tone: "neutral" },
  UNDER_ASSESSMENT: { label: "Under assessment", tone: "info" },
  AWAITING_HUMAN_REVIEW: { label: "With an underwriter", tone: "pending" },
  APPROVED: { label: "Approved", tone: "good" },
  DECLINED: { label: "Declined", tone: "bad" },
  REVIEW_LAPSED: { label: "Review lapsed", tone: "bad" }
};

export const statusOf = (status: string) => STATUS[status] ?? { label: status, tone: "neutral" as Tone };

export const STATUS_FILTERS = ["ALL", ...Object.keys(STATUS)] as const;

/** Applications with `status`, or every one for ALL. */
export function withStatus(rows: readonly ApplicationRow[], status: string): ApplicationRow[] {
  return status === "ALL" ? [...rows] : rows.filter((r) => r.status === status);
}

export function statusCounts(rows: readonly ApplicationRow[]): Record<string, number> {
  const counts: Record<string, number> = { ALL: rows.length };
  for (const key of Object.keys(STATUS)) counts[key] = 0;
  for (const r of rows) counts[r.status] = (counts[r.status] ?? 0) + 1;
  return counts;
}

/** An amount as the desk prints it: whole units, grouped. */
export const money = (n: number | undefined | null) => (n == null ? "—" : Math.round(n).toLocaleString("en-GB"));

export const percent = (n: number | undefined | null) => (n == null ? "—" : `${Math.round(n * 100)}%`);

// ---------------------------------------------------------------- the reviews

/** Each event under `control`, by the application it is about; the newest raise wins. */
export function byApplication(events: readonly HumanEvent[], control: string): Map<string, HumanEvent> {
  const map = new Map<string, HumanEvent>();
  const sorted = [...events].filter((e) => e.control.rowId === control && e.subject.ref);
  sorted.sort((a, b) => Date.parse(a.trigger.detectedAt) - Date.parse(b.trigger.detectedAt));
  for (const e of sorted) map.set(e.subject.ref!, e);
  return map;
}

export interface QueueItem {
  application: ApplicationRow;
  event?: HumanEvent;
}

/**
 * The applications waiting for an underwriter, each with its review event,
 * most urgent first: the soonest deadline, then the oldest raise.
 */
export function underwritingQueue(waiting: readonly ApplicationRow[], events: readonly HumanEvent[]): QueueItem[] {
  const byId = new Map(events.map((e) => [e.id, e]));
  const items = waiting.map((application) => ({
    application,
    event: application.reviewEventId ? byId.get(application.reviewEventId) : undefined
  }));
  const due = (i: QueueItem) => (i.event?.sla.dueAt ? Date.parse(i.event.sla.dueAt) : Number.POSITIVE_INFINITY);
  const raised = (i: QueueItem) => (i.event ? Date.parse(i.event.trigger.detectedAt) : Number.POSITIVE_INFINITY);
  return items.sort((a, b) => due(a) - due(b) || raised(a) - raised(b));
}

/** The figures the underwriting review shows, from its subject's `facts`. */
export interface Facts {
  amount?: number;
  termMonths?: number;
  monthlyIncome?: number;
  monthlyDebts?: number;
  instalment?: number;
  creditDefaults?: number;
}

export function factsOf(event: Pick<HumanEvent, "subject">): Facts {
  const raw = (event.subject.detail as { facts?: Record<string, unknown> }).facts ?? {};
  const num = (k: string) => (typeof raw[k] === "number" ? (raw[k] as number) : undefined);
  return {
    amount: num("amount"),
    termMonths: num("termMonths"),
    monthlyIncome: num("monthlyIncome"),
    monthlyDebts: num("monthlyDebts"),
    instalment: num("instalment"),
    creditDefaults: num("creditDefaults")
  };
}

/** Debts and the new instalment as a share of income, or undefined without an income. */
export function debtToIncome(f: Facts): number | undefined {
  if (!f.monthlyIncome) return undefined;
  return ((f.monthlyDebts ?? 0) + (f.instalment ?? 0)) / f.monthlyIncome;
}

/**
 * Who may not decide an appeal beyond the applicant who filed it: the person
 * who made the decision under appeal (and, by the control, their managers).
 */
export function barredReviewers(event: Pick<HumanEvent, "routing" | "proposedBy">): string[] {
  return (event.routing.independentOf ?? []).filter((s) => s !== event.proposedBy && !s.startsWith("applicant:"));
}

/** Pending first, most urgent first; then the answered, newest first. */
export function deskOrder(events: readonly HumanEvent[]): HumanEvent[] {
  const due = (e: HumanEvent) => (e.sla.dueAt ? Date.parse(e.sla.dueAt) : Number.POSITIVE_INFINITY);
  const closed = (e: HumanEvent) => Date.parse(e.closedAt ?? e.trigger.detectedAt) || 0;
  const pending = events.filter((e) => e.pending).sort((a, b) => due(a) - due(b));
  const answered = events.filter((e) => !e.pending).sort((a, b) => closed(b) - closed(a));
  return [...pending, ...answered];
}
