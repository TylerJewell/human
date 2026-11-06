package io.akka.human.engine;

import static io.akka.human.engine.Fx.control;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import io.akka.human.api.Sticky;
import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingsTest {

  private static ControlCatalog catalog(HumanControl... controls) {
    return new ControlCatalog() {
      @Override
      public List<HumanControl> controls() {
        return List.of(controls);
      }

      @Override
      public String digest() {
        return "test";
      }
    };
  }

  @Test
  void theFixtureCatalogHasNoProblemsAndServesEveryPattern() {
    assertEquals(List.of(), Settings.problems(Fx.CATALOG));
    assertEquals(14, Fx.CONTROLS.all().size());
    assertEquals(Arrays.asList(Pattern.values()), Fx.CONTROLS.patterns());
    assertTrue(Fx.CATALOG.digest().startsWith("sha256:"));
  }

  @Test
  void theRegistriesNameFourteenPatternsAndEveryDecision() {
    var verify = Verify.registries();
    for (var p : Pattern.values()) assertEquals(p, verify.pattern(p).pattern());
    assertEquals(Action.values().length, verify.decisions().size());
    assertEquals("PT30M", verify.sharedDefaults().get("claim_ttl"));
  }

  @Test
  void actionApprovalTakesThePatternDefaultsAndTheControlsSettings() {
    var s = control("pt-00001");
    assertEquals(Pattern.ACTION_APPROVAL, s.pattern());
    assertEquals("Action approval", s.patternName());
    assertEquals("HITL", s.mode());
    assertTrue(s.patternBlocks());
    assertEquals(List.of(Sticky.APPROVE_ONCE, Sticky.APPROVE_FOR_PATTERN), s.sticky());
    assertEquals("RESUME_WITH_DEFAULT", s.timeoutBehaviour());
    assertEquals(Action.DENY, s.timeoutDefault());
    assertEquals(10, s.minimumReviewSeconds());
    assertEquals("ENFORCE", s.minimumReview());
    assertEquals("system-owners", s.routing().queue());
    assertEquals(List.of("ai-system-owner"), s.routing().roles());
    assertEquals(List.of("eu-ai-act-oversight"), s.routing().attestations());
    assertEquals(2, s.escalationChain().size());
    assertEquals("head-of-operations", s.queueAt(1));
    assertEquals(List.of("head-of-operations"), s.rolesAt(1));
    assertEquals(Duration.ofMinutes(30), s.windowAt(1));
    assertEquals(List.of(Action.APPROVE, Action.DENY, Action.EDIT, Action.REQUEST_MORE_INFO, Action.ESCALATE),
        s.allowedActions());
    assertEquals("Edit, then approve", s.label(Action.EDIT));
    assertEquals("SINGLE", s.routing().reviewMode());
    assertEquals(1, s.routing().required());
    assertEquals("DENY", s.routing().splitVote());
    assertEquals(List.of("proposer"), s.routing().independence());
    assertEquals(0.75, s.atRiskAt());
    assertEquals("PT30M", s.claimTtl());
    assertEquals("RUNS", s.clockWhileSentBack());
    assertEquals("ACCEPT_BREACHED", s.lateDecision());
    assertTrue(s.rationaleRequired());
    assertEquals("HumanOversightDecisionRecordedV2", s.proofEvent());
    assertEquals(Map.of("pre-production", "warn", "production", "prevent"), s.strength());
    assertEquals("prevent", s.strengthIn("local"));
    assertEquals("warn", s.strengthIn("pre-production"));
    assertEquals("tool-policy", s.trigger().get("detector"));
  }

  @Test
  void changeAuthorizationIsTwoPersonIndependentOfProposerAndBuilder() {
    var s = control("pt-00003");
    assertEquals("TWO_PERSON", s.routing().reviewMode());
    assertEquals(2, s.routing().required());
    assertEquals(List.of("proposer", "builder"), s.routing().independence());
    assertEquals("ESCALATE", s.timeoutBehaviour());
    assertEquals(List.of(Action.APPROVE, Action.DENY, Action.SEND_BACK), s.allowedActions());
    assertEquals("Request changes", s.label(Action.SEND_BACK));
    assertEquals("ai-change-board", s.routing().queue());
    assertEquals(0, s.minimumReviewSeconds());
  }

  @Test
  void patternSpecificDefaults() {
    var flag = control("pt-00009");
    assertFalse(flag.rationaleRequired());
    assertEquals("ABANDON", flag.timeoutBehaviour());
    assertEquals("HOTL", flag.mode());

    assertEquals("WARN", control("pt-00011").minimumReview());
    assertEquals("RAISE_TIMEOUT", control("pt-00013").timeoutBehaviour());
    assertEquals(List.of("builder"), control("pt-00013").routing().independence());
    assertEquals(List.of("first-reviewer", "first-reviewer-line"), control("pt-00007").routing().independence());
    assertEquals(List.of("builder"), control("pt-00005").routing().independence());
    assertNull(control("pt-00008").timeoutBehaviour());
    assertNull(control("pt-00008").deadline());
    assertEquals("ESCALATE", control("pt-00014").timeoutBehaviour());
  }

  @Test
  void haltRestartIsTwoPersonAndNamesItsExercisers() {
    var s = control("pt-00012");
    assertTrue(s.haltRestart());
    assertEquals("HALT", s.mode());
    assertFalse(s.patternBlocks());
    assertEquals("TWO_PERSON", s.routing().reviewMode());
    assertEquals(2, s.routing().required());
    assertEquals(List.of("safety-officer"), s.exercisers());
    assertEquals("capability", s.haltScope());
    assertEquals("eligibility-assessment", s.haltTarget());
    assertEquals("all-runtime-operations", s.haltAffectedCalls());
    assertEquals("RESUME_WITH_DEFAULT", s.timeoutBehaviour());
    assertEquals(Action.DENY, s.timeoutDefault());
    assertEquals(s, Fx.CONTROLS.haltFor("eligibility-assessment").orElseThrow());
  }

  @Test
  void aControlWithoutAChainQueuesUnderItsFirstRole() {
    var s = control("pt-00004");
    assertEquals("compliance-lead", s.routing().queue());
    assertEquals(1, s.levels());
    assertEquals(List.of("compliance-lead"), s.rolesAt(0));
    assertEquals(Duration.ofDays(2), s.deadline());
  }

  @Test
  void controlsFindTheFirstOfAPattern() {
    assertEquals("pt-00009", Fx.CONTROLS.firstOf(Pattern.FLAG_FEEDBACK).orElseThrow().control().rowId());
    assertTrue(Fx.CONTROLS.find("nope").isEmpty());
  }

  @Test
  void controlSettingsOverrideAllowedActionsAndRequiredVotes() {
    var s = Fx.custom("decision-review", "human-domain-decision", "output",
        "routing", Map.of("roles", List.of("case-reviewer"), "review_mode", "TWO_PERSON"),
        "allowed_actions", List.of("APPROVE", "RECUSE"));
    assertEquals(2, s.routing().required());
    assertEquals(List.of(Action.APPROVE, Action.RECUSE), s.allowedActions());
    assertEquals("Confirm", s.label(Action.APPROVE));
    assertEquals("Recuse", s.label(Action.RECUSE));
  }

  @Test
  void invalidControlsAreListed() {
    var unknownPattern = Fx.humanControl("bad-1", "rubber-stamp", "human-domain-decision", "output");
    var wrongFlavor = Fx.humanControl("bad-2", "triage", "human-halt", "behaviour", "routing",
        Map.of("roles", List.of("r")));
    var unknownAction = Fx.humanControl("bad-3", "decision-review", "human-domain-decision", "output", "routing",
        Map.of("roles", List.of("r")), "allowed_actions", List.of("APPROVE", "SHRUG"));
    var noHalt = new HumanControl("bad-4", "Halt", null, "human-halt", 1,
        Map.of("pattern", "halt-restart", "subject_kind", "system", "routing", Map.of("roles", List.of("r"))), Map.of());
    var badDuration = Fx.humanControl("bad-5", "triage", "human-monitoring", "behaviour", "routing",
        Map.of("roles", List.of("r")), "sla_target", "thirty minutes");
    var noRoles = Fx.humanControl("bad-6", "triage", "human-monitoring", "behaviour");
    var badKind = Fx.humanControl("bad-7", "triage", "human-monitoring", "vibes", "routing", Map.of("roles", List.of("r")));

    var problems = Settings.problems(catalog(unknownPattern, wrongFlavor, unknownAction, noHalt, badDuration, noRoles,
        badKind, badKind));
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-1:") && p.contains("not one of the fourteen")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-2:") && p.contains("flavor")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-3:") && p.contains("SHRUG")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-4:") && p.contains("halt_config")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-5:") && p.contains("ISO 8601")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-6:") && p.contains("no roles")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad-7:") && p.contains("subject kind")), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.contains("used twice")), problems.toString());

    var thrown = assertThrows(IllegalStateException.class, () -> Controls.of(catalog(unknownPattern, wrongFlavor)));
    assertTrue(thrown.getMessage().contains("bad-1") && thrown.getMessage().contains("bad-2"));
  }

  @Test
  void durationsReadWeeksMonthsAndYears() {
    assertEquals(Duration.ofMinutes(30), Durations.parse("PT30M"));
    assertEquals(Duration.ofDays(14), Durations.parse("P2W"));
    assertEquals(Duration.ofDays(1).plusHours(2), Durations.parse("P1DT2H"));
    assertEquals(Duration.ofDays(30), Durations.parse("P1M"));
    assertEquals(Duration.ofDays(365), Durations.parse("P1Y"));
    assertEquals(Duration.ofMillis(1500), Durations.parse("PT1.5S"));
    assertFalse(Durations.valid("P"));
    assertFalse(Durations.valid("PT"));
    assertFalse(Durations.valid("30m"));
    assertThrows(IllegalArgumentException.class, () -> Durations.parse("soon"));
  }
}
