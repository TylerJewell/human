import {
  formatMoment,
  formatSpan,
  readable,
  readClock,
  serviceNow,
  type CompanionService,
  type ControlSettings,
  type Halt,
  type HumanEventRow,
  type OverseerRegistry,
  type StandingDecision,
  type Summary,
  type Verdict
} from "@akka-human/kit";

// What the console works out from the companion's answers, apart from how it
// draws them: what needs attention, the overview's sentence, the controls by
// service, a conformance report's totals, and the page an address names.

/** A control as the companion merges it: the service that declares it travels with it. */
export type ServiceControl = ControlSettings & { service?: string };

/** A verdict as the companion merges it, with the service that gave it. */
export type ServiceVerdict = Verdict & { service?: string };

export interface AttentionItem {
  id: string;
  severity: "urgent" | "risk";
  /** One sentence that says what needs attention. */
  text: string;
  /** Where it is dealt with: an event's page or a view. */
  to: string;
  /** What the link says. */
  action: string;
}

/** People who could take a control's events today: qualified, in one of its roles, with authority over it when they have any. */
export function takersOf(registry: OverseerRegistry, control: ServiceControl): string[] {
  return registry.overseers
    .filter((o) => o.qualified)
    .filter((o) => o.roles.some((r) => control.routing.roles.includes(r)))
    .filter(
      (o) =>
        !o.authority?.length || o.authority.some((a) => a.controls.includes("*") || a.controls.includes(control.control.rowId))
    )
    .map((o) => o.subject);
}

const sentence = (text: string) => (/[.!?]$/.test(text) ? text : `${text}.`);

/**
 * What needs attention, most urgent first: each event past its deadline; each
 * halt in effect; each service that does not answer; then each overseer who
 * cannot decide, the controls nobody owns, each control nobody can take, and
 * each standing decision about to expire.
 */
export function attentionOf({
  overdue,
  halts,
  services,
  registry,
  controls,
  standing,
  unowned = 0,
  now,
  nameOf
}: {
  overdue: readonly HumanEventRow[];
  halts: readonly Halt[];
  services: readonly CompanionService[];
  registry: OverseerRegistry | undefined;
  controls: readonly ServiceControl[];
  standing: readonly StandingDecision[];
  /** Controls with no accountable owner, as the summary counts them. */
  unowned?: number;
  /** This browser's time; each overdue row is read against its own service's clock. */
  now: number;
  nameOf: (subject: string) => string;
}): AttentionItem[] {
  const items: AttentionItem[] = [];
  for (const row of overdue) {
    const at = serviceNow(row, now);
    const clock = readClock(row.sla, row.detectedAt, at);
    if (clock.tone !== "overdue" || !row.sla.dueAt) continue;
    items.push({
      id: `overdue:${row.id}`,
      severity: "urgent",
      text: `${formatSpan(at - Date.parse(row.sla.dueAt))} past its deadline, on ${row.owner?.service ?? row.subject.service ?? "a service"}: ${row.ask.question}`,
      to: `/events/${encodeURIComponent(row.id)}`,
      action: "Open the event"
    });
  }
  for (const h of halts.filter((x) => x.halted))
    items.push({
      id: `halt:${h.service ?? ""}:${h.control.rowId}`,
      severity: "urgent",
      text: `${readable(h.target)}${h.service ? ` on ${h.service}` : ""} has been halted${h.since ? ` since ${formatMoment(h.since)}` : ""}${h.by ? `, by ${nameOf(h.by)}` : ""}.`,
      to: "/halts",
      action: "Halts"
    });
  for (const s of services.filter((x) => !x.up))
    items.push({
      id: `down:${s.service}`,
      severity: "urgent",
      text: `${s.service} does not answer at ${s.url}, so its events are not shown.`,
      to: "/services",
      action: "Services"
    });
  for (const o of registry?.overseers ?? []) {
    if (o.qualified) continue;
    items.push({
      id: `overseer:${o.subject}`,
      severity: "risk",
      text: sentence(`${o.name ?? o.subject} is not qualified to decide${o.qualifiedReason ? `: ${o.qualifiedReason}` : ""}`),
      to: "/overseers",
      action: "Overseers"
    });
  }
  if (unowned > 0)
    items.push({
      id: "unowned",
      severity: "risk",
      text: `${unowned} human ${unowned === 1 ? "control has" : "controls have"} no accountable owner to answer for ${unowned === 1 ? "its" : "their"} oversight.`,
      to: "/controls",
      action: "Controls"
    });
  if (registry)
    for (const c of controls)
      if (takersOf(registry, c).length === 0)
        items.push({
          id: `untaken:${c.service ?? ""}:${c.control.rowId}`,
          severity: "risk",
          text: `Nobody qualified can take the events of ${sentence(`${c.control.name}${c.service ? ` (${c.service})` : ""}`)}`,
          to: "/controls",
          action: "Controls"
        });
  for (const s of standing.filter((x) => x.state === "EXPIRING"))
    items.push({
      id: `standing:${s.id}`,
      severity: "risk",
      text: `A standing decision expires${s.expiresAt ? ` on ${formatMoment(s.expiresAt)}` : " soon"}: ${sentence(s.allows)}`,
      to: "/standing",
      action: "Standing decisions"
    });
  return items;
}

/** The overview's one sentence: whether decisions wait, whether any is overdue, and whether every service answers. */
export function overviewSentence(summary: Summary | undefined, services: readonly CompanionService[]): string {
  const down = services.filter((s) => !s.up).length;
  const services_ = down
    ? ` ${down} of ${services.length} services ${down === 1 ? "does" : "do"} not answer.`
    : services.length
      ? ` All ${services.length === 1 ? "one service answers" : `${services.length} services answer`}.`
      : " No service is registered.";
  if (!summary) return services_.trim();
  const waiting = summary.loop.in.pending + summary.loop.on.pending + summary.loop.over.pending;
  const overdue = summary.overdue;
  const lead =
    waiting === 0
      ? "Nothing needs a decision."
      : `${waiting} ${waiting === 1 ? "decision is" : "decisions are"} waiting${overdue ? `, ${overdue} overdue` : ", none overdue"}.`;
  return `${lead}${services_}`;
}

/** Controls by the service that declares them, services in name order and controls by id within each. */
export function controlsByService(controls: readonly ServiceControl[]): [string, ServiceControl[]][] {
  const groups = new Map<string, ServiceControl[]>();
  for (const c of controls) {
    const key = c.service ?? "unknown service";
    groups.set(key, [...(groups.get(key) ?? []), c]);
  }
  return [...groups]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([service, list]) => [service, [...list].sort((a, b) => a.control.rowId.localeCompare(b.control.rowId))]);
}

export interface ConformanceStep {
  name: string;
  status: string;
  request?: string;
  http?: number;
  detail?: string;
}

export interface ConformanceScenario {
  pattern: string;
  scenario: string;
  control?: string;
  status: string;
  detail?: string | null;
  durationMs?: number;
  steps: ConformanceStep[];
}

/** The conformance suite's report, as the companion answers it. */
export interface ConformanceReport {
  url?: string;
  service?: string;
  library?: string;
  contract?: number;
  startedAt?: string;
  finishedAt?: string;
  totals?: { passed: number; failed: number; skipped: number; total: number };
  scenarios: ConformanceScenario[];
}

/** A report's totals, counted from its scenarios when it gives none, and whether it passed. */
export function reportTotals(report: ConformanceReport) {
  const count = (status: string) => report.scenarios.filter((s) => s.status === status).length;
  const totals = report.totals ?? {
    passed: count("passed"),
    failed: count("failed"),
    skipped: count("skipped"),
    total: report.scenarios.length
  };
  return { ...totals, ok: totals.failed === 0 };
}

/** Failed scenarios first, then the rest, each group by pattern. */
export function scenarioOrder(scenarios: readonly ConformanceScenario[]): ConformanceScenario[] {
  const rank = (s: ConformanceScenario) => (s.status === "failed" ? 0 : s.status === "passed" ? 1 : 2);
  return [...scenarios].sort((a, b) => rank(a) - rank(b) || a.pattern.localeCompare(b.pattern));
}

/** Verdicts newest first, kept to those that match a conclusion and a service when given. */
export function verdictsOf(
  verdicts: readonly ServiceVerdict[],
  filter: { conclusion?: string; service?: string }
): ServiceVerdict[] {
  return verdicts
    .filter((v) => !filter.conclusion || v.conclusion === filter.conclusion)
    .filter((v) => !filter.service || (v.service ?? v.source.service) === filter.service)
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
}

export const VIEWS = [
  { id: "overview", label: "Overview" },
  { id: "queue", label: "Queue" },
  { id: "halts", label: "Halts" },
  { id: "standing", label: "Standing decisions" },
  { id: "quality", label: "Quality" },
  { id: "overseers", label: "Overseers" },
  { id: "controls", label: "Controls" },
  { id: "verdicts", label: "Verdicts" },
  { id: "services", label: "Services" }
] as const;

export type View = (typeof VIEWS)[number]["id"];

export type Route = { view: View } | { view: "event"; id: string };

/** The page an address's hash names: `#/queue`, `#/events/<id>`; anything else is the overview. */
export function routeOf(hash: string): Route {
  const parts = hash
    .replace(/^#\/?/, "")
    .split("/")
    .filter(Boolean)
    .map(decodeURIComponent);
  if (parts[0] === "events" && parts[1]) return { view: "event", id: parts[1] };
  const view = VIEWS.find((v) => v.id === parts[0]);
  return { view: view?.id ?? "overview" };
}
