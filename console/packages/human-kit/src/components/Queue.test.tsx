import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { rowOf } from "../parse";
import type { HumanEventRow } from "../types";
import { event, eventExamples, NOW, renderWith, stubClient } from "../test/fixtures";
import { byUrgency } from "../words";
import { matches, Queue } from "./Queue";

const minutes = (n: number) => new Date(NOW + n * 60_000).toISOString();

const row = (id: string, sla: Partial<HumanEventRow["sla"]>, detectedAt = minutes(-10), more: Partial<HumanEventRow> = {}): HumanEventRow => ({
  ...rowOf(event("HumanEvent.action")),
  id,
  ask: { question: `Question ${id}` },
  detectedAt,
  sla: { state: "running", dueAt: null, target: null, ...sla },
  ...more
});

describe("queue order", () => {
  const rows = [
    row("later", { dueAt: minutes(20), target: "PT30M" }),
    row("none-new", {}, minutes(-1)),
    row("overdue", { dueAt: minutes(-5), target: "PT30M" }),
    row("none-old", {}, minutes(-50)),
    row("soon", { dueAt: minutes(2), target: "PT30M" })
  ];

  it("puts the overdue first, then the least time left, then those without a deadline, oldest first", () => {
    expect(byUrgency(rows).map((r) => r.id)).toEqual(["overdue", "soon", "later", "none-old", "none-new"]);
  });

  it("draws the rows in that order, each with its deadline ring", () => {
    render(<Queue rows={rows} now={NOW} />);
    const drawn = screen.getAllByTestId("queue-row");
    expect(drawn.map((r) => r.getAttribute("data-id"))).toEqual(["overdue", "soon", "later", "none-old", "none-new"]);
    expect(drawn.map((r) => r.getAttribute("data-tone"))).toEqual(["overdue", "at-risk", "running", "none", "none"]);
    expect(screen.getAllByTestId("row-ring")).toHaveLength(5);
    expect(screen.getAllByTestId("row-clock")[0]).toHaveTextContent("5 min over");
  });

  it("orders answered events newest closed first", () => {
    const closed = [
      row("a", {}, minutes(-60), { pending: false, state: "APPLIED", closedAt: minutes(-30) }),
      row("b", {}, minutes(-60), { pending: false, state: "APPLIED", closedAt: minutes(-5) })
    ];
    render(<Queue rows={closed} now={NOW} initial={{ state: "resolved" }} />);
    expect(screen.getAllByTestId("queue-row").map((r) => r.getAttribute("data-id"))).toEqual(["b", "a"]);
  });
});

describe("queue filters", () => {
  const all = eventExamples.map(([, e]) => rowOf(e));

  it("filters rows in hand by subject kind, pattern, deadline and words", () => {
    expect(all.filter((r) => matches(r, { state: "pending", subject: "records" }, NOW)).map((r) => r.pattern)).toEqual(["match-adjudication"]);
    expect(all.filter((r) => matches(r, { state: "pending", pattern: "hand-off" }, NOW))).toHaveLength(1);
    expect(all.filter((r) => matches(r, { state: "resolved" }, NOW)).map((r) => r.id)).toEqual(["he-20261029-00099"]);
    expect(all.filter((r) => matches(r, { state: "pending", sla: "at-risk" }, Date.parse("2026-10-29T14:25:00Z")))).toHaveLength(10);
    expect(all.filter((r) => matches(r, { state: "pending", q: "00007" }, NOW))).toHaveLength(1);
  });

  it("asks the client for what the filters say", async () => {
    const client = stubClient();
    (client.events as unknown as { mockResolvedValue: (v: unknown) => void }).mockResolvedValue({ rows: all.filter((r) => r.pending), total: 10, unreadable: 0 });
    renderWith(client, <Queue now={NOW} />);
    expect(await screen.findAllByTestId("queue-row")).toHaveLength(10);
    expect(client.events).toHaveBeenLastCalledWith(expect.objectContaining({ state: "pending", limit: 50 }));
    fireEvent.change(screen.getByTestId("queue-pattern"), { target: { value: "triage" } });
    await waitFor(() => expect(client.events).toHaveBeenLastCalledWith(expect.objectContaining({ pattern: "triage" })));
    fireEvent.change(screen.getByTestId("queue-subject"), { target: { value: "behaviour" } });
    fireEvent.change(screen.getByTestId("queue-sla"), { target: { value: "overdue" } });
    fireEvent.click(screen.getByTestId("queue-routable"));
    fireEvent.change(screen.getByTestId("queue-search"), { target: { value: "referral" } });
    fireEvent.submit(screen.getByRole("search"));
    await waitFor(() =>
      expect(client.events).toHaveBeenLastCalledWith(
        expect.objectContaining({ state: "pending", pattern: "triage", subject: "behaviour", sla: "overdue", routable: true, q: "referral" })
      )
    );
    fireEvent.click(screen.getByTestId("queue-state-resolved"));
    await waitFor(() => expect(client.events).toHaveBeenLastCalledWith(expect.objectContaining({ state: "resolved", sla: undefined })));
  });

  it("opens a row's event", () => {
    const opened: string[] = [];
    render(<Queue rows={all} now={NOW} initial={{ subject: "records" }} onOpen={(r) => opened.push(r.id)} />);
    fireEvent.click(screen.getByTestId("queue-row").querySelector("button")!);
    expect(opened).toEqual(["he-20261029-00006"]);
  });

  it("says when nothing matches", () => {
    render(<Queue rows={all} now={NOW} initial={{ pattern: "oversight-quality" }} />);
    expect(screen.getByTestId("queue-empty")).toHaveTextContent("Nothing is waiting that matches these filters.");
  });
});

describe("queue clocks", () => {
  it("reads each row's deadline against its own service's clock", () => {
    const due = minutes(10);
    const rows = [
      row("in-step", { dueAt: due, target: "PT30M" }),
      // This service's clock runs 15 minutes ahead, so its deadline has passed.
      row("ahead", { dueAt: due, target: "PT30M" }, minutes(-10), { clockOffset: 15 * 60_000 })
    ];
    render(<Queue rows={rows} now={NOW} />);
    const drawn = screen.getAllByTestId("queue-row");
    expect(drawn.map((r) => [r.getAttribute("data-id"), r.getAttribute("data-tone")])).toEqual([
      ["ahead", "overdue"],
      ["in-step", "running"]
    ]);
    expect(screen.getAllByTestId("row-clock")[0]).toHaveTextContent("5 min over");
  });
});
