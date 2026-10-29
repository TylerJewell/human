package io.akka.human.local;

import akka.javasdk.agent.Decision;
import akka.javasdk.agent.GuardrailContext;
import akka.javasdk.agent.ToolCallGuardrail;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.akka.human.api.Pattern;
import io.akka.human.api.RaiseRequest;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.engine.HumanEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tool call that needs approval, standing in for SDK request 13 (tool calls that wait for
 * approval). A {@code ToolCallGuardrail} can only allow, deny or fail, so the call is denied with
 * "awaiting approval: &lt;eventId&gt;" while an action-approval event is raised for it; the calling
 * workflow retries after the decision, and the retried call passes once. A standing decision on the
 * call's match key lets it pass at once.
 *
 * <p>Configured per tool, with the action-approval control and what the match key covers:
 * {@code tool} (any call of the tool) or {@code tool-arguments} (the default: the tool with these
 * arguments).
 *
 * <pre>{@code
 * "payment approval" {
 *   class = "io.akka.human.local.ApprovalGuardrail", agents = ["*"], tools = ["pay"]
 *   control = "pt-0001", match = "tool-arguments"
 * }
 * }</pre>
 */
public final class ApprovalGuardrail implements ToolCallGuardrail {

  public static final String AWAITING = "awaiting approval: ";

  private static final ObjectMapper CANONICAL = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

  private final String control;
  private final boolean byArguments;

  public ApprovalGuardrail(GuardrailContext context) {
    this(context.config().getString("control"),
        !context.config().hasPath("match") || !"tool".equals(context.config().getString("match")));
  }

  public ApprovalGuardrail(String control, boolean byArguments) {
    this.control = control;
    this.byArguments = byArguments;
  }

  @Override
  public Decision decide(CallContext call) {
    return decide(call.agentId(), call.sessionId(), call.toolName(), call.arguments());
  }

  /** The decision for one call; the same call made again finds the event raised for it. */
  public Decision decide(String agentId, String sessionId, String tool, String argumentsJson) {
    var installed = HumanGuardrails.installed();
    var human = installed.human();
    var arguments = arguments(argumentsJson);
    var matchKey = byArguments ? tool + ":" + canonical(arguments) : tool;
    var key = (sessionId == null ? "" : sessionId) + "|" + matchKey;
    var approvals = installed.client().forKeyValueEntity("tool-" + hash(key));
    var known = approvals.method(ToolApprovalEntity::get).invoke();
    if (known.eventId() != null) {
      var event = human.find(known.eventId()).event();
      if (event != null && event.pending()) return new Decision.Deny(AWAITING + event.id());
      if (event != null && approved(event) && approvals.method(ToolApprovalEntity::use).invoke(event.id())) {
        return new Decision.Allow("approved in " + event.id());
      }
    }
    var subject = Subject.action("Call " + tool, tool, arguments).withAgent(agentId).withSession(sessionId);
    var raised = human.open(RaiseRequest.of(Pattern.ACTION_APPROVAL, "Should " + agentId + " call " + tool + "?")
        .control(control).subject(subject)
        .trigger(new Trigger("guardrail", tool, agentId + " asked to call " + tool, List.of(), List.of()))
        .proposedBy("agent:" + agentId).dedupeKey(key).matchKey(matchKey).build());
    if (raised.refused()) return new Decision.Fail(raised.refusal().error());
    var event = raised.event();
    approvals.method(ToolApprovalEntity::track).invoke(event.id());
    if (event.pending()) return new Decision.Deny(AWAITING + event.id());
    if (approved(event) && approvals.method(ToolApprovalEntity::use).invoke(event.id())) {
      return new Decision.Allow("approved by standing decision " + event.outcome().standingDecisionId());
    }
    return new Decision.Deny("denied: " + event.id());
  }

  private static boolean approved(HumanEvent e) {
    return e.outcome() != null && e.outcome().action() != null && e.outcome().action().proceeds();
  }

  private static Map<String, Object> arguments(String json) {
    try {
      return CANONICAL.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<TreeMap<String, Object>>() {});
    } catch (Exception e) {
      return new TreeMap<>(Map.of("raw", String.valueOf(json)));
    }
  }

  private static String canonical(Map<String, Object> arguments) {
    try {
      return CANONICAL.writeValueAsString(arguments);
    } catch (Exception e) {
      return String.valueOf(arguments);
    }
  }

  private static String hash(String key) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 24);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
