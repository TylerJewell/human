package io.akka.human.samples.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.human.akka.HumanQueries;
import io.akka.human.api.Action;
import io.akka.human.api.Reviewer;
import io.akka.human.api.Sticky;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.Rules;
import io.akka.human.engine.State;
import io.akka.human.engine.Verdicts;
import io.akka.human.samples.patterns.application.NoticeWorkflow;
import io.akka.human.samples.patterns.application.PaymentWorkflow;
import io.akka.human.samples.patterns.application.RolloutWorkflow;
import io.akka.human.local.HaltGuardrails;
import io.akka.human.local.WorkflowControl;
import io.akka.human.testkit.HumanTestKit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Each scenario of the sample, raised through its domain route and decided as people would. */
public class PatternsIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration WAIT = Duration.ofSeconds(20);

  private static final Reviewer TYLER = HumanTestKit.reviewer("tyler.jewell@akka.io", "ai-system-owner",
      "head-of-operations", "safety-officer", "model-risk-owner");
  private static final Reviewer LIND = HumanTestKit.reviewer("j.lind@example.com", "head-of-operations", "case-reviewer",
      "ai-system-owner", "appeals-officer");
  private static final Reviewer DEVOS = HumanTestKit.reviewer("a.devos@example.com", "model-risk-owner", "data-steward");
  private static final Reviewer CHEN = HumanTestKit.reviewer("r.chen@example.com", "compliance-lead", "appeals-officer");
  private static final Reviewer PATEL = HumanTestKit.reviewer("s.patel@example.com", "safety-officer", "oversight-officer");
  private static final Reviewer MOREAU = HumanTestKit.reviewer("l.moreau@example.com", "safety-officer", "duty-officer",
      "feedback-triager", "data-steward");

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
  public void anApprovedPaymentResumesItsWorkflow() {
    var id = start("/payments", Map.of("supplier", "S-77", "amount", 4200, "currency", "EUR"));
    var waiting = awaitPayment(id, p -> p.status().equals("AWAITING_APPROVAL"));
    var event = kit.event(waiting.approvalEventId());
    assertEquals("action-approval", event.settings().pattern().id());
    assertEquals(List.of("agent:payments"), event.independentOf());

    kit.claimAndDecide(event.id(), TYLER, Action.APPROVE, "The invoice matches the purchase order.");
    var paid = awaitPayment(id, p -> p.status().equals("PAID"));
    assertEquals(4200, paid.amount());
    assertEquals(State.APPLIED, kit.awaitApplied(event.id()).state());
  }

  @Test
  public void anEditedPaymentPaysTheEditedAmount() {
    var id = start("/payments", Map.of("supplier", "S-78", "amount", 4200));
    var eventId = awaitPayment(id, p -> p.status().equals("AWAITING_APPROVAL")).approvalEventId();
    HumanTestKit.ok(kit.claim(eventId, LIND), "claim");
    kit.advance(Duration.ofSeconds(10));
    var edits = Map.<String, Object>of("summary", "amount 4200 to 4000",
        "patch", List.of(Map.of("op", "replace", "path", "/amount", "value", 4000)));
    HumanTestKit.ok(kit.decide(eventId, new Rules.DecisionRequest(LIND, Action.EDIT, "Pay 4,000; the rest is disputed.",
        edits, null, null, List.of(), null)), "decide");
    assertEquals(4000, awaitPayment(id, p -> p.status().equals("PAID")).amount());
  }

  @Test
  public void aPaymentNobodyApprovesTimesOutDenied() {
    var id = start("/payments", Map.of("supplier", "S-79", "amount", 5000));
    var eventId = awaitPayment(id, p -> p.status().equals("AWAITING_APPROVAL")).approvalEventId();
    kit.advance(Duration.ofMinutes(31));
    assertEquals(State.TIMED_OUT, kit.tick(eventId).state());
    var denied = awaitPayment(id, p -> p.status().equals("DENIED"));
    assertEquals("timed out; the default applies: DENY", denied.note());
    assertEquals("inconclusive", Verdicts.of(kit.awaitApplied(eventId)).conclusion());
  }

  @Test
  public void anUnknownResultIsReconciledAndRetried() {
    var id = start("/payments", Map.of("supplier", "S-80", "amount", 500, "resultUnknown", true));
    var waiting = awaitPayment(id, p -> p.status().equals("AWAITING_RECONCILIATION"));
    var event = kit.event(waiting.reconciliationEventId());
    assertEquals("outcome-reconciliation", event.settings().pattern().id());
    HumanTestKit.ok(kit.decide(event.id(), LIND, Action.REPLAN, "The provider has no record of it; send again."), "decide");
    var paid = awaitPayment(id, p -> p.status().equals("PAID"));
    assertEquals(2, paid.attempts());
  }

  @Test
  public void aNoticeEscalatesWhenNobodyReviewsIt() {
    var id = start("/notices", Map.of("applicant", "A-12", "text", "Your claim is denied.", "decisionRef", "claim-12"));
    var eventId = Awaitility.await().atMost(WAIT).until(
        () -> componentClient.forWorkflow(id).method(NoticeWorkflow::get).invoke(),
        n -> n.status().equals("AWAITING_REVIEW")).eventId();
    kit.advance(Duration.ofHours(4).plusMinutes(1));
    var escalated = kit.tick(eventId);
    assertEquals(State.ESCALATED, escalated.state());
    assertEquals("head-of-operations", escalated.queue());
    kit.claimAndDecide(eventId, LIND, Action.APPROVE, "The notice gives the reason and the appeal route.");
    var sent = Awaitility.await().atMost(WAIT).until(
        () -> componentClient.forWorkflow(id).method(NoticeWorkflow::get).invoke(), n -> n.status().equals("SENT"));
    assertEquals("Your claim is denied.", sent.text());
  }

  @Test
  public void aRolloutNeedsTwoApproversAfterASendBack() {
    var id = start("/rollouts", Map.of("model", "notice-writing", "from", "m-4", "to", "m-5",
        "proposedBy", "r.chen@example.com", "builtBy", "trainer", "trafficShare", 0.1));
    var eventId = Awaitility.await().atMost(WAIT).until(
        () -> componentClient.forWorkflow(id).method(RolloutWorkflow::get).invoke(),
        r -> r.status().equals("AWAITING_AUTHORIZATION")).eventId();
    HumanTestKit.ok(kit.sendBack(eventId, TYLER, "Attach the evaluation on the appeal letters."), "send back");
    assertEquals(State.CHANGES_REQUESTED, kit.event(eventId).state());
    assertEquals(403, kit.resubmit(eventId, TYLER, "not mine").refusal().status());
    HumanTestKit.ok(kit.resubmit(eventId, CHEN, "Evaluation attached."), "resubmit");

    var first = HumanTestKit.ok(kit.decide(eventId, TYLER, Action.APPROVE, "The evaluation holds."), "first vote");
    assertEquals(State.AWAITING_SECOND, first.state());
    assertEquals(409, kit.decide(eventId, TYLER, Action.APPROVE, "again").refusal().status());
    HumanTestKit.ok(kit.decide(eventId, DEVOS, Action.APPROVE, "Agreed."), "second vote");
    var rolled = Awaitility.await().atMost(WAIT).until(
        () -> componentClient.forWorkflow(id).method(RolloutWorkflow::get).invoke(), r -> r.status().equals("ROLLED_OUT"));
    assertTrue(rolled.note().contains("tyler.jewell@akka.io") && rolled.note().contains("a.devos@example.com"));
  }

  @Test
  public void aHaltStopsEligibilityUntilTwoOfficersRestartIt() {
    assertEquals(200, post("/eligibility/assess", Map.of("applicant", "A-1", "income", 20000)).status());
    var halt = post("/oversight/halts", Map.of("reviewer", PATEL, "control", "pt-00012", "lift", false,
        "reason", "Referral drift"));
    assertEquals(200, halt.status(), halt.body()::toString);
    assertEquals(503, post("/eligibility/assess", Map.of("applicant", "A-2", "income", 20000)).status());
    assertTrue(HaltGuardrails.decision(PatternsSetup.ELIGIBILITY, Set.of("all-runtime-operations"))
        instanceof akka.javasdk.agent.Decision.Deny);

    put("/eligibility/safe-state", Map.of("backlogDrained", false));
    var lift = post("/oversight/halts", Map.of("reviewer", TYLER, "control", "pt-00012", "lift", true,
        "reason", "Backlog drained"));
    var liftId = lift.body().get("id").asText();
    assertEquals(409, post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "ok")).status());
    put("/eligibility/safe-state", Map.of("backlogDrained", true));
    assertEquals("AWAITING_SECOND",
        post("/oversight/events/" + liftId + "/decision", decision(PATEL, "APPROVE", "Drained.")).body().get("state").asText());
    assertEquals("DECIDED",
        post("/oversight/events/" + liftId + "/decision", decision(MOREAU, "APPROVE", "Agreed.")).body().get("state").asText());
    Awaitility.await().atMost(WAIT).until(() -> post("/eligibility/assess", Map.of("applicant", "A-3", "income", 20000))
        .status() == 200);
  }

  @Test
  public void aStandingApprovalSettlesTheNextPayment() {
    var first = start("/payments", Map.of("supplier", "S-88", "amount", 3000));
    var eventId = awaitPayment(first, p -> p.status().equals("AWAITING_APPROVAL")).approvalEventId();
    HumanTestKit.ok(kit.claim(eventId, TYLER), "claim");
    kit.advance(Duration.ofSeconds(10));
    HumanTestKit.ok(kit.decide(eventId, new Rules.DecisionRequest(TYLER, Action.APPROVE, "A regular supplier.", null,
        Sticky.APPROVE_FOR_PATTERN, null, List.of(), null)), "decide");
    awaitPayment(first, p -> p.status().equals("PAID"));
    kit.awaitApplied(eventId);

    var second = start("/payments", Map.of("supplier", "S-88", "amount", 3500));
    var paid = awaitPayment(second, p -> p.status().equals("PAID"));
    var settled = kit.event(paid.approvalEventId());
    assertEquals("sd-" + eventId, settled.outcome().standingDecisionId());
    assertTrue(settled.outcome().byStanding());
  }

  @Test
  public void aToolCallWaitsForApprovalThenPassesOnce() {
    var call = Map.of("agentId", "payments", "sessionId", "s-" + System.nanoTime(), "tool", "pay",
        "arguments", "{\"supplier\":\"S-1\",\"amount\":5000}");
    var denied = post("/tool-calls", call).body();
    assertEquals("deny", denied.get("decision").asText());
    var reason = denied.get("reason").asText();
    assertTrue(reason.startsWith("awaiting approval: "), reason);
    var eventId = reason.substring("awaiting approval: ".length());
    assertEquals("deny", post("/tool-calls", call).body().get("decision").asText());

    kit.claimAndDecide(eventId, TYLER, Action.APPROVE, "Within the supplier's contract.");
    assertEquals("allow", post("/tool-calls", call).body().get("decision").asText());
    assertEquals("deny", post("/tool-calls", call).body().get("decision").asText());
  }

  @Test
  public void anAgentsApprovalTaskIsCompleted() {
    var approval = post("/agent-approvals", Map.of("agentId", "payments", "tool", "pay",
        "arguments", Map.of("supplier", "S-2", "amount", 1500))).body();
    var taskId = approval.get("taskId").asText();
    kit.claimAndDecide(approval.get("eventId").asText(), TYLER, Action.APPROVE, "Approved for the agent.");
    Awaitility.await().atMost(WAIT).until(() -> get("/agent-approvals/" + taskId).body().get("status").asText()
        .equals("COMPLETED"));
  }

  @Test
  public void raisedAndCarriedOn() {
    var flag = post("/replies/flow-9/flag", Map.of("by", "customer@example.com", "note", "The amount is wrong",
        "output", "You owe 40 EUR")).body();
    var flagId = flag.get("eventId").asText();
    HumanTestKit.ok(kit.decide(flagId, MOREAU, Action.APPROVE, null), "accept flag");
    Awaitility.await().atMost(WAIT).until(() -> get("/records/" + flagId).status() == 200);
    assertEquals("accepted as an eval case", get("/records/" + flagId).body().get("outcome").asText());

    post("/monitor/referral-share", Map.of("referralShare", 12.0));
    var triage = awaitPending("triage");
    HumanTestKit.ok(kit.decide(triage.id(), TYLER, Action.APPROVE, "Threshold recalibrated."), "resolve");
    Awaitility.await().atMost(WAIT).until(() -> get("/records/" + triage.id()).status() == 200);

    post("/attestations/run", Map.of());
    var attestation = awaitPending("attestation");
    assertEquals(List.of("model-team"), attestation.independentOf());
    HumanTestKit.ok(kit.decide(attestation.id(), DEVOS, Action.APPROVE, "Back-test within tolerance."), "attest");
    Awaitility.await().atMost(WAIT).until(() -> get("/records/" + attestation.id()).status() == 200);
    assertEquals("attested", get("/records/" + attestation.id()).body().get("outcome").asText());
  }

  @Test
  public void suspendingAWorkflowRecordsWhoDidIt() {
    var id = start("/payments", Map.of("supplier", "S-90", "amount", 4000));
    awaitPayment(id, p -> p.status().equals("AWAITING_APPROVAL"));
    var control = new WorkflowControl(componentClient, kit::now);
    control.suspend(PaymentWorkflow.class, id, TYLER, "Supplier under investigation");
    control.resume(PaymentWorkflow.class, id, LIND, "Investigation closed");
    var history = control.history(id);
    assertEquals(List.of("suspend", "resume"), history.stream().map(i -> i.kind()).toList());
    assertEquals(List.of(TYLER.subject(), LIND.subject()), history.stream().map(i -> i.by()).toList());
  }

  // ---- helpers

  private record Reply(int status, JsonNode body) {}

  private PaymentWorkflow.Payment awaitPayment(String id, Predicate<PaymentWorkflow.Payment> condition) {
    return Awaitility.await().atMost(WAIT).ignoreExceptions()
        .until(() -> componentClient.forWorkflow(id).method(PaymentWorkflow::get).invoke(), condition::test);
  }

  private HumanEvent awaitPending(String pattern) {
    return Awaitility.await().atMost(WAIT).until(
        () -> HumanQueries.pending(componentClient, testKit.getMaterializer()).stream()
            .filter(e -> e.settings().pattern().id().equals(pattern)).findFirst().orElse(null),
        java.util.Objects::nonNull);
  }

  private String start(String path, Map<String, Object> body) {
    var reply = post(path, body);
    assertEquals(200, reply.status(), reply.body()::toString);
    return reply.body().get("id").asText();
  }

  private static Map<String, Object> decision(Reviewer reviewer, String action, String rationale) {
    return Map.of("reviewer", Map.of("subject", reviewer.subject(), "roles", reviewer.roles()), "action", action,
        "rationale", rationale);
  }

  private Reply get(String path) {
    var response = httpClient.GET(path).invoke();
    return new Reply(response.status().intValue(), parse(response.body().toArray()));
  }

  private Reply post(String path, Object body) {
    return send(httpClient.POST(path), body);
  }

  private Reply put(String path, Object body) {
    return send(httpClient.PUT(path), body);
  }

  private Reply send(akka.javasdk.http.RequestBuilder<akka.util.ByteString> request, Object body) {
    try {
      var response = request.withRequestBody(ContentTypes.APPLICATION_JSON, MAPPER.writeValueAsBytes(body)).invoke();
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
