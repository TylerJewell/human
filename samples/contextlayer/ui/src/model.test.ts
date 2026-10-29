import type { HumanEvent, OverseerRegistry } from "@akka-human/kit";
import { describe, expect, it } from "vitest";
import example from "../../../../spec/examples/HumanEvent.records.json";
import {
  afterDeadline,
  caseCounts,
  casesWith,
  decisionClock,
  deciders,
  fieldRows,
  headlineColumns,
  hms,
  initialsOf,
  linkTitle,
  LINK_REVIEW,
  recordOf,
  sortLinks,
  type Case,
  type LinkView
} from "./model";

const member = (source: string, ref: string, loadedBy: string, fields: Record<string, string>) => ({ source, ref, loadedBy, fields });

const link = (clusterId: string, status: LinkView["status"], eventId: string | null = "he-1"): LinkView => ({
  clusterId,
  rule: "name-dob",
  status,
  left: member("claims", "CL-871", "priya.k@example.com", { name: "Sam Ortiz", dateOfBirth: "1990-05-05", email: "sam.o@claims.example" }),
  right:
    status === "SINGLE"
      ? null
      : member("wealth", "W-2", "dan.m@example.com", { name: "Sam Ortiz", dateOfBirth: "1990-05-05", email: "sam@wealth.example", postcode: "02139" }),
  agree: ["dateOfBirth", "name"],
  differ: ["email"],
  reviewedBy: status === "CONFIRMED" ? "rita.reviewer@example.com" : null,
  eventId: status === "SINGLE" ? null : eventId
});

const kase = (id: string, status: Case["status"], openedAt: string, headline: Record<string, string>): Case => ({
  id,
  watcherId: "stalled-claims",
  title: `Claim ${id} stalled`,
  headline,
  status,
  openedAt,
  readings: 1,
  closedBy: null,
  reason: null,
  eventId: `he-${id}`
});

/** The records example, at the service's time `now`, with its clock agreeing with the browser's. */
const event = (patch: Partial<HumanEvent> = {}): HumanEvent => ({ ...(example as unknown as HumanEvent), clockOffset: 0, ...patch });

const at = (iso: string) => Date.parse(iso);

const registry: OverseerRegistry = {
  overseers: [
    { subject: "rita@example.com", name: "Rita Reviewer", roles: ["data-steward"], qualified: true },
    { subject: "raj@example.com", name: "Raj Reviewer", roles: ["data-steward"], qualified: true },
    { subject: "uma@example.com", name: "Uma Suspended", roles: ["data-steward"], qualified: false, qualifiedReason: "Training lapsed" },
    { subject: "ada@example.com", name: "Ada Approver", roles: ["approver"], qualified: true }
  ]
};

describe("links", () => {
  it("sorts links into waiting for a review, decided, and keys with one record", () => {
    const sorted = sortLinks([link("a", "PENDING"), link("b", "CONFIRMED"), link("c", "SINGLE"), link("d", "REJECTED"), link("e", "PENDING", null)]);
    expect(sorted.toReview.map((l) => l.clusterId)).toEqual(["a"]);
    expect(sorted.decided.map((l) => l.clusterId)).toEqual(["b", "d"]);
    expect(sorted.single).toBe(1);
  });

  it("lines up every field of both records, agreeing as the service says", () => {
    const rows = fieldRows(link("a", "PENDING"));
    expect(rows.map((r) => [r.field, r.same])).toEqual([
      ["name", true],
      ["dateOfBirth", true],
      ["email", false],
      ["postcode", false]
    ]);
    expect(rows[3]).toMatchObject({ left: undefined, right: "02139" });
  });

  it("names a link by the name the records share", () => {
    expect(linkTitle(link("a", "PENDING"))).toBe("Sam Ortiz");
    expect(linkTitle({ ...link("a", "PENDING"), left: member("crm", "C-1", "x", { sku: "SKU-9" }), right: null })).toBe("SKU-9");
  });
});

describe("cases", () => {
  const cases = [
    kase("case-1", "OPEN", "2026-10-29T08:00:00Z", { claim: "CL-889", client: "Pat Lee" }),
    kase("case-2", "RESOLVED", "2026-10-28T08:00:00Z", { claim: "CL-755" }),
    kase("case-3", "OPEN", "2026-10-29T09:00:00Z", { claim: "CL-860", balance: "$301,200" })
  ];

  it("filters by status, newest first", () => {
    expect(casesWith(cases, "OPEN").map((c) => c.id)).toEqual(["case-3", "case-1"]);
    expect(casesWith(cases, "ALL")).toHaveLength(3);
    expect(caseCounts(cases)).toEqual({ OPEN: 2, RESOLVED: 1, DISMISSED: 0, ALL: 3 });
  });

  it("shows the headline fields as columns in the order they first appear", () => {
    expect(headlineColumns(cases)).toEqual(["claim", "client", "balance"]);
    expect(headlineColumns(cases, 2)).toEqual(["claim", "client"]);
  });
});

describe("the decision clock", () => {
  it("counts the deadline down on the service's clock", () => {
    const clock = decisionClock(event(), at("2026-10-29T14:05:00Z"));
    expect(clock.left).toBe(25 * 60);
    expect(clock.remaining).toBeCloseTo(25 / 30);
    expect(clock.tone).toBe("running");
    expect(hms(clock.left!)).toBe("0:25:00");
  });

  it("reads the service's time when its clock runs ahead of the browser's", () => {
    const clock = decisionClock(event({ clockOffset: 10 * 60_000 }), at("2026-10-29T14:05:00Z"));
    expect(clock.left).toBe(15 * 60);
  });

  it("counts the minimum review time from the acting reviewer's claim", () => {
    const held = event({
      state: "CLAIMED",
      holder: { subject: "rita@example.com", since: "2026-10-29T14:04:56Z", expiresAt: "2026-10-29T14:34:56Z" }
    });
    expect(decisionClock(held, at("2026-10-29T14:05:00Z"), "raj@example.com").minimum).toMatchObject({ started: false, met: false });
    expect(decisionClock(held, at("2026-10-29T14:05:00Z"), "rita@example.com").minimum).toEqual({
      seconds: 10,
      started: true,
      elapsed: 4,
      met: false
    });
    expect(decisionClock(held, at("2026-10-29T14:05:30Z"), "rita@example.com").minimum).toMatchObject({ elapsed: 10, met: true });
  });

  it("says what happens at the deadline", () => {
    expect(afterDeadline(event())).toBe("then “Different” by default");
    const escalating = event({ sla: { ...event().sla, timeoutBehaviour: "ESCALATE" } });
    expect(afterDeadline(escalating)).toBe("then to head of operations");
    expect(hms(90061)).toBe("1 d 1:01");
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
  });

  it("names the people a link review keeps out in the service's words", () => {
    const review = event({
      control: { rowId: LINK_REVIEW, name: "Link Review" },
      proposedBy: "priya.k@example.com",
      builtBy: "dan.m@example.com",
      routing: { ...event().routing, independentOf: ["priya.k@example.com", "dan.m@example.com"] }
    });
    const words = { proposer: "loaded one of the records", builder: "loaded one of the records" };
    const barred = deciders(review, registry, undefined, words).filter((p) => !p.may && p.barred !== "unqualified");
    expect(barred.map((p) => [p.initials, p.note])).toEqual([
      ["PK", "loaded one of the records, so cannot decide"],
      ["DM", "loaded one of the records, so cannot decide"]
    ]);
    expect(initialsOf("Rita Reviewer")).toBe("RR");
  });
});

describe("where an event belongs", () => {
  it("opens the link or the case the event is about", () => {
    const subject = { ...event().subject, ref: "name-dob-062223f9" };
    expect(recordOf({ control: { rowId: LINK_REVIEW, name: "Link Review" }, subject })).toEqual({ path: "/links/name-dob-062223f9", label: "The link" });
    expect(recordOf({ control: { rowId: "custom-cl0046", name: "Case Resolution" }, subject: { ...subject, ref: "case-1" } })?.path).toBe("/cases/case-1");
    expect(recordOf({ control: { rowId: "custom-cl0005", name: "Release Approval" }, subject })).toBeUndefined();
  });
});
