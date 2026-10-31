package io.akka.human.engine;

import static io.akka.human.engine.Fx.DEVOS;
import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.actor;
import static io.akka.human.engine.Fx.at;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.refused;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.reviewer;
import static io.akka.human.engine.Fx.seconds;
import static io.akka.human.engine.Fx.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import io.akka.human.api.Resume;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LifecycleTest {

  private static HumanEvent change() {
    var s = control("pt-0003");
    return raise(s, request(s).proposedBy("agent:rollouts").build());
  }

  @Test
  void aSendBackNeedsAProposerAndWhatToChange() {
    var s = control("pt-0003");
    var noProposer = raise(s);
    assertEquals("there is no proposer to send this back to",
        refused(Rules.sendBack(noProposer, actor(TYLER), "More evals", minutes(1)), 422).error());
    refused(Rules.sendBack(change(), actor(TYLER), " ", minutes(1)), 422);
    refused(Rules.sendBack(raise(control("pt-0001")), actor(TYLER), "x", minutes(1)), 422);
  }

  @Test
  void onlyTheProposerResubmitsAndANewRoundStarts() {
    var voted = decide(change(), TYLER, Action.APPROVE, minutes(1));
    var sendBack = new Rules.DecisionRequest(reviewer(DEVOS), Action.SEND_BACK, "Add the offline eval", null, null, null,
        List.of(), null);
    var sentBack = ok(voted, Rules.decide(voted, actor(DEVOS), sendBack, Map.of(), minutes(2)));
    assertEquals(State.CHANGES_REQUESTED, sentBack.state());
    assertTrue(sentBack.pending());
    assertEquals("the event is with its proposer for changes",
        refused(Rules.claim(sentBack, actor(DEVOS), minutes(3)), 409).error());
    refused(Rules.decide(sentBack, actor(DEVOS), Fx.decision(reviewer(DEVOS), Action.APPROVE), Map.of(), minutes(3)), 409);
    assertFalse(Rules.routable(sentBack, actor(DEVOS), minutes(3)));

    refused(Rules.resubmit(sentBack, reviewer(TYLER), "", minutes(4)), 403);
    refused(Rules.resubmit(change(), Reviewer.of("agent:rollouts"), "", minutes(4)), 409);
    var resubmitted = ok(sentBack, Rules.resubmit(sentBack, Reviewer.of("agent:rollouts"), "Eval attached", minutes(4)));
    assertEquals(State.OPEN, resubmitted.state());
    assertEquals(List.of(), resubmitted.roundVotes());
    assertEquals(State.AWAITING_SECOND, decide(resubmitted, TYLER, Action.APPROVE, minutes(5)).state());
    assertEquals("Resubmitted: Eval attached", resubmitted.history().getLast().note());
  }

  @Test
  void theProposerOrAnAdminWithdraws() {
    var s = control("pt-0001");
    var e = raise(s, request(s).proposedBy("agent:payments").build());
    refused(Rules.withdraw(e, reviewer(TYLER), "no", minutes(1)), 403);
    var withdrawn = ok(e, Rules.withdraw(e, Reviewer.of("agent:payments"), "Paid by other means", minutes(1)));
    assertEquals(State.WITHDRAWN, withdrawn.state());
    assertFalse(withdrawn.pending());
    assertEquals(HumanEvent.ABANDONED, withdrawn.outcome().slaOutcome());
    assertNull(withdrawn.outcome().action());
    assertEquals(minutes(1), withdrawn.closedAt());
    assertEquals("Withdrawn: Paid by other means", withdrawn.history().getLast().note());
    assertEquals(Verdicts.INCONCLUSIVE, Verdicts.of(withdrawn).conclusion());
    refused(Rules.withdraw(withdrawn, Reviewer.of("agent:payments"), "", minutes(2)), 409);
    assertEquals(State.WITHDRAWN, ok(e, Rules.withdraw(e, Reviewer.of("ops", "admin"), "", minutes(1))).state());
  }

  @Test
  void aSupersededEventClosesWithoutAPerson() {
    var superseded = ok(raise(control("pt-0005")), Rules.supersede(raise(control("pt-0005")), "The records merged",
        minutes(1)));
    assertEquals(State.WITHDRAWN, superseded.state());
    assertEquals("system:superseded", superseded.history().getLast().actor());
    assertEquals(Verdicts.INCONCLUSIVE, Verdicts.of(superseded).conclusion());
    refused(Rules.supersede(superseded, "again", minutes(2)), 409);
  }

  @Test
  void theAccountableOwnerReopensAClosedEvent() {
    var decided = decide(claim(raise(control("pt-0001")), TYLER, T0), TYLER, Action.APPROVE, seconds(15));
    refused(Rules.reopen(raise(control("pt-0001")), Reviewer.of("o", "ai-system-owner"), "why", "he-new", minutes(1)), 409);
    refused(Rules.reopen(decided, reviewer(Fx.CHEN), "why", "he-new", minutes(1)), 403);
    refused(Rules.reopen(decided, Reviewer.of("o", "ai-system-owner"), " ", "he-new", minutes(1)), 422);
    var reopened = ok(decided, Rules.reopen(decided, Reviewer.of("o", "ai-system-owner"), "New facts", "he-new",
        minutes(1)));
    assertEquals(State.REOPENED, reopened.state());
    assertFalse(reopened.pending());
    assertEquals(List.of(new HumanEvent.Related("he-new", "reopened-as")), reopened.related());
    assertEquals(Verdicts.UPHELD, Verdicts.of(reopened).conclusion());
    refused(Rules.reopen(reopened, Reviewer.of("o", "ai-system-owner"), "again", "he-new-2", minutes(2)), 409);
  }

  @Test
  void aKilledEventIsAnError() {
    var killed = ok(raise(control("pt-0001")), Rules.kill(raise(control("pt-0001")), "dev", "reset", minutes(1)));
    assertEquals(State.KILLED, killed.state());
    assertEquals(HumanEvent.KILLED, killed.outcome().slaOutcome());
    var verdict = Verdicts.of(killed);
    assertEquals(Verdicts.ERROR, verdict.conclusion());
    assertEquals(List.of("killed"), verdict.reasons());
    refused(Rules.kill(killed, "dev", "again", minutes(2)), 409);
  }

  @Test
  void applyingIsRecordedOnce() {
    var decided = decide(claim(raise(control("pt-0001")), TYLER, T0), TYLER, Action.APPROVE, seconds(15));
    refused(Rules.applied(raise(control("pt-0001")), "x", minutes(1)), 409);
    var applied = ok(decided, Rules.applied(decided, "Resumed payment pay-1", minutes(1)));
    assertEquals(State.APPLIED, applied.state());
    assertEquals("Resumed payment pay-1", applied.outcome().applied());
    assertEquals(minutes(1), applied.outcome().appliedAt());
    assertTrue(applied.outcome().delivered());
    assertEquals(List.of(), Rules.applied(applied, "again", minutes(2)).value());
    assertEquals(List.of(), Rules.applyFailed(applied, "late failure", minutes(2)).value());
    var verdict = Verdicts.of(applied);
    assertEquals(minutes(1), verdict.appliedAt());
    assertEquals(minutes(1), verdict.at());

    var failed = ok(decided, Rules.applyFailed(decided, "workflow gone", minutes(1)));
    assertEquals(State.DECIDED, failed.state());
    assertEquals("workflow gone", failed.outcome().applyError());
    assertEquals(Verdicts.ERROR, Verdicts.of(failed).conclusion());
    assertEquals(List.of("not-applied"), Verdicts.of(failed).reasons());

    var timedOut = tick(raise(control("pt-0001")), minutes(30));
    var told = ok(timedOut, Rules.applied(timedOut, "Told the workflow", minutes(31)));
    assertEquals(State.TIMED_OUT, told.state());
    assertNotNull(told.outcome().appliedAt());
  }

  @Test
  void raisingAgainAddsAnOccurrence() {
    var e = raise(control("pt-0010"));
    var latest = Raise.trigger(control("pt-0010"), new Trigger("monitor", "referral-share < 20", "Fell again",
        List.of(new Trigger.Value("referral-share", 11, 20)), List.of()), minutes(10));
    var repeated = ok(e, Rules.repeat(e, latest, minutes(10)));
    assertEquals(2, repeated.trigger().occurrences());
    assertEquals(T0, repeated.trigger().detectedAt());
    assertEquals("Fell again", repeated.trigger().reason());
    assertEquals("Raised again (2 occurrences)", repeated.history().getLast().note());
    var closed = decide(repeated, DEVOS, Action.APPROVE, minutes(11));
    refused(Rules.repeat(closed, latest, minutes(12)), 409);
  }

  @Test
  void askingForMoreKeepsTheEventPending() {
    var e = raise(control("pt-0001"));
    var asked = ok(e, Rules.requestMoreInfo(e, actor(TYLER), "Which purchase order?", minutes(1)));
    assertEquals(State.OPEN, asked.state());
    assertTrue(asked.pending());
    assertEquals(List.of(new HumanEvent.InfoRequest(TYLER, minutes(1), "Which purchase order?")), asked.infoRequests());
    var viaDecision = new Rules.DecisionRequest(reviewer(TYLER), Action.REQUEST_MORE_INFO, "Which order?", null, null,
        null, List.of(), null);
    assertEquals(1, ok(e, Rules.decide(e, actor(TYLER), viaDecision, Map.of(), minutes(1))).infoRequests().size());
    refused(Rules.requestMoreInfo(e, actor(TYLER), " ", minutes(1)), 422);
    refused(Rules.requestMoreInfo(raise(control("pt-0003")), actor(TYLER), "why?", minutes(1)), 422);
  }

  @Test
  void aShadowOrRecordControlRecordsWithoutRouting() {
    var shadow = raise(Fx.custom("triage", "human-monitoring", "behaviour",
        "routing", Map.of("roles", List.of("model-risk-owner")), "sla_target", "PT8H", "shadow", true));
    assertEquals(State.DETECTED, shadow.state());
    assertTrue(shadow.shadow());
    assertFalse(shadow.blocking());
    assertNull(shadow.nextCheckAt());
    assertEquals(List.of(), Rules.tick(shadow, at(Duration.ofDays(1))));
    refused(Rules.claim(shadow, actor(DEVOS), minutes(1)), 409);
    assertEquals("Recorded without routing (shadow)", shadow.history().getFirst().note());

    var record = raise(Fx.custom("action-approval", "human-system-authorization", "action",
        "routing", Map.of("roles", List.of("ai-system-owner")), "strength", Map.of("local", "record")));
    assertEquals(State.DETECTED, record.state());
    assertEquals("record", record.strength());

    var warn = raise(Fx.custom("action-approval", "human-system-authorization", "action",
        "routing", Map.of("roles", List.of("ai-system-owner")), "strength", Map.of("local", "warn")));
    assertEquals(State.OPEN, warn.state());
    assertFalse(warn.blocking());
  }

  @Test
  void aRaiseIsCheckedAgainstTheControlsPatternAndSubjectKind() {
    var s = control("pt-0001");
    assertNull(Raise.check(s, request(s).build()));
    var wrongPattern = io.akka.human.api.RaiseRequest.of(Pattern.TRIAGE, "q").control("pt-0001")
        .subject(Fx.subjectFor(io.akka.human.api.SubjectKind.ACTION)).build();
    assertEquals(422, Raise.check(s, wrongPattern).status());
    var wrongKind = io.akka.human.api.RaiseRequest.of(null, "q").control("pt-0001")
        .subject(Subject.output("x", "y")).build();
    assertTrue(Raise.check(s, wrongKind).error().contains("judges a action subject"));
  }

  @Test
  void aRaiseKeepsWhatTheServiceSaid() {
    var s = control("pt-0001");
    var e = raise(s, request(s).dedupeKey("pay-3141").matchKey("pay:S-77").resumes(Resume.workflow("payment", "pay-3141"))
        .effect(Action.APPROVE, "The payment is sent").evidence("invoice", "inv-9").related("he-0", "x-follows")
        .simulated(true).trigger(new Trigger(null, null, "The agent asked to pay 4,200 EUR.",
            List.of(new Trigger.Value("amount", 4200, 1000)), List.of(new Trigger.Condition("amount > 1000", true))))
        .build());
    assertEquals("pay-3141", e.dedupeKey());
    assertEquals("pay:S-77", e.matchKey());
    assertEquals(Resume.workflow("payment", "pay-3141"), e.resumes());
    assertEquals("The payment is sent", e.effects().get(Action.APPROVE));
    assertEquals("inv-9", e.evidence().getFirst().get("ref"));
    assertEquals(List.of(new HumanEvent.Related("he-0", "x-follows")), e.related());
    assertTrue(e.simulated());
    assertEquals("tool-policy", e.trigger().detector());
    assertEquals("pay.amount > 1000", e.trigger().rule());
    assertEquals(4200, e.trigger().values().getFirst().get("value"));
    assertEquals(true, e.trigger().conditions().getFirst().get("met"));
  }
}
