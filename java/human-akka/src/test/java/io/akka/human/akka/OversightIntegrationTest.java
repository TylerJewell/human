package io.akka.human.akka;

import static io.akka.human.akka.TestPlugins.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.DependencyProvider;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.api.spi.ProofRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** The library's components in a running service, driven through /oversight and the client. */
public class OversightIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration WAIT = Duration.ofSeconds(15);

  private static final Map<String, Object> TYLER = reviewer("tyler.jewell@akka.io", "ai-system-owner", "head-of-operations",
      "safety-officer", "model-risk-owner");
  private static final Map<String, Object> LIND = reviewer("j.lind@example.com", "ai-system-owner", "head-of-operations");
  private static final Map<String, Object> DEVOS = reviewer("a.devos@example.com", "model-risk-owner");
  private static final Map<String, Object> OSEI = reviewer("k.osei@example.com", "case-reviewer");
  private static final Map<String, Object> PATEL = reviewer("s.patel@example.com", "safety-officer", "oversight-officer");
  private static final Map<String, Object> MOREAU = reviewer("l.moreau@example.com", "safety-officer", "feedback-triager");

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDependencyProvider(new DependencyProvider() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T getDependency(Class<T> type) {
        if (type == HumanPlugins.class) return (T) TestPlugins.PLUGINS;
        throw new IllegalArgumentException("no dependency " + type);
      }
    });
  }

  private record Reply(int status, JsonNode body) {}

  @Test
  public void servesTheReadRoutes() {
    var info = get("/oversight/info");
    assertEquals(200, info.status());
    assertConforms("ServiceInfo", info.body());
    assertEquals(16, info.body().get("controls").asInt());
    assertEquals(14, info.body().get("patterns").size());

    var controls = get("/oversight/controls");
    assertEquals(16, controls.body().get("controls").size());
    for (var c : controls.body().get("controls")) assertConforms("ControlSettings", c);

    assertConforms("OverseerRegistry", get("/oversight/registry").body());
    assertConforms("Summary", get("/oversight/summary").body());
    assertConforms("Quality", get("/oversight/quality?window=P30D").body());
    for (var h : get("/oversight/halts").body().get("halts")) assertConforms("Halt", h);
    assertEquals(422, get("/oversight/quality?window=soon").status());
    assertEquals(404, get("/oversight/events/he-nothing").status());
  }

  @Test
  public void raiseClaimDecideResumesTheWorkflow() throws IOException {
    var request = (ObjectNode) example("RaiseRequest.action.json");
    request.put("dedupeKey", "pay-" + UUID.randomUUID());
    request.put("matchKey", "pay:" + UUID.randomUUID());
    var workflowId = "pay-" + UUID.randomUUID();
    request.set("resumes", MAPPER.valueToTree(Map.of("kind", "workflow", "target", "payment", "id", workflowId)));
    var raised = post("/oversight/dev/events", request);
    assertEquals(200, raised.status(), raised.body()::toString);
    assertConforms("HumanEvent", raised.body());
    var id = raised.body().get("id").asText();
    assertEquals("OPEN", raised.body().get("state").asText());

    assertEquals(200, post("/oversight/dev/events", request).status());
    assertEquals(2, get("/oversight/events/" + id).body().at("/trigger/occurrences").asInt());

    var noClaim = post("/oversight/events/" + id + "/decision", decision(TYLER, "APPROVE", "fine"));
    assertEquals(409, noClaim.status());
    assertEquals(403, post("/oversight/events/" + id + "/claim", Map.of("reviewer", OSEI)).status());
    assertEquals(200, post("/oversight/events/" + id + "/claim", Map.of("reviewer", TYLER)).status());
    var other = post("/oversight/events/" + id + "/claim", Map.of("reviewer", LIND));
    assertEquals(409, other.status());
    assertEquals("CLAIMED", other.body().get("state").asText());
    assertConforms("Error", other.body());

    var rushed = post("/oversight/events/" + id + "/decision", decision(TYLER, "APPROVE", "fine"));
    assertEquals(422, rushed.status(), rushed.body()::toString);
    assertTrue(rushed.body().get("errors").size() > 0);
    assertEquals(422, post("/oversight/events/" + id + "/decision", decision(TYLER, "REVERSE", "no")).status());

    TestPlugins.CLOCK.advance(Duration.ofSeconds(11));
    var approval = decision(TYLER, "APPROVE", "The invoice matches.");
    approval.putArray("conditions").addObject().put("text", "Pay only once the purchase order is signed");
    var decided = post("/oversight/events/" + id + "/decision", approval);
    assertEquals(200, decided.status(), decided.body()::toString);
    assertEquals("DECIDED", decided.body().get("state").asText());
    assertEquals("Pay only once the purchase order is signed", decided.body().at("/outcome/conditions/0/text").asText());
    assertEquals(TestPlugins.CLOCK.now().getEpochSecond(), java.time.Instant.parse(decided.body().get("now").asText())
        .getEpochSecond(), 2);

    Awaitility.await().atMost(WAIT).until(() -> get("/oversight/events/" + id).body().get("state").asText().equals("APPLIED"));
    assertTrue(TestPlugins.RESUMED.contains(workflowId + ":APPROVE:COMPLETED"));
    var applied = get("/oversight/events/" + id).body();
    assertConforms("HumanEvent", applied);
    assertEquals("upheld", applied.get("conclusion").asText());

    Awaitility.await().atMost(WAIT).until(() -> TestPlugins.LEDGER.records.containsKey("pr-" + id + "-decision"));
    var proof = TestPlugins.LEDGER.records.get("pr-" + id + "-decision");
    assertEquals(ProofRecord.DECISION, proof.type());
    assertEquals("upheld", proof.payload().get("verdict"));

    var proofText = httpClient.GET("/oversight/proof").invoke();
    var lines = proofText.body().utf8String().lines().toList();
    assertTrue(lines.stream().anyMatch(l -> l.contains("pr-" + id + "-decision")));
    for (var line : lines) assertConforms("ProofRecord", parse(line.getBytes(StandardCharsets.UTF_8)));

    Awaitility.await().atMost(WAIT).until(() -> {
      var verdicts = get("/oversight/verdicts").body().get("verdicts");
      for (var v : verdicts) if (v.get("eventId").asText().equals(id)) return true;
      return false;
    });

    Awaitility.await().atMost(WAIT).until(() -> get("/oversight/events?state=resolved&control=pt-0001").body().get("total").asInt() > 0);
    var page = get("/oversight/events?state=resolved&control=pt-0001").body();
    assertConforms("EventsPage", page);
  }

  @Test
  public void aTimeoutAppliesTheDefault() {
    var human = new HumanClient(componentClient, TestPlugins.PLUGINS);
    var workflowId = "pay-" + UUID.randomUUID();
    var raised = human.raise(payment("Should the agent pay 2,000 EUR?").resumes(Resume.workflow("payment", workflowId))
        .build()).toCompletableFuture().join();
    awaitInView(raised.eventId());
    TestPlugins.CLOCK.advance(Duration.ofMinutes(31));
    var tick = post("/oversight/dev/tick", Map.of());
    assertEquals(200, tick.status());
    assertTrue(tick.body().get("expired").asInt() >= 1);
    var event = get("/oversight/events/" + raised.eventId()).body();
    assertEquals("TIMED_OUT", event.get("state").asText());
    Awaitility.await().atMost(WAIT).until(() -> TestPlugins.RESUMED.contains(workflowId + ":DENY:TIMED_OUT"));
    var decision = human.outcome(raised.eventId()).toCompletableFuture().join().orElseThrow();
    assertTrue(decision.timedOut());
    assertEquals("inconclusive", decision.conclusion());
  }

  @Test
  public void aDeadlineTimerActsWithoutATick() {
    // Timers run on real time; with the clock at real time the 3-second deadline acts within the test.
    TestPlugins.CLOCK.reset();
    var human = new HumanClient(componentClient, TestPlugins.PLUGINS);
    var raised = human.raise(Patterns.actionApproval("A short one?").control("t-0001")
        .subject(Subject.action("Pay 1 EUR", "pay", Map.of("amount", 1))).build()).toCompletableFuture().join();
    Awaitility.await().atMost(WAIT).until(() -> get("/oversight/events/" + raised.eventId()).body().get("state").asText()
        .equals("TIMED_OUT"));
  }

  @Test
  public void escalationMovesToTheNextQueueThenTimesOut() {
    var id = raiseChange("m.okafor@example.com");
    TestPlugins.CLOCK.advance(Duration.ofDays(1).plusMinutes(1));
    post("/oversight/dev/tick", Map.of());
    var escalated = get("/oversight/events/" + id).body();
    assertEquals("ESCALATED", escalated.get("state").asText());
    assertEquals(1, escalated.at("/escalation/level").asInt());
    assertEquals("head-of-operations", escalated.at("/routing/queue").asText());
    assertEquals(403, post("/oversight/events/" + id + "/decision", decision(DEVOS, "APPROVE", "ok")).status());

    TestPlugins.CLOCK.advance(Duration.ofDays(1).plusMinutes(1));
    post("/oversight/dev/tick", Map.of());
    var timedOut = get("/oversight/events/" + id).body();
    assertEquals("TIMED_OUT", timedOut.get("state").asText());
    assertEquals("breached", timedOut.at("/verdict/conclusion").asText());
    assertEquals("missed-deadline", timedOut.at("/verdict/reasons/0").asText());
  }

  @Test
  public void twoPersonChangeWithSendBack() {
    var id = raiseChange("m.okafor@example.com");
    var proposer = reviewer("m.okafor@example.com", "case-reviewer");
    assertEquals(200, post("/oversight/events/" + id + "/send-back",
        Map.of("reviewer", TYLER, "requested", "Add the offline evaluation.")).status());
    assertEquals(403, post("/oversight/events/" + id + "/resubmit", Map.of("reviewer", TYLER)).status());
    var resubmitted = post("/oversight/events/" + id + "/resubmit", Map.of("reviewer", proposer, "note", "added"));
    assertEquals("OPEN", resubmitted.body().get("state").asText());

    var first = post("/oversight/events/" + id + "/decision", decision(TYLER, "APPROVE", "Evaluation holds."));
    assertEquals("AWAITING_SECOND", first.body().get("state").asText(), first.body()::toString);
    assertEquals(409, post("/oversight/events/" + id + "/decision", decision(TYLER, "APPROVE", "again")).status());
    var second = post("/oversight/events/" + id + "/decision", decision(DEVOS, "APPROVE", "Agreed."));
    assertEquals("DECIDED", second.body().get("state").asText(), second.body()::toString);
    assertEquals(2, second.body().get("decisions").size());
  }

  @Test
  public void haltAndRestart() {
    TestPlugins.BACKLOG_DRAINED.set(false);
    assertEquals(403, post("/oversight/halts", Map.of("reviewer", TYLER.entrySet().stream()
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getKey().equals("roles")
            ? List.of("ai-system-owner") : e.getValue())), "control", "pt-0012", "lift", false, "reason", "drift")).status());
    var halt = post("/oversight/halts", Map.of("reviewer", PATEL, "control", "pt-0012", "lift", false,
        "reason", "Referral drift"));
    assertEquals(200, halt.status(), halt.body()::toString);
    assertEquals("DECIDED", halt.body().get("state").asText());
    var haltId = halt.body().get("id").asText();
    assertTrue(new HumanClient(componentClient, TestPlugins.PLUGINS).halted("eligibility-assessment")
        .toCompletableFuture().join());
    assertEquals(409, post("/oversight/halts", Map.of("reviewer", PATEL, "control", "pt-0012", "lift", false,
        "reason", "again")).status());
    Awaitility.await().atMost(WAIT).until(() -> TestPlugins.LEDGER.records.containsKey("pr-" + haltId + "-halt"));
    Awaitility.await().atMost(WAIT).until(() -> TestPlugins.LEDGER.records.containsKey("pr-" + haltId + "-decision"));

    var lift = post("/oversight/halts", Map.of("reviewer", TYLER, "control", "pt-0012", "lift", true,
        "reason", "Backlog drained"));
    assertEquals(200, lift.status(), lift.body()::toString);
    var liftId = lift.body().get("id").asText();
    assertEquals("OPEN", lift.body().get("state").asText());
    var refused = post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "ok"));
    assertEquals(409, refused.status(), refused.body()::toString);

    TestPlugins.BACKLOG_DRAINED.set(true);
    var first = post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "Backlog drained."));
    assertEquals("AWAITING_SECOND", first.body().get("state").asText(), first.body()::toString);
    var second = post("/oversight/events/" + liftId + "/decision", decision(MOREAU, "APPROVE", "Agreed."));
    assertEquals("DECIDED", second.body().get("state").asText(), second.body()::toString);
    Awaitility.await().atMost(WAIT).until(() -> !new HumanClient(componentClient, TestPlugins.PLUGINS)
        .halted("eligibility-assessment").toCompletableFuture().join());
    Awaitility.await().atMost(WAIT).until(() -> TestPlugins.LEDGER.records.containsKey("pr-" + liftId + "-lift"));
    var lifted = TestPlugins.LEDGER.records.get("pr-" + liftId + "-lift");
    assertEquals("pr-" + haltId + "-halt", lifted.payload().get("halt_ref"));
    assertConforms("ProofRecord", MAPPER.valueToTree(io.akka.human.engine.Json.proof(lifted)));
  }

  @Test
  public void aHaltIsExercisedAtOnceEvenUnderAMinimumReviewTime() {
    var halt = post("/oversight/halts", Map.of("reviewer", PATEL, "control", "t-0002", "lift", false,
        "reason", "Stop it now"));
    assertEquals(200, halt.status(), halt.body()::toString);
    assertEquals("DECIDED", halt.body().get("state").asText());
    assertTrue(new HumanClient(componentClient, TestPlugins.PLUGINS).halted("review-time-capability")
        .toCompletableFuture().join());

    var lift = post("/oversight/halts", Map.of("reviewer", TYLER, "control", "t-0002", "lift", true, "reason", "Safe again"));
    var liftId = lift.body().get("id").asText();
    assertEquals(409, post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "ok")).status());
    assertEquals(200, post("/oversight/events/" + liftId + "/claim", Map.of("reviewer", PATEL)).status());
    assertEquals(422, post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "too quick")).status());
  }

  @Test
  public void aStandingDecisionSettlesTheNextRaise() {
    var human = new HumanClient(componentClient, TestPlugins.PLUGINS);
    var key = "pay:" + UUID.randomUUID();
    var first = human.raise(payment("Pay S-55 900 EUR?").matchKey(key).build()).toCompletableFuture().join();
    assertEquals(200, post("/oversight/events/" + first.eventId() + "/claim", Map.of("reviewer", TYLER)).status());
    TestPlugins.CLOCK.advance(Duration.ofSeconds(11));
    var body = decision(TYLER, "APPROVE", "Regular supplier.");
    body.put("sticky", "APPROVE_FOR_PATTERN");
    var decided = post("/oversight/events/" + first.eventId() + "/decision", body);
    assertEquals(200, decided.status(), decided.body()::toString);
    var standingId = "sd-" + first.eventId();
    Awaitility.await().atMost(WAIT).until(() -> has(get("/oversight/standing").body().get("standing"), standingId));
    for (var d : get("/oversight/standing").body().get("standing")) assertConforms("StandingDecision", d);

    var second = human.raise(payment("Pay S-55 900 EUR again?").matchKey(key).build()).toCompletableFuture().join();
    assertEquals("DECIDED", second.state());
    assertTrue(second.settled().isPresent());
    assertEquals(standingId, second.settled().get().standingDecisionId());

    var revoked = post("/oversight/standing/" + standingId + "/revoke", Map.of("reviewer", TYLER, "reason", "Supplier changed"));
    assertEquals(200, revoked.status(), revoked.body()::toString);
    assertEquals("REVOKED", revoked.body().get("state").asText());
    var third = human.raise(payment("Pay S-55 900 EUR a third time?").matchKey(key).build()).toCompletableFuture().join();
    assertEquals("OPEN", third.state());
  }

  @Test
  public void flagsContestsAndOverrides() {
    var flag = post("/oversight/flags", Map.of("reviewer", reviewer("someone@example.com"), "note", "Wrong amount",
        "output", "You owe 40 EUR", "flowId", "flow-1"));
    assertEquals(200, flag.status(), flag.body()::toString);
    assertEquals("flag-feedback", flag.body().get("pattern").asText());
    var flagId = flag.body().get("id").asText();
    var accepted = post("/oversight/events/" + flagId + "/decision", decision(MOREAU, "APPROVE", null));
    assertEquals(200, accepted.status(), accepted.body()::toString);
    Awaitility.await().atMost(WAIT).until(() -> TestPlugins.EVAL_CASES.contains(flagId));
    Awaitility.await().atMost(WAIT).until(() -> get("/oversight/events/" + flagId).body().at("/outcome/applied").asText()
        .equals("eval case written"));

    var contest = post("/oversight/contests", Map.of("reviewer", reviewer("applicant@example.com"), "control", "pt-0007",
        "ref", flagId, "grounds", "The flag was wrong"));
    assertEquals(200, contest.status(), contest.body()::toString);
    assertEquals("appeal", contest.body().at("/subject/kind").asText());
    assertEquals("l.moreau@example.com", contest.body().at("/subject/detail/original/decidedBy").asText());

    var override = post("/oversight/overrides", Map.of("reviewer", PATEL, "control", "pt-0008", "ref", flagId,
        "action", "OVERRIDE", "rationale", "Correct amount is 38 EUR", "replacement", "You owe 38 EUR"));
    assertEquals(200, override.status(), override.body()::toString);
    assertEquals("DECIDED", override.body().get("state").asText());
    assertEquals("You owe 38 EUR", override.body().at("/outcome/replacement").asText());
    assertEquals(403, post("/oversight/overrides", Map.of("reviewer", OSEI, "control", "pt-0008", "ref", flagId,
        "action", "DISREGARD", "rationale", "no")).status());
  }

  // ---- helpers

  private String raiseChange(String proposer) {
    var human = new HumanClient(componentClient, TestPlugins.PLUGINS);
    var raised = human.raise(Patterns.changeAuthorization("Roll out m-5 to 10% of traffic?").control("pt-0003")
        .subject(Subject.change("Roll out m-5", "model-registry", "rollout", "rollout-" + UUID.randomUUID(),
            Map.of("model", "m-4"), Map.of("model", "m-5")))
        .proposedBy(proposer).builtBy("trainer").build()).toCompletableFuture().join();
    awaitInView(raised.eventId());
    return raised.eventId();
  }

  private static io.akka.human.api.RaiseRequest.Builder payment(String question) {
    return Patterns.actionApproval(question).control("pt-0001").proposedBy("agent:payments")
        .subject(Subject.action("Pay a supplier", "pay", Map.of("supplier", "S-55", "amount", 900)));
  }

  private void awaitInView(String id) {
    Awaitility.await().atMost(WAIT).until(() -> {
      for (var e : get("/oversight/events?state=pending&limit=200").body().get("events")) {
        if (e.get("id").asText().equals(id)) return true;
      }
      return false;
    });
  }

  private static boolean has(JsonNode list, String id) {
    for (var item : list) if (item.get("id").asText().equals(id)) return true;
    return false;
  }

  private static Map<String, Object> reviewer(String subject, String... roles) {
    return Map.of("subject", subject, "name", subject, "roles", List.of(roles), "identityProvider", "test",
        "rehearsal", false);
  }

  private static ObjectNode decision(Map<String, Object> reviewer, String action, String rationale) {
    var body = MAPPER.createObjectNode();
    body.set("reviewer", MAPPER.valueToTree(reviewer));
    body.put("action", action);
    if (rationale != null) body.put("rationale", rationale);
    return body;
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
      return bytes.length == 0 ? MAPPER.nullNode() : MAPPER.readTree(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode example(String name) throws IOException {
    try (var in = OversightIntegrationTest.class.getClassLoader().getResourceAsStream("spec/examples/" + name)) {
      assertNotNull(in, name);
      return MAPPER.readTree(in);
    }
  }
}
