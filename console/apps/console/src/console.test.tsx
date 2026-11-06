import type { CompanionService, Halt, HumanEventRow, OverseerRegistry, StandingDecision, Summary, Verdict } from "@akka-human/kit";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import page from "../../../../spec/examples/CompanionEventsPage.one.json";
import actionEvent from "../../../../spec/examples/HumanEvent.action.json";
import controlExample from "../../../../spec/examples/ControlSettings.action.json";
import haltExample from "../../../../spec/examples/Halt.halted.json";
import registryExample from "../../../../spec/examples/OverseerRegistry.basic.json";
import standingExample from "../../../../spec/examples/StandingDecision.pattern.json";
import summaryExample from "../../../../spec/examples/Summary.basic.json";
import verdictExample from "../../../../spec/examples/Verdict.upheld.json";
import { runConformance } from "./api";
import { App } from "./App";
import {
  attentionOf,
  controlsByService,
  overviewSentence,
  reportTotals,
  routeOf,
  scenarioOrder,
  takersOf,
  verdictsOf,
  type ConformanceReport,
  type ServiceControl
} from "./logic";

const NOW = Date.parse("2026-10-29T14:40:00Z");
const registry = registryExample as unknown as OverseerRegistry;
const control = controlExample as unknown as ServiceControl;
const row = page.rows[0] as unknown as HumanEventRow;

const services: CompanionService[] = [
  { service: "patterns", url: "http://localhost:9164", up: true, info: { service: "patterns", contract: 3, patterns: ["action-approval"], devRoutes: true, controls: 14 } },
  { service: "contextlayer", url: "http://localhost:9165", up: false, info: null }
];

describe("attention", () => {
  const items = () =>
    attentionOf({
      overdue: [row, { ...row, id: "he-ahead", clockOffset: -60 * 60_000 }],
      halts: [haltExample as unknown as Halt],
      services,
      registry,
      controls: [{ ...control, service: "patterns" }, { ...control, service: "patterns", control: { rowId: "pt-x", name: "Nobody holds this role" }, routing: { ...control.routing, roles: ["auditor"] } }],
      standing: [{ ...(standingExample as unknown as StandingDecision), state: "EXPIRING" }],
      unowned: 2,
      now: NOW,
      nameOf: (s) => (s === "s.patel@example.com" ? "S. Patel" : s)
    });

  it("lists what is urgent before what is a risk, each with where it is dealt with", () => {
    const list = items();
    expect(list.map((i) => `${i.severity}:${i.id}`)).toEqual([
      "urgent:overdue:he-20261029-00001",
      "urgent:halt:patterns:pt-00012",
      "urgent:down:contextlayer",
      "risk:overseer:k.osei@example.com",
      "risk:unowned",
      "risk:untaken:patterns:pt-x",
      "risk:standing:sd-1"
    ]);
    expect(list[0]).toMatchObject({ text: "10 min past its deadline, on patterns: Should the agent pay supplier S-77 4,200 EUR?", to: "/events/he-20261029-00001" });
    expect(list[1].text).toMatch(/^Eligibility assessment on patterns has been halted since .*, by S\. Patel\.$/);
    expect(list[2].text).toBe("contextlayer does not answer at http://localhost:9165, so its events are not shown.");
    expect(list[3].text).toBe("K. Osei is not qualified to decide: EU AI Act oversight training expired.");
    expect(list[4].text).toBe("2 human controls have no accountable owner to answer for their oversight.");
    expect(list[5].text).toBe("Nobody qualified can take the events of Nobody holds this role (patterns).");
  });

  it("reads each overdue row against its own service's clock", () => {
    // The second row's service runs an hour behind, so its deadline has not passed there.
    expect(items().some((i) => i.id === "overdue:he-ahead")).toBe(false);
  });

  it("finds who could take a control's events", () => {
    expect(takersOf(registry, control)).toEqual(["tyler.jewell@akka.io"]);
  });
});

describe("overview sentence", () => {
  it("says what waits and which services answer", () => {
    const summary = summaryExample as unknown as Summary;
    expect(overviewSentence(summary, services)).toBe("1 decision is waiting, none overdue. 1 of 2 services does not answer.");
    expect(overviewSentence({ ...summary, loop: { in: { pending: 0, overdue: 0 }, on: { pending: 0, overdue: 0 }, over: { pending: 0, overdue: 0 } } }, [services[0]])).toBe(
      "Nothing needs a decision. All one service answers."
    );
  });
});

describe("controls, verdicts and reports", () => {
  it("groups controls by service, in name order", () => {
    const groups = controlsByService([
      { ...control, service: "patterns", control: { rowId: "pt-00002", name: "b" } },
      { ...control, service: "loan-origination" },
      { ...control, service: "patterns" }
    ]);
    expect(groups.map(([s, list]) => [s, list.map((c) => c.control.rowId)])).toEqual([
      ["loan-origination", ["pt-00001"]],
      ["patterns", ["pt-00001", "pt-00002"]]
    ]);
  });

  it("filters verdicts by conclusion and service, newest first", () => {
    const v = verdictExample as unknown as Verdict;
    const list = [
      { ...v, verdictId: "a", observedAt: "2026-10-29T10:00:00Z", subject: { service: "patterns" } },
      { ...v, verdictId: "b", observedAt: "2026-10-29T12:00:00Z", subject: { service: "loan-origination" }, conclusion: "breached" as const },
      { ...v, verdictId: "c", observedAt: "2026-10-29T11:00:00Z", subject: { service: "patterns" } }
    ];
    expect(verdictsOf(list, {}).map((x) => x.verdictId)).toEqual(["b", "c", "a"]);
    expect(verdictsOf(list, { service: "patterns", conclusion: "upheld" }).map((x) => x.verdictId)).toEqual(["c", "a"]);
  });

  it("counts a report and puts its failures first", () => {
    const report: ConformanceReport = {
      scenarios: [
        { pattern: "triage", scenario: "resolve", status: "passed", steps: [] },
        { pattern: "action-approval", scenario: "approve", status: "failed", steps: [] },
        { pattern: "exception", scenario: "expiry", status: "skipped", steps: [] }
      ]
    };
    expect(reportTotals(report)).toEqual({ passed: 1, failed: 1, skipped: 1, total: 3, ok: false });
    expect(scenarioOrder(report.scenarios).map((s) => s.status)).toEqual(["failed", "passed", "skipped"]);
  });

  it("reads the page an address names", () => {
    expect(routeOf("#/events/he%201")).toEqual({ view: "event", id: "he 1" });
    expect(routeOf("#/halts")).toEqual({ view: "halts" });
    expect(routeOf("")).toEqual({ view: "overview" });
    expect(routeOf("#/nowhere")).toEqual({ view: "overview" });
  });
});

describe("conformance run", () => {
  it("posts the service and answers the report", async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ totals: { passed: 3, failed: 0, skipped: 0, total: 3 }, scenarios: [] }), { status: 200 }));
    const report = await runConformance("patterns", fetch as unknown as typeof globalThis.fetch);
    expect(fetch).toHaveBeenCalledWith("/api/companion/human/conformance", expect.objectContaining({ method: "POST", body: JSON.stringify({ service: "patterns" }) }));
    expect(reportTotals(report).ok).toBe(true);
  });

  it("says why it could not run", async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ error: "the conformance suite is not built" }), { status: 503 }));
    await expect(runConformance("patterns", fetch as unknown as typeof globalThis.fetch)).rejects.toMatchObject({
      status: 503,
      message: "the conformance suite is not built"
    });
  });
});

describe("the console against a companion", () => {
  const calls: { method: string; url: string; body?: unknown }[] = [];
  beforeEach(() => {
    calls.length = 0;
    window.location.hash = "";
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init?: RequestInit) => {
        const method = init?.method ?? "GET";
        calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
        const path = url.replace("/api/companion/human", "").split("?")[0];
        const json: unknown =
          path === "/events/he-20261029-00001"
            ? actionEvent
            : path === "/reviewer" && method === "PUT"
            ? JSON.parse(String(init?.body))
            : path === "/services"
            ? { services }
            : path === "/summary"
              ? summaryExample
              : path === "/events"
                ? page
                : path === "/halts"
                  ? { halts: [haltExample] }
                  : path === "/standing"
                    ? { standing: [standingExample] }
                    : path === "/registry"
                      ? registryExample
                      : path === "/controls"
                        ? { controls: [{ ...control, service: "patterns" }] }
                        : path === "/reviewer"
                          ? { subject: "tyler.jewell@akka.io", name: "Tyler Jewell", roles: ["ai-system-owner"] }
                          : path === "/conformance"
                            ? {
                                service: "patterns",
                                totals: { passed: 1, failed: 1, skipped: 0, total: 2 },
                                scenarios: [
                                  { pattern: "action-approval", scenario: "approve", status: "passed", steps: [] },
                                  { pattern: "halt-restart", scenario: "restart", status: "failed", detail: "expected 409", steps: [{ name: "lift", status: "failed", request: "POST /oversight/halts", http: 200, detail: "expected 409" }] }
                                ]
                              }
                            : { error: `not served: ${path}` };
        return new Response(JSON.stringify(json), { status: (json as { error?: string }).error ? 404 : 200 });
      })
    );
  });
  afterEach(() => vi.unstubAllGlobals());

  it("opens on the overview: the sentence, the loops, what needs attention and the services", async () => {
    render(<App />);
    expect(await screen.findByText("1 decision is waiting, none overdue. 1 of 2 services does not answer.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId("attention-item").length).toBeGreaterThanOrEqual(2));
    expect(screen.getByTestId("summary-in")).toHaveTextContent("In the loop1");
    expect(within(screen.getByTestId("services-mini")).getByText("contextlayer")).toBeInTheDocument();
    expect(await screen.findByTestId("reviewer-person")).toHaveValue("tyler.jewell@akka.io");
  });

  it("lists the queue with a service filter, and runs conformance from the services view", async () => {
    window.location.hash = "#/queue";
    render(<App />);
    expect(await screen.findByTestId("queue-row")).toHaveAttribute("data-id", "he-20261029-00001");
    await waitFor(() => expect(screen.getByTestId("queue-service")).toHaveTextContent("contextlayer"));
    fireEvent.change(screen.getByTestId("queue-service"), { target: { value: "patterns" } });
    await waitFor(() => expect(calls.some((c) => c.url.includes("/events?") && c.url.includes("service=patterns"))).toBe(true));

    window.location.hash = "#/services";
    window.dispatchEvent(new HashChangeEvent("hashchange"));
    fireEvent.click(await screen.findByTestId("conformance-patterns"));
    const report = await screen.findByTestId("conformance-report");
    expect(report).toHaveTextContent("Does not conform");
    expect(within(report).getAllByTestId("conformance-scenario").map((s) => s.getAttribute("data-status"))).toEqual(["failed", "passed"]);
    expect(calls.find((c) => c.url.endsWith("/conformance"))).toMatchObject({ method: "POST", body: { service: "patterns" } });
    expect(screen.getByTestId("conformance-contextlayer")).toBeDisabled();
  });

  it("shows each service's controls with their pattern and settings", async () => {
    window.location.hash = "#/controls";
    render(<App />);
    const row = await screen.findByTestId("control-row");
    expect(row).toHaveTextContent("Payments above the limit need the system owner");
    expect(row).toHaveTextContent("Action approval");
    fireEvent.click(within(row).getByRole("button", { name: "Settings" }));
    expect(screen.getByTestId("control-settings")).toHaveTextContent("Approve, Deny, Edit, then approve, Ask for more, Escalate");
  });
});

describe("switching who acts", () => {
  beforeEach(() => {
    window.location.hash = "#/events/he-20261029-00001";
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, init?: RequestInit) => {
        const method = init?.method ?? "GET";
        const path = url.replace("/api/companion/human", "").split("?")[0];
        switchCalls.push(`${method} ${path}`);
        const json: unknown =
          path === "/events/he-20261029-00001"
            ? actionEvent
            : path === "/registry"
              ? registryExample
              : path === "/reviewer"
                ? method === "PUT"
                  ? JSON.parse(String(init?.body))
                  : { subject: "k.osei@example.com", name: "K. Osei", roles: ["risk-officer"] }
                : path === "/services"
                  ? { services }
                  : { error: "not served" };
        return new Response(JSON.stringify(json), { status: (json as { error?: string }).error ? 404 : 200 });
      })
    );
  });
  afterEach(() => vi.unstubAllGlobals());
  const switchCalls: string[] = [];

  it("chooses the reviewer through the companion and claims nothing", async () => {
    render(<App />);
    expect(await screen.findByTestId("event-owner")).toHaveTextContent("Owned by patterns");
    const person = await screen.findByTestId("reviewer-person");
    await waitFor(() => expect(person).toHaveValue("k.osei@example.com"));
    fireEvent.change(person, { target: { value: "tyler.jewell@akka.io" } });
    fireEvent.click(screen.getByTestId("reviewer-act"));
    await waitFor(() => expect(switchCalls).toContain("PUT /reviewer"));
    await screen.findByTestId("action-claim");
    expect(switchCalls.some((c) => c.endsWith("/claim"))).toBe(false);
  });
});
