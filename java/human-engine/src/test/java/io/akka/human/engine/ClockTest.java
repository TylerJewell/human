package io.akka.human.engine;

import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.OSEI;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClockTest {

  @Test
  void anEventTurnsAtRiskAtThreeQuartersOfItsWindow() {
    var e = raise(control("pt-0001"));
    assertEquals(List.of(), Rules.tick(e, seconds(1349)));
    assertEquals("running", e.slaState(seconds(1349)));
    assertEquals("at-risk", e.slaState(seconds(1350)));
    var atRisk = tick(e, seconds(1350));
    assertTrue(atRisk.atRiskNoted());
    assertTrue(atRisk.pending());
    assertEquals(minutes(30), atRisk.nextCheckAt());
    assertEquals(List.of(), Rules.tick(atRisk, seconds(1400)));
  }

  @Test
  void theNextCheckIsTheEarlierOfTheClaimLapsingAndTheWindow() {
    var e = claim(raise(control("pt-0002")), LIND, T0);
    assertEquals(minutes(30), e.nextCheckAt());
    var lapsed = Rules.tick(e, minutes(30));
    assertEquals(1, lapsed.size());
    var released = assertInstanceOf(HumanEventEvent.Released.class, lapsed.getFirst());
    assertEquals(HumanEvent.SYSTEM, released.by());
    var open = tick(e, minutes(30));
    assertEquals(State.OPEN, open.state());
    assertNull(open.holder());
    assertEquals(at(Duration.ofHours(3)), open.nextCheckAt());
  }

  @Test
  void resumeWithDefaultDenyTimesOutInconclusive() {
    var e = tick(raise(control("pt-0001")), minutes(30));
    assertEquals(State.TIMED_OUT, e.state());
    assertFalse(e.pending());
    assertEquals(Action.DENY, e.outcome().action());
    assertEquals(HumanEvent.TIMED_OUT, e.outcome().slaOutcome());
    assertEquals("RESUME_WITH_DEFAULT", e.outcome().timeoutBehaviour());
    assertEquals("missed", e.slaState(minutes(31)));
    assertEquals(Verdicts.INCONCLUSIVE, Verdicts.of(e).conclusion());
    assertNull(e.nextCheckAt());
    assertEquals(2, Rules.tick(raise(control("pt-0001")), minutes(30)).size());
  }

  @Test
  void resumeWithAnApprovingDefaultFailsOpen() {
    var s = Fx.custom("action-approval", "human-system-authorization", "action",
        "routing", Map.of("roles", List.of("ai-system-owner")), "sla_target", "PT30M", "timeout_default", "APPROVE");
    var e = tick(raise(s), minutes(30));
    assertEquals(Action.APPROVE, e.outcome().action());
    var verdict = Verdicts.of(e);
    assertEquals(Verdicts.BREACHED, verdict.conclusion());
    assertEquals(List.of("fail-open"), verdict.reasons());
  }

  @Test
  void raiseTimeoutClosesWithoutAnAction() {
    var e = tick(raise(control("pt-0013")), at(Duration.ofDays(14)));
    assertEquals(State.TIMED_OUT, e.state());
    assertNull(e.outcome().action());
    assertEquals("RAISE_TIMEOUT", e.outcome().timeoutBehaviour());
    assertEquals(Verdicts.INCONCLUSIVE, Verdicts.of(e).conclusion());
  }

  @Test
  void escalateMovesToTheNextQueueThenTimesOutAtTheLast() {
    var e = raise(control("pt-0002"));
    var escalated = tick(e, at(Duration.ofHours(4)));
    assertEquals(State.ESCALATED, escalated.state());
    assertEquals(1, escalated.level());
    assertEquals("head-of-operations", escalated.queue());
    assertEquals(List.of("head-of-operations"), escalated.currentRoles());
    assertEquals(at(Duration.ofHours(4)), escalated.startedAt());
    assertEquals(at(Duration.ofHours(8)), escalated.dueAt());
    assertFalse(escalated.atRiskNoted());
    assertEquals("Escalated to head-of-operations: the deadline passed", escalated.history().getLast().note());
    assertEquals(HumanEvent.SYSTEM, escalated.history().getLast().actor());

    var timedOut = tick(escalated, at(Duration.ofHours(8)));
    assertEquals(State.TIMED_OUT, timedOut.state());
    var verdict = Verdicts.of(timedOut);
    assertEquals(Verdicts.BREACHED, verdict.conclusion());
    assertEquals(List.of("missed-deadline"), verdict.reasons());
  }

  @Test
  void abandonClosesTheEventAbandoned() {
    var e = tick(raise(control("pt-0009")), at(Duration.ofDays(3)));
    assertEquals(State.ABANDONED, e.state());
    assertEquals(HumanEvent.ABANDONED, e.outcome().slaOutcome());
    assertEquals(Verdicts.INCONCLUSIVE, Verdicts.of(e).conclusion());
  }

  @Test
  void anEventWithoutADeadlineHasNoClock() {
    var e = raise(control("pt-0008"));
    assertNull(e.dueAt());
    assertNull(e.nextCheckAt());
    assertEquals("none", e.slaState(T0));
    assertEquals(List.of(), Rules.tick(e, at(Duration.ofDays(400))));
  }

  @Test
  void aPersonEscalatesToTheNextQueueWhoseRolesThenDecide() {
    var e = raise(control("pt-0001"));
    refused(Rules.escalate(e, actor(TYLER), " ", minutes(1)), 422);
    var escalated = ok(e, Rules.escalate(e, actor(TYLER), "Above my limit", minutes(1)));
    assertEquals(1, escalated.level());
    assertEquals(State.ESCALATED, escalated.state());
    assertEquals(minutes(31), escalated.dueAt());
    refused(Rules.claim(escalated, Fx.actor(TYLER, "ai-system-owner"), minutes(2)), 403);
    var claimed = claim(escalated, LIND, minutes(2));
    var released = ok(claimed, Rules.release(claimed, reviewer(LIND), minutes(3)));
    assertEquals(State.ESCALATED, released.state());
    var last = refused(Rules.escalate(escalated, actor(TYLER), "Further", minutes(2)), 409);
    assertEquals("the event is already with the last queue", last.error());

    var viaDecision = new Rules.DecisionRequest(reviewer(TYLER), Action.ESCALATE, "Above my limit", null, null, null,
        List.of(), null);
    assertEquals(State.ESCALATED, ok(e, Rules.decide(e, actor(TYLER), viaDecision, Map.of(), minutes(1))).state());
    var change = raise(control("pt-0003"));
    assertEquals(1, ok(change, Rules.escalate(change, Fx.actor(TYLER, "model-risk-owner"), "why", minutes(1))).level());
  }

  @Test
  void aPausedClockShiftsTheDeadlineByTheTimeSentBack() {
    var s = Fx.custom("change-authorization", "human-system-authorization", "change",
        "routing", Map.of("roles", List.of("model-risk-owner")), "sla_target", "P1D", "clock_while_sent_back", "PAUSED");
    var e = raise(s, request(s).proposedBy("agent:rollouts").build());
    var sentBack = ok(e, Rules.sendBack(e, actor(TYLER), "Add the offline eval", at(Duration.ofHours(1))));
    assertEquals(State.CHANGES_REQUESTED, sentBack.state());
    assertEquals("Add the offline eval", sentBack.requested());
    assertEquals("paused", sentBack.slaState(at(Duration.ofHours(2))));
    assertNull(sentBack.nextCheckAt());
    assertEquals(List.of(), Rules.tick(sentBack, at(Duration.ofDays(2))));
    assertFalse(sentBack.overdue(at(Duration.ofDays(2))));

    var resubmitted = ok(sentBack, Rules.resubmit(sentBack, Reviewer.of("agent:rollouts"), "Eval attached",
        at(Duration.ofHours(3))));
    assertEquals(State.OPEN, resubmitted.state());
    assertEquals(at(Duration.ofDays(1).plusHours(2)), resubmitted.dueAt());
    assertNull(resubmitted.pausedAt());
    assertNull(resubmitted.requested());
  }

  @Test
  void aRunningClockKeepsTheDeadlineWhileSentBack() {
    var s = control("pt-0003");
    var e = raise(s, request(s).proposedBy("agent:rollouts").build());
    var sentBack = ok(e, Rules.sendBack(e, actor(TYLER), "Add the offline eval", at(Duration.ofHours(1))));
    assertNull(sentBack.pausedAt());
    assertEquals("running", sentBack.slaState(at(Duration.ofHours(2))));
    var resubmitted = ok(sentBack, Rules.resubmit(sentBack, Reviewer.of("agent:rollouts"), null, at(Duration.ofHours(3))));
    assertEquals(at(Duration.ofDays(1)), resubmitted.dueAt());
    assertEquals(State.ESCALATED, tick(sentBack, at(Duration.ofDays(1))).state());
  }

  @Test
  void aDeadlineIsExtendedOnceByAtMostSevenDays() {
    var e = raise(control("pt-0001"));
    var extended = ok(e, Rules.extend(e, actor(TYLER), "PT1H", "Waiting for the invoice", minutes(5)));
    assertEquals(minutes(90), extended.dueAt());
    assertEquals(1, extended.slaExtensions().size());
    assertEquals("PT1H", extended.slaExtensions().getFirst().extra());
    assertEquals("the deadline has already been extended once",
        refused(Rules.extend(extended, actor(TYLER), "PT1H", "again", minutes(6)), 409).error());
    refused(Rules.extend(e, actor(TYLER), "P8D", "too long", minutes(5)), 422);
    refused(Rules.extend(e, actor(TYLER), "nope", "bad", minutes(5)), 422);
    refused(Rules.extend(e, actor(TYLER), "PT0S", "nothing", minutes(5)), 422);
    refused(Rules.extend(e, actor(TYLER), "PT1H", " ", minutes(5)), 422);
    assertEquals(minutes(30).plus(Duration.ofDays(7)),
        ok(e, Rules.extend(e, actor(TYLER), "P7D", "longest", minutes(5))).dueAt());
    refused(Rules.extend(raise(control("pt-0008")), actor(TYLER), "PT1H", "no deadline", minutes(5)), 422);
    refused(Rules.extend(e, actor(OSEI), "PT1H", "not mine", minutes(5)), 403);
  }

  @Test
  void theAccountableOwnerMayExtendWithoutTheQueuesRole() {
    var e = raise(control("pt-0002"));
    var byOwner = Fx.actor(TYLER, "head-of-operations");
    assertEquals(at(Duration.ofHours(5)), ok(e, Rules.extend(e, byOwner, "PT1H", "Busy week", minutes(1))).dueAt());
  }

  @Test
  void anOverdueEventIsOverdueUntilTheTickActs() {
    var e = claim(raise(control("pt-0011")), Fx.MOREAU, T0);
    assertTrue(e.overdue(minutes(5)));
    assertEquals("overdue", e.slaState(minutes(6)));
    var decided = decide(e, Fx.MOREAU, Action.DECLINE_SYSTEM, minutes(6));
    assertTrue(decided.decisions().getFirst().late());
    assertFalse(decided.overdue(minutes(7)));
  }
}
