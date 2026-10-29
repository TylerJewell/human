import type { HumanEvent } from "@akka-human/kit";
import { describe, expect, it } from "vitest";
import example from "../../../../spec/examples/HumanEvent.records.json";
import {
  caseCounts,
  casesWith,
  fieldRows,
  headlineColumns,
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

/** The records example. */
const event = (patch: Partial<HumanEvent> = {}): HumanEvent => ({ ...(example as unknown as HumanEvent), ...patch });

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

describe("where an event belongs", () => {
  it("opens the link or the case the event is about", () => {
    const subject = { ...event().subject, ref: "name-dob-062223f9" };
    expect(recordOf({ control: { rowId: LINK_REVIEW, name: "Link Review" }, subject })).toEqual({ path: "/links/name-dob-062223f9", label: "The link" });
    expect(recordOf({ control: { rowId: "custom-cl0046", name: "Case Resolution" }, subject: { ...subject, ref: "case-1" } })?.path).toBe("/cases/case-1");
    expect(recordOf({ control: { rowId: "custom-cl0005", name: "Release Approval" }, subject })).toBeUndefined();
  });
});
