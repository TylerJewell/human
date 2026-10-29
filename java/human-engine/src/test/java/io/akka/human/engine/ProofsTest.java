package io.akka.human.engine;

import static io.akka.human.engine.Fx.PATEL;
import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.actor;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.reviewer;
import static io.akka.human.engine.Fx.seconds;
import static io.akka.human.engine.Fx.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Subject;
import io.akka.human.api.spi.ProofRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofsTest {

  @Test
  void aDecisionRecordCarriesTheVotesTheVerdictAndTheSource() {
    var decided = decide(claim(raise(control("pt-0001")), TYLER, T0), TYLER, Action.APPROVE, seconds(15));
    var record = Proofs.decision(decided, seconds(16));
    assertEquals(ProofRecord.DECISION, record.type());
    assertEquals("pr-" + decided.id() + "-decision", record.recordId());
    assertEquals(seconds(16), record.recordedAt());
    assertEquals("patterns", record.service());
    var p = record.payload();
    for (var field : List.of("event_id", "control_id", "pattern", "reviewer_id", "action", "decided_at", "subject_ref",
        "source")) {
      assertTrue(p.containsKey(field), field);
    }
    assertEquals(decided.id(), p.get("event_id"));
    assertEquals("pt-0001", p.get("control_id"));
    assertEquals("action-approval", p.get("pattern"));
    assertEquals("action", p.get("subject_kind"));
    assertEquals(TYLER, p.get("reviewer_id"));
    assertEquals(Fx.overseer(TYLER).roles(), p.get("reviewer_roles"));
    assertEquals("APPROVE", p.get("action"));
    assertEquals(seconds(15).toString(), p.get("decided_at"));
    assertEquals(decided.id(), p.get("subject_ref"));
    assertEquals(15000L, p.get("review_duration_ms"));
    assertEquals(List.of(Map.of("reviewer_id", TYLER, "action", "APPROVE", "decided_at", seconds(15).toString())),
        p.get("votes"));
    assertEquals(false, p.get("late"));
    assertEquals("upheld", p.get("verdict"));
    assertEquals("runtime", p.get("source"));
    assertEquals(Fx.CATALOG.digest(), p.get("catalog_digest"));
  }

  @Test
  void nothingIsRecordedForAnEventClosedWithoutADecision() {
    assertNull(Proofs.decision(raise(control("pt-0001")), T0));
    var s = control("pt-0001");
    var e = raise(s, request(s).proposedBy("agent:payments").build());
    assertNull(Proofs.decision(ok(e, Rules.withdraw(e, Reviewer.of("agent:payments"), "", minutes(1))), minutes(1)));
  }

  @Test
  void aTimeoutDefaultAndAStandingDecisionNameWhatDecided() {
    var timedOut = tick(raise(control("pt-0001")), minutes(30));
    assertEquals("system:timeout", Proofs.decision(timedOut, minutes(30)).payload().get("reviewer_id"));
    assertEquals("DENY", Proofs.decision(timedOut, minutes(30)).payload().get("action"));
    var e = raise(control("pt-0001"));
    var settled = ok(e, Rules.settleByStanding(e, "sd-3", Action.APPROVE, T0));
    assertEquals("standing:sd-3", Proofs.decision(settled, T0).payload().get("reviewer_id"));
  }

  @Test
  void aHaltAndItsLiftAreRecordedWithTheirReference() {
    var halt = decide(raise(control("pt-0012")), PATEL, Action.HALT, minutes(1));
    var exercised = Proofs.haltExercised(halt, minutes(1));
    assertEquals(ProofRecord.HALT_EXERCISED, exercised.type());
    assertEquals(Proofs.haltRecordId(halt.id()), exercised.recordId());
    var p = exercised.payload();
    assertEquals("pt-0012", p.get("control_id"));
    assertEquals(PATEL, p.get("exerciser_id"));
    assertEquals("capability", p.get("scope"));
    assertEquals("eligibility-assessment", p.get("target"));
    assertEquals("all-runtime-operations", p.get("affected_calls"));
    assertEquals("Checked the subject.", p.get("reason"));
    assertEquals(minutes(1).toString(), p.get("halted_at"));
    assertEquals(false, p.get("safe_state_verified"));

    var s = control("pt-0012");
    var lift = raise(s, request(s).subject(Subject.system("Restart", "capability", "eligibility-assessment",
        "all-runtime-operations", true)).build());
    var checks = Map.of("backlog drained", true);
    var first = ok(lift, Rules.decide(lift, actor(TYLER), Fx.decision(reviewer(TYLER), Action.APPROVE), checks, minutes(2)));
    var lifted = ok(first, Rules.decide(first, actor(PATEL), Fx.decision(reviewer(PATEL), Action.APPROVE), checks,
        minutes(3)));
    var record = Proofs.haltLifted(lifted, exercised.recordId(), minutes(4));
    assertEquals(ProofRecord.HALT_LIFTED, record.type());
    assertEquals(Proofs.liftRecordId(lifted.id()), record.recordId());
    assertEquals(exercised.recordId(), record.payload().get("halt_ref"));
    assertEquals(List.of(TYLER, PATEL), record.payload().get("lifted_by"));
    assertEquals(List.of(Map.of("check", "backlog drained", "passed", true)), record.payload().get("safe_state_checks"));
    assertEquals(minutes(4).toString(), record.payload().get("lifted_at"));
    assertEquals("eligibility-assessment", Proofs.decision(lifted, minutes(4)).payload().get("subject_ref"));
  }
}
