import { act, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { afterDeadline, barredWord, deciders, decisionClock, formatCountdown, initialsOf, settleStage } from "./clock";
import { DecisionClock } from "./components/DecisionClock";
import { OversightProvider, useEventWrite, useOnSettled } from "./hooks";
import { rowOf } from "./parse";
import type { HumanEvent, OverseerRegistry } from "./types";
import { event as exampleEvent, renderWith, stubClient } from "./test/fixtures";

const at = (iso: string) => Date.parse(iso);

/** The records example: a match review routed to reviewers, raised at 14:00 with a 30-minute deadline. */
const event = (more: Partial<HumanEvent> = {}): HumanEvent => {
  const base = exampleEvent("HumanEvent.records");
  return {
    ...base,
    routing: { ...base.routing, roles: ["reviewer"], independentOf: ["agent:payments"] },
    proposedBy: "agent:payments",
    actionLabels: { ...base.actionLabels, DENY: "Different" },
    minimumReviewSeconds: 10,
    sla: { ...base.sla, dueAt: "2026-10-29T14:30:00Z", startedAt: "2026-10-29T14:00:00Z", target: "PT30M", timeoutBehaviour: "RESUME_WITH_DEFAULT", default: "DENY" },
    escalation: { level: 0, chain: ["reviewers", "head-of-operations"] },
    ...more
  };
};

const registry: OverseerRegistry = {
  overseers: [
    { subject: "rita@example.com", name: "Rita Reviewer", roles: ["reviewer"], qualified: true },
    { subject: "raj@example.com", name: "Raj Reviewer", roles: ["reviewer"], qualified: true },
    { subject: "uma@example.com", name: "Uma Suspended", roles: ["reviewer"], qualified: false, qualifiedReason: "training lapsed" },
    { subject: "ada@example.com", name: "Ada Approver", roles: ["approver"], qualified: true }
  ]
};

describe("the decision clock", () => {
  it("counts the deadline down on the service's clock", () => {
    const clock = decisionClock(event(), at("2026-10-29T14:05:00Z"));
    expect(clock.left).toBe(25 * 60);
    expect(clock.remaining).toBeCloseTo(25 / 30);
    expect(clock.tone).toBe("running");
    expect(formatCountdown(clock.left!)).toBe("0:25:00");
  });

  it("reads the service's time when its clock runs ahead of the browser's", () => {
    expect(decisionClock(event({ clockOffset: 10 * 60_000 }), at("2026-10-29T14:05:00Z")).left).toBe(15 * 60);
  });

  it("counts the minimum review time from the acting reviewer's claim", () => {
    const held = event({ state: "CLAIMED", holder: { subject: "rita@example.com", since: "2026-10-29T14:04:56Z", expiresAt: "2026-10-29T14:34:56Z" } });
    expect(decisionClock(held, at("2026-10-29T14:05:00Z"), "raj@example.com").minimum).toMatchObject({ started: false, met: false });
    expect(decisionClock(held, at("2026-10-29T14:05:00Z"), "rita@example.com").minimum).toEqual({ seconds: 10, started: true, elapsed: 4, met: false });
    expect(decisionClock(held, at("2026-10-29T14:05:30Z"), "rita@example.com").minimum).toMatchObject({ elapsed: 10, met: true });
  });

  it("says what happens at the deadline", () => {
    expect(afterDeadline(event())).toBe("then “Different” by default");
    expect(afterDeadline(event({ sla: { ...event().sla, timeoutBehaviour: "ESCALATE" } }))).toBe("then to head of operations");
    expect(afterDeadline(event({ sla: { ...event().sla, timeoutBehaviour: "RAISE_TIMEOUT" } }))).toBe("then it times out");
    expect(formatCountdown(90061)).toBe("1 d 1:01");
  });
});

describe("who may decide", () => {
  it("strikes out the requester and the unqualified, and puts the acting reviewer first among the rest", () => {
    const people = deciders(event(), registry, "raj@example.com");
    expect(people.map((p) => [p.name, p.may, p.barred ?? ""])).toEqual([
      ["agent:payments", false, "requested"],
      ["Raj Reviewer", true, ""],
      ["Rita Reviewer", true, ""],
      ["Uma Suspended", false, "unqualified"]
    ]);
    expect(people[0].note).toBe("asked for it, so cannot decide");
    expect(people[1].me).toBe(true);
    expect(people[3].note).toBe("not qualified: training lapsed");
  });

  it("names the people kept out in the service's words", () => {
    const review = event({
      proposedBy: "priya.k@example.com",
      builtBy: "dan.m@example.com",
      routing: { ...event().routing, independentOf: ["priya.k@example.com", "dan.m@example.com"] }
    });
    const words = { proposer: "loaded one of the records", builder: "loaded one of the records", proposerChip: "Loaded it", builderChip: "Loaded it" };
    const barred = deciders(review, registry, undefined, words).filter((p) => !p.may && p.barred !== "unqualified");
    expect(barred.map((p) => [p.initials, p.note, barredWord(p, words)])).toEqual([
      ["PK", "loaded one of the records, so cannot decide", "Loaded it"],
      ["DM", "loaded one of the records, so cannot decide", "Loaded it"]
    ]);
    expect(initialsOf("Rita Reviewer")).toBe("RR");
  });

  it("marks who has voted, and nobody may decide a closed event", () => {
    const voted = event({ decisions: [{ reviewer: { subject: "rita@example.com", roles: ["reviewer"] }, action: "APPROVE", submittedAt: "2026-10-29T14:03:00Z" }] });
    expect(deciders(voted, registry).find((p) => p.subject === "rita@example.com")).toMatchObject({ may: false, barred: "voted" });
    expect(deciders(event({ pending: false, state: "APPLIED" }), registry).filter((p) => p.may)).toEqual([]);
  });
});

describe("DecisionClock", () => {
  it("draws the time left, the review time and who may decide, with you marked", () => {
    const held = event({ state: "CLAIMED", holder: { subject: "rita@example.com", since: "2026-10-29T14:04:56Z", expiresAt: "2026-10-29T14:34:56Z" } });
    render(<DecisionClock event={held} registry={registry} me="rita@example.com" now={at("2026-10-29T14:05:00Z")} />);
    expect(screen.getByTestId("clock-left")).toHaveTextContent("0:25:00");
    expect(screen.getByTestId("clock-minimum")).toHaveTextContent("looked at for 4 s of 10 s");
    expect(screen.getByTestId("clock-summary")).toHaveTextContent("Needs 1 decision from a reviewer other than agent:payments.");
    const rows = screen.getAllByTestId("decider");
    expect(rows.map((r) => r.getAttribute("data-may"))).toEqual(["false", "true", "true", "false"]);
    expect(within(rows[1]).getByText("(you)")).toBeInTheDocument();
    expect(within(rows[1]).getByText(/holds it now/)).toBeInTheDocument();
    expect(within(rows[3]).getByText("Not qualified")).toBeInTheDocument();
  });

  it("says how a closed event ended", () => {
    const closed = exampleEvent("HumanEvent.system-lifted");
    render(<DecisionClock event={closed} registry={registry} now={at("2026-10-29T15:00:00Z")} />);
    expect(screen.getByTestId("decision-clock")).toHaveTextContent("decided");
    expect(screen.getByTestId("decision-clock")).toHaveTextContent("Approved");
    expect(screen.queryByTestId("clock-left")).toBeNull();
  });
});

describe("settling", () => {
  it("ranks how far an event has settled", () => {
    expect([event(), event({ pending: false, state: "DECIDED" }), event({ pending: false, state: "APPLIED" }), event({ pending: false, state: "TIMED_OUT" })].map(settleStage)).toEqual([0, 1, 2, 2]);
  });

  it("fires when a decision made here settles the event, and again when it is applied", async () => {
    const open = event();
    const client = stubClient({ event: vi.fn(async () => open) });
    const settled = vi.fn();
    let decide: () => Promise<unknown> = async () => undefined;
    function Screen() {
      useOnSettled(open.id, (e) => settled(e.state));
      const write = useEventWrite(open.id);
      decide = () => write.run({ kind: "decision", body: { action: "APPROVE", rationale: "Same person." } });
      return null;
    }
    client.write.mockResolvedValue({ ...open, pending: false, state: "DECIDED" });
    renderWith(client, <Screen />);
    await waitFor(() => expect(client.event).toHaveBeenCalled());
    expect(settled).not.toHaveBeenCalled();
    await act(async () => void (await decide()));
    expect(settled).toHaveBeenCalledWith("DECIDED");
    client.write.mockResolvedValue({ ...open, pending: false, state: "APPLIED" });
    await act(async () => void (await decide()));
    expect(settled.mock.calls.map((c) => c[0])).toEqual(["DECIDED", "APPLIED"]);
  });

  it("fires when someone else decides it, as the event's polling reads", async () => {
    const open = event();
    let served: HumanEvent = open;
    const client = stubClient({ event: vi.fn(async () => served) });
    const settled = vi.fn();
    function Screen() {
      useOnSettled(open.id, (e) => settled(e.state));
      return null;
    }
    render(
      <OversightProvider client={client} pollMs={30}>
        <Screen />
      </OversightProvider>
    );
    await waitFor(() => expect(client.event).toHaveBeenCalled());
    served = { ...open, pending: false, state: "APPLIED" };
    await waitFor(() => expect(settled).toHaveBeenCalledWith("APPLIED"));
    expect(settled).toHaveBeenCalledTimes(1);
  });

  it("does not fire for an event already settled when first read, nor for another event", async () => {
    const closed = event({ pending: false, state: "APPLIED" });
    const client = stubClient({ event: vi.fn(async () => closed) });
    const settled = vi.fn();
    function Screen() {
      const [id, setId] = useState(closed.id);
      useOnSettled(id, settled);
      return (
        <button type="button" onClick={() => setId("he-other")}>
          other
        </button>
      );
    }
    renderWith(client, <Screen />);
    await waitFor(() => expect(client.event).toHaveBeenCalled());
    await act(async () => screen.getByText("other").click());
    expect(settled).not.toHaveBeenCalled();
  });
});

describe("subject.ref on rows", () => {
  it("is carried from the event to its row", () => {
    const e = event({ subject: { ...event().subject, ref: "name-dob-062223f9" } });
    expect(rowOf(e).subject.ref).toBe("name-dob-062223f9");
  });
});
