package io.akka.human.engine;

import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.at;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.ok;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.seconds;
import static io.akka.human.engine.Fx.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import io.akka.human.api.Reviewer;
import io.akka.human.api.SubjectKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One test per row of the contract's "Settling and verdicts" table, and the verdict's source. */
class VerdictsTest {

  private static void assertVerdict(HumanEvent e, String slaOutcome, String conclusion, List<String> reasons) {
    assertEquals(slaOutcome, e.outcome().slaOutcome());
    var verdict = Verdicts.of(e);
    assertEquals(conclusion, verdict.conclusion());
    assertEquals(reasons, verdict.reasons());
  }

  @Test
  void aPendingEventHasNoVerdict() {
    assertNull(Verdicts.of(raise(control("pt-0001"))));
  }

  @Test
  void decidedInTimeIsUpheld() {
    var e = decide(claim(raise(control("pt-0001")), TYLER, T0), TYLER, Action.DENY, seconds(15));
    assertVerdict(e, "COMPLETED", "upheld", List.of());
    var v = Verdicts.of(e);
    assertEquals("vd-" + e.id(), v.id());
    assertEquals(e.id(), v.eventId());
    assertEquals("system-owners · " + e.id(), v.boundaryRef());
    assertEquals(Pattern.ACTION_APPROVAL, v.pattern());
    assertEquals(SubjectKind.ACTION, v.subjectKind());
    assertEquals(Action.DENY, v.action());
    assertEquals(List.of(TYLER), v.deciders());
    assertEquals(seconds(15), v.at());
    assertNull(v.appliedAt());
    assertEquals("patterns", v.service());
  }

  @Test
  void decidedAfterTheDeadlineIsBreachedLate() {
    var e = decide(claim(raise(control("pt-0001")), TYLER, minutes(29)), TYLER, Action.APPROVE, minutes(31));
    assertVerdict(e, "COMPLETED", "breached", List.of("late"));
  }

  @Test
  void decidedUnderTheMinimumWithWarnIsBreachedBelowMinimum() {
    var s = Fx.custom("hand-off", "human-domain-decision", "conversation",
        "routing", Map.of("roles", List.of("duty-officer")), "minimum_review_seconds", 30);
    var e = decide(claim(raise(s), Fx.MOREAU, T0), Fx.MOREAU, Action.APPROVE, seconds(3));
    assertVerdict(e, "COMPLETED", "breached", List.of("below-minimum"));
  }

  @Test
  void aDefaultThatLetsTheSubjectProceedFailsOpen() {
    var s = Fx.custom("exception", "human-system-authorization", "exception",
        "routing", Map.of("roles", List.of("compliance-lead")), "sla_target", "P2D", "timeout_default", "APPROVE");
    assertVerdict(tick(raise(s), at(Duration.ofDays(2))), "TIMED_OUT", "breached", List.of("fail-open"));
  }

  @Test
  void aDenyingDefaultOrARaisedTimeoutIsInconclusive() {
    assertVerdict(tick(raise(control("pt-0004")), at(Duration.ofDays(2))), "TIMED_OUT", "inconclusive", List.of());
    assertVerdict(tick(raise(control("pt-0013")), at(Duration.ofDays(14))), "TIMED_OUT", "inconclusive", List.of());
  }

  @Test
  void escalateAtTheLastQueueIsAMissedDeadline() {
    var e = tick(tick(raise(control("pt-0010")), at(Duration.ofHours(8))), at(Duration.ofHours(16)));
    assertEquals(State.TIMED_OUT, e.state());
    assertVerdict(e, "TIMED_OUT", "breached", List.of("missed-deadline"));
  }

  @Test
  void abandonedAndWithdrawnAreInconclusive() {
    assertVerdict(tick(raise(control("pt-0009")), at(Duration.ofDays(3))), "ABANDONED", "inconclusive", List.of());
    var s = control("pt-0005");
    var e = raise(s, request(s).proposedBy("agent:matcher").build());
    var withdrawn = ok(e, Rules.withdraw(e, Reviewer.of("agent:matcher"), "", minutes(1)));
    assertVerdict(withdrawn, "ABANDONED", "inconclusive", List.of());
  }

  @Test
  void killedOrNeverAppliedIsAnError() {
    var killed = ok(raise(control("pt-0006")), Rules.kill(raise(control("pt-0006")), "dev", "", minutes(1)));
    assertVerdict(killed, "KILLED", "error", List.of("killed"));
    var decided = decide(raise(control("pt-0006")), TYLER, Action.APPROVE, minutes(1));
    var failed = ok(decided, Rules.applyFailed(decided, "the payment service is gone", minutes(2)));
    assertVerdict(failed, "COMPLETED", "error", List.of("not-applied"));
  }

  @Test
  void theSourceIsRuntimeRehearsalOrSimulated() {
    var runtime = decide(raise(control("pt-0006")), TYLER, Action.APPROVE, minutes(1));
    assertEquals("runtime", Verdicts.of(runtime).source());

    var e = raise(control("pt-0006"));
    var rehearsing = new Reviewer(TYLER, "Tyler Jewell", Fx.overseer(TYLER).roles(), "human-companion", true);
    var rehearsed = ok(e, Rules.decide(e, new Qualification.Actor(rehearsing, Fx.overseer(TYLER), true),
        Fx.decision(rehearsing, Action.APPROVE), Map.of(), minutes(1)));
    assertEquals(State.DECIDED, rehearsed.state());
    assertEquals("rehearsal", Verdicts.of(rehearsed).source());

    var s = control("pt-0006");
    var simulated = decide(raise(s, request(s).simulated(true).build()), TYLER, Action.APPROVE, minutes(1));
    assertEquals("simulated", Verdicts.of(simulated).source());

    var timedOut = tick(raise(control("pt-0001")), minutes(30));
    assertEquals("runtime", Verdicts.source(timedOut));
    assertEquals(List.of(), Verdicts.of(timedOut).deciders());
  }

  @Test
  void aStandingDecisionUpholds() {
    var e = raise(control("pt-0001"));
    var settled = ok(e, Rules.settleByStanding(e, "sd-9", Action.APPROVE, T0));
    assertVerdict(settled, "COMPLETED", "upheld", List.of());
    assertEquals(List.of("standing:sd-9"), Verdicts.of(settled).deciders());
  }
}
