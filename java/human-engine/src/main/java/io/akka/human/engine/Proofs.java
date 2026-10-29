package io.akka.human.engine;

import io.akka.human.api.spi.ProofRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The proof records Verify's audit taxonomy names, with their snake_case payloads. Each record id is
 * derived from its event, so appending a record twice keeps one.
 */
public final class Proofs {

  private Proofs() {}

  public static String decisionRecordId(HumanEvent e) {
    return "pr-" + e.id() + "-decision";
  }

  public static String haltRecordId(String eventId) {
    return "pr-" + eventId + "-halt";
  }

  public static String liftRecordId(String eventId) {
    return "pr-" + eventId + "-lift";
  }

  /** HumanOversightDecisionRecordedV2 for an event closed with an action; null when nothing was decided. */
  public static ProofRecord decision(HumanEvent e, Instant recordedAt) {
    var o = e.outcome();
    if (o == null || o.action() == null) return null;
    var verdict = Verdicts.of(e);
    var vote = e.settlingVote();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("event_id", e.id());
    payload.put("control_id", e.settings().control().rowId());
    payload.put("pattern", e.settings().pattern().id());
    payload.put("subject_kind", e.subject().kind().id());
    payload.put("reviewer_id", vote != null ? vote.reviewer().subject()
        : o.byStanding() ? "standing:" + o.standingDecisionId() : "system:timeout");
    payload.put("reviewer_roles", vote != null ? vote.reviewer().roles() : List.of());
    payload.put("action", o.action().name());
    if (o.sticky() != null) payload.put("sticky", o.sticky().name());
    payload.put("decided_at", e.closedAt().toString());
    payload.put("subject_ref", e.subjectRef());
    payload.put("rationale", o.rationale() == null ? "" : o.rationale());
    if (vote != null) payload.put("review_duration_ms", Duration.between(vote.presentedAt(), vote.submittedAt()).toMillis());
    payload.put("votes", e.roundVotes().stream().map(v -> (Object) Map.of(
        "reviewer_id", v.reviewer().subject(), "action", v.action().name(), "decided_at", v.submittedAt().toString()))
        .toList());
    payload.put("late", e.roundVotes().stream().anyMatch(HumanEvent.Vote::late));
    payload.put("verdict", verdict == null ? null : verdict.conclusion());
    payload.put("source", Verdicts.source(e));
    payload.put("catalog_digest", e.catalogDigest());
    return new ProofRecord(ProofRecord.DECISION, decisionRecordId(e), recordedAt, e.service(), payload);
  }

  public static ProofRecord haltExercised(HumanEvent e, Instant at) {
    var vote = e.settlingVote();
    var payload = new LinkedHashMap<String, Object>();
    payload.put("control_id", e.settings().control().rowId());
    payload.put("exerciser_id", vote == null ? HumanEvent.SYSTEM : vote.reviewer().subject());
    payload.put("exerciser_roles", vote == null ? List.of() : vote.reviewer().roles());
    payload.put("scope", e.settings().haltScope());
    payload.put("target", e.haltTarget());
    payload.put("affected_calls", e.settings().haltAffectedCalls());
    payload.put("reason", e.outcome() == null ? "" : e.outcome().rationale());
    payload.put("halted_at", e.closedAt().toString());
    payload.put("safe_state_verified", false);
    payload.put("event_id", e.id());
    return new ProofRecord(ProofRecord.HALT_EXERCISED, haltRecordId(e.id()), at, e.service(), payload);
  }

  /** HaltLifted for an approved restart; {@code haltRef} is the HaltExercised record it ends. */
  @SuppressWarnings("unchecked")
  public static ProofRecord haltLifted(HumanEvent e, String haltRef, Instant at) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("control_id", e.settings().control().rowId());
    payload.put("halt_ref", haltRef);
    payload.put("lifted_by", e.deciders());
    var checks = e.subject().detail().get("safeStateChecks") instanceof List<?> list ? list : List.of();
    payload.put("safe_state_checks", checks.stream().map(c -> {
      var check = (Map<String, Object>) c;
      return (Object) Map.of("check", String.valueOf(check.get("name")), "passed", Boolean.TRUE.equals(check.get("passed")));
    }).toList());
    payload.put("lifted_at", at.toString());
    payload.put("event_id", e.id());
    return new ProofRecord(ProofRecord.HALT_LIFTED, liftRecordId(e.id()), at, e.service(), payload);
  }
}
