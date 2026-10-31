package io.akka.human.samples.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import akka.javasdk.testkit.TestModelProvider.AiResponse;
import akka.javasdk.testkit.TestModelProvider.ToolInvocationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.samples.patterns.application.PayToolAgent;
import io.akka.human.testkit.HumanTestKit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The tool-call approval guardrail inside a model's tool loop: a scripted model calls {@code pay}, the
 * runtime runs the configured guardrail on the call, and approvals are given through /oversight.
 */
public class ToolLoopIntegrationTest extends TestKitSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String AWAITING = "awaiting approval: ";
  private static final String PAY_TOOL = "PayToolAgent_pay";

  private static final Map<String, Object> TYLER = Map.of("subject", "tyler.jewell@akka.io",
      "roles", List.of("ai-system-owner", "head-of-operations", "safety-officer", "model-risk-owner"));

  private static final TestModelProvider model = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("human.register = false\nakka.javasdk.agent.openai.api-key = n/a")
        .withModelProvider(PayToolAgent.class, model);
  }

  /** The model calls pay for the supplier the request names, then reports whatever the tool returned. */
  private void scriptModel(String supplier) {
    model.whenMessage(m -> m.contains("pay " + supplier))
        .reply(new ToolInvocationRequest(PAY_TOOL, "{\"supplier\":\"" + supplier + "\",\"amount\":5000}"));
    model.whenToolResult(tr -> tr.name().equals(PAY_TOOL)).thenReply(tr -> new AiResponse("tool said: " + tr.content()));
  }

  // A denied tool call fails the agent's request with the guardrail's reason; the model is not given
  // the refusal to react to, so the next attempt is a new request.
  @Test
  public void aToolCallIsRefusedUntilApprovedThenPassesOnce() {
    var supplier = "S-" + UUID.randomUUID();
    scriptModel(supplier);
    var session = UUID.randomUUID().toString();

    var first = blocked(session, supplier);
    var eventId = first.substring(first.indexOf(AWAITING) + AWAITING.length()).trim();
    assertTrue(blocked(session, supplier).contains(AWAITING + eventId), "an attempt before the decision finds the same event");

    approve(eventId, null);
    assertEquals("tool said: paid 5000 to " + supplier, run(session, supplier), "the retry after approval passes");
    assertTrue(blocked(session, supplier).contains(AWAITING), "the approval covered one call; the next needs a new one");
  }

  @Test
  public void aStandingApprovalLetsLaterMatchingCallsPass() {
    var supplier = "S-" + UUID.randomUUID();
    scriptModel(supplier);
    var session = UUID.randomUUID().toString();
    var first = blocked(session, supplier);
    var eventId = first.substring(first.indexOf(AWAITING) + AWAITING.length()).trim();

    approve(eventId, "APPROVE_FOR_PATTERN");
    kit().awaitApplied(eventId);
    assertEquals("tool said: paid 5000 to " + supplier, run(session, supplier));
    assertEquals("tool said: paid 5000 to " + supplier, run(session, supplier), "a later matching call needs no new approval");
    assertEquals("tool said: paid 5000 to " + supplier, run(UUID.randomUUID().toString(), supplier), "the standing decision is not tied to the session");
  }

  private HumanTestKit kit() {
    return new HumanTestKit(componentClient, getDependency(HumanPlugins.class));
  }

  private String run(String session, String supplier) {
    return componentClient.forAgent().inSession(session).method(PayToolAgent::run).invoke("pay " + supplier);
  }

  /** The guardrail's message when it refuses the call; fails if the request completed. */
  private String blocked(String session, String supplier) {
    return assertThrows(RuntimeException.class, () -> run(session, supplier)).getMessage();
  }

  private void approve(String eventId, String sticky) {
    var claim = post("/oversight/events/" + eventId + "/claim", Map.of("reviewer", TYLER));
    assertEquals(200, claim.get("status").asInt(), claim.toString());
    post("/oversight/dev/clock", Map.of("advance", "PT10S"));
    var body = new java.util.HashMap<String, Object>(Map.of("reviewer", TYLER, "action", "APPROVE",
        "rationale", "Within the supplier's contract."));
    if (sticky != null) body.put("sticky", sticky);
    var decided = post("/oversight/events/" + eventId + "/decision", body);
    assertEquals(200, decided.get("status").asInt(), decided.toString());
  }

  private JsonNode post(String path, Object body) {
    try {
      var response = httpClient.POST(path).withRequestBody(ContentTypes.APPLICATION_JSON, MAPPER.writeValueAsBytes(body))
          .invoke();
      var out = MAPPER.createObjectNode();
      out.put("status", response.status().intValue());
      out.set("body", MAPPER.readTree(response.body().toArray()));
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
