package io.akka.human.samples.contextlayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.State;
import io.akka.human.testkit.HumanTestKit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** contextlayer's flows, raised through its domain routes and decided as its people would. */
public class ContextLayerIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration WAIT = Duration.ofSeconds(20);

  private static final Reviewer ADA = HumanTestKit.reviewer("ada.approver@example.com", "approver");
  private static final Reviewer BEN = HumanTestKit.reviewer("ben.approver@example.com", "approver");
  private static final Reviewer CLEO = HumanTestKit.reviewer("cleo.approver@example.com", "approver", "operator");
  private static final Reviewer RITA = HumanTestKit.reviewer("rita.reviewer@example.com", "reviewer");
  private static final Reviewer OLGA = HumanTestKit.reviewer("olga.operator@example.com", "operator", "reviewer");
  private static final Reviewer OMAR = HumanTestKit.reviewer("omar.operator@example.com", "operator");
  private static final Reviewer ALICE = HumanTestKit.reviewer("alice.admin@example.com", "admin");

  private HumanTestKit kit;

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAdditionalConfig("human.register = false");
  }

  @BeforeEach
  public void kit() {
    kit = new HumanTestKit(componentClient, getDependency(HumanPlugins.class));
  }

  @Test
  public void aLinkIsConfirmedByAReviewerWhoLoadedNeitherRecord() {
    var key = "pat-" + UUID.randomUUID() + "@example.com";
    var first = post("/identity/records", record(key, "crm", "c-1", "omar.operator@example.com", "Pat Lee"));
    assertEquals("SINGLE", first.body().get("status").asText());
    var link = post("/identity/records", record(key, "billing", "b-7", "olga.operator@example.com", "Pat Lee")).body();
    assertEquals("PENDING", link.get("status").asText());
    assertEquals("crm", link.at("/left/source").asText());
    assertEquals("billing", link.at("/right/source").asText());
    var eventId = link.get("eventId").asText();

    var event = kit.event(eventId);
    assertEquals("match-adjudication", event.settings().pattern().id());
    assertEquals(List.of("omar.operator@example.com", "olga.operator@example.com"), event.independentOf());
    assertEquals(403, kit.claim(eventId, OLGA).refusal().status());

    kit.claimAndDecide(eventId, RITA, Action.APPROVE, "Same name, same email, same date of birth.");
    kit.awaitApplied(eventId);
    var clusterId = link.get("clusterId").asText();
    assertEquals("CONFIRMED", get("/links/" + clusterId).body().get("status").asText());
    assertEquals("rita.reviewer@example.com", get("/links/" + clusterId).body().get("reviewedBy").asText());
  }

  @Test
  public void aChangedClusterSupersedesItsPendingReview() {
    var key = "sam-" + UUID.randomUUID() + "@example.com";
    post("/identity/records", record(key, "crm", "c-2", "omar.operator@example.com", "Sam Roe"));
    var firstEvent = post("/identity/records", record(key, "billing", "b-8", "omar.operator@example.com", "Sam Roe"))
        .body().get("eventId").asText();
    var second = post("/identity/records", record(key, "support", "s-3", "omar.operator@example.com", "Sam Roe")).body();
    assertEquals(State.WITHDRAWN, kit.event(firstEvent).state());
    assertFalse(second.get("eventId").asText().equals(firstEvent));
  }

  @Test
  public void anActionWaitsForApprovalAndAnUnknownOutcomeIsResolved() {
    var playbook = livePlaybook();
    var low = action(playbook, "send-summary", "ok", "LOW");
    awaitAction(low, a -> a.get("status").asText().equals("SUCCEEDED"));

    var key = action(playbook, "refund-order", "refund 120 EUR, timeout", "HIGH");
    var waiting = awaitAction(key, a -> a.get("status").asText().equals("AWAITING_APPROVAL"));
    var approval = waiting.get("approvalEventId").asText();
    assertEquals(403, kit.claim(approval, ADA).refusal().status());
    kit.claimAndDecide(approval, BEN, Action.APPROVE, "The refund matches the complaint.");

    var unknown = awaitAction(key, a -> a.get("status").asText().equals("OUTCOME_UNKNOWN") && a.hasNonNull("unknownEventId"));
    var unknownEvent = unknown.get("unknownEventId").asText();
    assertEquals("outcome-reconciliation", kit.event(unknownEvent).settings().pattern().id());
    assertEquals(403, kit.decide(unknownEvent, HumanTestKit.reviewer("ada.approver@example.com", "reviewer"),
        Action.APPROVE, "it did").refusal().status());
    HumanTestKit.ok(kit.decide(unknownEvent, RITA, Action.DENY, "The payments system has no such refund."), "resolve");
    var resolved = awaitAction(key, a -> a.hasNonNull("resolution"));
    assertEquals("DID_NOT_TAKE_EFFECT", resolved.get("resolution").asText());
    assertEquals("rita.reviewer@example.com", resolved.get("resolvedBy").asText());
  }

  @Test
  public void releasesConnectorsAndJoinsKeepTheirIndependence() {
    var playbook = create();
    var release = post("/assistants/" + playbook + "/releases", Map.of("version", "1.0", "by", "ada.approver@example.com"))
        .body().get("eventId").asText();
    HumanTestKit.ok(kit.decide(release, ADA, Action.APPROVE, "My own playbook's release."), "release");
    awaitPlaybook(playbook, p -> p.at("/playbook/releases/0/status").asText().equals("APPROVED"));

    post("/connectors/crm/versions", Map.of("hash", "h-" + UUID.randomUUID(), "by", "cleo.approver@example.com"));
    var activation = post("/connectors/crm/activate", Map.of("mode", "LIVE", "by", "cleo.approver@example.com"))
        .body().get("eventId").asText();
    assertEquals(403, kit.decide(activation, CLEO, Action.APPROVE, "mine").refusal().status());
    HumanTestKit.ok(kit.decide(activation, BEN, Action.APPROVE, "Reviewed the pack's actions."), "activate");
    Awaitility.await().atMost(WAIT).until(() -> get("/connectors/crm").body().get("mode").asText().equals("LIVE"));

    var claims = setDomain("claims-" + UUID.randomUUID(), "ben.approver@example.com");
    var wealth = setDomain("wealth-" + UUID.randomUUID(), "alice.admin@example.com");
    var join = post("/join-rules", Map.of("a", claims, "b", wealth, "by", "cleo.approver@example.com", "reason", "advice"))
        .body().get("eventId").asText();
    assertEquals(List.of("ben.approver@example.com", "alice.admin@example.com"), kit.event(join).independentOf());
    assertEquals(403, kit.decide(join, BEN, Action.APPROVE, "I changed it").refusal().status());
    HumanTestKit.ok(kit.decide(join, ADA, Action.APPROVE, "The join is needed for advice."), "join");
    Awaitility.await().atMost(WAIT).until(() -> {
      for (var j : get("/domains").body().get("joins")) {
        if (j.get("a").asText().equals(claims) && j.get("status").asText().equals("APPROVED")) return true;
      }
      return false;
    });
  }

  @Test
  public void aHaltStopsAPlaybookUntilTwoApproversRestartIt() {
    var playbook = livePlaybook();
    var halt = post("/assistants/" + playbook + "/halt", Map.of("reviewer", reviewer(ADA), "reason", "Wrong refunds"));
    assertEquals(200, halt.status(), halt.body()::toString);
    assertEquals(ContextLayerSetup.HALT, kit.event(halt.body().get("eventId").asText()).settings().control().rowId());
    assertTrue(get("/assistants/" + playbook).body().get("halted").asBoolean());
    var refused = action(playbook, "refund-order", "refund 5 EUR", "LOW");
    awaitAction(refused, a -> a.get("status").asText().equals("DENIED"));

    var restart = post("/assistants/" + playbook + "/restart", Map.of("reviewer", reviewer(ADA), "reason", "Fixed"));
    var restartId = restart.body().get("eventId").asText();
    assertEquals(ContextLayerSetup.RESTART, kit.event(restartId).settings().control().rowId());
    assertEquals(State.AWAITING_SECOND,
        HumanTestKit.ok(kit.decide(restartId, ADA, Action.APPROVE, "The refund rule is fixed."), "first").state());
    HumanTestKit.ok(kit.decide(restartId, BEN, Action.APPROVE, "Checked the fix."), "second");
    Awaitility.await().atMost(WAIT).until(() -> !get("/assistants/" + playbook).body().get("halted").asBoolean());
  }

  @Test
  public void aWaiverLapsesAtItsEndDate() {
    var rowId = "custom-cl0045";
    var tooLong = kit.now().plus(Duration.ofDays(400)).toString();
    assertEquals(422, post("/controls/" + rowId + "/waivers", Map.of("by", "ada.approver@example.com", "reason", "x",
        "until", tooLong)).status());
    var until = kit.now().plus(Duration.ofDays(2)).toString();
    var waiver = post("/controls/" + rowId + "/waivers", Map.of("by", "ada.approver@example.com",
        "reason", "Operators are on leave this week", "until", until)).body().get("eventId").asText();
    HumanTestKit.ok(kit.decide(waiver, ALICE, Action.APPROVE, "Two days is acceptable."), "waive");
    Awaitility.await().atMost(WAIT).until(() -> get("/controls/" + rowId + "/claims").body().get("waived").asBoolean());
    kit.advance(Duration.ofDays(3));
    assertFalse(get("/controls/" + rowId + "/claims").body().get("waived").asBoolean());
  }

  @Test
  public void aWatcherOpensACaseThatAReviewerResolves() {
    var watcher = "w-" + UUID.randomUUID().toString().substring(0, 6);
    var reading = Map.of("title", "Refunds above normal", "metric", "refunds-per-hour", "value", 42, "threshold", 20,
        "comparison", ">", "headline", Map.of("customer", "c-12", "region", "EU"));
    var opened = post("/watchers/" + watcher + "/readings", reading).body();
    var caseId = opened.get("caseId").asText();
    var again = post("/watchers/" + watcher + "/readings", reading).body();
    assertEquals(caseId, again.get("caseId").asText());
    var eventId = opened.get("eventId").asText();
    assertEquals(2, kit.event(eventId).trigger().occurrences());
    assertEquals("EU", get("/cases/" + caseId).body().at("/headline/region").asText());

    HumanTestKit.ok(kit.decide(eventId, RITA, Action.APPROVE, "A promotion explains the refunds."), "resolve");
    Awaitility.await().atMost(WAIT).until(() -> get("/cases/" + caseId).body().get("status").asText().equals("RESOLVED"));
  }

  @Test
  public void questionsRunOnlyWhenAPersonRunsThem() {
    var asked = post("/questions", Map.of("text", "Which EU customers have open claims?", "by", "ada.approver@example.com"));
    var eventId = asked.body().get("eventId").asText();
    var questionId = kit.event(eventId).subject().ref();
    assertEquals("DRAFTED", get("/questions/" + questionId).body().get("status").asText());
    HumanTestKit.ok(kit.decide(eventId, OMAR, Action.APPROVE, null), "run");
    Awaitility.await().atMost(WAIT).until(() -> get("/questions/" + questionId).body().get("status").asText().equals("RAN"));
    assertEquals("omar.operator@example.com", get("/questions/" + questionId).body().get("ranBy").asText());
  }

  @Test
  public void aFailedStepIsHandedToAnOperatorWithoutStoppingTheRun() {
    var playbook = create();
    var runId = post("/assistants/" + playbook + "/runs", Map.of("steps", List.of("read", "score", "notify"),
        "failing", List.of("score"), "by", "ada.approver@example.com")).body().get("runId").asText();
    var run = Awaitility.await().atMost(WAIT).until(() -> get("/runs/" + runId).body(),
        r -> r.get("status").asText().equals("DONE"));
    assertEquals(1, run.get("handOffs").size());
    var eventId = run.get("handOffs").get(0).asText();
    HumanTestKit.ok(kit.decide(eventId, OMAR, Action.APPROVE, "Restarted the scoring service."), "acknowledge");
    assertEquals("acknowledged by omar.operator@example.com", kit.awaitApplied(eventId).outcome().applied());
  }

  @Test
  public void anAcceptanceHoldsForTheCatalogItWasGivenUnder() {
    var rowId = "custom-cl0004";
    var eventId = post("/controls/" + rowId + "/acceptance", Map.of("by", "ada.approver@example.com",
        "reason", "Reviewed for the project")).body().get("eventId").asText();
    HumanTestKit.ok(kit.decide(eventId, BEN, Action.APPROVE, "Fits the project."), "accept");
    Awaitility.await().atMost(WAIT).until(() -> get("/controls/" + rowId + "/claims").body()
        .get("acceptanceState").asText().equals("current"));
  }

  @Test
  public void theScreensAreServedBesideTheApi() {
    var index = httpClient.GET("/").invoke();
    assertEquals(200, index.status().intValue());
    assertTrue(index.body().utf8String().contains("<html"));
    assertTrue(httpClient.GET("/decisions/he-1").invoke().body().utf8String().contains("<html"));
    assertEquals("contextlayer", get("/oversight/info").body().get("service").asText());
    assertEquals(17, get("/oversight/controls").body().get("controls").size());
  }

  // ---- helpers

  private record Reply(int status, JsonNode body) {}

  private String create() {
    return post("/assistants", Map.of("name", "Refunds", "by", "ada.approver@example.com")).body().at("/playbook/id").asText();
  }

  /** A playbook with an approved release, moved to LIVE. */
  private String livePlaybook() {
    var playbook = create();
    var release = post("/assistants/" + playbook + "/releases", Map.of("version", "1.0", "by", "ada.approver@example.com"))
        .body().get("eventId").asText();
    HumanTestKit.ok(kit.decide(release, BEN, Action.APPROVE, "Release reviewed."), "release");
    awaitPlaybook(playbook, p -> p.at("/playbook/releases/0/status").asText().equals("APPROVED"));
    var stage = post("/assistants/" + playbook + "/stage", Map.of("stage", "LIVE", "by", "ada.approver@example.com"))
        .body().get("eventId").asText();
    HumanTestKit.ok(kit.decide(stage, BEN, Action.APPROVE, "Shadow results were clean."), "stage");
    awaitPlaybook(playbook, p -> p.at("/playbook/stage").asText().equals("LIVE"));
    return playbook;
  }

  private String setDomain(String name, String by) {
    var eventId = post("/domains/" + name, Map.of("entityTypes", List.of("Claim"), "readerRoles", List.of("reviewer"),
        "by", by, "reason", "New data")).body().get("eventId").asText();
    HumanTestKit.ok(kit.decide(eventId, ALICE, Action.APPROVE, "Domain reviewed."), "domain");
    Awaitility.await().atMost(WAIT).until(() -> get("/domains").body().get("domains").has(name));
    return name;
  }

  private String action(String playbook, String type, String payload, String risk) {
    return post("/assistants/" + playbook + "/actions", Map.of("actionType", type, "payload", payload, "risk", risk,
        "requestedBy", "ada.approver@example.com")).body().get("actionKey").asText();
  }

  private JsonNode awaitAction(String key, Predicate<JsonNode> condition) {
    return Awaitility.await().atMost(WAIT).ignoreExceptions().until(() -> get("/actions/" + key).body(), condition);
  }

  private void awaitPlaybook(String id, Predicate<JsonNode> condition) {
    Awaitility.await().atMost(WAIT).until(() -> get("/assistants/" + id).body(), condition);
  }

  private static Map<String, Object> record(String key, String source, String ref, String loadedBy, String name) {
    return Map.of("rule", "email", "key", key, "entityType", "Customer", "source", source, "ref", ref,
        "loadedBy", loadedBy, "fields", Map.of("name", name, "email", key));
  }

  private static Map<String, Object> reviewer(Reviewer r) {
    return Map.of("subject", r.subject(), "roles", r.roles());
  }

  private Reply get(String path) {
    var response = httpClient.GET(path).invoke();
    return new Reply(response.status().intValue(), parse(response.body().toArray()));
  }

  private Reply post(String path, Object body) {
    try {
      var response = httpClient.POST(path).withRequestBody(ContentTypes.APPLICATION_JSON, MAPPER.writeValueAsBytes(body))
          .invoke();
      return new Reply(response.status().intValue(), parse(response.body().toArray()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode parse(byte[] bytes) {
    try {
      return bytes.length == 0 || (bytes[0] != '{' && bytes[0] != '[') ? MAPPER.nullNode() : MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
