import type { HumanEvent } from "@akka-human/kit";

// What the screens show, worked out from the service's records and events:
// which links wait for a review, which cases are open, and where an event
// belongs. Kept free of React so it can be tested on its own.

export const LINK_REVIEW = "custom-cl0004";
export const CASE_RESOLUTION = "custom-cl0046";

// ---------------------------------------------------------------- links

/** One source record in a link, as `GET /links` gives it. */
export interface Member {
  source: string;
  ref: string;
  loadedBy: string;
  fields: Record<string, string>;
}

/** `status`: SINGLE (one record so far), PENDING (waiting for a review), CONFIRMED or REJECTED. */
export interface LinkView {
  clusterId: string;
  rule: string;
  status: "SINGLE" | "PENDING" | "CONFIRMED" | "REJECTED";
  left: Member | null;
  right: Member | null;
  agree: string[];
  differ: string[];
  reviewedBy: string | null;
  eventId: string | null;
}

export interface Links {
  /** Two records and a review raised for them. */
  toReview: LinkView[];
  /** Confirmed or rejected. */
  decided: LinkView[];
  /** Keys that hold one record so far, so nothing to review yet. */
  single: number;
}

export function sortLinks(links: readonly LinkView[]): Links {
  return {
    toReview: links.filter((l) => l.status === "PENDING" && l.eventId && l.left && l.right),
    decided: links.filter((l) => l.status === "CONFIRMED" || l.status === "REJECTED"),
    single: links.filter((l) => l.status === "SINGLE").length
  };
}

const TITLE_FIELDS = ["name", "fullName", "email", "title", "sku"];

/** What a link is called in a list: the name the records share, or their first field. */
export function linkTitle(link: LinkView): string {
  const fields = { ...link.right?.fields, ...link.left?.fields };
  for (const f of TITLE_FIELDS) if (fields[f]) return fields[f];
  const first = Object.values(fields)[0];
  return first ?? link.clusterId;
}

export interface FieldRow {
  field: string;
  left?: string;
  right?: string;
  same: boolean;
}

/**
 * Every field either record has, in the left record's order, and whether the
 * two agree. The service's `agree` and `differ` lists decide; a field neither
 * names is compared here.
 */
export function fieldRows(link: LinkView): FieldRow[] {
  const left = link.left?.fields ?? {};
  const right = link.right?.fields ?? {};
  const names = [...new Set([...Object.keys(left), ...Object.keys(right)])];
  return names.map((field) => ({
    field,
    left: left[field],
    right: right[field],
    same: link.differ.includes(field) ? false : link.agree.includes(field) ? true : left[field] === right[field]
  }));
}

/** The people who loaded the records, each once. */
export const loadersOf = (link: LinkView) =>
  [...new Set([link.left?.loadedBy, link.right?.loadedBy].filter((s): s is string => Boolean(s)))];

// ---------------------------------------------------------------- cases

/** `status`: OPEN, RESOLVED or DISMISSED. `headline` holds the fields a list shows. */
export interface Case {
  id: string;
  watcherId: string;
  title: string;
  headline: Record<string, string>;
  status: "OPEN" | "RESOLVED" | "DISMISSED";
  openedAt: string;
  readings: number;
  closedBy: string | null;
  reason: string | null;
  eventId: string | null;
}

export const CASE_FILTERS = [
  { id: "OPEN", label: "Open" },
  { id: "RESOLVED", label: "Resolved" },
  { id: "DISMISSED", label: "Dismissed" },
  { id: "ALL", label: "All" }
] as const;

export type CaseFilter = (typeof CASE_FILTERS)[number]["id"];

/** Cases under `filter`, newest first. */
export function casesWith(cases: readonly Case[], filter: CaseFilter): Case[] {
  return cases
    .filter((c) => filter === "ALL" || c.status === filter)
    .sort((a, b) => Date.parse(b.openedAt) - Date.parse(a.openedAt));
}

export function caseCounts(cases: readonly Case[]): Record<CaseFilter, number> {
  const count = (s: string) => cases.filter((c) => c.status === s).length;
  return { OPEN: count("OPEN"), RESOLVED: count("RESOLVED"), DISMISSED: count("DISMISSED"), ALL: cases.length };
}

/** The headline fields a case list shows as columns, in the order they first appear. */
export function headlineColumns(cases: readonly Case[], max = 3): string[] {
  return [...new Set(cases.flatMap((c) => Object.keys(c.headline ?? {})))].slice(0, max);
}

// ---------------------------------------------------------------- where an event belongs

/** The screen that shows the record an event is about, when contextlayer has one. */
export function recordOf(event: Pick<HumanEvent, "control" | "subject">): { path: string; label: string } | undefined {
  const ref = event.subject.ref;
  if (!ref) return undefined;
  if (event.control.rowId === LINK_REVIEW) return { path: `/links/${ref}`, label: "The link" };
  if (event.control.rowId === CASE_RESOLUTION) return { path: `/cases/${ref}`, label: "The case" };
  return undefined;
}
