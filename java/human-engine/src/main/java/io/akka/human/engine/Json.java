package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.spi.HumanControl;
import io.akka.human.api.spi.Overseer;
import io.akka.human.api.spi.ProofRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract's JSON (spec/oversight.schema.json), as ordered maps a JSON writer serialises as they
 * are. Fields follow the schema's names and order; times are ISO 8601 UTC strings.
 */
public final class Json {

  private Json() {}

  public static Map<String, Object> event(HumanEvent e, Instant now) {
    return event(e, now, null);
  }

  /**
   * The event; {@code blockedBy} names the oversight-quality event that blocks settling decisions on it
   * ({@link Blocks}), or is null.
   */
  public static Map<String, Object> event(HumanEvent e, Instant now, String blockedBy) {
    var s = e.settings();
    var verdict = Verdicts.of(e);
    var m = new LinkedHashMap<String, Object>();
    m.put("id", e.id());
    m.put("state", e.state().name());
    m.put("pending", e.pending());
    m.put("shadow", e.shadow());
    m.put("strength", e.strength());
    m.put("pattern", s.pattern().id());
    m.put("patternName", s.patternName());
    m.put("control", control(s.control()));
    m.put("flavor", s.flavor());
    m.put("mode", s.mode());
    m.put("blocking", e.blocking());
    m.put("subject", subject(e));
    m.put("trigger", trigger(e.trigger()));
    m.put("ask", ask(e));
    m.put("environment", e.environment());
    m.put("owner", map("kind", "service", "service", e.service()));
    m.put("proposedBy", e.proposedBy());
    m.put("builtBy", e.builtBy());
    m.put("routing", routing(e));
    m.put("accountableOwner", s.accountableOwner());
    var holder = e.holderAt(now);
    m.put("holder", holder == null ? null : map("subject", holder.subject(), "roles", holder.roles(),
        "since", str(holder.since()), "expiresAt", str(holder.expiresAt())));
    m.put("claimTtl", s.claimTtl());
    m.put("sla", sla(e, now));
    m.put("escalation", map("level", e.level(),
        "chain", s.escalationChain().stream().map(ControlSettings.Queue::queue).toList()));
    m.put("allowedActions", names(s.allowedActions()));
    m.put("actionLabels", labels(s));
    var effects = new LinkedHashMap<String, Object>();
    e.effects().forEach((action, text) -> effects.put(action.name(), text));
    m.put("effects", effects);
    m.put("sticky", s.sticky().stream().map(Enum::name).toList());
    m.put("rationaleRequired", s.rationaleRequired());
    m.put("minimumReviewSeconds", s.minimumReviewSeconds());
    m.put("minimumReview", s.minimumReview());
    m.put("blockedBy", blockedBy);
    m.put("slaExtensions", e.slaExtensions().stream().map(x -> (Object) map("by", x.by(), "at", str(x.at()),
        "extra", x.extra(), "reason", x.reason())).toList());
    m.put("aiRecommendation", e.aiRecommendation());
    m.put("context", null);
    m.put("presentedAt", str(e.presentedAt()));
    m.put("claimedAt", str(e.claimedAt()));
    m.put("detectedAt", str(e.detectedAt()));
    m.put("closedAt", str(e.closedAt()));
    m.put("decisions", e.decisions().stream().map(Json::vote).toList());
    m.put("infoRequests", e.infoRequests().stream().map(r -> (Object) map("by", r.by(), "at", str(r.at()),
        "question", r.question())).toList());
    m.put("recused", e.recused());
    m.put("outcome", outcome(e.outcome()));
    m.put("verdictId", verdict == null ? null : verdict.id());
    m.put("conclusion", verdict == null ? null : verdict.conclusion());
    m.put("verdict", verdict == null ? null : verdict(verdict));
    m.put("related", e.related().stream().map(r -> (Object) map("eventId", r.eventId(), "relation", r.relation()))
        .toList());
    m.put("history", e.history().stream().map(h -> (Object) map("at", str(h.at()), "state", h.state().name(),
        "actor", h.actor(), "note", h.note())).toList());
    m.put("evidence", e.evidence());
    m.put("resumes", map("kind", e.resumes().kind(), "target", e.resumes().target(), "id", e.resumes().id()));
    m.put("simulated", e.simulated());
    m.put("now", str(now));
    return m;
  }

  /** The companion's row: what a queue lists. */
  public static Map<String, Object> row(HumanEvent e, Instant now) {
    var s = e.settings();
    var verdict = Verdicts.of(e);
    var m = new LinkedHashMap<String, Object>();
    m.put("id", e.id());
    m.put("state", e.state().name());
    m.put("pending", e.pending());
    m.put("pattern", s.pattern().id());
    m.put("patternName", s.patternName());
    m.put("control", control(s.control()));
    m.put("flavor", s.flavor());
    m.put("mode", s.mode());
    m.put("blocking", e.blocking());
    m.put("subject", map("kind", e.subject().kind().id(), "summary", e.subject().summary(),
        "service", e.subject().service(), "agent", e.subject().agent()));
    m.put("ask", map("question", e.ask().question()));
    m.put("queue", e.queue());
    m.put("reviewers", map("required", s.routing().required(), "voted", e.roundVotes().size()));
    var holder = e.holderAt(now);
    m.put("holder", holder == null ? null : map("subject", holder.subject(), "roles", holder.roles(),
        "since", str(holder.since()), "expiresAt", str(holder.expiresAt())));
    m.put("sla", map("state", e.slaState(now), "dueAt", str(e.dueAt()), "target", s.slaTarget()));
    m.put("aiRecommendation", e.aiRecommendation());
    m.put("shadow", e.shadow());
    m.put("detectedAt", str(e.detectedAt()));
    m.put("closedAt", str(e.closedAt()));
    m.put("outcome", outcome(e.outcome()));
    m.put("decidedBy", e.deciders());
    m.put("conclusion", verdict == null ? null : verdict.conclusion());
    m.put("simulated", e.simulated());
    m.put("now", str(now));
    m.put("owner", map("service", e.service()));
    return m;
  }

  public static Map<String, Object> verdict(Verdicts.Verdict v) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", v.id());
    m.put("eventId", v.eventId());
    m.put("control", control(v.control()));
    m.put("pattern", v.pattern().id());
    m.put("subjectKind", v.subjectKind().id());
    m.put("mechanism", "human");
    m.put("boundary", map("kind", "work-item", "ref", v.boundaryRef()));
    m.put("conclusion", v.conclusion());
    m.put("reasons", v.reasons());
    m.put("source", map("kind", v.source(), "service", v.service()));
    m.put("action", v.action() == null ? null : v.action().name());
    m.put("deciders", v.deciders());
    m.put("appliedAt", str(v.appliedAt()));
    m.put("at", str(v.at()));
    return m;
  }

  public static Map<String, Object> controlSettings(ControlSettings s) {
    var m = new LinkedHashMap<String, Object>();
    m.put("control", control(s.control()));
    m.put("pattern", s.pattern().id());
    m.put("flavor", s.flavor());
    m.put("subjectKind", s.subjectKind().id());
    m.put("blocking", s.patternBlocks());
    m.put("mode", s.mode());
    m.put("allowedActions", names(s.allowedActions()));
    m.put("actionLabels", labels(s));
    m.put("routing", routing(s, List.of()));
    m.put("accountableOwner", s.accountableOwner());
    m.put("slaTarget", s.slaTarget());
    m.put("atRiskAt", s.atRiskAt());
    m.put("claimTtl", s.claimTtl());
    if (s.timeoutBehaviour() != null) m.put("timeoutBehaviour", s.timeoutBehaviour());
    m.put("timeoutDefault", s.timeoutDefault() == null ? null : s.timeoutDefault().name());
    m.put("escalationChain", s.escalationChain().stream().map(q -> (Object) map("queue", q.queue(), "roles", q.roles(),
        "after", q.after())).toList());
    m.put("sticky", s.sticky().stream().map(Enum::name).toList());
    m.put("rationaleRequired", s.rationaleRequired());
    m.put("minimumReviewSeconds", s.minimumReviewSeconds());
    m.put("minimumReview", s.minimumReview());
    m.put("clockWhileSentBack", s.clockWhileSentBack());
    m.put("lateDecision", s.lateDecision());
    m.put("aiRecommendation", s.aiRecommendation());
    m.put("proofEvent", s.proofEvent());
    m.put("shadow", s.shadow());
    m.put("strength", s.strength());
    m.put("halt", s.halt().isEmpty() ? null : s.halt());
    m.put("blockDecisions", s.blockDecisions());
    return m;
  }

  public static Map<String, Object> standing(StandingDecision d, Instant now) {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", d.id());
    m.put("kind", d.kind());
    m.put("eventId", d.eventId());
    m.put("control", control(d.control()));
    m.put("matchKey", d.matchKey());
    m.put("allows", d.allows());
    m.put("grantedBy", d.grantedBy());
    m.put("grantedAt", str(d.grantedAt()));
    m.put("expiresAt", str(d.expiresAt()));
    m.put("state", d.stateAt(now));
    m.put("uses", d.usedBy().size());
    var conditions = new java.util.ArrayList<Object>(d.conditions());
    if (d.sessionId() != null) conditions.add(map("sessionId", d.sessionId()));
    m.put("conditions", conditions);
    m.put("revoked", d.revoked() == null ? null : map("by", d.revoked().by(), "at", str(d.revoked().at()),
        "reason", d.revoked().reason()));
    return m;
  }

  /**
   * A halt as the console shows it.
   *
   * @param checks the target's safe-state checks now
   * @param callsPerMinute the calls the target is making, or null when unknown
   */
  public static Map<String, Object> halt(HaltState h, Map<String, Boolean> checks, Double callsPerMinute, String service) {
    var m = new LinkedHashMap<String, Object>();
    m.put("scope", h.scope());
    m.put("target", h.target());
    m.put("control", control(h.control()));
    m.put("affectedCalls", h.affectedCalls());
    m.put("halted", h.halted());
    m.put("since", str(h.since()));
    m.put("by", h.by());
    m.put("eventId", h.eventId());
    m.put("liftEventId", h.liftEventId());
    m.put("callsPerMinute", callsPerMinute);
    m.put("safeStateChecks", checks(checks));
    m.put("service", service);
    return m;
  }

  /** The overseer registry as the contract shows it, with attestations judged on the day of {@code now}. */
  public static Map<String, Object> registry(List<Overseer> overseers, List<HumanControl> controls, Instant now) {
    var day = LocalDate.ofInstant(now, ZoneOffset.UTC);
    var people = overseers.stream().map(o -> {
      var m = new LinkedHashMap<String, Object>();
      m.put("subject", o.subject());
      m.put("name", o.name());
      m.put("roles", o.roles());
      m.put("groups", o.groups());
      m.put("reportsTo", o.reportsTo());
      m.put("authority", o.authority().stream().map(a -> (Object) map("controls", a.controls(),
          "actions", a.actions() == null ? List.of() : names(a.actions().stream().sorted().toList()))).toList());
      m.put("attestations", o.attestations().stream().map(a -> (Object) map("id", a.id(),
          "completedAt", a.completedAt().toString(), "expiresAt", a.expiresAt() == null ? null : a.expiresAt().toString(),
          "expired", !a.validOn(day))).toList());
      m.put("qualified", o.qualified());
      m.put("qualifiedReason", o.qualifiedReason());
      return (Object) m;
    }).toList();
    var owners = controls.stream().filter(c -> c.humanConfig().get("accountable_owner") != null)
        .map(c -> (Object) map("control", c.id(), "accountable", String.valueOf(c.humanConfig().get("accountable_owner"))))
        .toList();
    return map("overseers", people, "owners", owners);
  }

  public static Map<String, Object> proof(ProofRecord r) {
    return map("type", r.type(), "recordId", r.recordId(), "recordedAt", str(r.recordedAt()), "service", r.service(),
        "payload", r.payload());
  }

  public static Map<String, Object> error(Refusal r) {
    var m = new LinkedHashMap<String, Object>();
    m.put("error", r.error());
    if (r.kind() == Refusal.Kind.CONFLICT && r.state() != null) m.put("state", r.state());
    if (r.kind() == Refusal.Kind.INVALID) m.put("errors", r.errors());
    if (r.blockedBy() != null) m.put("blockedBy", r.blockedBy());
    return m;
  }

  // ---- parts

  static Map<String, Object> control(ControlSettings.ControlRef c) {
    return map("rowId", c.rowId(), "name", c.name(), "corpusRef", c.corpusRef(), "revision", c.revision());
  }

  private static Map<String, Object> subject(HumanEvent e) {
    var subject = e.subject();
    var m = new LinkedHashMap<String, Object>();
    m.put("kind", subject.kind().id());
    m.put("summary", subject.summary());
    m.put("service", subject.service());
    m.put("agent", subject.agent());
    m.put("component", subject.component());
    m.put("sessionId", subject.sessionId());
    m.put("flowId", subject.flowId());
    m.put("traceId", subject.traceId());
    m.put("workflowId", subject.workflowId());
    m.put("ref", subject.ref());
    m.put("detail", subject.detail());
    return m;
  }

  private static Map<String, Object> trigger(HumanEvent.Trigger t) {
    var m = new LinkedHashMap<String, Object>();
    m.put("detector", t.detector());
    m.put("rule", t.rule());
    m.put("reason", t.reason());
    m.put("values", t.values());
    m.put("conditions", t.conditions());
    m.put("detectedAt", str(t.detectedAt()));
    m.put("occurrences", t.occurrences());
    return m;
  }

  private static Map<String, Object> ask(HumanEvent e) {
    var checks = e.ask().checks().stream().map(c -> {
      var check = new LinkedHashMap<String, Object>();
      check.put("text", c.text());
      if (c.section() != null) check.put("section", c.section());
      return (Object) check;
    }).toList();
    return map("question", e.ask().question(), "checks", checks);
  }

  private static Map<String, Object> routing(HumanEvent e) {
    var m = routing(e.settings(), e.independentOf());
    m.put("queue", e.queue());
    m.put("roles", e.currentRoles());
    return m;
  }

  private static Map<String, Object> routing(ControlSettings s, List<String> independentOf) {
    var r = s.routing();
    var m = new LinkedHashMap<String, Object>();
    m.put("queue", r.queue());
    m.put("roles", r.roles());
    m.put("groups", r.groups());
    m.put("attestations", r.attestations());
    m.put("reviewMode", r.reviewMode());
    m.put("required", r.required());
    m.put("of", r.of());
    m.put("splitVote", r.splitVote());
    m.put("independence", r.independence());
    m.put("independentOf", independentOf);
    return m;
  }

  private static Map<String, Object> sla(HumanEvent e, Instant now) {
    var s = e.settings();
    var m = new LinkedHashMap<String, Object>();
    m.put("target", s.slaTarget());
    m.put("startedAt", str(e.startedAt()));
    m.put("dueAt", str(e.dueAt()));
    m.put("atRiskAt", s.atRiskAt());
    m.put("state", e.slaState(now));
    if (s.timeoutBehaviour() != null) m.put("timeoutBehaviour", s.timeoutBehaviour());
    m.put("default", s.timeoutDefault() == null ? null : s.timeoutDefault().name());
    m.put("lateDecision", s.lateDecision());
    m.put("clockWhileSentBack", s.clockWhileSentBack());
    return m;
  }

  private static Object vote(HumanEvent.Vote v) {
    var m = new LinkedHashMap<String, Object>();
    m.put("reviewer", reviewer(v.reviewer()));
    m.put("action", v.action().name());
    m.put("rationale", v.rationale());
    m.put("qualified", true);
    m.put("independent", true);
    m.put("edits", v.edits());
    m.put("sticky", v.sticky() == null ? null : v.sticky().name());
    if (v.replacement() != null) m.put("replacement", v.replacement());
    m.put("presentedAt", str(v.presentedAt()));
    m.put("submittedAt", str(v.submittedAt()));
    m.put("reviewSeconds", v.reviewSeconds());
    m.put("belowMinimum", v.belowMinimum());
    m.put("late", v.late());
    m.put("checks", v.checks());
    return m;
  }

  public static Map<String, Object> reviewer(io.akka.human.api.Reviewer r) {
    return map("subject", r.subject(), "name", r.name(), "roles", r.roles(), "identityProvider", r.identityProvider(),
        "rehearsal", r.rehearsal());
  }

  private static Map<String, Object> outcome(HumanEvent.Outcome o) {
    if (o == null) return null;
    var m = new LinkedHashMap<String, Object>();
    m.put("action", o.action() == null ? null : o.action().name());
    m.put("sticky", o.sticky() == null ? null : o.sticky().name());
    m.put("agreedWithAi", o.agreedWithAi());
    m.put("edits", o.edits());
    if (o.replacement() != null) m.put("replacement", o.replacement());
    m.put("conditions", o.conditions());
    m.put("standingDecisionId", o.standingDecisionId());
    m.put("slaOutcome", o.slaOutcome());
    m.put("appliedAt", str(o.appliedAt()));
    m.put("applied", o.applied());
    m.put("applyError", o.applyError());
    return m;
  }

  private static List<Object> checks(Map<String, Boolean> checks) {
    return checks.entrySet().stream().map(c -> (Object) map("name", c.getKey(), "passed", c.getValue())).toList();
  }

  private static Map<String, Object> labels(ControlSettings s) {
    var m = new LinkedHashMap<String, Object>();
    for (var action : s.allowedActions()) m.put(action.name(), s.label(action));
    return m;
  }

  private static List<String> names(List<Action> actions) {
    return actions.stream().map(Enum::name).toList();
  }

  static String str(Instant at) {
    return at == null ? null : at.toString();
  }

  /** An ordered map from alternating names and values; null values are kept. */
  static Map<String, Object> map(Object... namesAndValues) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < namesAndValues.length; i += 2) m.put((String) namesAndValues[i], namesAndValues[i + 1]);
    return m;
  }
}
