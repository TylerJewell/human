package io.akka.human.engine;

import static io.akka.human.engine.Fx.humanControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Subject;
import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A target halted under one control and restarted under another, and targets named per call. */
class HaltControlsTest {

  private static final HumanControl EXERCISE = humanControl("h-halt", "halt-restart", "human-halt", "system",
      "routing", java.util.Map.of("roles", List.of("safety-officer"), "review_mode", "SINGLE", "required", 1),
      "allowed_actions", List.of("HALT"));
  private static final HumanControl RESTART = humanControl("h-restart", "halt-restart", "human-halt", "system",
      "routing", java.util.Map.of("roles", List.of("safety-officer")), "allowed_actions", List.of("APPROVE", "DENY"));
  private static final HumanControl BOTH = humanControl("h-both", "halt-restart", "human-halt", "system",
      "routing", java.util.Map.of("roles", List.of("safety-officer")));

  private static Controls controls(HumanControl... controls) {
    return Controls.of(new ControlCatalog() {
      @Override
      public List<HumanControl> controls() {
        return List.of(controls);
      }

      @Override
      public String digest() {
        return "test";
      }
    });
  }

  @Test
  void aHaltOrRestartNamedOnTheOtherControlGoesToTheOneThatAllowsIt() {
    var controls = controls(EXERCISE, RESTART);
    var exercise = controls.find("h-halt").orElseThrow();
    var restart = controls.find("h-restart").orElseThrow();
    assertEquals(exercise, controls.haltControl(exercise, false).orElseThrow());
    assertEquals(restart, controls.haltControl(exercise, true).orElseThrow());
    assertEquals(exercise, controls.haltControl(restart, false).orElseThrow());
    assertEquals(restart, controls.haltControl(restart, true).orElseThrow());
  }

  @Test
  void aControlThatDoesBothKeepsBoth() {
    var controls = controls(BOTH);
    var both = controls.find("h-both").orElseThrow();
    assertEquals(both, controls.haltControl(both, false).orElseThrow());
    assertEquals(both, controls.haltControl(both, true).orElseThrow());
  }

  @Test
  void noControlRestartsATargetOnlyHalted() {
    var controls = controls(EXERCISE);
    assertTrue(controls.haltControl(controls.find("h-halt").orElseThrow(), true).isEmpty());
  }

  @Test
  void theEventsTargetIsTheOneTheCallNamed() {
    var settings = controls(BOTH).find("h-both").orElseThrow();
    var named = Subject.system("Halt playbook 7", "capability", "playbook-7", "all-runtime-operations", false);
    var event = Raise.event("he-1", settings, io.akka.human.api.RaiseRequest.of(null, "Halt?").control("h-both")
        .subject(named).build(), new Raise.Context("svc", "local", "d", s -> null), Fx.T0);
    assertEquals("playbook-7", event.haltTarget());
    var state = HaltState.notHalted(settings, "playbook-7").listedUnder(settings.control());
    assertEquals("playbook-7", state.target());
    assertEquals("h-both", state.control().rowId());
  }

  @Test
  void aHaltIsExercisedAtOnceWhileARestartKeepsItsClaimAndReviewTime() {
    var settings = Fx.custom("halt-restart", "human-halt", "system", "routing",
        java.util.Map.of("roles", List.of("safety-officer")), "minimum_review_seconds", 30);
    var halt = Fx.raise(settings, Fx.request(settings).subject(Subject.system("Halt eligibility", "capability",
        "eligibility-assessment", "all-runtime-operations", false)).build());
    var halted = Fx.decide(halt, Fx.PATEL, io.akka.human.api.Action.HALT, Fx.seconds(1));
    assertEquals(State.DECIDED, halted.state());
    assertEquals(false, halted.decisions().getFirst().belowMinimum());
    assertEquals(Verdicts.UPHELD, Verdicts.of(halted).conclusion());
    // One person may halt, so the halt's verdict asks for no second person.
    assertEquals(null, ((java.util.Map<?, ?>) Json.verdict(halted).get("detail")).get("twoPerson"));

    var restart = Fx.raise(settings, Fx.request(settings).subject(Subject.system("Restart eligibility", "capability",
        "eligibility-assessment", "all-runtime-operations", true)).build());
    var approve = Fx.decision(Fx.reviewer(Fx.PATEL), io.akka.human.api.Action.APPROVE);
    assertEquals("claim the event first",
        Fx.refused(Rules.decide(restart, Fx.actor(Fx.PATEL), approve, java.util.Map.of(), Fx.seconds(1)), 409).error());
    var claimed = Fx.claim(restart, Fx.PATEL, Fx.seconds(1));
    Fx.refused(Rules.decide(claimed, Fx.actor(Fx.PATEL), approve, java.util.Map.of(), Fx.seconds(5)), 422);
  }
}
