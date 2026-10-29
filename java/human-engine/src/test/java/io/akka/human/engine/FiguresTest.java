package io.akka.human.engine;

import static io.akka.human.engine.Fx.DEVOS;
import static io.akka.human.engine.Fx.MOREAU;
import static io.akka.human.engine.Fx.PATEL;
import static io.akka.human.engine.Fx.T0;
import static io.akka.human.engine.Fx.TYLER;
import static io.akka.human.engine.Fx.claim;
import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.decide;
import static io.akka.human.engine.Fx.minutes;
import static io.akka.human.engine.Fx.raise;
import static io.akka.human.engine.Fx.request;
import static io.akka.human.engine.Fx.seconds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Sticky;
import io.akka.human.api.Subject;
import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class FiguresTest {

  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  /** An oversight-quality control watching a hand-off control that only warns below its minimum review time. */
  private static Controls watched() {
    var quality = Fx.humanControl("q-1", "oversight-quality", "human-monitoring", "behaviour",
        "trigger", Map.of("detector", "monitor", "metric", "below-minimum-share", "comparison", ">", "threshold", 0.2,
            "window", "P7D"),
        "routing", Map.of("roles", List.of("ai-system-owner")), "accountable_owner", "ai-system-owner",
        "sla_target", "P2D");
    var handOff = Fx.humanControl("w-1", "hand-off", "human-domain-decision", "conversation",
        "routing", Map.of("roles", List.of("duty-officer")), "sla_target", "PT5M", "minimum_review_seconds", 20);
    return Controls.of(new ControlCatalog() {
      @Override
      public List<HumanControl> controls() {
        return List.of(quality, handOff);
      }

      @Override
      public String digest() {
        return "test";
      }
    });
  }

  @Test
  void theSummaryCountsWhatWaitsByLoopStatePatternAndClock() {
    var now = minutes(25);
    var atRisk = raise(control("pt-0001"));
    var overdue = raise(control("pt-0011"));
    var flag = raise(control("pt-0009"));
    var halted = decide(raise(control("pt-0012")), PATEL, Action.HALT, minutes(1));
    var yesterday = raise(control("pt-0004"), request(control("pt-0004")).build(), T0.minus(Duration.ofDays(1)));
    var shadow = raise(Fx.custom("triage", "human-monitoring", "behaviour",
        "routing", Map.of("roles", List.of("model-risk-owner")), "shadow", true));
    var events = List.of(atRisk, overdue, flag, halted, yesterday, shadow);

    var pending = claim(raise(control("pt-0001"), request(control("pt-0001")).matchKey("k").build()), TYLER, T0);
    var granted = Fx.ok(pending, Rules.decide(pending, Fx.actor(TYLER), new Rules.DecisionRequest(Fx.reviewer(TYLER),
        Action.APPROVE, "Known supplier", null, Sticky.APPROVE_FOR_PATTERN, null, List.of(), null), Map.of(), seconds(15)));
    var active = StandingDecision.from(granted);
    var expiring = new StandingDecision("sd-x", "pattern-approval", "he-x", control("pt-0001").control(), "k2", null,
        "approve", TYLER, T0.minus(Duration.ofDays(25)), T0.plus(Duration.ofDays(5)), Action.APPROVE, List.of(), null,
        List.of());
    var revoked = active.revokedBy(TYLER, T0, "no");
    var halts = List.<Map<String, Object>>of(Json.halt(HaltState.notHalted(control("pt-0012")), Map.of(), null, "patterns"));

    var summary = Figures.summary(events, Fx.CONTROLS, halts, List.of(active, expiring, revoked), now);
    var loop = map(summary.get("loop"));
    assertEquals(3L, map(loop.get("in")).get("pending"));
    assertEquals(1L, map(loop.get("in")).get("overdue"));
    assertEquals(1L, map(loop.get("on")).get("pending"));
    assertEquals(0L, map(loop.get("over")).get("pending"));
    assertEquals(7, list(map(loop.get("in")).get("raised7d")).size());
    assertEquals(List.of(1L, 2L), list(map(loop.get("in")).get("raised7d")).subList(5, 7));
    assertEquals(1L, list(map(loop.get("over")).get("raised7d")).getLast());
    assertEquals(Map.of("OPEN", 4L, "DECIDED", 1L), summary.get("states"));
    assertEquals(Map.of("action-approval", 1L, "hand-off", 1L, "flag-feedback", 1L, "exception", 1L),
        summary.get("patterns"));
    assertEquals(1L, summary.get("atRisk"));
    assertEquals(1L, summary.get("overdue"));
    var raised30d = list(summary.get("raised30d"));
    assertEquals(30, raised30d.size());
    assertEquals(4L, raised30d.get(29));
    assertEquals(1L, raised30d.get(28));
    assertEquals(Map.of("active", 2L, "expiring", 1L), summary.get("standing"));
    assertEquals(0L, summary.get("unownedControls"));
    assertEquals(halts, summary.get("halts"));
  }

  @Test
  void qualityReportsReviewTimesReviewersOutcomesAndSignals() {
    var controls = watched();
    var handOff = controls.find("w-1").orElseThrow();
    var rushed = decide(claim(raise(handOff), MOREAU, T0), MOREAU, Action.APPROVE, seconds(5));
    var careful = decide(claim(raise(handOff), MOREAU, T0), MOREAU, Action.DECLINE_SYSTEM, seconds(25));
    var now = T0.plus(Duration.ofHours(1));

    var signals = Figures.signals(List.of(rushed, careful), controls, now);
    assertEquals(1, signals.size());
    var signal = signals.getFirst();
    assertEquals("q-1", signal.qualityControl());
    assertEquals("w-1", signal.control());
    assertEquals("below-minimum-share", signal.signal());
    assertEquals(0.5, signal.value());
    assertEquals(0.2, signal.limit());
    assertTrue(signal.breached());
    assertNull(signal.eventId());

    var quality = controls.find("q-1").orElseThrow();
    var raised = raise(quality, request(quality).subject(Subject.behaviour("Rushed hand-offs", "below-minimum-share", 0.5,
        0.2, ">").withRef("w-1")).build(), minutes(30));
    var events = List.of(rushed, careful, raised);
    assertEquals(raised.id(), Figures.signals(events, controls, now).getFirst().eventId());

    var q = Figures.quality(events, controls, s -> s.equals(MOREAU) ? "L. Moreau" : null, "P30D", now);
    assertEquals("P30D", q.get("window"));
    var times = map(list(q.get("reviewTimes")).getFirst());
    assertEquals("w-1", times.get("control"));
    assertEquals(20, times.get("minimumSeconds"));
    assertEquals(List.of(Json.map("upTo", 10, "count", 1L), Json.map("upTo", 20, "count", 0L),
        Json.map("upTo", null, "count", 1L)), times.get("buckets"));
    assertEquals(0.5, times.get("belowMinimumShare"));

    var reviewer = map(list(q.get("reviewers")).getFirst());
    assertEquals(MOREAU, reviewer.get("subject"));
    assertEquals("L. Moreau", reviewer.get("name"));
    assertEquals(2, reviewer.get("decisions"));
    assertEquals(0.5, reviewer.get("approvalRate"));
    assertEquals(0.0, reviewer.get("overrideRate"));
    assertNull(reviewer.get("agreementWithAi"));
    assertEquals(15.0, reviewer.get("medianReviewSeconds"));
    assertEquals(0L, reviewer.get("open"));
    assertEquals(0, reviewer.get("claimed"));
    assertNull(reviewer.get("oldestClaimSeconds"));
    assertEquals(1.0, reviewer.get("share"));

    var outcomes = map(q.get("outcomes"));
    assertEquals(3, outcomes.get("triggered"));
    assertEquals(1L, outcomes.get("approved"));
    assertEquals(0L, outcomes.get("denied"));
    assertEquals(0L, outcomes.get("timedOut"));
    assertEquals(Json.map("triggers", 3, "reachedPerson", 2L), q.get("coverage"));
    assertEquals(Json.map("twoPersonEvents", 0, "agreed", 0L), q.get("agreement"));
    var signalJson = map(list(q.get("signals")).getFirst());
    assertEquals(Json.map("control", "w-1", "signal", "below-minimum-share", "value", 0.5, "limit", 0.2,
        "eventId", raised.id()), signalJson);
    assertEquals(1, list(q.get("weekly")).size());
    assertEquals(2L, map(list(q.get("heatmap")).getFirst()).get("decisions"));
  }

  @Test
  void theFixtureSignalWatchesEveryControlWithAMinimumReviewTime() {
    var signals = Figures.signals(List.of(), Fx.CONTROLS, T0);
    assertEquals(List.of("pt-0001", "pt-0002", "pt-0007"), signals.stream().map(Figures.Signal::control).toList());
    for (var s : signals) {
      assertEquals("pt-0014", s.qualityControl());
      assertEquals(0.0, s.value());
      assertEquals(0.2, s.limit());
      assertFalse(s.breached());
    }
  }

  @Test
  void twoPersonAgreementAndReviewersWhoHoldEvents() {
    var s = control("pt-0003");
    var agreed = decide(decide(raise(s), TYLER, Action.APPROVE, minutes(1)), DEVOS, Action.APPROVE, minutes(2));
    var held = claim(raise(control("pt-0002")), Fx.LIND, minutes(3));
    var lindVote = decide(claim(raise(control("pt-0002")), Fx.LIND, T0), Fx.LIND, Action.APPROVE, minutes(1));
    var q = Figures.quality(List.of(agreed, held, lindVote), Fx.CONTROLS, x -> null, "P30D", minutes(10));
    assertEquals(Json.map("twoPersonEvents", 1, "agreed", 1L), q.get("agreement"));
    var lind = list(q.get("reviewers")).stream().map(FiguresTest::map)
        .filter(r -> r.get("subject").equals(Fx.LIND)).findFirst().orElseThrow();
    assertEquals(1, lind.get("claimed"));
    assertEquals(420L, lind.get("oldestClaimSeconds"));
    assertEquals(1L, lind.get("open"));
  }

  @Test
  void signalsCompareEachWay() {
    assertTrue(Figures.crosses(0.3, ">", 0.2));
    assertFalse(Figures.crosses(0.2, ">", 0.2));
    assertTrue(Figures.crosses(0.2, ">=", 0.2));
    assertTrue(Figures.crosses(0.1, "<", 0.2));
    assertTrue(Figures.crosses(0.2, "<=", 0.2));
    assertTrue(Figures.crosses(0.2, "==", 0.2));
    assertTrue(Figures.crosses(0.3, "!=", 0.2));
  }
}
