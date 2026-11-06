package io.akka.human.engine;

import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.seconds;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.akka.human.api.Action;
import io.akka.human.api.Sticky;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Conditions an approval carries, the service's time on what it answers, and a typed AI recommendation. */
class ConditionsTest {

  private static final List<Map<String, Object>> CONDITIONS = List.of(
      Map.of("text", "Pay only once the purchase order is signed", "dueAt", "2026-11-05T00:00:00Z"),
      Map.of("text", "Notify the supplier"));

  private static Rules.DecisionRequest approve(Action action, Sticky sticky, List<Map<String, Object>> conditions) {
    return new Rules.DecisionRequest(Fx.reviewer(TYLER), action, "Known supplier", null, sticky, null, List.of(), null,
        conditions);
  }

  @Test
  void anApprovalsConditionsReachTheOutcomeAndTheStandingDecision() {
    var settings = control("pt-00001");
    var event = Fx.claim(Fx.raise(settings, Fx.request(settings).matchKey("pay:S-77").proposedBy("agent:payments").build()),
        TYLER, seconds(1));
    var decided = Fx.ok(event, Rules.decide(event, Fx.actor(TYLER), approve(Action.APPROVE, Sticky.APPROVE_FOR_PATTERN,
        CONDITIONS), Map.of(), seconds(15)));
    assertEquals(CONDITIONS, decided.outcome().conditions());
    assertEquals(CONDITIONS, StandingDecision.from(decided).conditions());
    var json = Json.event(decided, seconds(16));
    assertEquals(seconds(16).toString(), json.get("now"));
    SchemaTest.assertConforms("HumanEvent", json);
    SchemaTest.assertConforms("HumanEventRow", Json.row(decided, seconds(16)));
    SchemaTest.assertConforms("StandingDecision", Json.standing(StandingDecision.from(decided), "payments", seconds(16)));
  }

  @Test
  void conditionsGoWithAnApprovalAndNeedTheirText() {
    var settings = control("pt-00001");
    var event = Fx.claim(Fx.raise(settings), TYLER, seconds(1));
    Fx.refused(Rules.decide(event, Fx.actor(TYLER), approve(Action.DENY, null, CONDITIONS), Map.of(), seconds(15)), 422);
    Fx.refused(Rules.decide(event, Fx.actor(TYLER), approve(Action.APPROVE, null, List.of(Map.of("text", " "))), Map.of(),
        seconds(15)), 422);
    Fx.refused(Rules.decide(event, Fx.actor(TYLER), approve(Action.APPROVE, null,
        List.of(Map.of("text", "x", "dueAt", "next week"))), Map.of(), seconds(15)), 422);
  }

  @Test
  void anAiRecommendationNamesItsActionAndIsStampedWhenMade() {
    var settings = control("pt-00001");
    var missing = Fx.request(settings).build();
    var withoutAction = new io.akka.human.api.RaiseRequest(missing.pattern(), missing.control(), missing.ask(),
        missing.subject(), null, null, null, null, null, null, Map.of("agent", "judge"), null, null, null, null, false);
    assertEquals(422, Raise.check(settings, withoutAction).status());

    var recommended = Fx.request(settings).aiRecommendation("judge", Action.APPROVE, 0.8, "Within the contract").build();
    var event = Fx.raise(settings, recommended);
    assertEquals(T0.toString(), event.aiRecommendation().get("at"));
    SchemaTest.assertConforms("HumanEvent", Json.event(event, T0));
  }
}
