import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { HumanEvent } from "../types";
import { event, eventExamples, NOW } from "../test/fixtures";
import { SubjectView } from "./SubjectView";

describe("SubjectView", () => {
  it.each(eventExamples)("draws %s by its kind", (_, e) => {
    render(<SubjectView event={e} now={NOW} />);
    expect(screen.getByTestId(`subject-${e.subject.kind}`)).toBeInTheDocument();
    expect(screen.queryByTestId("subject-fallback")).toBeNull();
  });

  it("shows an action's tool, arguments and the authority it lacks", () => {
    render(<SubjectView event={event("HumanEvent.action")} now={NOW} />);
    const args = screen.getByTestId("action-arguments");
    expect(within(args).getByText("supplier")).toBeInTheDocument();
    expect(within(args).getByText("S-77")).toBeInTheDocument();
    expect(within(args).getByText("4,200")).toBeInTheDocument();
    expect(screen.getByText("amount > 1000")).toBeInTheDocument();
    expect(screen.getByText("Approval by an AI system owner.")).toBeInTheDocument();
  });

  it("shows a behaviour's value against its threshold, with a sparkline", () => {
    render(<SubjectView event={event("HumanEvent.behaviour")} now={NOW} />);
    expect(screen.getByTestId("behaviour-value")).toHaveAttribute("data-tone", "danger");
    expect(screen.getByTestId("behaviour-value")).toHaveTextContent("18%");
    expect(screen.getByTestId("behaviour-sparkline")).toBeInTheDocument();
    expect(screen.getByTestId("sparkline-threshold")).toHaveTextContent("< 20%");
    expect(screen.getByTestId("sparkline-baseline")).toHaveTextContent("baseline 35%");
    expect(screen.getByRole("img", { name: /referral-share over 24 hours, now 18%/ })).toBeInTheDocument();
  });

  it("shows a change as before and after, with what it reaches and its evidence", () => {
    render(<SubjectView event={event("HumanEvent.change")} now={NOW} />);
    const row = screen.getByTestId("change-row");
    expect(row).toHaveAttribute("data-change", "amended");
    expect(within(row).getByTestId("diff-before")).toHaveTextContent("m-4");
    expect(within(row).getByTestId("diff-after")).toHaveTextContent("m-5");
    expect(screen.getByTestId("change-answer")).toHaveTextContent("Changes Model.");
    expect(screen.getByTestId("change-blast")).toHaveTextContent("10%");
    expect(screen.getByTestId("change-blast")).toHaveTextContent("Notice writing");
    expect(screen.getByTestId("change-evidence")).toHaveTextContent("quality 0.91 to 0.90");
  });

  it("marks the words a long setting changes", () => {
    const e = event("HumanEvent.change");
    e.subject.detail = {
      ...e.subject.detail,
      before: { prompt: "You write notices to applicants in plain words and short sentences." },
      after: { prompt: "You write notices to applicants in plain words, short sentences and their language." },
      labels: { prompt: "Prompt" }
    };
    const { container } = render(<SubjectView event={e} now={NOW} />);
    expect(container.querySelectorAll(".hk-diff__removed").length).toBeGreaterThan(0);
    const added = [...container.querySelectorAll(".hk-diff__added")].map((n) => n.textContent).join("|");
    expect(added).toMatch(/language/);
    expect(screen.getByTestId("diff-before")).toHaveTextContent("plain words and short sentences.");
  });

  it("shows a restart request with its safe-state checks", () => {
    render(<SubjectView event={event("HumanEvent.system")} now={NOW} />);
    expect(screen.getByText("Asks to restart Eligibility assessment.")).toBeInTheDocument();
    expect(screen.getByTestId("safe-state-check")).toHaveAttribute("data-passed", "false");
    expect(screen.getByTestId("safe-state-summary")).toHaveTextContent("0 of 1 check has passed. A restart can be approved only once every check passes.");
  });

  it("shows two records side by side, the fields that agree and differ", () => {
    render(<SubjectView event={event("HumanEvent.records")} now={NOW} />);
    const rows = screen.getAllByTestId("records-row");
    expect(rows.map((r) => r.getAttribute("data-agreement"))).toEqual(["agree", "differ"]);
    expect(within(rows[1]).getByText("1990-02-01")).toBeInTheDocument();
    expect(within(rows[1]).getByText("1990-01-02")).toBeInTheDocument();
    expect(screen.getByTestId("records-score")).toHaveTextContent("82%");
    expect(screen.getByText(/1 of 2 fields agree; Date of birth differs/)).toBeInTheDocument();
  });

  it("shows an appeal's original decision and grounds, and who may not decide", () => {
    const { rerender } = render(<SubjectView event={event("HumanEvent.appeal")} now={NOW} />);
    expect(screen.getByTestId("appeal-original")).toHaveTextContent("Loan declined");
    expect(screen.getByTestId("appeal-original")).toHaveTextContent("Denied");
    expect(screen.getByTestId("appeal-grounds")).toHaveTextContent("My income was misread.");
    expect(screen.getByTestId("appeal-excluded")).toHaveTextContent("system:underwriter");
    expect(screen.queryByTestId("appeal-barred")).toBeNull();
    rerender(<SubjectView event={event("HumanEvent.appeal")} now={NOW} reviewer={{ subject: "system:underwriter", roles: [] }} />);
    expect(screen.getByTestId("appeal-barred")).toBeInTheDocument();
  });

  it("shows an exception's rule, scope and how long until it expires", () => {
    const { rerender } = render(<SubjectView event={event("HumanEvent.exception")} now={NOW} />);
    expect(screen.getByTestId("exception-rule")).toHaveTextContent("No production deploys on Fridays");
    expect(screen.getByTestId("exception-expiry")).toHaveTextContent(/^Expires in 24 hours/);
    expect(screen.getByTestId("exception-compensating")).toHaveTextContent("on-call reviewer present");
    rerender(<SubjectView event={event("HumanEvent.exception")} now={Date.parse("2026-10-31T14:00:00Z")} />);
    expect(screen.getByTestId("exception-window")).toHaveAttribute("data-expired", "true");
    expect(screen.getByTestId("exception-expiry")).toHaveTextContent("Expired 24 hours ago.");
  });

  it("shows a conversation's transcript and offers the take-over", () => {
    const onAction = vi.fn();
    render(<SubjectView event={event("HumanEvent.conversation")} now={NOW} onAction={onAction} />);
    expect(screen.getAllByTestId("conversation-turn")).toHaveLength(2);
    expect(screen.getByText("I want to talk to a person.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Take over" }));
    expect(onAction).toHaveBeenCalledWith("DECLINE_SYSTEM");
  });

  it("does not offer the take-over once someone has the conversation", () => {
    const e = event("HumanEvent.conversation");
    e.subject.detail = { ...e.subject.detail, takenOverBy: "l.moreau@example.com" };
    render(<SubjectView event={e} now={NOW} onAction={vi.fn()} />);
    expect(screen.getByTestId("conversation-taken")).toHaveTextContent("Taken over by l.moreau@example.com");
    expect(screen.queryByRole("button", { name: "Take over" })).toBeNull();
  });

  it("shows an attestation's evidence and how old it is", () => {
    const e = event("HumanEvent.attestation");
    const { rerender } = render(<SubjectView event={e} now={NOW} />);
    expect(screen.getByTestId("attestation-freshness")).toHaveTextContent("1 of 1 piece is current for a 90 days cadence.");
    expect(screen.getByTestId("attestation-facts")).toHaveTextContent("Never");
    rerender(<SubjectView event={e} now={Date.parse("2027-03-01T00:00:00Z")} />);
    expect(screen.getByTestId("attestation-freshness")).toHaveTextContent("0 of 1 piece is current");
    expect(within(screen.getByTestId("attestation-evidence")).getByText("Stale")).toBeInTheDocument();
  });

  it("shows the summary and raw detail for a subject kind it does not know", () => {
    const e: HumanEvent = { ...event("HumanEvent.action"), subject: { kind: "contract" as never, summary: "Clause 7 changed", detail: { clause: 7 } } };
    render(<SubjectView event={e} now={NOW} />);
    expect(screen.getByTestId("subject-fallback")).toHaveTextContent("Clause 7 changed");
    expect(screen.getByTestId("subject-fallback-reason")).toHaveTextContent("does not know the subject kind “contract”");
    expect(screen.getByTestId("subject-raw")).toHaveTextContent('"clause": 7');
  });

  it("shows the summary and raw detail for a detail that does not match its kind", () => {
    const e = event("HumanEvent.change");
    e.subject.detail = { system: "model-registry" };
    render(<SubjectView event={e} now={NOW} />);
    expect(screen.getByTestId("subject-fallback-reason")).toHaveTextContent("The change detail does not match the contract");
    expect(screen.getByTestId("subject-raw")).toHaveTextContent("model-registry");
  });
});
