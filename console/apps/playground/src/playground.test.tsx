import { PATTERNS, type ControlSettings, type OversightError } from "@akka-human/kit";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EVENT_EXAMPLES, example, exampleEvent } from "./examples";
import { Gallery } from "./gallery/Gallery";
import { createMemoryClient } from "./gallery/memory";
import { proxied } from "./live/Live";
import { sampleFor } from "./live/samples";

const registry = example<{ overseers: { subject: string; name?: string; roles: string[]; qualified: boolean }[] }>("OverseerRegistry.basic");
const tyler = { subject: "tyler.jewell@akka.io", name: "Tyler Jewell", roles: ["ai-system-owner", "safety-officer"] };
const patel = { subject: "s.patel@example.com", name: "S. Patel", roles: ["safety-officer"] };

const memory = (events = EVENT_EXAMPLES.map(exampleEvent)) =>
  createMemoryClient(
    {
      events,
      registry: { overseers: [...registry.overseers, { ...patel, qualified: true }] } as never,
      halts: [],
      standing: [],
      quality: example("Quality.basic"),
      summary: example("Summary.basic")
    },
    tyler
  );

describe("gallery memory service", () => {
  it("claims, then decides and applies an event with one reviewer", async () => {
    const client = memory();
    const claimed = await client.claim("he-20261029-00001");
    expect(claimed).toMatchObject({ state: "CLAIMED", holder: { subject: tyler.subject } });
    const early = await client
      .decide("he-20261029-00001", { action: "APPROVE", rationale: "PO matches." })
      .catch((e: OversightError) => e);
    expect(early).toMatchObject({ status: 422 });
    expect((early as OversightError).errors[0]).toMatch(/under the minimum review time/);
  });

  it("refuses an answer the event does not allow, and a missing rationale", async () => {
    const client = memory(EVENT_EXAMPLES.map(exampleEvent).map((e) => ({ ...e, minimumReviewSeconds: 0 })));
    const refused = await client.decide("he-20261029-00001", { action: "REVERSE" }).catch((e: OversightError) => e);
    expect(refused).toMatchObject({ status: 422, errors: ["REVERSE is not allowed on this event", "a rationale is required"] });
  });

  it("takes two votes before a two-person event settles", async () => {
    const lifted = exampleEvent("HumanEvent.system-lifted");
    const open = {
      ...lifted,
      id: "he-two",
      state: "OPEN" as const,
      pending: true,
      decisions: [],
      history: lifted.history.slice(0, 1),
      outcome: null,
      conclusion: null,
      verdict: null,
      minimumReviewSeconds: 0
    };
    const client = memory([open]);
    const first = await client.decide("he-two", { action: "APPROVE", rationale: "Drained." });
    expect(first.state).toBe("AWAITING_SECOND");
    expect(await client.decide("he-two", { action: "APPROVE", rationale: "Again." }).catch((e: OversightError) => e.status)).toBe(409);
    await client.setReviewer(patel);
    const second = await client.decide("he-two", { action: "APPROVE", rationale: "Agreed." });
    expect(second).toMatchObject({ state: "APPLIED", pending: false, verdict: { deciders: [tyler.subject, patel.subject] } });
  });
});

describe("raise samples", () => {
  it("has a question and the right subject kind for every pattern", () => {
    for (const pattern of PATTERNS) {
      const kind = pattern === "triage" || pattern === "oversight-quality" ? "behaviour" : undefined;
      const control = example<ControlSettings>("ControlSettings.action");
      const settings = { ...control, pattern, subjectKind: kind ?? (exampleKind(pattern) as ControlSettings["subjectKind"]) };
      const raise = sampleFor(settings, "patterns");
      expect(raise.control).toBe("pt-0001");
      expect(raise.ask.question.length, pattern).toBeGreaterThan(10);
      expect(raise.subject.kind, pattern).toBe(settings.subjectKind);
      expect(raise.subject.service).toBe("patterns");
      expect(Object.keys(raise.subject.detail).length, pattern).toBeGreaterThan(0);
    }
  });
});

/** The subject kind each pattern raises by default (spec/verify/human-patterns.yaml). */
function exampleKind(pattern: string): string {
  const kinds: Record<string, string> = {
    "action-approval": "action",
    "decision-review": "output",
    "change-authorization": "change",
    exception: "exception",
    "match-adjudication": "records",
    "outcome-reconciliation": "action",
    "contest-appeal": "appeal",
    override: "output",
    "flag-feedback": "output",
    "hand-off": "conversation",
    "halt-restart": "system",
    attestation: "attestation"
  };
  return kinds[pattern];
}

describe("gallery", () => {
  it.each(EVENT_EXAMPLES)("draws %s offline", async (name) => {
    render(<Gallery route={[name]} go={() => undefined} />);
    expect(await screen.findByTestId("event-page")).toBeInTheDocument();
    expect(screen.getByTestId("decision-panel")).toBeInTheDocument();
  });

  it("draws the fallbacks", async () => {
    const { unmount } = render(<Gallery route={["he-edge-unknown-kind"]} go={() => undefined} />);
    expect(await screen.findByTestId("subject-fallback")).toBeInTheDocument();
    unmount();
    render(<Gallery route={["unreadable"]} go={() => undefined} />);
    expect(screen.getByTestId("event-unreadable")).toBeInTheDocument();
  });
});

describe("proxied", () => {
  it("sends localhost targets through the dev server and leaves others alone", () => {
    expect(proxied("http://localhost:9167")).toBe("/proxy/localhost:9167");
    expect(proxied("http://127.0.0.1:9164/")).toBe("/proxy/127.0.0.1:9164");
    expect(proxied("https://oversight.example.com/")).toBe("https://oversight.example.com");
  });
});
