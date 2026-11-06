import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ContractError } from "../parse";
import type { HumanEvent, Quality, StandingDecision, Summary, Halt } from "../types";
import { event, eventExamples, example, NOW, registry, renderWith, stubClient, tyler } from "../test/fixtures";
import { EventPage } from "./EventPage";
import { HaltsPanel, OverseersList, QualityView, StandingList, SummaryTiles } from "./governance";
import { journeyOf } from "./History";

describe("EventPage", () => {
  it.each(eventExamples)("lays out %s in the one fixed page", (_, e) => {
    render(<EventPage event={e} reviewer={tyler} registry={registry} now={NOW} />);
    const header = screen.getByTestId("event-header");
    expect(within(header).getByTestId("event-eyebrow")).toHaveTextContent(e.patternName);
    expect(within(header).getByTestId("event-eyebrow")).toHaveTextContent(e.control.name);
    expect(within(header).getByTestId("event-where")).toHaveTextContent("patterns");
    expect(within(header).getByRole("heading", { level: 1 })).toHaveTextContent(e.ask.question);
    expect(within(header).getByTestId("hk-deadline")).toBeInTheDocument();
    expect(screen.getByTestId("event-subject")).toContainElement(screen.getByTestId(`subject-${e.subject.kind}`));
    expect(screen.getByTestId("decision-panel")).toBeInTheDocument();
    expect(screen.getByTestId("history")).toContainElement(screen.getByTestId("journey"));
  });

  it("reads the event through the client and keeps it current after a write", async () => {
    const e = event("HumanEvent.action");
    const client = stubClient({ event: () => Promise.resolve(e) });
    client.write.mockResolvedValue({
      ...e,
      state: "CLAIMED",
      holder: { subject: tyler.subject, roles: tyler.roles, since: new Date(NOW).toISOString(), expiresAt: new Date(NOW + 1_800_000).toISOString() },
      history: [...e.history, { at: new Date(NOW).toISOString(), state: "CLAIMED", actor: tyler.subject, note: "claimed" }]
    } satisfies HumanEvent);
    renderWith(client, <EventPage eventId={e.id} reviewer={tyler} registry={registry} now={NOW} />);
    expect(await screen.findByTestId("event-question")).toHaveTextContent("Should the agent pay supplier S-77 4,200 EUR?");
    expect(within(screen.getByTestId("event-eyebrow")).getByTestId("hk-state")).toHaveTextContent("Open");
    fireEvent.click(screen.getByTestId("action-claim"));
    await waitFor(() => expect(within(screen.getByTestId("event-eyebrow")).getByTestId("hk-state")).toHaveTextContent("Claimed"));
    expect(screen.getAllByTestId("history-entry")).toHaveLength(2);
    expect(screen.getByText(/Held by/)).toBeInTheDocument();
  });

  it("starts the take-over from the transcript", async () => {
    const e = event("HumanEvent.conversation");
    const duty = { subject: "l.moreau@example.com", name: "L. Moreau", roles: e.routing.roles };
    const people = { ...registry, overseers: [...registry.overseers, { ...duty, qualified: true }] };
    const client = stubClient();
    client.write.mockResolvedValue({
      ...e,
      state: "CLAIMED",
      holder: { subject: duty.subject, roles: duty.roles, since: new Date(NOW).toISOString(), expiresAt: new Date(NOW + 1_800_000).toISOString() }
    });
    renderWith(client, <EventPage event={e} reviewer={duty} registry={people} now={NOW} />);
    fireEvent.click(screen.getByTestId("conversation-take-over"));
    await waitFor(() => expect(client.write).toHaveBeenCalledWith(e.id, { kind: "claim" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: /Take over/ })).toBeChecked());
  });

  it("shows the summary and raw JSON of an event it cannot read", async () => {
    const raw = { id: "he-1", subject: { summary: "A claim from the future" } };
    const client = stubClient({ event: () => Promise.reject(new ContractError("event", "A claim from the future", raw, ["ask: Invalid key"])) });
    renderWith(client, <EventPage eventId="he-1" />);
    const page = await screen.findByTestId("event-unreadable");
    expect(page).toHaveTextContent("A claim from the future");
    expect(page).toHaveTextContent("ask: Invalid key");
  });

  it("shows a failed read in one sentence", async () => {
    const client = stubClient({ event: () => Promise.reject(new Error("The service could not be reached")) });
    renderWith(client, <EventPage eventId="he-1" />);
    expect(await screen.findByTestId("event-failed")).toHaveTextContent("The service could not be reached.");
  });
});

describe("History", () => {
  it("walks a two-person restart from routing to applied, then waits for its verdict", () => {
    const { main } = journeyOf(event("HumanEvent.system-lifted"));
    expect(main.map((s) => `${s.label}:${s.status}:${s.effect}`)).toEqual([
      "Routed:done:neutral",
      "Decided:done:applied",
      "Next reviewer:done:applied",
      "Applied:done:applied",
      "Verdict:done:saved"
    ]);
  });

  it("lists the steps still to come for an open event", () => {
    render(<EventPage event={event("HumanEvent.action")} reviewer={tyler} registry={registry} now={NOW} />);
    expect(screen.getByTestId("journey-next")).toHaveTextContent("Next: claim → decide → apply → verdict");
  });

  it("shows the proof of a closed event: verdict, source and every decision", () => {
    render(<EventPage event={event("HumanEvent.system-lifted")} reviewer={tyler} registry={registry} now={NOW} />);
    const proof = screen.getByTestId("history-proof");
    expect(within(proof).getByTestId("proof-facts")).toHaveTextContent("Not counted as evidence");
    expect(within(proof).getByTestId("proof-facts")).toHaveTextContent("Tyler Jewell, s.patel@example.com");
    expect(within(within(proof).getByTestId("proof-decisions")).getAllByRole("row")).toHaveLength(3);
    expect(screen.getAllByTestId("history-entry")).toHaveLength(4);
  });
});

describe("governance views", () => {
  it("shows a halt with its checks, and holds the restart until they pass", () => {
    render(<HaltsPanel halts={[example<Halt>("Halt.halted")]} now={NOW} />);
    expect(screen.getByTestId("halt-tile")).toHaveAttribute("data-halted", "true");
    expect(screen.getByTestId("halt-restart")).toBeDisabled();
    expect(screen.getByTestId("safe-state-check")).toHaveAttribute("data-passed", "false");
  });

  it("halts a running scope after a confirmed reason", async () => {
    const halt = { ...example<Halt>("Halt.halted"), halted: false, since: null, by: null };
    const client = stubClient({ halt: (control, lift, reason) => Promise.resolve({ ...event("HumanEvent.system"), id: `he-${control}-${lift}-${reason}` }) });
    renderWith(client, <HaltsPanel halts={[halt]} now={NOW} />);
    fireEvent.click(screen.getByTestId("halt-exercise"));
    fireEvent.change(screen.getByTestId("write-reason"), { target: { value: "Drift" } });
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    expect(await screen.findByRole("status")).toHaveTextContent("Halted; the event is he-pt-00012-false-Drift.");
  });

  it("lists standing decisions and revokes one with a reason", async () => {
    const sd = example<StandingDecision>("StandingDecision.pattern");
    const revoked: string[] = [];
    const client = stubClient({ revokeStanding: (id, reason) => (revoked.push(`${id}:${reason}`), Promise.resolve({ ...sd, state: "REVOKED" })) });
    renderWith(client, <StandingList standing={[sd]} now={NOW} />);
    expect(screen.getByTestId("standing-row")).toHaveTextContent("pay supplier S-77 up to 4,200 EUR");
    expect(screen.getByTestId("standing-row")).toHaveTextContent("Expires in 30 days");
    expect(screen.getByTestId("standing-row")).toHaveTextContent("held by payments");
    fireEvent.click(screen.getByTestId("standing-revoke"));
    fireEvent.change(screen.getByTestId("write-reason"), { target: { value: "Supplier changed bank" } });
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() => expect(revoked).toEqual(["sd-1:Supplier changed bank"]));
  });

  it("shows review times against the minimum, reviewers and signals", () => {
    render(<QualityView quality={example<Quality>("Quality.basic")} />);
    expect(screen.getAllByTestId("review-histogram")).toHaveLength(1);
    expect(screen.getByRole("img", { name: "Minimum review time: 10 s" })).toBeInTheDocument();
    expect(screen.getByTestId("quality-reviewer")).toHaveTextContent("Tyler Jewell");
    expect(screen.getByTestId("quality-signal")).toHaveTextContent("Within");
  });

  it("shows who may oversee, and why someone may not", () => {
    render(<OverseersList registry={registry} now={NOW} />);
    const people = screen.getAllByTestId("overseer");
    expect(people[1]).toHaveAttribute("data-qualified", "false");
    expect(people[1]).toHaveTextContent("EU AI Act oversight training expired");
  });

  it("shows the loop's figures", () => {
    render(<SummaryTiles summary={example<Summary>("Summary.basic")} />);
    expect(screen.getByTestId("summary-in")).toHaveTextContent("In the loop1none overdue");
    expect(screen.getByTestId("summary-halted")).toHaveTextContent("Halted1of 1 scopes");
  });
});
