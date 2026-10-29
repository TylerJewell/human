import {
  actionLabel,
  OversightError,
  parseDuration,
  rowOf,
  votesOf,
  type Decision,
  type DecisionInput,
  type EventsList,
  type EventsQuery,
  type EventWrite,
  type Halt,
  type HumanEvent,
  type OversightClient,
  type OverseerRegistry,
  type Quality,
  type Reviewer,
  type StandingDecision,
  type Summary
} from "@akka-human/kit";

// An oversight service held in memory, for the gallery: the spec's examples as
// its events, and each write applied the way the contract describes, closely
// enough to review the components without a server. Refusals answer the
// contract's statuses (403, 409, 422) so their messages can be seen too.

export interface MemoryData {
  events: HumanEvent[];
  registry: OverseerRegistry;
  halts: Halt[];
  standing: StandingDecision[];
  quality: Quality;
  summary: Summary;
}

const CLAIMABLE = new Set(["OPEN", "ESCALATED", "REOPENED"]);

export function createMemoryClient(data: MemoryData, reviewer: Reviewer): OversightClient {
  const events = new Map(data.events.map((e) => [e.id, structuredClone(e)]));
  const halts = structuredClone(data.halts);
  const standing = structuredClone(data.standing);
  let me = reviewer;
  const iso = (ms = Date.now()) => new Date(ms).toISOString();

  const get = (id: string) => {
    const e = events.get(id);
    if (!e) throw new OversightError(404, `No event ${id}`, { error: `No event ${id}` });
    return e;
  };
  const save = (e: HumanEvent) => {
    events.set(e.id, e);
    return answer(e);
  };
  /** An event as this service answers it: stamped with its time, which is this browser's. */
  const answer = (e: HumanEvent): HumanEvent => ({ ...structuredClone(e), now: iso(), clockOffset: 0 });
  const step = (e: HumanEvent, state: HumanEvent["state"], note: string, actor = me.subject) => {
    e.state = state;
    e.history = [...e.history, { at: iso(), state, actor, note }];
  };
  const refuse = (status: number, error: string, more: Record<string, unknown> = {}): never => {
    throw new OversightError(status, error, { error, ...more });
  };
  const overseer = () => data.registry.overseers.find((o) => o.subject === me.subject);
  const mayAct = (e: HumanEvent) => {
    if (!e.pending) refuse(409, "the event is closed", { state: e.state });
    const o = overseer();
    if (!o) refuse(403, `${me.subject} is not in the overseer registry`);
    if (!o!.qualified) refuse(403, `${o!.name ?? me.subject} is not qualified: ${o!.qualifiedReason ?? "no reason given"}`);
    if (!me.roles.some((r) => e.routing.roles.includes(r))) refuse(403, `none of ${me.roles.join(", ")} may decide events for ${e.routing.queue}`);
    if ((e.routing.independentOf ?? []).includes(me.subject)) refuse(403, "independence bars the proposer from deciding");
    if (e.holder && e.holder.subject !== me.subject && e.state !== "AWAITING_SECOND")
      refuse(409, "claimed by another reviewer", { state: e.state });
  };

  function decide(e: HumanEvent, body: DecisionInput) {
    mayAct(e);
    const errors: string[] = [];
    if (!e.allowedActions.includes(body.action)) errors.push(`${body.action} is not allowed on this event`);
    if (e.rationaleRequired && !body.rationale?.trim() && body.action !== "RECUSE") errors.push("a rationale is required");
    for (const c of body.checks ?? []) if (!e.ask.checks.some((k) => k.text === c)) errors.push(`“${c}” is not one of the event's checks`);
    const minimum = e.minimumReviewSeconds ?? 0;
    const heldByMe = e.holder?.subject === me.subject;
    if (minimum > 0 && !heldByMe && e.state !== "AWAITING_SECOND") refuse(409, "claim the event before deciding it", { state: e.state });
    if (votesOf(e).some((d) => d.reviewer.subject === me.subject)) refuse(409, "you have already voted on this event", { state: e.state });
    const from = Date.parse(e.holder?.since ?? body.presentedAt ?? iso());
    const reviewSeconds = Math.max(0, Math.round((Date.now() - from) / 1000));
    const below = reviewSeconds < minimum;
    if (below && (e.minimumReview ?? "ENFORCE") === "ENFORCE") errors.push(`decided after ${reviewSeconds} s, under the minimum review time of ${minimum} s`);
    if (errors.length) refuse(422, "the decision is invalid", { errors });

    if (body.action === "REQUEST_MORE_INFO") {
      e.infoRequests = [...(e.infoRequests ?? []), { by: me.subject, at: iso(), question: body.rationale ?? "" }];
      e.history = [...e.history, { at: iso(), state: e.state, actor: me.subject, note: `Asked: ${body.rationale ?? ""}` }];
      return;
    }
    if (body.action === "RECUSE") {
      e.recused = [...(e.recused ?? []), me.subject];
      e.holder = null;
      step(e, "OPEN", "Recused");
      return;
    }
    const due = e.sla.dueAt ? Date.parse(e.sla.dueAt) : undefined;
    const late = due !== undefined && Date.now() > due;
    const decision: Decision = {
      reviewer: { ...me, identityProvider: "playground-gallery", rehearsal: true },
      action: body.action,
      rationale: body.rationale,
      qualified: true,
      independent: true,
      edits: body.edits ?? null,
      sticky: body.sticky ?? null,
      presentedAt: body.presentedAt,
      submittedAt: iso(),
      reviewSeconds,
      belowMinimum: below,
      late,
      checks: body.checks ?? []
    };
    e.decisions = [...e.decisions, decision];
    e.holder = null;
    const votes = votesOf(e);
    const required = e.routing.required;
    if (votes.length < required) {
      step(e, "AWAITING_SECOND", `${body.action}, ${votes.length} of ${required}`);
      return;
    }
    const agreed = votes.every((v) => v.action === votes[0].action);
    const action = agreed ? votes[0].action : e.routing.splitVote === "ESCALATE" ? "ESCALATE" : "DENY";
    step(e, "DECIDED", required > 1 ? `${body.action}, ${votes.length} of ${required}` : body.action);
    const applied = `${actionLabel(action, e.actionLabels)} applied${body.edits ? ` with ${body.edits.summary}` : ""}.`;
    step(e, "APPLIED", "applied", "system");
    const conclusion = late || below ? "breached" : "upheld";
    e.pending = false;
    e.closedAt = iso();
    e.outcome = {
      action,
      sticky: body.sticky ?? null,
      agreedWithAi: null,
      edits: body.edits ?? null,
      conditions: (body.conditions ?? []).map((c) => ({ ...c, met: null })),
      standingDecisionId: null,
      slaOutcome: "COMPLETED",
      appliedAt: iso(),
      applied,
      applyError: null
    };
    e.conclusion = conclusion;
    e.verdictId = `vd-${e.id}`;
    e.verdict = {
      id: `vd-${e.id}`,
      eventId: e.id,
      control: e.control,
      pattern: e.pattern,
      subjectKind: e.subject.kind,
      mechanism: "human",
      boundary: { kind: "work-item", ref: `${e.routing.queue} · ${e.id}` },
      conclusion,
      reasons: late ? ["late"] : below ? ["below-minimum"] : [],
      source: { kind: "rehearsal", service: e.owner.service },
      action,
      deciders: votes.map((v) => v.reviewer.subject),
      appliedAt: iso(),
      at: iso()
    };
    if (body.sticky === "APPROVE_FOR_PATTERN" || body.sticky === "DENY_FOR_PATTERN") {
      const id = `sd-${standing.length + 1}`;
      e.outcome.standingDecisionId = id;
      standing.unshift({
        id,
        kind: body.sticky === "APPROVE_FOR_PATTERN" ? "pattern-approval" : "pattern-denial",
        eventId: e.id,
        control: e.control,
        matchKey: null,
        allows: e.subject.summary,
        grantedBy: me.subject,
        grantedAt: iso(),
        expiresAt: iso(Date.now() + 30 * 86_400_000),
        state: "ACTIVE",
        uses: 0,
        conditions: [],
        revoked: null
      });
    }
  }

  function apply(e: HumanEvent, w: EventWrite) {
    switch (w.kind) {
      case "claim":
        mayAct(e);
        if (!CLAIMABLE.has(e.state) || e.holder) refuse(409, "the event cannot be claimed now", { state: e.state });
        e.holder = { subject: me.subject, roles: me.roles, since: iso(), expiresAt: iso(Date.now() + (parseDuration(e.claimTtl) ?? 1_800_000)) };
        e.claimedAt = iso();
        step(e, "CLAIMED", "Claimed");
        return;
      case "release":
        if (e.holder?.subject !== me.subject) refuse(409, "you do not hold this event", { state: e.state });
        e.holder = null;
        step(e, "OPEN", "Released to the queue");
        return;
      case "decision":
        decide(e, w.body);
        return;
      case "escalate": {
        mayAct(e);
        const chain = e.escalation?.chain ?? [];
        const level = (e.escalation?.level ?? 0) + 1;
        if (level >= chain.length) refuse(409, "there is no next tier", { state: e.state });
        e.escalation = { level, chain };
        e.routing = { ...e.routing, queue: chain[level] };
        e.holder = null;
        step(e, "ESCALATED", `To ${chain[level]}: ${w.reason}`);
        return;
      }
      case "send-back":
        mayAct(e);
        e.holder = null;
        step(e, "CHANGES_REQUESTED", w.requested);
        return;
      case "resubmit":
        step(e, "OPEN", w.note ?? "Resubmitted");
        return;
      case "withdraw":
        step(e, "WITHDRAWN", w.reason);
        e.pending = false;
        e.closedAt = iso();
        e.outcome = { action: null, slaOutcome: "ABANDONED" };
        e.conclusion = "inconclusive";
        return;
      case "extend": {
        if ((e.slaExtensions ?? []).length) refuse(409, "the deadline was already extended once", { state: e.state });
        const extra = parseDuration(w.extra) ?? 0;
        e.slaExtensions = [{ by: me.subject, at: iso(), extra: w.extra, reason: w.reason }];
        if (e.sla.dueAt) e.sla = { ...e.sla, dueAt: iso(Date.parse(e.sla.dueAt) + extra) };
        e.history = [...e.history, { at: iso(), state: e.state, actor: me.subject, note: `Deadline extended by ${w.extra}` }];
        return;
      }
      case "reopen": {
        if (e.pending) refuse(409, "only a closed event can be reopened", { state: e.state });
        const again: HumanEvent = structuredClone(e);
        again.id = `${e.id}-r${(e.related ?? []).length + 1}`;
        Object.assign(again, { pending: true, closedAt: null, outcome: null, conclusion: null, verdict: null, verdictId: null, holder: null });
        again.history = [...e.history];
        step(again, "OPEN", `Reopened from ${e.id}: ${w.reason}`);
        events.set(again.id, again);
        e.related = [...(e.related ?? []), { eventId: again.id, relation: "reopened-as" }];
        step(e, "REOPENED", w.reason);
        return;
      }
    }
  }

  const list = (query: EventsQuery = {}): EventsList => {
    const pending = query.state !== "resolved";
    const rows = [...events.values()]
      .filter((e) => (pending ? e.pending : !e.pending))
      .filter((e) => !query.pattern || e.pattern === query.pattern)
      .filter((e) => !query.subject || e.subject.kind === query.subject)
      .filter((e) => !query.routable || me.roles.some((r) => e.routing.roles.includes(r)))
      .filter((e) => !query.q || `${e.ask.question} ${e.subject.summary} ${e.id}`.toLowerCase().includes(query.q.toLowerCase()))
      .map((e) => rowOf(answer(e)));
    return { rows, total: rows.length, next: null, unreadable: 0 };
  };

  const write = async (id: string, w: EventWrite) => {
    const e = structuredClone(get(id));
    apply(e, w);
    return save(e);
  };

  const unsupported = () => Promise.reject(new OversightError(404, "The gallery does not take this call."));

  return {
    mode: "service",
    baseUrl: "memory:",
    events: async (query) => list(query),
    event: async (id) => answer(get(id)),
    write,
    claim: (id) => write(id, { kind: "claim" }),
    release: (id) => write(id, { kind: "release" }),
    decide: (id, body) => write(id, { kind: "decision", body }),
    escalate: (id, reason) => write(id, { kind: "escalate", reason }),
    sendBack: (id, requested) => write(id, { kind: "send-back", requested }),
    resubmit: (id, note) => write(id, { kind: "resubmit", note }),
    withdraw: (id, reason) => write(id, { kind: "withdraw", reason }),
    extend: (id, extra, reason) => write(id, { kind: "extend", extra, reason }),
    reopen: (id, reason) => write(id, { kind: "reopen", reason }),
    summary: async () => structuredClone(data.summary),
    quality: async () => structuredClone(data.quality),
    verdicts: async () => [...events.values()].flatMap((e) => (e.verdict ? [e.verdict] : [])),
    halts: async () => structuredClone(halts),
    standing: async () => structuredClone(standing),
    revokeStanding: async (id, reason) => {
      const s = standing.find((x) => x.id === id);
      if (!s) return refuse(404, `No standing decision ${id}`);
      Object.assign(s, { state: "REVOKED", revoked: { by: me.subject, at: iso(), reason } });
      return structuredClone(s);
    },
    registry: async () => structuredClone(data.registry),
    controls: async () => [],
    halt: async (control, lift, reason) => {
      const h = halts.find((x) => x.control.rowId === control);
      if (!h) return refuse(404, `No halt on ${control}`);
      if (!lift) Object.assign(h, { halted: true, since: iso(), by: me.subject });
      const source = [...events.values()].find((e) => e.subject.kind === "system");
      const e: HumanEvent = structuredClone(source ?? data.events[0]);
      e.id = `he-halt-${Date.now()}`;
      e.history = [{ at: iso(), state: "OPEN", actor: me.subject, note: reason }];
      e.decisions = [];
      if (lift) h.liftEventId = e.id;
      return save(e);
    },
    flag: unsupported,
    contest: unsupported,
    override: unsupported,
    reviewer: async () => me,
    setReviewer: async (r) => (me = r)
  };
}
