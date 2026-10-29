import { describe, expect, it } from "vitest";
import { parseEvent, parseRow, readSubject, rowOf, SUBJECT_KINDS } from "./parse";
import { eventExamples, example, examples } from "./test/fixtures";

describe("parseEvent", () => {
  it("reads every HumanEvent example", () => {
    expect(eventExamples.length).toBeGreaterThanOrEqual(11);
    for (const [name] of eventExamples) {
      const parsed = parseEvent(examples[name]);
      expect(parsed.ok, name).toBe(true);
    }
  });

  it("covers every subject kind the contract names", () => {
    const kinds = new Set(eventExamples.map(([, e]) => e.subject.kind));
    for (const kind of SUBJECT_KINDS) expect(kinds.has(kind), kind).toBe(true);
  });

  it("reads an event with a pattern, action and state it does not know", () => {
    const raw = example<Record<string, unknown>>("HumanEvent.action");
    raw.pattern = "peer-review";
    raw.state = "PARKED";
    raw.allowedActions = ["APPROVE", "PARK"];
    const parsed = parseEvent(raw);
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.value.pattern).toBe("peer-review");
      expect(parsed.value.allowedActions).toContain("PARK");
    }
  });

  it("fills what the page walks when it is left out", () => {
    const raw = example<Record<string, unknown>>("HumanEvent.action");
    delete raw.decisions;
    delete raw.history;
    delete raw.pending;
    const parsed = parseEvent(raw);
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.value.decisions).toEqual([]);
      expect(parsed.value.history).toEqual([]);
      expect(parsed.value.pending).toBe(true);
    }
  });

  it("keeps the summary and the raw JSON when the envelope cannot be read", () => {
    const raw = { id: 7, subject: { kind: "action", summary: "Pay S-77", detail: {} }, ask: { question: "Pay?" } };
    const parsed = parseEvent(raw);
    expect(parsed.ok).toBe(false);
    if (!parsed.ok) {
      expect(parsed.summary).toBe("Pay S-77");
      expect(parsed.raw).toBe(raw);
      expect(parsed.issues.join(" ")).toMatch(/id/);
    }
  });

  it("falls back to the question, then the id, for an unreadable event's words", () => {
    const parsed = parseEvent({ ask: { question: "Should it pay?" } });
    expect(!parsed.ok && parsed.summary).toBe("Should it pay?");
    expect(!parseEvent(null).ok).toBe(true);
  });
});

describe("readSubject", () => {
  it("reads each example's detail by its kind", () => {
    for (const [name, e] of eventExamples) {
      const reading = readSubject(e.subject);
      expect(reading.kind, name).toBe(e.subject.kind);
    }
  });

  it("says why an unknown kind is shown raw", () => {
    const reading = readSubject({ kind: "contract" as never, detail: { clause: 7 } });
    expect(reading.kind).toBe("unknown");
    if (reading.kind === "unknown") {
      expect(reading.reason).toMatch(/does not know the subject kind “contract”/);
      expect(reading.detail).toEqual({ clause: 7 });
    }
  });

  it("says why a detail that does not match its kind is shown raw", () => {
    const reading = readSubject({ kind: "records", detail: { entityType: "customer" } });
    expect(reading.kind).toBe("unknown");
    if (reading.kind === "unknown") expect(reading.reason).toMatch(/records detail does not match/);
  });
});

describe("rows", () => {
  it("reads the companion's row example", () => {
    const page = example<{ rows: unknown[] }>("CompanionEventsPage.one");
    const parsed = parseRow(page.rows[0]);
    expect(parsed.ok).toBe(true);
  });

  it("builds the companion's row from a full event", () => {
    const [, e] = eventExamples.find(([n]) => n === "HumanEvent.system-lifted")!;
    const row = rowOf(e);
    expect(row.queue).toBe("system-owners");
    expect(row.reviewers).toEqual({ required: 2, voted: 2 });
    expect(row.decidedBy).toEqual(["tyler.jewell@akka.io", "s.patel@example.com"]);
    expect(row.conclusion).toBe("upheld");
  });
});

describe("the service's clock", () => {
  it("records how far the service's clock is ahead of this browser's when an answer is read", () => {
    const raw = { ...example<Record<string, unknown>>("HumanEvent.action"), now: "2026-10-29T15:00:00Z" };
    const parsed = parseEvent(raw, Date.parse("2026-10-29T14:00:00Z"));
    expect(parsed.ok && parsed.value.clockOffset).toBe(3_600_000);
    expect(parsed.ok && rowOf(parsed.value)).toMatchObject({ now: "2026-10-29T15:00:00Z", clockOffset: 3_600_000 });
    const row = parseRow({ ...example<{ rows: Record<string, unknown>[] }>("CompanionEventsPage.one").rows[0], now: "2026-10-29T13:00:00Z" }, Date.parse("2026-10-29T14:00:00Z"));
    expect(row.ok && row.value.clockOffset).toBe(-3_600_000);
  });

  it("leaves the offset unset when the service gives no time", () => {
    const raw = example<Record<string, unknown>>("HumanEvent.action");
    delete raw.now;
    const parsed = parseEvent(raw);
    expect(parsed.ok && parsed.value.clockOffset).toBeUndefined();
  });
});
