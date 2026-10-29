package io.akka.human.engine;

import static io.akka.human.engine.Fx.CHEN;
import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.MOREAU;
import static io.akka.human.engine.Fx.OKAFOR;
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
import static io.akka.human.engine.Fx.refused;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.reviewer;
import static io.akka.human.engine.Fx.seconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaimAndDecideTest {

  private static Rules.DecisionRequest ruling(String subject, Action action, String rationale) {
    return new Rules.DecisionRequest(reviewer(subject), action, rationale, null, null, null, List.of(), null);
  }

  @Test
  void aRaisedEventIsRoutedToTheFirstQueueWithItsDeadline() {
    var s = control("pt-0001");
    var e = raise(s, request(s).proposedBy("agent:payments").dedupeKey("pay-1").matchKey("pay:S-77").build());
    assertEquals(State.OPEN, e.state());
    assertTrue(e.pending());
    assertTrue(e.blocking());
    assertEquals("prevent", e.strength());
    assertEquals("system-owners", e.queue());
    assertEquals(T0.plus(Duration.ofMinutes(30)), e.dueAt());
    assertEquals(T0.plus(Duration.ofSeconds(1350)), e.atRiskAt());
    assertEquals(e.atRiskAt(), e.nextCheckAt());
    assertEquals(List.of("agent:payments"), e.independentOf());
    assertEquals("patterns", e.subject().service());
    assertEquals("tool-policy", e.trigger().detector());
    assertEquals("pay.amount > 1000", e.trigger().rule());
    assertEquals(1, e.trigger().occurrences());
    assertEquals("none", e.resumes().kind());
    assertEquals(1, e.history().size());
    assertEquals("Routed to system-owners", e.history().getFirst().note());
    assertEquals("running", e.slaState(T0));
  }

  @Test
  void aClaimHoldsTheEventForTheClaimTtl() {
    var e = claim(raise(control("pt-0002")), LIND, T0);
    assertEquals(State.CLAIMED, e.state());
    assertEquals(LIND, e.holder().subject());
    assertEquals(minutes(30), e.holder().expiresAt());
    assertEquals(T0, e.claimedAt());
    assertEquals(T0, e.presentedAt());

    var again = claim(e, LIND, minutes(10));
    assertEquals(minutes(40), again.holder().expiresAt());
    assertEquals(T0, again.presentedAt());
  }

  @Test
  void anEventHeldByAnotherCannotBeClaimedUntilTheClaimLapses() {
    var e = claim(raise(control("pt-0002")), LIND, T0);
    var refusal = refused(Rules.claim(e, actor(OKAFOR), minutes(5)), 409);
    assertEquals("claimed by another reviewer", refusal.error());
    assertEquals("CLAIMED", refusal.state());

    assertEquals(State.OPEN, e.stateAt(minutes(30)));
    assertNull(e.holderAt(minutes(30)));
    var taken = claim(e, OKAFOR, minutes(30));
    assertEquals(OKAFOR, taken.holder().subject());
  }

  @Test
  void theHolderOrAnAdminReleases() {
    var e = claim(raise(control("pt-0002")), LIND, T0);
    refused(Rules.release(e, reviewer(OKAFOR), minutes(1)), 403);
    var released = ok(e, Rules.release(e, reviewer(LIND), minutes(1)));
    assertEquals(State.OPEN, released.state());
    assertNull(released.holder());
    refused(Rules.release(released, reviewer(LIND), minutes(2)), 409);
    var byAdmin = ok(e, Rules.release(e, Reviewer.of("ops@example.com", "admin"), minutes(1)));
    assertEquals(State.OPEN, byAdmin.state());
  }

  @Test
  void aDecisionNeedsAClaimWhenTheControlMeasuresReviewTime() {
    var e = raise(control("pt-0001"));
    var refusal = refused(Rules.decide(e, actor(TYLER), ruling(TYLER, Action.APPROVE, "ok"), Map.of(), seconds(20)), 409);
    assertEquals("claim the event first", refusal.error());
  }

  @Test
  void theMinimumReviewTimeIsEnforced() {
    var e = claim(raise(control("pt-0001")), TYLER, T0);
    var refusal = refused(Rules.decide(e, actor(TYLER), ruling(TYLER, Action.APPROVE, "ok"), Map.of(), seconds(5)), 422);
    assertTrue(refusal.error().contains("minimum review time is 10 seconds"));

    var decided = decide(e, TYLER, Action.APPROVE, seconds(15));
    assertEquals(State.DECIDED, decided.state());
    assertFalse(decided.pending());
    var vote = decided.decisions().getFirst();
    assertEquals(15, vote.reviewSeconds());
    assertFalse(vote.belowMinimum());
    assertFalse(vote.late());
    assertEquals(T0, vote.presentedAt());
    assertEquals(Action.APPROVE, decided.outcome().action());
    assertEquals(HumanEvent.COMPLETED, decided.outcome().slaOutcome());
    assertEquals(seconds(15), decided.closedAt());
    assertNull(decided.holder());
    assertEquals("met", decided.slaState(seconds(20)));
    assertEquals(List.of(TYLER), decided.deciders());
    assertNull(decided.nextCheckAt());
  }

  @Test
  void aWarnedMinimumRecordsTheDecisionBelowTheMinimum() {
    var s = Fx.custom("hand-off", "human-domain-decision", "conversation",
        "routing", Map.of("roles", List.of("duty-officer")), "sla_target", "PT5M", "minimum_review_seconds", 20);
    assertEquals("WARN", s.minimumReview());
    var e = claim(raise(s), MOREAU, T0);
    var decided = decide(e, MOREAU, Action.DECLINE_SYSTEM, seconds(5));
    assertTrue(decided.decisions().getFirst().belowMinimum());
    var verdict = Verdicts.of(decided);
    assertEquals(Verdicts.BREACHED, verdict.conclusion());
    assertEquals(List.of("below-minimum"), verdict.reasons());
  }

  @Test
  void aControlWithoutAMinimumNeedsNoClaim() {
    var decided = decide(raise(control("pt-0004")), CHEN, Action.APPROVE, minutes(1));
    assertEquals(State.DECIDED, decided.state());
    assertEquals(minutes(1), decided.decisions().getFirst().presentedAt());
    assertEquals(0, decided.decisions().getFirst().reviewSeconds());
  }

  @Test
  void aLaterVoterOrAControlWithoutMinimumMaySayWhenTheyWereShownTheEvent() {
    var e = raise(control("pt-0004"));
    var r = new Rules.DecisionRequest(reviewer(CHEN), Action.APPROVE, "ok", null, null, minutes(1), List.of(), null);
    var decided = ok(e, Rules.decide(e, actor(CHEN), r, Map.of(), minutes(3)));
    assertEquals(120, decided.decisions().getFirst().reviewSeconds());
  }

  @Test
  void aRationaleIsRequiredUnlessTheControlWaivesIt() {
    var e = claim(raise(control("pt-0001")), TYLER, T0);
    var refusal = refused(Rules.decide(e, actor(TYLER), ruling(TYLER, Action.APPROVE, "  "), Map.of(), seconds(15)), 422);
    assertEquals(List.of("a rationale is required"), refusal.errors());

    var flag = raise(control("pt-0009"));
    var accepted = ok(flag, Rules.decide(flag, actor(MOREAU), ruling(MOREAU, Action.APPROVE, null), Map.of(), minutes(1)));
    assertEquals(State.DECIDED, accepted.state());
    assertEquals("", accepted.decisions().getFirst().rationale());
  }

  @Test
  void tickedChecksMustBeTheEventsOwn() {
    var s = control("pt-0001");
    var e = claim(raise(s, request(s).check("The invoice matches the purchase order.", "subject").build()), TYLER, T0);
    var wrong = new Rules.DecisionRequest(reviewer(TYLER), Action.APPROVE, "ok", null, null, null,
        List.of("Something else"), null);
    var refusal = refused(Rules.decide(e, actor(TYLER), wrong, Map.of(), seconds(15)), 422);
    assertTrue(refusal.error().contains("not a check of this event"));

    var right = new Rules.DecisionRequest(reviewer(TYLER), Action.APPROVE, "ok", null, null, null,
        List.of("The invoice matches the purchase order."), null);
    var decided = ok(e, Rules.decide(e, actor(TYLER), right, Map.of(), seconds(15)));
    assertEquals(List.of("The invoice matches the purchase order."), decided.decisions().getFirst().checks());
  }

  @Test
  void anEditNeedsItsEditsAndAnOverrideItsReplacement() {
    var e = claim(raise(control("pt-0001")), TYLER, T0);
    var noEdits = ruling(TYLER, Action.EDIT, "Pay less");
    assertTrue(refused(Rules.decide(e, actor(TYLER), noEdits, Map.of(), seconds(15)), 422).errors()
        .contains("an edit needs edits"));
    var edits = Map.<String, Object>of("summary", "amount 4200 to 4000",
        "patch", List.of(Map.of("op", "replace", "path", "/amount", "value", 4000)));
    var withEdits = new Rules.DecisionRequest(reviewer(TYLER), Action.EDIT, "Pay less", edits, null, null, List.of(), null);
    var edited = ok(e, Rules.decide(e, actor(TYLER), withEdits, Map.of(), seconds(15)));
    assertEquals(Action.EDIT, edited.outcome().action());
    assertEquals(edits, edited.outcome().edits());

    var o = raise(control("pt-0008"));
    var noReplacement = ruling(PATEL, Action.OVERRIDE, "Wrong figure");
    assertTrue(refused(Rules.decide(o, actor(PATEL), noReplacement, Map.of(), minutes(1)), 422).errors()
        .contains("an override needs the replacement output"));
    var replaced = new Rules.DecisionRequest(reviewer(PATEL), Action.OVERRIDE, "Wrong figure", null, null, null,
        List.of(), "The corrected notice.");
    var overridden = ok(o, Rules.decide(o, actor(PATEL), replaced, Map.of(), minutes(1)));
    assertEquals("The corrected notice.", overridden.outcome().replacement());
    assertEquals(Action.OVERRIDE, overridden.outcome().action());
  }

  @Test
  void anActionTheEventDoesNotAllowIsInvalid() {
    var e = claim(raise(control("pt-0001")), TYLER, T0);
    assertTrue(refused(Rules.decide(e, actor(TYLER), ruling(TYLER, Action.REVERSE, "x"), Map.of(), seconds(15)), 422)
        .error().contains("does not allow REVERSE"));
    refused(Rules.decide(e, actor(TYLER), ruling(TYLER, null, "x"), Map.of(), seconds(15)), 422);
  }

  @Test
  void aDecisionOnAClosedEventConflicts() {
    var decided = decide(claim(raise(control("pt-0001")), TYLER, T0), TYLER, Action.DENY, seconds(15));
    var refusal = refused(Rules.decide(decided, actor(TYLER), ruling(TYLER, Action.APPROVE, "x"), Map.of(), seconds(20)),
        409);
    assertEquals("DECIDED", refusal.state());
    refused(Rules.claim(decided, actor(TYLER), seconds(20)), 409);
  }

  @Test
  void aLateDecisionIsAcceptedAndBreached() {
    var e = claim(raise(control("pt-0001")), TYLER, minutes(25));
    var decided = decide(e, TYLER, Action.APPROVE, minutes(31));
    assertTrue(decided.decisions().getFirst().late());
    assertEquals("missed", decided.slaState(minutes(32)));
    var verdict = Verdicts.of(decided);
    assertEquals(Verdicts.BREACHED, verdict.conclusion());
    assertEquals(List.of("late"), verdict.reasons());
  }

  @Test
  void aLateDecisionIsRefusedWhenTheControlSaysSo() {
    var s = Fx.custom("action-approval", "human-system-authorization", "action",
        "routing", Map.of("roles", List.of("ai-system-owner")), "sla_target", "PT30M", "late_decision", "REFUSE");
    var e = raise(s);
    assertEquals("the event is past its deadline", refused(Rules.claim(e, actor(TYLER), minutes(31)), 409).error());
    refused(Rules.decide(e, actor(TYLER), ruling(TYLER, Action.APPROVE, "ok"), Map.of(), minutes(31)), 409);
    assertEquals(State.DECIDED, decide(e, TYLER, Action.APPROVE, minutes(29)).state());
  }

  @Test
  void aSingleVoteSettlesTheEventWhateverItDecides() {
    for (var action : List.of(Action.APPROVE, Action.DENY)) {
      var decided = decide(claim(raise(control("pt-0001")), TYLER, T0), TYLER, action, seconds(15));
      assertEquals(action, decided.outcome().action());
      assertEquals(Verdicts.UPHELD, Verdicts.of(decided).conclusion());
    }
  }

  @Test
  void anAiRecommendationIsComparedWithTheOutcome() {
    var s = control("pt-0002");
    var e = raise(s, request(s).aiRecommendation("judge", Action.APPROVE, 0.9, "Looks right").build());
    var agreed = decide(claim(e, LIND, T0), LIND, Action.APPROVE, seconds(30));
    assertEquals(Boolean.TRUE, agreed.outcome().agreedWithAi());
    var disagreed = decide(claim(e, LIND, T0), LIND, Action.DENY, seconds(30));
    assertEquals(Boolean.FALSE, disagreed.outcome().agreedWithAi());
    assertNull(decide(claim(raise(s), LIND, T0), LIND, Action.DENY, seconds(30)).outcome().agreedWithAi());
  }
}
