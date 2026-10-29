package io.akka.human.local;

import akka.javasdk.agent.Decision;
import akka.javasdk.agent.GuardrailContext;
import akka.javasdk.agent.ModelCallGuardrail;
import akka.javasdk.agent.ToolCallGuardrail;
import io.akka.human.engine.HaltState;
import java.util.Set;

/**
 * Halts within one service, standing in for runtime request 14 (a scoped halt). Configured per agent
 * with the halted {@code target}; while a halt stops the target, the calls its {@code affected_calls}
 * names are denied with the halt's details. A halt across services needs the runtime.
 *
 * <pre>{@code
 * akka.javasdk.agent.guardrails {
 *   "eligibility halt (tools)" { class = "io.akka.human.local.HaltGuardrails$Tools", agents = ["*"], target = "eligibility-assessment" }
 *   "eligibility halt (model)" { class = "io.akka.human.local.HaltGuardrails$Model", agents = ["*"], target = "eligibility-assessment" }
 * }
 * }</pre>
 */
public final class HaltGuardrails {

  private static final Set<String> STOPS_TOOLS = Set.of("all-runtime-operations", "tool-calls-only", "write-operations-only");
  private static final Set<String> STOPS_MODELS = Set.of("all-runtime-operations", "llm-calls-only");

  private HaltGuardrails() {}

  /** Denies tool calls while the target is halted. */
  public static final class Tools implements ToolCallGuardrail {
    private final String target;

    public Tools(GuardrailContext context) {
      this.target = targetOf(context);
    }

    @Override
    public Decision decide(CallContext call) {
      return decision(target, STOPS_TOOLS);
    }
  }

  /** Denies model calls while the target is halted. */
  public static final class Model implements ModelCallGuardrail {
    private final String target;

    public Model(GuardrailContext context) {
      this.target = targetOf(context);
    }

    @Override
    public Decision decide(CallContext call) {
      return decision(target, STOPS_MODELS);
    }
  }

  /** Allows unless a halt stops {@code target} for calls of the kinds given. */
  public static Decision decision(String target, Set<String> stopped) {
    var halt = HumanGuardrails.installed().human().halt(target).state();
    if (halt == null || !halt.halted() || !stopped.contains(halt.affectedCalls())) return new Decision.Allow();
    return new Decision.Deny(describe(halt));
  }

  static String describe(HaltState halt) {
    return "halted: " + halt.target() + " by " + halt.by() + " since " + halt.since() + " (" + halt.eventId() + ")";
  }

  private static String targetOf(GuardrailContext context) {
    return context.config().hasPath("target") ? context.config().getString("target") : "system";
  }
}
