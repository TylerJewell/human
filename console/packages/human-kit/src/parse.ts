import * as v from "valibot";
import type {
  Action,
  ControlSettings,
  HumanEvent,
  HumanEventRow,
  Halt,
  OverseerRegistry,
  Quality,
  ServiceInfo,
  StandingDecision,
  Subject,
  SubjectDetails,
  SubjectKind,
  Summary,
  Verdict
} from "./types";

// Reading what a server sends. The envelope of an event is read leniently:
// enumerations as strings, so a pattern, state, action or subject kind this kit
// does not know still reads, and the arrays a page walks default to empty. A
// subject's detail is checked against its kind's shape separately, so a detail
// that does not match costs only the subject view, which then shows the summary
// and the raw detail. An event whose envelope cannot be read at all keeps its
// summary and raw JSON in place of the page.

const str = v.string();
const optStr = v.nullish(v.string());
const strs = v.optional(v.array(v.string()), []);
const record = v.record(v.string(), v.unknown());
const optRecord = v.optional(record, {});
const num = v.number();

// ---------------------------------------------------------------- the event envelope

const ControlRefSchema = v.looseObject({
  rowId: v.optional(str, ""),
  name: v.optional(str, ""),
  corpusRef: optStr,
  revision: v.nullish(num)
});

const ReviewerSchema = v.looseObject({
  subject: str,
  name: v.optional(str),
  roles: strs
});

const SubjectSchema = v.looseObject({
  kind: str,
  summary: v.optional(str, ""),
  service: optStr,
  agent: optStr,
  ref: optStr,
  detail: v.optional(v.nullable(record), {})
});

const HolderSchema = v.nullish(
  v.looseObject({
    subject: str,
    roles: strs,
    since: v.optional(str, ""),
    expiresAt: v.optional(str, "")
  })
);

const SlaSchema = v.optional(
  v.looseObject({
    state: v.optional(str, "none"),
    dueAt: optStr,
    target: optStr,
    startedAt: v.optional(str),
    atRiskAt: v.optional(num)
  }),
  { state: "none" }
);

const DecisionSchema = v.looseObject({
  reviewer: ReviewerSchema,
  action: str,
  submittedAt: v.optional(str, ""),
  checks: strs
});

const HistorySchema = v.looseObject({
  at: str,
  state: str,
  actor: v.optional(str, "system"),
  note: v.optional(v.nullable(str), "")
});

const EventSchema = v.looseObject({
  id: str,
  state: str,
  pending: v.optional(v.boolean()),
  pattern: v.optional(str, "unknown"),
  patternName: v.optional(str),
  control: v.optional(ControlRefSchema, { rowId: "", name: "" }),
  flavor: v.optional(str, "human-domain-decision"),
  mode: v.optional(str, "HITL"),
  blocking: v.optional(v.boolean(), false),
  subject: SubjectSchema,
  trigger: v.optional(
    v.looseObject({
      detector: v.optional(str, "unknown"),
      detectedAt: v.optional(str)
    }),
    {}
  ),
  ask: v.looseObject({
    question: str,
    checks: v.optional(v.array(v.looseObject({ text: str, section: v.optional(str) })), [])
  }),
  owner: v.optional(v.looseObject({ kind: v.optional(str, "service"), service: v.optional(str, "") })),
  routing: v.optional(
    v.looseObject({
      queue: v.optional(str, ""),
      roles: strs,
      reviewMode: v.optional(str, "SINGLE"),
      required: v.optional(num, 1),
      independentOf: strs
    }),
    {}
  ),
  holder: HolderSchema,
  sla: SlaSchema,
  allowedActions: strs,
  actionLabels: v.optional(v.record(str, str), {}),
  effects: v.optional(v.record(str, str), {}),
  sticky: strs,
  decisions: v.optional(v.array(DecisionSchema), []),
  history: v.optional(v.array(HistorySchema), [])
});

const RowSchema = v.looseObject({
  id: str,
  state: str,
  pending: v.optional(v.boolean()),
  pattern: v.optional(str, "unknown"),
  patternName: v.optional(str),
  control: v.optional(ControlRefSchema, { rowId: "", name: "" }),
  flavor: v.optional(str, "human-domain-decision"),
  mode: v.optional(str, "HITL"),
  blocking: v.optional(v.boolean(), false),
  subject: v.looseObject({ kind: str, summary: v.optional(str, ""), service: optStr, agent: optStr, ref: optStr }),
  ask: v.optional(v.looseObject({ question: v.optional(str, "") }), { question: "" }),
  queue: v.optional(str, ""),
  reviewers: v.optional(v.looseObject({ required: v.optional(num, 1), voted: v.optional(num, 0) }), {}),
  holder: HolderSchema,
  sla: SlaSchema,
  detectedAt: v.optional(str, ""),
  closedAt: optStr,
  decidedBy: strs
});

const PENDING_STATES = new Set([
  "DETECTED",
  "OPEN",
  "CLAIMED",
  "IN_REVIEW",
  "AWAITING_SECOND",
  "ESCALATED",
  "CHANGES_REQUESTED"
]);

/** Whether a state is one of the pending ones the contract lists. */
export const isPendingState = (state: string) => PENDING_STATES.has(state);

export type Parsed<T> =
  | { ok: true; value: T }
  | {
      ok: false;
      /** What the JSON says it is about, or the best words found for it. */
      summary: string;
      raw: unknown;
      issues: string[];
    };

const issueText = (issues: readonly v.BaseIssue<unknown>[]) =>
  issues.map((issue) => {
    const path = v.getDotPath(issue);
    return path ? `${path}: ${issue.message}` : issue.message;
  });

const at = (raw: unknown, ...path: string[]): unknown =>
  path.reduce<unknown>(
    (value, key) => (value && typeof value === "object" ? (value as Record<string, unknown>)[key] : undefined),
    raw
  );

/** The words an unreadable event or row is shown by: its summary, its question or its id. */
function summaryOf(raw: unknown): string {
  for (const path of [["subject", "summary"], ["ask", "question"], ["id"]]) {
    const value = at(raw, ...path);
    if (typeof value === "string" && value.trim()) return value;
  }
  return "An event this console cannot read";
}

/**
 * How far the service's clock is ahead of this browser's, from the `now` it
 * answered with and when the answer was read; undefined when it gave no `now`.
 */
function offsetOf(now: unknown, receivedAt: number): number | undefined {
  const at = typeof now === "string" ? Date.parse(now) : NaN;
  return Number.isFinite(at) ? at - receivedAt : undefined;
}

/**
 * An event as the contract describes it, or its summary and raw JSON when it
 * cannot be read. `receivedAt` is when the answer arrived, by this browser's
 * clock; with the event's `now` it gives the service's clock offset.
 */
export function parseEvent(raw: unknown, receivedAt: number = Date.now()): Parsed<HumanEvent> {
  const parsed = v.safeParse(EventSchema, raw);
  if (!parsed.success) {
    return { ok: false, summary: summaryOf(raw), raw, issues: issueText(parsed.issues) };
  }
  const e = parsed.output;
  const detectedAt = (e.trigger.detectedAt as string | undefined) ?? (e.detectedAt as string | undefined) ?? e.history[0]?.at ?? "";
  const value = {
    ...e,
    clockOffset: offsetOf(e.now, receivedAt),
    pending: e.pending ?? isPendingState(e.state),
    patternName: e.patternName ?? e.pattern,
    trigger: { ...e.trigger, detectedAt },
    owner: e.owner ?? { kind: "service", service: e.subject.service ?? "" },
    subject: { ...e.subject, detail: e.subject.detail ?? {} },
    history: e.history.map((h) => ({ ...h, note: h.note ?? "" }))
  };
  return { ok: true, value: value as unknown as HumanEvent };
}

/** A queue row, or its summary and raw JSON when it cannot be read; `receivedAt` as for parseEvent. */
export function parseRow(raw: unknown, receivedAt: number = Date.now()): Parsed<HumanEventRow> {
  const parsed = v.safeParse(RowSchema, raw);
  if (!parsed.success) {
    return { ok: false, summary: summaryOf(raw), raw, issues: issueText(parsed.issues) };
  }
  const r = parsed.output;
  return {
    ok: true,
    value: {
      ...r,
      clockOffset: offsetOf(r.now, receivedAt),
      pending: r.pending ?? isPendingState(r.state),
      patternName: r.patternName ?? r.pattern
    } as unknown as HumanEventRow
  };
}

/** The row a queue shows for a full event, as the companion would send it. */
export function rowOf(event: HumanEvent): HumanEventRow {
  return {
    id: event.id,
    now: event.now,
    clockOffset: event.clockOffset,
    state: event.state,
    pending: event.pending,
    pattern: event.pattern,
    patternName: event.patternName,
    control: event.control,
    flavor: event.flavor,
    mode: event.mode,
    blocking: event.blocking,
    subject: {
      kind: event.subject.kind,
      summary: event.subject.summary,
      service: event.subject.service,
      agent: event.subject.agent,
      ref: event.subject.ref ?? null
    },
    ask: { question: event.ask.question },
    queue: event.routing.queue,
    reviewers: { required: event.routing.required, voted: votesOf(event).length },
    holder: event.holder ?? null,
    sla: {
      state: event.sla.state,
      dueAt: event.sla.dueAt ?? null,
      target: event.sla.target ?? null,
      startedAt: event.sla.startedAt,
      atRiskAt: event.sla.atRiskAt
    },
    aiRecommendation: event.aiRecommendation ?? null,
    shadow: event.shadow ?? false,
    detectedAt: event.trigger.detectedAt,
    closedAt: event.closedAt ?? null,
    outcome: event.outcome ?? null,
    decidedBy: event.decisions.map((d) => d.reviewer.subject),
    conclusion: event.conclusion ?? null,
    simulated: event.simulated ?? false,
    owner: { service: event.owner.service }
  };
}

/** The votes of the current round: a reopened event starts counting again. */
export function votesOf(event: Pick<HumanEvent, "history" | "decisions">) {
  const reopened = event.history.findLastIndex((h) => h.state === "REOPENED");
  if (reopened < 0) return event.decisions;
  const since = Date.parse(event.history[reopened].at);
  return event.decisions.filter((d) => d.submittedAt && Date.parse(d.submittedAt) > since);
}

/** The verdict's settling decision as the contract names actions: request-more-info is REQUEST_MORE_INFO. */
export const verdictAction = (verdict: Verdict) =>
  verdict.detail.action ? (verdict.detail.action.toUpperCase().replaceAll("-", "_") as Action) : undefined;

/** Who decided: each voter, the one reviewer, or the standing decision that settled the item. */
export function verdictDeciders(verdict: Verdict): string[] {
  const d = verdict.detail;
  if (d.votes?.length) return d.votes.map((vote) => vote.subject);
  if (d.reviewer) return [d.reviewer.subject];
  return d.standing ? [`standing:${d.standing.decisionId}`] : [];
}

// ---------------------------------------------------------------- subject details

const Instant = v.string();

const DETAIL = {
  action: v.looseObject({
    tool: str,
    arguments: record,
    authority: v.optional(v.looseObject({ missing: strs, required: optRecord })),
    recentSteps: v.optional(v.array(record), []),
    sentAt: v.optional(Instant),
    requestedBy: v.optional(str),
    lastKnown: v.optional(str)
  }),
  behaviour: v.looseObject({
    metric: str,
    value: num,
    unit: v.optional(str),
    window: v.optional(str),
    comparison: v.optional(str),
    threshold: v.optional(num),
    baseline: v.optional(num),
    series: v.optional(v.array(v.looseObject({ at: Instant, value: num })), []),
    contributors: v.optional(
      v.array(v.looseObject({ kind: str, ref: str, weight: v.optional(num) })),
      []
    )
  }),
  change: v.looseObject({
    system: str,
    change: str,
    ref: str,
    before: record,
    after: record,
    proposedBy: v.optional(str),
    builtBy: v.optional(str),
    labels: v.optional(v.record(str, str)),
    evidence: v.optional(v.array(v.looseObject({ kind: str, ref: str, summary: v.optional(str) })), []),
    blastRadius: v.optional(
      v.looseObject({ useCases: v.optional(v.array(str)), trafficShare: v.optional(num) })
    ),
    stage: v.optional(str)
  }),
  system: v.looseObject({
    scope: str,
    target: str,
    affectedCalls: str,
    safeStateChecks: v.array(v.looseObject({ name: str, passed: v.nullable(v.boolean()) })),
    halted: v.boolean(),
    callsPerMinute: v.optional(num),
    lift: v.optional(v.boolean())
  }),
  output: v.looseObject({
    output: str,
    replacement: optStr,
    flag: v.nullish(v.looseObject({ by: v.optional(str), note: v.optional(str) })),
    flowId: optStr,
    decision: v.nullish(
      v.looseObject({ ref: v.optional(str), summary: v.optional(str), affects: v.optional(str) })
    )
  }),
  records: (() => {
    const side = v.looseObject({ source: str, ref: str, loadedBy: v.optional(str), fields: record });
    return v.looseObject({
      entityType: str,
      left: side,
      right: side,
      labels: v.optional(v.record(str, str)),
      agree: v.optional(v.array(str)),
      differ: v.optional(v.array(str)),
      score: v.optional(num),
      method: v.optional(str)
    });
  })(),
  appeal: v.looseObject({
    original: v.looseObject({
      ref: str,
      decision: str,
      summary: str,
      decidedBy: v.optional(str),
      decidedAt: v.optional(Instant)
    }),
    grounds: str,
    appellant: v.optional(v.looseObject({ ref: v.optional(str), name: v.optional(str) })),
    right: v.optional(str),
    filedAt: v.optional(Instant),
    excluded: v.optional(v.array(str), []),
    attachments: v.optional(v.array(v.looseObject({ kind: str, ref: str })), [])
  }),
  exception: v.looseObject({
    rule: v.looseObject({ id: str, text: str }),
    scope: str,
    until: Instant,
    requestedBy: v.optional(str),
    justification: v.optional(str),
    from: v.optional(Instant),
    compensating: v.optional(v.array(str), []),
    grantor: v.optional(str)
  }),
  conversation: v.looseObject({
    channel: str,
    reason: str,
    transcript: v.array(v.looseObject({ at: v.optional(Instant), role: str, text: str })),
    run: v.nullish(
      v.looseObject({ workflowId: v.optional(str), step: v.optional(str), error: v.optional(str) })
    ),
    takenOverBy: optStr
  }),
  attestation: v.looseObject({
    attests: str,
    target: v.looseObject({ ref: str, name: str }),
    evidence: v.array(
      v.looseObject({
        kind: str,
        ref: str,
        summary: v.optional(str),
        asOf: v.optional(Instant),
        stale: v.optional(v.boolean())
      })
    ),
    cadence: v.optional(str),
    periodEnd: v.optional(Instant),
    lastAttestedAt: optStr
  })
} as const satisfies Record<SubjectKind, v.GenericSchema>;

/** The subject kinds this kit draws. */
export const SUBJECT_KINDS = Object.keys(DETAIL) as SubjectKind[];

export const isSubjectKind = (kind: string): kind is SubjectKind => Object.hasOwn(DETAIL, kind);

export type SubjectReading =
  | { [K in SubjectKind]: { kind: K; detail: SubjectDetails[K] } }[SubjectKind]
  | { kind: "unknown"; detail: Record<string, unknown>; reason: string };

/**
 * A subject's detail, checked against its kind's shape: the typed detail, or why
 * it cannot be drawn by its kind (an unknown kind, or a detail that does not
 * match), with the raw detail to show instead.
 */
export function readSubject(subject: Pick<Subject, "kind" | "detail">): SubjectReading {
  const detail = subject.detail ?? {};
  if (!isSubjectKind(subject.kind)) {
    return {
      kind: "unknown",
      detail,
      reason: `This console does not know the subject kind “${subject.kind}”, so it shows the summary and the raw detail.`
    };
  }
  const parsed = v.safeParse(DETAIL[subject.kind], detail);
  if (!parsed.success) {
    return {
      kind: "unknown",
      detail,
      reason: `The ${subject.kind} detail does not match the contract (${issueText(parsed.issues).join("; ")}), so only the summary and the raw detail are shown.`
    };
  }
  return { kind: subject.kind, detail: parsed.output } as SubjectReading;
}

// ---------------------------------------------------------------- the rest of the contract

const ChecksSchema = v.optional(
  v.array(v.looseObject({ name: str, passed: v.optional(v.nullable(v.boolean()), null) })),
  []
);

const HaltSchema = v.looseObject({
  scope: v.optional(str, "capability"),
  target: str,
  control: v.optional(ControlRefSchema, { rowId: "", name: "" }),
  halted: v.optional(v.boolean(), false),
  safeStateChecks: ChecksSchema
});

const LoopSchema = v.optional(
  v.looseObject({ pending: v.optional(num, 0), overdue: v.optional(num, 0), raised7d: v.optional(v.array(num), []) }),
  {}
);

const SummarySchema = v.looseObject({
  loop: v.optional(v.looseObject({ in: LoopSchema, on: LoopSchema, over: LoopSchema }), {}),
  states: v.optional(v.record(str, num), {}),
  patterns: v.optional(v.record(str, num), {}),
  atRisk: v.optional(num, 0),
  overdue: v.optional(num, 0),
  halts: v.optional(v.array(HaltSchema), []),
  raised30d: v.optional(v.array(num), []),
  standing: v.optional(v.looseObject({ active: v.optional(num, 0), expiring: v.optional(num, 0) }), {}),
  unownedControls: v.optional(num, 0)
});

const QualitySchema = v.looseObject({
  window: v.optional(str, "P30D"),
  reviewTimes: v.optional(
    v.array(
      v.looseObject({
        control: str,
        buckets: v.optional(v.array(v.looseObject({ upTo: v.nullish(num), count: num })), [])
      })
    ),
    []
  ),
  reviewers: v.optional(v.array(v.looseObject({ subject: str, decisions: v.optional(num, 0) })), []),
  outcomes: v.optional(v.record(str, num), {}),
  signals: v.optional(v.array(v.looseObject({ control: str, signal: str, value: num })), [])
});

const RegistrySchema = v.looseObject({
  overseers: v.optional(
    v.array(
      v.looseObject({
        subject: str,
        roles: strs,
        qualified: v.optional(v.boolean(), false),
        attestations: v.optional(v.array(v.looseObject({ id: str, completedAt: v.optional(str, "") })), []),
        authority: v.optional(v.array(v.looseObject({ controls: strs, actions: v.optional(v.array(str)) })), [])
      })
    ),
    []
  ),
  owners: v.optional(v.array(v.looseObject({ control: str, accountable: str })), [])
});

const StandingSchema = v.looseObject({
  id: str,
  service: v.optional(str, ""),
  kind: v.optional(str, "pattern-approval"),
  eventId: v.optional(str, ""),
  control: v.optional(ControlRefSchema, { rowId: "", name: "" }),
  allows: v.optional(str, ""),
  grantedBy: v.optional(str, ""),
  grantedAt: v.optional(str, ""),
  state: v.optional(str, "ACTIVE")
});

const ControlSettingsSchema = v.looseObject({
  control: ControlRefSchema,
  pattern: str,
  flavor: v.optional(str, "human-domain-decision"),
  subjectKind: str,
  allowedActions: strs,
  routing: v.optional(v.looseObject({ queue: v.optional(str, ""), roles: strs }), {})
});

const ServiceInfoSchema = v.looseObject({
  service: str,
  contract: v.optional(num, 2),
  patterns: strs,
  devRoutes: v.optional(v.boolean(), false)
});

/** Reads `raw` with `schema`, or throws a ContractError naming what does not match. */
function read<T>(what: string, schema: v.GenericSchema, raw: unknown): T {
  const parsed = v.safeParse(schema, raw);
  if (!parsed.success) throw new ContractError(what, summaryOf(raw), raw, issueText(parsed.issues));
  return parsed.output as T;
}

/** A list read item by item: an item that does not match is left out rather than failing the list. */
function readList<T>(schema: v.GenericSchema, raw: unknown): T[] {
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((item) => {
    const parsed = v.safeParse(schema, item);
    return parsed.success ? [parsed.output as T] : [];
  });
}

export const readSummary = (raw: unknown) => read<Summary>("summary", SummarySchema, raw);
export const readQuality = (raw: unknown) => read<Quality>("quality", QualitySchema, raw);
export const readRegistry = (raw: unknown) => read<OverseerRegistry>("overseer registry", RegistrySchema, raw);
export const readServiceInfo = (raw: unknown) => read<ServiceInfo>("service info", ServiceInfoSchema, raw);
export const readHalts = (raw: unknown) => readList<Halt>(HaltSchema, raw);
export const readStanding = (raw: unknown) => readList<StandingDecision>(StandingSchema, raw);
export const readStandingOne = (raw: unknown) => read<StandingDecision>("standing decision", StandingSchema, raw);
export const readControls = (raw: unknown) => readList<ControlSettings>(ControlSettingsSchema, raw);

/** JSON from a server that does not read as the contract says. */
export class ContractError extends Error {
  constructor(
    readonly what: string,
    readonly summary: string,
    readonly raw: unknown,
    readonly issues: string[]
  ) {
    super(`The ${what} does not match the oversight contract: ${issues.slice(0, 3).join("; ")}`);
    this.name = "ContractError";
  }
}
