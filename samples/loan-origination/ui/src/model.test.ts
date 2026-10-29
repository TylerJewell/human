import type { HumanEvent } from "@akka-human/kit";
import { describe, expect, it } from "vitest";
import example from "../../../../spec/examples/HumanEvent.output.json";
import {
  barredReviewers,
  byApplication,
  CONTEST,
  debtToIncome,
  deskOrder,
  factsOf,
  HELD_NOTICE,
  money,
  statusCounts,
  statusOf,
  underwritingQueue,
  UNDERWRITING,
  withStatus,
  type ApplicationRow
} from "./model";

const row = (id: string, status: string, reviewEventId: string | null = null): ApplicationRow => ({
  id,
  applicant: `Applicant ${id}`,
  amount: 30000,
  status,
  referred: status === "AWAITING_HUMAN_REVIEW",
  reviewEventId
});

/** An event of `control` about application `ref`, raised at `detectedAt` and due at `dueAt`. */
const event = (id: string, control: string, ref: string, detectedAt: string, dueAt: string | null, patch: Partial<HumanEvent> = {}): HumanEvent => {
  const base = example as unknown as HumanEvent;
  return {
    ...base,
    id,
    control: { rowId: control, name: control },
    subject: { ...base.subject, ref },
    trigger: { ...base.trigger, detectedAt },
    sla: { ...base.sla, dueAt },
    ...patch
  };
};

describe("applications", () => {
  const rows = [row("app-1", "APPROVED"), row("app-2", "AWAITING_HUMAN_REVIEW"), row("app-3", "DECLINED"), row("app-4", "AWAITING_HUMAN_REVIEW")];

  it("filters by status and counts each", () => {
    expect(withStatus(rows, "AWAITING_HUMAN_REVIEW").map((r) => r.id)).toEqual(["app-2", "app-4"]);
    expect(withStatus(rows, "ALL")).toHaveLength(4);
    expect(statusCounts(rows)).toMatchObject({ ALL: 4, APPROVED: 1, AWAITING_HUMAN_REVIEW: 2, DECLINED: 1, DOCUMENTS_REQUESTED: 0 });
  });

  it("names each status in the desk's words, and an unknown one as it is", () => {
    expect(statusOf("AWAITING_HUMAN_REVIEW")).toEqual({ label: "With an underwriter", tone: "pending" });
    expect(statusOf("SOMETHING_NEW")).toEqual({ label: "SOMETHING_NEW", tone: "neutral" });
    expect(money(25000)).toBe("25,000");
    expect(money(undefined)).toBe("—");
  });
});

describe("the reviews of an application", () => {
  it("finds each review by the application it is about, the latest raise winning", () => {
    const events = [
      event("he-1", UNDERWRITING, "app-1", "2026-10-29T10:00:00Z", "2026-10-31T10:00:00Z"),
      event("he-2", HELD_NOTICE, "app-1", "2026-10-29T11:00:00Z", "2026-10-29T15:00:00Z"),
      event("he-3", UNDERWRITING, "app-1", "2026-10-29T12:00:00Z", "2026-10-31T12:00:00Z"),
      event("he-4", UNDERWRITING, "app-2", "2026-10-29T12:30:00Z", "2026-10-31T12:30:00Z")
    ];
    const reviews = byApplication(events, UNDERWRITING);
    expect(reviews.get("app-1")?.id).toBe("he-3");
    expect(reviews.get("app-2")?.id).toBe("he-4");
    expect(byApplication(events, HELD_NOTICE).get("app-1")?.id).toBe("he-2");
    expect(byApplication(events, CONTEST).size).toBe(0);
  });

  it("orders the underwriting queue by the review's deadline, joined by the review's event id", () => {
    const waiting = [row("app-2", "AWAITING_HUMAN_REVIEW", "he-late"), row("app-4", "AWAITING_HUMAN_REVIEW", "he-soon"), row("app-5", "AWAITING_HUMAN_REVIEW")];
    const events = [
      event("he-late", UNDERWRITING, "app-2", "2026-10-29T09:00:00Z", "2026-10-31T09:00:00Z"),
      event("he-soon", UNDERWRITING, "app-4", "2026-10-29T08:00:00Z", "2026-10-30T08:00:00Z")
    ];
    const queue = underwritingQueue(waiting, events);
    expect(queue.map((i) => [i.application.id, i.event?.id])).toEqual([
      ["app-4", "he-soon"],
      ["app-2", "he-late"],
      ["app-5", undefined]
    ]);
  });

  it("puts waiting reviews first, most urgent first, then the answered, newest first", () => {
    const open = (id: string, due: string) => event(id, HELD_NOTICE, id, "2026-10-29T08:00:00Z", due, { pending: true });
    const closed = (id: string, at: string) => event(id, HELD_NOTICE, id, "2026-10-29T08:00:00Z", null, { pending: false, state: "APPLIED", closedAt: at });
    const ordered = deskOrder([closed("c-old", "2026-10-29T09:00:00Z"), open("o-late", "2026-10-29T14:00:00Z"), closed("c-new", "2026-10-29T10:00:00Z"), open("o-soon", "2026-10-29T12:00:00Z")]);
    expect(ordered.map((e) => e.id)).toEqual(["o-soon", "o-late", "c-new", "c-old"]);
  });
});

describe("what an underwriter is shown", () => {
  it("reads the affordability figures from the review's subject", () => {
    const review = event("he-1", UNDERWRITING, "app-1", "2026-10-29T10:00:00Z", null, {
      subject: {
        kind: "output",
        summary: "Application app-1",
        ref: "app-1",
        detail: { output: "…", facts: { amount: 30000, termMonths: 36, monthlyIncome: 4000, monthlyDebts: 600, instalment: 950, creditDefaults: 0, note: "x" } }
      }
    });
    const facts = factsOf(review);
    expect(facts).toEqual({ amount: 30000, termMonths: 36, monthlyIncome: 4000, monthlyDebts: 600, instalment: 950, creditDefaults: 0 });
    expect(debtToIncome(facts)).toBeCloseTo(0.3875);
    expect(debtToIncome({})).toBeUndefined();
  });

  it("bars the person who made the decision under appeal, not the applicant who filed it", () => {
    const appeal = event("he-9", CONTEST, "app-3", "2026-10-29T10:00:00Z", null, {
      proposedBy: "applicant:app-3",
      routing: { ...(example as unknown as HumanEvent).routing, independentOf: ["applicant:app-3", "j.lind@example.com"] }
    });
    expect(barredReviewers(appeal)).toEqual(["j.lind@example.com"]);
  });
});
