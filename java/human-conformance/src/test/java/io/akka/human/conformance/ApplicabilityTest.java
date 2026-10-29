package io.akka.human.conformance;

import static io.akka.human.conformance.Fixtures.chain;
import static io.akka.human.conformance.Fixtures.control;
import static io.akka.human.conformance.Fixtures.person;
import static io.akka.human.conformance.Fixtures.registry;
import static io.akka.human.conformance.Fixtures.target;
import static io.akka.human.conformance.Fixtures.twoPerson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ApplicabilityTest {
  private static final Registry TWO_OWNERS = registry(
      person("amy@x", true, "owner"),
      person("bob@x", true, "owner"),
      person("out@x", true, "other"));

  /** Skip reason per scenario name, for the scenarios that run for the target's pattern. */
  private static Map<String, String> reasons(Target t) {
    return PatternScenarios.all().stream()
        .filter(s -> s.onlyPattern() == null || s.onlyPattern().equals(t.control().pattern()))
        .collect(Collectors.toMap(Scenario::name, s -> String.valueOf(s.skipReason().apply(t))));
  }

  private static String reason(Target t, String scenario) {
    String r = reasons(t).get(scenario);
    return "null".equals(r) ? null : r;
  }

  private static Scenario scenario(String name) {
    return PatternScenarios.all().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }

  private static ObjectNode haltControl(String id, String target, String... actions) {
    ObjectNode c = control(id, "halt-restart", List.of("safety"), actions);
    c.putObject("halt").put("target", target);
    c.put("accountableOwner", "safety");
    return c;
  }

  @Test
  void splitHaltControlsAreChosenByWhatEachScenarioNeeds() {
    Control exercise = new Control(haltControl("h1", "playbooks", "HALT"));
    Control restart = new Control(twoPerson(haltControl("h2", "playbooks", "APPROVE", "DENY", "ESCALATE")));
    Control elsewhere = new Control(haltControl("h3", "billing", "HALT"));
    Registry safety = registry(person("a@x", true, "safety"), person("b@x", true, "safety"),
        person("c@x", true, "safety"));
    List<Control> controls = List.of(exercise, restart, elsewhere);

    for (String s : List.of("raise", "decide", "late-decision", "recuse", "reopen", "timeout", "restart")) {
      Suite.Choice choice = Suite.choose(scenario(s), "halt-restart", safety, controls);
      assertNull(choice.skipReason(), s);
      assertEquals("h2", choice.target().control().id(), s);
    }
    assertEquals("h1", Suite.choose(scenario("halt"), "halt-restart", safety, controls).target().control().id());

    Target t = new Target(restart, safety, controls);
    assertEquals("h1", Flows.exerciseControl(t).id());
    assertEquals(java.util.Set.of("h1", "h2"), Flows.pairedIds(t));

    Suite.Choice exerciseOnly = Suite.choose(scenario("raise"), "halt-restart", safety, List.of(exercise));
    assertTrue(exerciseOnly.skipReason().contains("allows no APPROVE"), exerciseOnly.skipReason());
    Suite.Choice restartOnly = Suite.choose(scenario("halt"), "halt-restart", safety, List.of(restart));
    assertTrue(restartOnly.skipReason().contains("allows no HALT"), restartOnly.skipReason());
  }

  @Test
  void aHaltIsMatchedOnItsTarget() {
    Control restart = new Control(haltControl("h2", "playbooks", "APPROVE", "DENY"));
    ObjectNode page = Json.object();
    ObjectNode halt = page.putArray("halts").addObject();
    halt.put("target", "playbooks").put("halted", true);
    halt.putObject("control").put("rowId", "h1");

    assertTrue(Flows.halted(page, restart));
    halt.put("halted", false);
    assertFalse(Flows.halted(page, restart));
  }

  @Test
  void amongSeveralControlsTheFirstThatAppliesIsChosen() {
    Control plain = new Control(control("c1", "action-approval", List.of("owner"), "APPROVE", "DENY"));
    Control asking = new Control(control("c2", "action-approval", List.of("owner"), "APPROVE", "REQUEST_MORE_INFO"));
    List<Control> controls = List.of(plain, asking);

    assertEquals("c1", Suite.choose(scenario("decide"), "action-approval", TWO_OWNERS, controls)
        .target().control().id());
    assertEquals("c2", Suite.choose(scenario("request-more-info"), "action-approval", TWO_OWNERS, controls)
        .target().control().id());
    Suite.Choice none = Suite.choose(scenario("send-back"), "action-approval", TWO_OWNERS, controls);
    assertEquals("c1", none.target().control().id());
    assertEquals("none of the 2 action-approval controls applies: the control does not allow SEND_BACK (c1, c2)",
        none.skipReason());
  }

  @Test
  void whenNoControlAppliesEachDistinctReasonIsNamedWithItsControls() {
    Control exercise = new Control(haltControl("h1", "playbooks", "HALT"));
    ObjectNode restartSettings = twoPerson(haltControl("h2", "playbooks", "APPROVE", "DENY"));
    ((ObjectNode) restartSettings.path("routing")).putArray("independence");
    Control restart = new Control(restartSettings);
    Registry safety = registry(person("a@x", true, "safety"), person("b@x", true, "safety"),
        person("c@x", true, "safety"));

    String reason = Suite.choose(scenario("independence"), "halt-restart", safety, List.of(exercise, restart))
        .skipReason();
    assertTrue(reason.contains("only exercises halts, so it raises no restart request (h1)"), reason);
    assertTrue(reason.contains("the control sets no independence rule (h2)"), reason);
  }

  @Test
  void anActionApprovalControlRunsTheLifecycleButNotPatternSpecificScenarios() {
    ObjectNode settings = chain(control("c1", "action-approval", List.of("owner"),
        "APPROVE", "DENY", "REQUEST_MORE_INFO", "ESCALATE"), "owners", "owner", "heads", "head");
    ((ObjectNode) settings).set("sticky", Json.array(List.of("APPROVE_ONCE", "APPROVE_FOR_PATTERN")));
    Target t = target(settings, TWO_OWNERS);

    for (String s : List.of("raise", "claim", "decide", "independence", "timeout", "late-decision", "escalate",
        "request-more-info", "recuse", "standing-decision", "dedupe", "extend", "withdraw")) {
      assertNull(reason(t, s), s);
    }
    assertEquals("the review mode is SINGLE", reason(t, "multi-vote"));
    assertEquals("the control does not allow SEND_BACK", reason(t, "send-back"));
    assertFalse(reasons(t).containsKey("halt"));
    assertFalse(reasons(t).containsKey("flag"));
  }

  @Test
  void escalationNeedsANextQueue() {
    Target t = target(control("c1", "triage", List.of("owner"), "APPROVE", "ESCALATE"), TWO_OWNERS);

    assertEquals("the control's escalation chain has no next queue", reason(t, "escalate"));
  }

  @Test
  void aTwoPersonControlNeedsTwoQualifiedReviewers() {
    ObjectNode settings = twoPerson(control("c1", "change-authorization", List.of("owner"),
        "APPROVE", "DENY", "SEND_BACK"));
    Target enough = target(settings, TWO_OWNERS);
    Target short1 = target(settings, registry(person("amy@x", true, "owner")));

    assertNull(reason(enough, "multi-vote"));
    assertNull(reason(enough, "decide"));
    assertNull(reason(enough, "send-back"));
    assertTrue(reason(short1, "decide").contains("1 qualified reviewer(s)"), reason(short1, "decide"));
    assertTrue(reason(short1, "multi-vote").contains("2 needed"));
  }

  @Test
  void aControlWithoutADeadlineHasNoTimeoutLateDecisionOrExtension() {
    ObjectNode settings = control("c1", "action-approval", List.of("owner"), "APPROVE", "DENY");
    settings.putNull("slaTarget");
    Target t = target(settings, TWO_OWNERS);

    assertEquals("the control sets no deadline (slaTarget)", reason(t, "timeout"));
    assertEquals("the control sets no deadline (slaTarget)", reason(t, "late-decision"));
    assertEquals("the control sets no deadline (slaTarget)", reason(t, "extend"));
  }

  @Test
  void overrideEventsAreOnlyCreatedThroughTheOverridesRoute() {
    ObjectNode settings = control("c1", "override", List.of("owner"), "OVERRIDE", "DISREGARD", "REVERSE");
    settings.remove("timeoutBehaviour");
    settings.put("accountableOwner", "owner");
    Target t = target(settings, TWO_OWNERS);

    assertTrue(reason(t, "raise").contains("POST /oversight/overrides"));
    assertTrue(reason(t, "dedupe").contains("POST /oversight/overrides"));
    assertNull(reason(t, "override"));
    assertNull(reason(t, "reopen"));
    assertEquals("OVERRIDE", Flows.overrideAction(t.control()));
  }

  @Test
  void haltRestartRaisesThroughTheHaltsRouteAndVotesExcludeTheRequester() {
    ObjectNode settings = twoPerson(control("c1", "halt-restart", List.of("safety"), "HALT", "APPROVE", "DENY"));
    Target three = target(settings, registry(person("a@x", true, "safety"), person("b@x", true, "safety"),
        person("c@x", true, "safety")));
    Target two = target(settings, registry(person("a@x", true, "safety"), person("b@x", true, "safety")));

    assertNull(reason(three, "raise"));
    assertNull(reason(three, "halt"));
    assertNull(reason(three, "restart"));
    assertTrue(reason(three, "multi-vote").contains("restart scenario"));
    assertTrue(reason(three, "dedupe").contains("POST /oversight/halts"));
    assertNotNull(reason(two, "restart"));
    assertEquals("DENY", Flows.settleAction(three.control()));
  }

  @Test
  void contestNeedsAnotherControlToContest() {
    ObjectNode appeal = control("c7", "contest-appeal", List.of("appeals"), "APPROVE", "REVERSE");
    ObjectNode review = control("c2", "decision-review", List.of("appeals"), "APPROVE", "DENY");
    Registry registry = registry(person("amy@x", true, "appeals"), person("bob@x", true, "appeals"));
    Control a = new Control(appeal);
    Target alone = new Target(a, registry, List.of(a));
    Target withSource = new Target(a, registry, List.of(new Control(review), a));

    assertTrue(reason(alone, "contest").contains("no other control"));
    assertNull(reason(withSource, "contest"));
    Flows.ContestSource source = Flows.contestSource(withSource).orElseThrow();
    assertEquals("c2", source.control().id());
    assertTrue(source.firstReviewerHearsAppeals());
  }

  @Test
  void theDisallowedActionIsOutsideTheEventsListAndNeverRecuse() {
    ObjectNode event = Json.object();
    event.set("allowedActions", Json.array(List.of("APPROVE", "DENY", "EDIT", "REDIRECT", "REPLAN", "ESCALATE", "HALT")));

    assertEquals("REQUEST_MORE_INFO", Flows.disallowedAction(event));
  }
}
