import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OversightError } from "../client";
import type { HumanEvent, Reviewer } from "../types";
import { parseEvent } from "../parse";
import { event, eventExamples, example, NOW, registry, renderWith, stubClient, tyler } from "../test/fixtures";
import { DecisionPanel, patchOf, rulesOf } from "./DecisionPanel";

const MOVES = new Set(["ESCALATE", "SEND_BACK", "REQUEST_MORE_INFO", "RECUSE"]);

/** Tyler, acting in the roles `e` is routed to. */
const actor = (e: HumanEvent): Reviewer => ({ ...tyler, roles: e.routing.roles });

const CHECK = "The invoice matches the purchase order.";

/** `e` asking for one check before it is approved. */
const withCheck = (e: HumanEvent): HumanEvent => ({ ...e, ask: { ...e.ask, checks: [{ text: CHECK, section: "subject" }] } });

/** `e` held by `who` since `secondsAgo` before NOW. */
const held = (e: HumanEvent, secondsAgo = 60, who = tyler.subject): HumanEvent => ({
  ...e,
  state: "CLAIMED",
  holder: {
    subject: who,
    roles: ["ai-system-owner"],
    since: new Date(NOW - secondsAgo * 1000).toISOString(),
    expiresAt: new Date(NOW + 30 * 60_000).toISOString()
  }
});

function show(e: HumanEvent, options: { reviewer?: Reviewer; now?: number; client?: ReturnType<typeof stubClient> } = {}) {
  const client = options.client ?? stubClient();
  client.write.mockImplementation(async (_id: string, w: { kind: string }) => (w.kind === "claim" ? held(e, 0) : { ...e, state: "APPLIED", pending: false }));
  const view = renderWith(client, <DecisionPanel event={e} reviewer={options.reviewer ?? actor(e)} registry={registry} now={options.now ?? NOW} />);
  return { ...view, client };
}

const choices = () => screen.queryAllByTestId(/^choice-[A-Z_]+$/).map((c) => c.getAttribute("data-testid")!.slice("choice-".length));

describe("DecisionPanel: what it offers", () => {
  it.each(eventExamples.filter(([, e]) => e.pending))("offers exactly %s's allowed answers, by their labels", (_, e) => {
    show(held(e));
    const answers = e.allowedActions.filter((a) => !MOVES.has(a));
    expect(choices()).toEqual(answers);
    for (const a of answers) expect(screen.getByTestId(`choice-${a}`)).toHaveTextContent(e.actionLabels[a]);
    for (const a of e.allowedActions.filter((x) => MOVES.has(x))) {
      expect(screen.getByRole("button", { name: e.actionLabels[a] })).toBeInTheDocument();
    }
  });

  it("offers no answer the event does not allow", () => {
    show(held(event("HumanEvent.exception")));
    expect(choices()).toEqual(["APPROVE", "DENY"]);
    expect(screen.queryByRole("button", { name: /Escalate|Ask for more|Request changes/ })).toBeNull();
  });

  it("shows each answer's effect when the event gives one", () => {
    const e = held({ ...event("HumanEvent.action"), effects: { APPROVE: "The payment of 4,200 EUR goes out today." } });
    show(e);
    expect(within(screen.getByTestId("choice-APPROVE")).getByTestId("choice-effect")).toHaveTextContent("The payment of 4,200 EUR goes out today.");
    expect(within(screen.getByTestId("choice-DENY")).getByTestId("choice-effect")).toHaveTextContent("The decision goes to Patterns, which applies it.");
  });
});

describe("DecisionPanel: the claim", () => {
  it("asks for the claim first when the control has a minimum review time, steps closed", async () => {
    const e = event("HumanEvent.action");
    const { client } = show(e);
    expect(screen.getByTestId("decision-after-claim")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /Approve/ })).toBeDisabled();
    expect(screen.queryByTestId("decision-submit")).toBeNull();
    fireEvent.click(screen.getByTestId("action-claim"));
    await waitFor(() => expect(client.write).toHaveBeenCalledWith(e.id, { kind: "claim" }));
    await screen.findByTestId("decision-submit");
    expect(screen.getByRole("radio", { name: /Approve/ })).toBeEnabled();
  });

  it("releases a claim after confirming it", async () => {
    const e = held(event("HumanEvent.action"));
    const { client } = show(e);
    fireEvent.click(screen.getByTestId("action-release"));
    expect(screen.getByTestId("hk-confirm")).toHaveTextContent("It goes back to its queue");
    expect(client.write).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() => expect(client.write).toHaveBeenCalledWith(e.id, { kind: "release" }));
  });

  it("says who holds an event someone else claimed", () => {
    show(held(event("HumanEvent.action"), 60, "k.osei@example.com"));
    expect(screen.getByTestId("decision-refusal")).toHaveTextContent("K. Osei holds this event for another 30 min.");
    expect(choices()).toEqual([]);
  });

  it("tells the holder when their claim lapses", () => {
    show(held(event("HumanEvent.action"), 60));
    expect(screen.getByTestId("claim-expiry")).toHaveTextContent("You hold this event; your claim lapses in 30 min.");
  });
});

describe("DecisionPanel: enforced before submitting", () => {
  const ready = () => held(withCheck(event("HumanEvent.action")), 60);

  it("asks for an answer, then every check for an approval, then a rationale", () => {
    show(ready());
    const submit = screen.getByTestId("decision-submit");
    expect(submit).toBeDisabled();
    expect(screen.getByTestId("decision-blocked")).toHaveTextContent("Choose an answer.");
    fireEvent.click(screen.getByRole("radio", { name: /Approve/ }));
    expect(screen.getByTestId("decision-blocked")).toHaveTextContent("Tick every check before you approve: 0 of 1 ticked.");
    fireEvent.click(screen.getByRole("checkbox", { name: "The invoice matches the purchase order." }));
    expect(screen.getByTestId("decision-blocked")).toHaveTextContent("Say why; this control asks for a rationale.");
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "PO 4471 matches." } });
    expect(screen.queryByTestId("decision-blocked")).toBeNull();
    expect(submit).toBeEnabled();
  });

  it("does not ask for the checks to deny", () => {
    show(ready());
    fireEvent.click(screen.getByRole("radio", { name: /Deny/ }));
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "No PO." } });
    expect(screen.getByTestId("decision-submit")).toBeEnabled();
  });

  it("holds Submit until the minimum review time has passed since the claim", () => {
    const e = held(event("HumanEvent.action"), 4);
    const client = stubClient();
    const view = renderWith(client, <DecisionPanel event={e} reviewer={tyler} registry={registry} now={NOW} />);
    fireEvent.click(screen.getByRole("radio", { name: /Deny/ }));
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "No PO." } });
    expect(screen.getByTestId("decision-submit")).toBeDisabled();
    expect(screen.getByTestId("decision-minimum")).toHaveTextContent("You can submit in 6 s; this control asks for at least 10 s of review.");
    view.rerender(<DecisionPanel event={e} reviewer={tyler} registry={registry} now={NOW + 6_000} />);
    expect(screen.getByTestId("decision-minimum")).toHaveTextContent("The minimum review time of 10 s has passed.");
    expect(screen.getByTestId("decision-submit")).toBeEnabled();
  });

  it("lets a decision under the minimum through when the control only warns, and says so", () => {
    const e = held({ ...event("HumanEvent.action"), minimumReview: "WARN" }, 4);
    show(e);
    fireEvent.click(screen.getByRole("radio", { name: /Deny/ }));
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "No PO." } });
    expect(screen.getByTestId("decision-submit")).toBeEnabled();
    expect(screen.getByTestId("decision-minimum")).toHaveTextContent("recorded as breached");
  });

  it("asks for a reason only when the control requires one", () => {
    show(held({ ...event("HumanEvent.action"), rationaleRequired: false }));
    fireEvent.click(screen.getByRole("radio", { name: /Deny/ }));
    expect(screen.getByTestId("decision-submit")).toBeEnabled();
    expect(screen.getByText("Why (optional)")).toBeInTheDocument();
  });
});

describe("DecisionPanel: what it sends", () => {
  it("confirms, then sends the answer, rationale, checks, scope and when it was presented", async () => {
    const e = held(withCheck(event("HumanEvent.action")), 60);
    const { client } = show(e);
    fireEvent.click(screen.getByRole("radio", { name: /Approve/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: "The invoice matches the purchase order." }));
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "PO 4471 matches." } });
    fireEvent.click(screen.getByRole("radio", { name: "For every match" }));
    fireEvent.click(screen.getByTestId("decision-submit"));
    expect(screen.getByTestId("hk-confirm")).toHaveTextContent("Approve: Pay supplier S-77 4,200 EUR");
    expect(screen.getByTestId("hk-confirm-changes")).toHaveTextContent("Scope: for every match");
    expect(client.write).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() =>
      expect(client.write).toHaveBeenCalledWith(e.id, {
        kind: "decision",
        body: {
          action: "APPROVE",
          rationale: "PO 4471 matches.",
          sticky: "APPROVE_FOR_PATTERN",
          presentedAt: e.holder!.since,
          checks: ["The invoice matches the purchase order."]
        }
      })
    );
    expect(await screen.findByTestId("decision-status")).toHaveTextContent("Your approval was recorded.");
  });

  it("sends an edit as a summary and a JSON patch", async () => {
    const e = held(withCheck(event("HumanEvent.action")), 60);
    const { client } = show(e);
    fireEvent.click(screen.getByRole("radio", { name: /Edit, then approve/ }));
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "Pay 4,000; the rest is disputed." } });
    expect(screen.getByTestId("decision-blocked")).toHaveTextContent("Change at least one value to submit an edit.");
    fireEvent.change(screen.getByTestId("edit-amount"), { target: { value: "4000" } });
    expect(screen.getByTestId("edit-summary")).toHaveTextContent("amount 4,200 → 4,000");
    fireEvent.click(screen.getByTestId("decision-submit"));
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() => expect(client.write).toHaveBeenCalled());
    expect(client.write.mock.calls[0][1].body).toMatchObject({
      action: "EDIT",
      edits: { summary: "amount 4,200 → 4,000", patch: [{ op: "replace", path: "/amount", value: 4000 }] }
    });
  });

  it("sends an override with the replacement output", async () => {
    const e = held({
      ...event("HumanEvent.output"),
      pattern: "override",
      patternName: "Override with justification",
      allowedActions: ["OVERRIDE", "DISREGARD", "REVERSE"],
      actionLabels: { OVERRIDE: "Override", DISREGARD: "Disregard", REVERSE: "Reverse" },
      ask: { question: "Override the notice?", checks: [] }
    });
    const { client } = show(e);
    fireEvent.click(screen.getByRole("radio", { name: /Override/ }));
    const replacement = screen.getByTestId("decision-replacement");
    expect(replacement).toHaveValue("Your application is declined.");
    fireEvent.change(replacement, { target: { value: "" } });
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "Income was misread." } });
    expect(screen.getByTestId("decision-blocked")).toHaveTextContent("Write the output that replaces the system's.");
    fireEvent.change(replacement, { target: { value: "Your application is approved." } });
    fireEvent.click(screen.getByTestId("decision-submit"));
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() => expect(client.write).toHaveBeenCalled());
    expect(client.write.mock.calls[0][1].body).toMatchObject({ action: "OVERRIDE", replacement: "Your application is approved." });
  });

  it("sends a change back with what should change, under the event's label", async () => {
    const e = held(event("HumanEvent.change"));
    const { client } = show(e);
    fireEvent.click(screen.getByRole("button", { name: "Request changes" }));
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    expect(screen.getByTestId("hk-confirm-problem")).toHaveTextContent("Say what the proposer should change.");
    expect(client.write).not.toHaveBeenCalled();
    fireEvent.change(screen.getByTestId("write-reason"), { target: { value: "Add the canary eval." } });
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() => expect(client.write).toHaveBeenCalledWith(e.id, { kind: "send-back", requested: "Add the canary eval." }));
  });

  it("asks for more as a REQUEST_MORE_INFO decision carrying the question", async () => {
    const e = held(event("HumanEvent.action"));
    const { client } = show(e);
    fireEvent.click(screen.getByRole("button", { name: "Ask for more" }));
    fireEvent.change(screen.getByTestId("write-reason"), { target: { value: "Which PO?" } });
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() =>
      expect(client.write).toHaveBeenCalledWith(e.id, {
        kind: "decision",
        body: { action: "REQUEST_MORE_INFO", rationale: "Which PO?", presentedAt: e.holder!.since }
      })
    );
  });
});

describe("DecisionPanel: refusals and server errors", () => {
  const send = async (error: OversightError) => {
    const client = stubClient();
    client.write.mockRejectedValue(error);
    renderWith(client, <DecisionPanel event={held(event("HumanEvent.action"))} reviewer={tyler} registry={registry} now={NOW} />);
    fireEvent.click(screen.getByRole("radio", { name: /Deny/ }));
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "No PO." } });
    fireEvent.click(screen.getByTestId("decision-submit"));
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    return screen.findByTestId("hk-confirm-error");
  };

  it("shows a 422 with what was wrong, in place", async () => {
    const shown = await send(new OversightError(422, "invalid decision", { error: "invalid decision", errors: ["rationale is too short"] }));
    expect(shown).toHaveTextContent("The request was refused: rationale is too short.");
    expect(shown).toHaveAttribute("data-status", "422");
  });

  it("shows a 409 with the state the event moved to", async () => {
    const shown = await send(new OversightError(409, "claimed by another reviewer", { error: "claimed by another reviewer", state: "CLAIMED" }));
    expect(shown).toHaveTextContent("The event has moved on: claimed by another reviewer. It is claimed now.");
  });

  it("shows a 403 as what the reviewer may not do", async () => {
    const shown = await send(new OversightError(403, "not qualified for pt-0001", { error: "not qualified for pt-0001" }));
    expect(shown).toHaveTextContent("You cannot do this: not qualified for pt-0001.");
  });

  it("refuses someone the registry does not list", () => {
    show(event("HumanEvent.action"), { reviewer: { subject: "r.chen@example.com", roles: ["compliance-lead"] } });
    expect(screen.getByTestId("decision-refusal")).toHaveTextContent("r.chen@example.com is not in the overseer registry.");
  });

  it("refuses a reviewer the event is not routed to", () => {
    const chen = { subject: "r.chen@example.com", name: "R. Chen", roles: ["compliance-lead"], qualified: true };
    const rules = rulesOf(event("HumanEvent.action"), chen, { ...registry, overseers: [...registry.overseers, chen] });
    expect(rules.refusal).toBe("Acting as Compliance lead, you cannot decide events routed to AI system owner.");
  });

  it("says why an unqualified overseer may not decide", () => {
    show(event("HumanEvent.action"), { reviewer: { subject: "k.osei@example.com", roles: ["ai-system-owner"] } });
    expect(screen.getByTestId("decision-refusal")).toHaveTextContent("K. Osei is not qualified to decide: EU AI Act oversight training expired.");
    expect(choices()).toEqual([]);
  });

  it("refuses the proposer", () => {
    const e = { ...event("HumanEvent.action"), routing: { ...event("HumanEvent.action").routing, independentOf: [tyler.subject] } };
    show(e);
    expect(screen.getByTestId("decision-refusal")).toHaveTextContent("someone independent of you must decide it");
  });

  it("refuses a restart while a safe-state check has not passed", () => {
    show(held(event("HumanEvent.system")));
    expect(screen.getByRole("radio", { name: /Approve restart/ })).toBeDisabled();
    expect(screen.getByTestId("choice-APPROVE")).toHaveTextContent("A restart can be approved only once every safe-state check passes; 1 of 1 has not.");
  });

  it("refuses answers the reviewer's authority does not cover", () => {
    const limited = {
      ...registry,
      overseers: registry.overseers.map((o) => (o.subject === tyler.subject ? { ...o, authority: [{ controls: ["*"], actions: ["DENY" as const] }] } : o))
    };
    const rules = rulesOf(held(event("HumanEvent.action")), tyler, limited);
    expect(rules.actionRefusal.APPROVE).toMatch(/does not include “Approve”/);
    expect(rules.actionRefusal.DENY).toBeUndefined();
  });
});

describe("DecisionPanel: votes", () => {
  const twoPerson = (votes: string[]): HumanEvent => {
    const e = event("HumanEvent.system-lifted");
    const decisions = e.decisions.filter((d) => votes.includes(d.reviewer.subject));
    return {
      ...e,
      state: "AWAITING_SECOND",
      pending: true,
      closedAt: null,
      outcome: null,
      conclusion: null,
      verdict: null,
      verdictId: null,
      decisions,
      history: e.history.slice(0, 1 + decisions.length).map((h, i) => (i === 1 ? { ...h, state: "AWAITING_SECOND" as const } : h)),
      subject: { ...e.subject, detail: { ...e.subject.detail, safeStateChecks: [{ name: "backlog drained", passed: true }] } }
    };
  };

  it("shows the votes so far against those needed, and takes the next vote without a claim", () => {
    show(twoPerson(["s.patel@example.com"]));
    expect(screen.getByTestId("vote-progress")).toHaveAccessibleName("1 of 2 votes");
    expect(screen.getAllByTestId("vote")).toHaveLength(1);
    expect(screen.queryByTestId("decision-claim")).toBeNull();
    expect(screen.getByRole("radio", { name: /Approve restart/ })).toBeEnabled();
  });

  it("tells the first voter that nothing changes until a second agrees", () => {
    show(twoPerson([]));
    fireEvent.click(screen.getByRole("radio", { name: /Keep halted/ }));
    expect(screen.getByTestId("decision-next")).toHaveTextContent("Your vote is the first of two. Nothing changes until another reviewer agrees.");
  });

  it("refuses a second vote from the same reviewer", () => {
    show(twoPerson([tyler.subject]));
    expect(screen.getByTestId("decision-refusal")).toHaveTextContent("You have already voted on this event");
  });
});

describe("DecisionPanel: a closed event", () => {
  it("shows who decided, the verdict, and offers the accountable owner the reopen", () => {
    show(event("HumanEvent.system-lifted"));
    expect(screen.getByRole("heading", { name: "The decision" })).toBeInTheDocument();
    expect(screen.getByTestId("decision-applied")).toHaveTextContent("The halt on eligibility-assessment was lifted.");
    expect(screen.getByTestId("hk-conclusion")).toHaveTextContent("Upheld");
    expect(screen.getAllByTestId("vote")).toHaveLength(2);
    expect(screen.getByTestId("action-reopen")).toBeInTheDocument();
    expect(choices()).toEqual([]);
  });
});

describe("patchOf", () => {
  it("is undefined when nothing changed, and types each value as the original", () => {
    expect(patchOf({ amount: 4200, note: "x" }, { amount: "4200" })).toBeUndefined();
    expect(patchOf({ amount: 4200, live: true }, { amount: "100", live: "false" })).toEqual({
      summary: "amount 4,200 → 100, live true → false",
      patch: [
        { op: "replace", path: "/amount", value: 100 },
        { op: "replace", path: "/live", value: false }
      ]
    });
  });
});

describe("DecisionPanel: conditions", () => {
  it("sends the conditions an approval carries, and none with a denial", async () => {
    const e = held(event("HumanEvent.action"), 60);
    const { client } = show(e);
    fireEvent.click(screen.getByRole("radio", { name: /^Approve/ }));
    fireEvent.click(screen.getByTestId("condition-add"));
    fireEvent.click(screen.getByTestId("condition-add"));
    const [first, second] = screen.getAllByTestId("condition-text");
    fireEvent.change(first, { target: { value: "Supplier confirms the bank account" } });
    fireEvent.change(screen.getAllByTestId("condition-due")[0], { target: { value: "2026-11-05" } });
    fireEvent.change(second, { target: { value: "  " } });
    fireEvent.change(screen.getByTestId("decision-rationale"), { target: { value: "PO matches." } });
    fireEvent.click(screen.getByTestId("decision-submit"));
    expect(screen.getByTestId("hk-confirm-changes")).toHaveTextContent("Conditions: Supplier confirms the bank account");
    fireEvent.click(screen.getByTestId("hk-confirm-run"));
    await waitFor(() => expect(client.write).toHaveBeenCalled());
    expect(client.write.mock.calls[0][1].body.conditions).toEqual([
      { text: "Supplier confirms the bank account", dueAt: "2026-11-05T23:59:59.000Z" }
    ]);
  });

  it("offers conditions only with an approving answer", () => {
    show(held(event("HumanEvent.action"), 60));
    fireEvent.click(screen.getByRole("radio", { name: /^Deny/ }));
    expect(screen.queryByTestId("decision-conditions")).toBeNull();
  });

  it("shows a closed event's conditions with whether each was met", () => {
    const e = event("HumanEvent.system-lifted");
    show({
      ...e,
      outcome: {
        ...e.outcome!,
        conditions: [
          { text: "Backlog stays under 50", dueAt: "2026-10-30T00:00:00Z", met: true },
          { text: "Re-run the eligibility evals", dueAt: "2026-10-29T12:00:00Z", met: null }
        ]
      }
    });
    const shown = within(screen.getByTestId("decision-closed")).getAllByTestId("condition");
    expect(shown.map((c) => c.textContent)).toEqual([expect.stringContaining("Met"), expect.stringContaining("Overdue")]);
  });
});

describe("DecisionPanel: the service's clock", () => {
  /** The action example as a service whose clock runs a day ahead of this browser answers it. */
  const ahead = (secondsHeld: number): HumanEvent => {
    const day = 86_400_000;
    const serviceNow = NOW + day;
    const raw = {
      ...example<HumanEvent>("HumanEvent.action"),
      now: new Date(serviceNow).toISOString(),
      state: "CLAIMED",
      holder: {
        subject: tyler.subject,
        roles: ["ai-system-owner"],
        since: new Date(serviceNow - secondsHeld * 1000).toISOString(),
        expiresAt: new Date(serviceNow + 20 * 60_000).toISOString()
      }
    };
    const parsed = parseEvent(raw, NOW);
    if (!parsed.ok) throw new Error(parsed.issues.join("; "));
    return parsed.value;
  };

  it("counts the minimum review and the claim against the service's time, not this browser's", () => {
    const e = ahead(4);
    expect(e.clockOffset).toBe(86_400_000);
    show(e);
    fireEvent.click(screen.getByRole("radio", { name: /^Deny/ }));
    expect(screen.getByTestId("decision-minimum")).toHaveTextContent("You can submit in 6 s");
    expect(screen.getByTestId("claim-expiry")).toHaveTextContent("your claim lapses in 20 min");
  });
});
