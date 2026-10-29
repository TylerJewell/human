package io.akka.human.engine;

import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.PATEL;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Sticky;
import io.akka.human.api.Subject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StandingAndHaltTest {

  private static Rules.DecisionRequest sticky(String subject, Action action, Sticky sticky) {
    return new Rules.DecisionRequest(reviewer(subject), action, "Known supplier", null, sticky, null, List.of(), null);
  }

  private static HumanEvent payment(String matchKey) {
    var s = control("pt-0001");
    return raise(s, request(s).matchKey(matchKey).build());
  }

  @Test
  void approveForPatternCreatesAStandingDecisionOnTheMatchKey() {
    var e = claim(payment("pay:S-77"), TYLER, T0);
    var decided = ok(e, Rules.decide(e, actor(TYLER), sticky(TYLER, Action.APPROVE, Sticky.APPROVE_FOR_PATTERN), Map.of(),
        seconds(15)));
    assertEquals("sd-" + e.id(), decided.outcome().standingDecisionId());
    assertEquals(Sticky.APPROVE_FOR_PATTERN, decided.outcome().sticky());

    var standing = StandingDecision.from(decided);
    assertEquals("sd-" + e.id(), standing.id());
    assertEquals("pattern-approval", standing.kind());
    assertEquals("pay:S-77", standing.matchKey());
    assertEquals(TYLER, standing.grantedBy());
    assertEquals(seconds(15), standing.grantedAt());
    assertEquals(seconds(15).plus(Duration.ofDays(30)), standing.expiresAt());
    assertEquals(Action.APPROVE, standing.action());
    assertTrue(standing.allows().contains("Pay S-77"));

    assertTrue(standing.matches("pt-0001", "pay:S-77", null, at(Duration.ofDays(1))));
    assertFalse(standing.matches("pt-0001", "pay:S-78", null, at(Duration.ofDays(1))));
    assertFalse(standing.matches("pt-0002", "pay:S-77", null, at(Duration.ofDays(1))));
    assertEquals("ACTIVE", standing.stateAt(at(Duration.ofDays(1))));
    assertEquals("EXPIRING", standing.stateAt(at(Duration.ofDays(24))));
    assertEquals("EXPIRED", standing.stateAt(at(Duration.ofDays(31))));
    assertFalse(standing.matches("pt-0001", "pay:S-77", null, at(Duration.ofDays(31))));

    var used = standing.usedFor("he-2").usedFor("he-2").usedFor("he-3");
    assertEquals(List.of("he-2", "he-3"), used.usedBy());
    var revoked = used.revokedBy(TYLER, at(Duration.ofDays(2)), "Supplier changed bank");
    assertEquals("REVOKED", revoked.stateAt(at(Duration.ofDays(3))));
    assertFalse(revoked.matches("pt-0001", "pay:S-77", null, at(Duration.ofDays(3))));
    assertEquals("Supplier changed bank", revoked.revoked().reason());
  }

  @Test
  void approveOnceExtendsToNothing() {
    var e = claim(payment("pay:S-77"), TYLER, T0);
    var once = ok(e, Rules.decide(e, actor(TYLER), sticky(TYLER, Action.APPROVE, Sticky.APPROVE_ONCE), Map.of(),
        seconds(15)));
    assertNull(once.outcome().standingDecisionId());
    assertNull(StandingDecision.from(once));
  }

  @Test
  void aStickyScopeMustBeOfferedFitTheDecisionAndHaveSomethingToMatch() {
    var e = claim(payment("pay:S-77"), TYLER, T0);
    assertTrue(refused(Rules.decide(e, actor(TYLER), sticky(TYLER, Action.DENY, Sticky.DENY_FOR_PATTERN), Map.of(),
        seconds(15)), 422).errors().contains("this control does not offer DENY_FOR_PATTERN"));
    assertTrue(refused(Rules.decide(e, actor(TYLER), sticky(TYLER, Action.DENY, Sticky.APPROVE_FOR_PATTERN), Map.of(),
        seconds(15)), 422).errors().contains("APPROVE_FOR_PATTERN goes with APPROVE or EDIT"));
    var noKey = claim(payment(null), TYLER, T0);
    assertTrue(refused(Rules.decide(noKey, actor(TYLER), sticky(TYLER, Action.APPROVE, Sticky.APPROVE_FOR_PATTERN),
        Map.of(), seconds(15)), 422).errors().contains("APPROVE_FOR_PATTERN needs the event's match key"));
  }

  @Test
  void denyForPatternAndApproveForSessionWhereTheControlOffersThem() {
    var s = Fx.custom("action-approval", "human-system-authorization", "action",
        "routing", Map.of("roles", List.of("ai-system-owner")),
        "sticky_modes", List.of("APPROVE_ONCE", "DENY_FOR_PATTERN", "APPROVE_FOR_SESSION"));
    var e = raise(s, request(s).matchKey("pay:S-99").build());
    var denied = ok(e, Rules.decide(e, actor(TYLER), sticky(TYLER, Action.DENY, Sticky.DENY_FOR_PATTERN), Map.of(),
        minutes(1)));
    var denial = StandingDecision.from(denied);
    assertEquals("pattern-denial", denial.kind());
    assertEquals(Action.DENY, denial.action());
    assertTrue(refused(Rules.decide(e, actor(TYLER), sticky(TYLER, Action.APPROVE, Sticky.DENY_FOR_PATTERN), Map.of(),
        minutes(1)), 422).errors().contains("DENY_FOR_PATTERN goes with DENY"));

    var inSession = raise(s, request(s).subject(Fx.subjectFor(io.akka.human.api.SubjectKind.ACTION).withSession("sess-1"))
        .build());
    var approved = ok(inSession, Rules.decide(inSession, actor(TYLER),
        sticky(TYLER, Action.APPROVE, Sticky.APPROVE_FOR_SESSION), Map.of(), minutes(1)));
    var session = StandingDecision.from(approved);
    assertEquals("session-approval", session.kind());
    assertTrue(session.matches(s.control().rowId(), null, "sess-1", minutes(2)));
    assertFalse(session.matches(s.control().rowId(), null, "sess-2", minutes(2)));
  }

  @Test
  void aStandingDecisionSettlesAMatchingRaise() {
    var e = payment("pay:S-77");
    var settled = ok(e, Rules.settleByStanding(e, "sd-1", Action.APPROVE, T0));
    assertEquals(State.DECIDED, settled.state());
    assertTrue(settled.outcome().byStanding());
    assertEquals("sd-1", settled.outcome().standingDecisionId());
    assertEquals(List.of("standing:sd-1"), settled.deciders());
    assertEquals("standing:sd-1", settled.history().getLast().actor());
    assertNull(settled.settlingVote());
    assertNull(StandingDecision.from(settled));
    assertEquals(Verdicts.UPHELD, Verdicts.of(settled).conclusion());
    refused(Rules.settleByStanding(settled, "sd-1", Action.APPROVE, T0), 409);
  }

  @Test
  void oneAuthorisedPersonExercisesAHalt() {
    var halt = raise(control("pt-0012"));
    assertFalse(halt.lift());
    assertEquals("HALT", halt.settings().mode());
    assertTrue(refused(Rules.decide(halt, actor(PATEL), Fx.decision(reviewer(PATEL), Action.APPROVE), Map.of(), T0), 422)
        .error().contains("a halt event takes HALT"));
    refused(Rules.decide(halt, actor(LIND, "safety-officer"), Fx.decision(reviewer(LIND), Action.HALT), Map.of(), T0), 403);
    var halted = decide(halt, PATEL, Action.HALT, T0);
    assertEquals(State.DECIDED, halted.state());
    assertEquals(Action.HALT, halted.outcome().action());
    assertEquals(List.of(PATEL), halted.deciders());
  }

  @Test
  void aRestartNeedsTwoApprovalsWithEverySafeStateCheckPassing() {
    var s = control("pt-0012");
    var lift = raise(s, request(s).subject(Subject.system("Restart eligibility", "capability", "eligibility-assessment",
        "all-runtime-operations", true)).build());
    assertTrue(lift.lift());
    assertTrue(refused(Rules.decide(lift, actor(PATEL), Fx.decision(reviewer(PATEL), Action.HALT), Map.of(), T0), 422)
        .error().contains("a restart event takes APPROVE or DENY"));
    var failing = refused(Rules.decide(lift, actor(TYLER), Fx.decision(reviewer(TYLER), Action.APPROVE),
        Map.of("backlog drained", false), minutes(1)), 409);
    assertTrue(failing.error().contains("[backlog drained]"), failing.error());

    var checked = Rules.decide(lift, actor(TYLER), Fx.decision(reviewer(TYLER), Action.APPROVE),
        Map.of("backlog drained", true), minutes(1));
    assertInstanceOf(HumanEventEvent.SafeStateChecked.class, checked.value().getFirst());
    var first = ok(lift, checked);
    assertEquals(State.AWAITING_SECOND, first.state());
    assertEquals(List.of(Map.of("name", "backlog drained", "passed", true)), first.subject().detail().get("safeStateChecks"));
    var restarted = ok(first, Rules.decide(first, actor(PATEL), Fx.decision(reviewer(PATEL), Action.APPROVE),
        Map.of("backlog drained", true), minutes(2)));
    assertEquals(State.DECIDED, restarted.state());
    assertEquals(Action.APPROVE, restarted.outcome().action());
    assertEquals(List.of(TYLER, PATEL), restarted.deciders());

    var kept = decide(lift, TYLER, Action.DENY, minutes(1));
    assertEquals(State.DECIDED, kept.state());
    assertEquals(Action.DENY, kept.outcome().action());
  }

  @Test
  void haltStateRecordsWhoHaltedUntilTheLift() {
    var s = control("pt-0012");
    var none = HaltState.notHalted(s);
    assertFalse(none.halted());
    var halted = none.exercised(PATEL, "Referral drift", "he-1", "pr-he-1-halt", T0);
    assertTrue(halted.halted());
    assertEquals(PATEL, halted.by());
    var lifting = halted.liftRaised("he-2");
    assertEquals("he-2", lifting.liftEventId());
    assertTrue(lifting.halted());
    var lifted = lifting.lifted();
    assertFalse(lifted.halted());
    assertNull(lifted.eventId());
    assertEquals("eligibility-assessment", lifted.target());
  }
}
