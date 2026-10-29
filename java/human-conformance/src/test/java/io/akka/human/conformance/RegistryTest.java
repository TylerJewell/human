package io.akka.human.conformance;

import static io.akka.human.conformance.Fixtures.authority;
import static io.akka.human.conformance.Fixtures.control;
import static io.akka.human.conformance.Fixtures.expired;
import static io.akka.human.conformance.Fixtures.person;
import static io.akka.human.conformance.Fixtures.registry;
import static io.akka.human.conformance.Fixtures.reportsTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegistryTest {
  private static List<String> subjects(List<Person> people) {
    return people.stream().map(Person::subject).toList();
  }

  @Test
  void decidersHoldTheRoleAreQualifiedAndAreOrderedBySubject() {
    Registry registry = registry(
        person("zed@x", true, "owner"),
        person("amy@x", true, "owner", "other"),
        person("bob@x", false, "owner"),
        person("cat@x", true, "other"));
    Control c = new Control(control("c1", "action-approval", List.of("owner"), "APPROVE", "DENY"));

    assertEquals(List.of("amy@x", "zed@x"), subjects(registry.deciders(c, Set.of(), "APPROVE")));
  }

  @Test
  void decidersExcludeTheBarredAndThoseWithoutAuthorityForTheAction() {
    Registry registry = registry(
        person("amy@x", true, "owner"),
        authority(person("bob@x", true, "owner"), "*", "DENY"),
        person("cat@x", true, "owner"));
    Control c = new Control(control("c1", "action-approval", List.of("owner"), "APPROVE", "DENY"));

    assertEquals(List.of("cat@x"), subjects(registry.deciders(c, Set.of("amy@x"), "APPROVE")));
    assertEquals(List.of("bob@x", "cat@x"), subjects(registry.deciders(c, Set.of("amy@x"), "DENY")));
  }

  @Test
  void anAttestationTheRoutingNamesMustBeHeldUnexpired() {
    ObjectNode settings = control("c1", "decision-review", List.of("reviewer"), "APPROVE");
    ((ObjectNode) settings.path("routing")).set("attestations", Json.array(List.of("oversight-training")));
    Registry registry = registry(person("amy@x", true, "reviewer"), expired(person("bob@x", true, "reviewer")));
    Control c = new Control(settings);

    assertEquals(List.of("amy@x"), subjects(registry.deciders(c, Set.of(), "APPROVE")));
    assertEquals("bob@x", registry.unqualified(c).orElseThrow().subject());
  }

  @Test
  void anUnqualifiedHolderOfTheRoleIsPreferredOverAnyUnqualifiedPerson() {
    Registry registry = registry(
        person("amy@x", false, "other"),
        person("bob@x", false, "reviewer"),
        person("cat@x", true, "reviewer"));
    Control c = new Control(control("c1", "decision-review", List.of("reviewer"), "APPROVE"));

    assertEquals("bob@x", registry.unqualified(c).orElseThrow().subject());
  }

  @Test
  void withoutRolesPrefersAQualifiedPerson() {
    Registry registry = registry(
        person("amy@x", false, "other"),
        person("bob@x", true, "other"),
        person("cat@x", true, "reviewer"));
    Control c = new Control(control("c1", "decision-review", List.of("reviewer"), "APPROVE"));

    assertEquals("bob@x", registry.withoutRoles(c).orElseThrow().subject());
  }

  @Test
  void theAccountableOwnerMayNameARoleOrAPerson() {
    ObjectNode byRole = control("c1", "action-approval", List.of("owner"), "APPROVE");
    byRole.put("accountableOwner", "head");
    ObjectNode byPerson = control("c2", "action-approval", List.of("owner"), "APPROVE");
    byPerson.put("accountableOwner", "zed@x");
    Registry registry = registry(
        person("amy@x", false, "head"),
        person("bob@x", true, "head"),
        person("zed@x", true, "owner"));

    assertEquals("bob@x", registry.accountable(new Control(byRole)).orElseThrow().subject());
    assertEquals("zed@x", registry.accountable(new Control(byPerson)).orElseThrow().subject());
    assertTrue(registry.isAccountable(registry.find("amy@x").orElseThrow(), new Control(byRole)));
  }

  @Test
  void anOutsiderHoldsNoRoleOfAnyQueueAndIsNotAccountable() {
    ObjectNode settings = Fixtures.chain(control("c1", "action-approval", List.of("owner"), "APPROVE"),
        "owners", "owner", "heads", "head");
    settings.put("accountableOwner", "auditor");
    Registry registry = registry(
        person("amy@x", true, "head"),
        person("bob@x", true, "auditor"),
        person("cat@x", true, "owner"),
        person("cid@x", false, "other"),
        person("dan@x", true, "other"),
        person("eve@x", true, "other"));
    Control c = new Control(settings);

    assertEquals("dan@x", registry.outsider(c, Set.of()).orElseThrow().subject());
    assertEquals("eve@x", registry.outsider(c, Set.of("dan@x")).orElseThrow().subject());
    assertEquals("dan@x", registry.proposer(c).subject());
  }

  @Test
  void theProposerIsTheSuitesAgentWhenEveryoneHasAPart() {
    Registry registry = registry(person("amy@x", true, "owner"));
    Control c = new Control(control("c1", "action-approval", List.of("owner"), "APPROVE"));

    assertSame(Person.AGENT, registry.proposer(c));
    assertFalse(registry.outsider(c, Set.of()).isPresent());
  }

  @Test
  void aReportingLineFollowsReportsToUpThroughTheManagers() {
    Registry registry = registry(
        reportsTo(person("amy@x", true, "clerk"), "bob@x"),
        reportsTo(person("bob@x", true, "clerk"), "dan@x"),
        person("cat@x", true, "appeals"),
        person("dan@x", true, "appeals"));
    Control c = new Control(control("c1", "contest-appeal", List.of("appeals"), "APPROVE"));

    Registry.Line line = registry.reportingLine(c, "APPROVE").orElseThrow();
    assertEquals("amy@x", line.first().subject());
    assertEquals("dan@x", line.line().subject());
  }

  @Test
  void aSubordinateIsNotInTheFirstDecidersLine() {
    Registry registry = registry(
        person("amy@x", true, "clerk"),
        reportsTo(person("bob@x", true, "appeals"), "amy@x"));
    Control c = new Control(control("c1", "contest-appeal", List.of("appeals"), "APPROVE"));

    assertFalse(registry.reportingLine(c, "APPROVE").isPresent());
  }

  @Test
  void theLiftRequesterIsTheLastExerciserAndExercisersFollowTheHaltConfig() {
    ObjectNode settings = control("c1", "halt-restart", List.of("safety"), "HALT", "APPROVE", "DENY");
    settings.putObject("halt").set("exerciser_personae", Json.array(List.of("operator")));
    Registry registry = registry(
        person("amy@x", true, "operator"),
        person("bob@x", true, "operator", "safety"),
        person("cat@x", true, "safety"));
    Control c = new Control(settings);

    assertEquals(List.of("amy@x", "bob@x"), subjects(registry.exercisers(c)));
    assertEquals("bob@x", registry.liftRequester(c).orElseThrow().subject());
  }

  @Test
  void theStampIsARehearsalFromTheSuite() {
    Person p = registry(person("amy@x", true, "owner")).find("amy@x").orElseThrow();

    ObjectNode stamp = p.stamp();
    assertEquals("amy@x", stamp.path("subject").asText());
    assertEquals("human-conformance", stamp.path("identityProvider").asText());
    assertTrue(stamp.path("rehearsal").asBoolean());
    assertEquals("owner", stamp.path("roles").get(0).asText());
  }
}
