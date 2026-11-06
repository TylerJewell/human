package io.akka.human.engine;

import static io.akka.human.engine.Fx.CHEN;
import static io.akka.human.engine.Fx.DEVOS;
import static io.akka.human.engine.Fx.LIND;
import static io.akka.human.engine.Fx.OKAFOR;
import static io.akka.human.engine.Fx.OSEI;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.engine.Qualification.Actor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QualificationTest {

  @Test
  void anUnqualifiedOverseerIsRefused() {
    var refusal = refused(Rules.claim(raise(control("pt-00002")), actor(OSEI), T0), 403);
    assertTrue(refusal.error().contains("is not qualified: EU AI Act oversight training expired"), refusal.error());
  }

  @Test
  void anAttestationMustBeValidOnTheDay() {
    var day = Instant.parse("2027-01-11T09:00:00Z");
    var e = raise(control("pt-00001"), request(control("pt-00001")).build(), day);
    var refusal = refused(Rules.claim(e, actor(TYLER), day), 403);
    assertTrue(refusal.error().contains("eu-ai-act-oversight has expired"), refusal.error());
    var lastDay = Instant.parse("2027-01-10T23:00:00Z");
    assertEquals(State.CLAIMED, claim(raise(control("pt-00001"), request(control("pt-00001")).build(), lastDay), TYLER,
        lastDay).state());
  }

  @Test
  void aRequiredAttestationMustHaveBeenCompleted() {
    var s = Fx.custom("decision-review", "human-domain-decision", "output",
        "routing", Map.of("roles", List.of("case-reviewer"), "attestations", List.of("sr11-7-validation")));
    assertTrue(refused(Rules.claim(raise(s), actor(LIND), T0), 403).error().contains("has not completed sr11-7-validation"));
  }

  @Test
  void aControlThatNamesGroupsTakesOnlyTheirMembers() {
    var s = Fx.custom("decision-review", "human-domain-decision", "output",
        "routing", Map.of("roles", List.of("case-reviewer"), "groups", List.of("credit-committee")));
    assertTrue(refused(Rules.claim(raise(s), actor(LIND), T0), 403).error().contains("none of the groups"));
  }

  @Test
  void authorityLimitsTheDecisionsAPersonMayMake() {
    var s = Fx.custom("decision-review", "human-domain-decision", "output",
        "routing", Map.of("roles", List.of("case-reviewer")), "allowed_actions", List.of("APPROVE", "SEND_BACK"));
    var e = raise(s, request(s).proposedBy("agent:notices").build());
    var sendBack = new Rules.DecisionRequest(reviewer(OKAFOR), Action.SEND_BACK, "Rewrite the reasons", null, null, null,
        List.of(), null);
    var refusal = refused(Rules.decide(e, actor(OKAFOR), sendBack, Map.of(), T0), 403);
    assertTrue(refusal.error().contains("no authority to SEND_BACK"), refusal.error());
    assertEquals(State.DECIDED, decide(e, OKAFOR, Action.APPROVE, T0).state());
    assertEquals(State.CHANGES_REQUESTED, decide(e, LIND, Action.SEND_BACK, T0).state());
  }

  @Test
  void aRoleMustBeHeldInTheRegistryNotOnlyClaimed() {
    var refusal = refused(Rules.claim(raise(control("pt-00001")), actor(CHEN, "ai-system-owner"), T0), 403);
    assertTrue(refusal.error().contains("does not hold [ai-system-owner] in the overseer registry"), refusal.error());
  }

  @Test
  void aPersonOutsideTheRegistryIsRefusedWhenTheServiceKeepsOne() {
    var ghost = new Actor(Reviewer.of("ghost@example.com", "ai-system-owner"), null, true);
    assertTrue(refused(Rules.claim(raise(control("pt-00001")), ghost, T0), 403).error()
        .contains("not in the overseer registry"));
    var noRegistry = Actor.unregistered(Reviewer.of("ghost@example.com", "ai-system-owner"));
    assertEquals(State.CLAIMED, ok(raise(control("pt-00001")), Rules.claim(raise(control("pt-00001")), noRegistry, T0))
        .state());
  }

  @Test
  void aReviewerWithoutTheQueuesRoleIsRefused() {
    var refusal = refused(Rules.claim(raise(control("pt-00001")), actor(CHEN), T0), 403);
    assertTrue(refusal.error().contains("this needs one of [ai-system-owner]"), refusal.error());
  }

  @Test
  void theProposerMayNotDecide() {
    var s = control("pt-00001");
    var e = raise(s, request(s).proposedBy(TYLER).build());
    assertEquals(List.of(TYLER), e.independentOf());
    assertTrue(refused(Rules.claim(e, actor(TYLER), T0), 403).error().contains("independence bars"));
  }

  @Test
  void theBuilderMayNotDecideAChange() {
    var s = control("pt-00003");
    var e = raise(s, request(s).proposedBy("agent:rollouts").builtBy(DEVOS).build());
    assertEquals(List.of("agent:rollouts", DEVOS), e.independentOf());
    refused(Rules.decide(e, actor(DEVOS), Fx.decision(reviewer(DEVOS), Action.APPROVE), Map.of(), T0), 403);
    assertEquals(State.AWAITING_SECOND, decide(e, TYLER, Action.APPROVE, T0).state());
  }

  @Test
  void anAppealBarsTheFirstReviewerAndTheirReportingLine() {
    var s = control("pt-00007");
    var e = raise(s, request(s).firstReviewer(OKAFOR).build());
    assertEquals(List.of(OKAFOR, CHEN), e.independentOf());
    assertEquals(List.of(OKAFOR, CHEN), e.subject().detail().get("excluded"));
    assertTrue(refused(Rules.claim(e, actor(OKAFOR), T0), 403).error().contains("independence bars " + OKAFOR));
    assertTrue(refused(Rules.claim(e, actor(CHEN), T0), 403).error().contains("independence bars " + CHEN));
    assertEquals(List.of(DEVOS, TYLER), raise(s, request(s).firstReviewer(DEVOS).build()).independentOf());
    var claimed = claim(e, LIND, T0);
    var upheld = decide(claimed, LIND, Action.APPROVE, minutes(1));
    assertEquals(Action.APPROVE, upheld.outcome().action());
  }

  @Test
  void routableKeepsOnlyWhatTheReviewerCanActOnNow() {
    var e = raise(control("pt-00002"));
    assertTrue(Rules.routable(e, actor(LIND), T0));
    assertFalse(Rules.routable(e, actor(OSEI), T0));
    assertFalse(Rules.routable(e, actor(TYLER), T0));
    var held = claim(e, LIND, T0);
    assertTrue(Rules.routable(held, actor(LIND), T0));
    assertFalse(Rules.routable(held, actor(OKAFOR), T0));
    assertTrue(Rules.routable(held, actor(OKAFOR), minutes(30)));

    var change = raise(control("pt-00003"));
    var voted = decide(change, TYLER, Action.APPROVE, T0);
    assertFalse(Rules.routable(voted, actor(TYLER), T0));
    assertTrue(Rules.routable(voted, actor(DEVOS), T0));
  }

  @Test
  void aRecusedReviewerStepsAsideAndCannotComeBack() {
    var e = claim(raise(control("pt-00002")), LIND, T0);
    var recuse = new Rules.DecisionRequest(reviewer(LIND), Action.RECUSE, "I know the applicant", null, null, null,
        List.of(), null);
    var recused = ok(e, Rules.decide(e, actor(LIND), recuse, Map.of(), minutes(1)));
    assertEquals(List.of(LIND), recused.recused());
    assertNull(recused.holder());
    assertEquals(State.OPEN, recused.state());
    assertTrue(recused.pending());
    assertTrue(refused(Rules.claim(recused, actor(LIND), minutes(2)), 403).error().contains("recused"));
    refused(Rules.decide(recused, actor(LIND), recuse, Map.of(), minutes(2)), 409);
    assertFalse(Rules.routable(recused, actor(LIND), minutes(2)));
    assertTrue(Rules.routable(recused, actor(OKAFOR), minutes(2)));
    refused(Rules.recuse(recused, reviewer(TYLER), "", minutes(2)), 403);
  }
}
