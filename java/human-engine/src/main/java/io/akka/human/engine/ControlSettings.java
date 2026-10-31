package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import io.akka.human.api.Sticky;
import io.akka.human.api.SubjectKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A human control with its settings resolved: the control's {@code human_config}, then its pattern's
 * defaults, then the shared defaults. An event keeps the settings it was raised under, so a later
 * catalog change does not alter an event already in front of someone.
 *
 * @param blockingKind the pattern's {@code blocking}: {@code true}, {@code false} or {@code system}
 * @param trigger the control's {@code trigger} block as written
 * @param halt the control's {@code halt_config}, for halt-restart controls; empty otherwise
 * @param blockDecisions oversight-quality: an event of this control blocks settling decisions on the
 *     control it judges until it is settled ({@link Blocks}). The control's {@code block_decisions}; on
 *     by default for oversight-quality, as the pattern registry describes, and off for every other pattern
 */
public record ControlSettings(
    ControlRef control,
    Pattern pattern,
    String patternName,
    String flavor,
    SubjectKind subjectKind,
    String blockingKind,
    List<Action> allowedActions,
    Map<Action, String> actionLabels,
    Routing routing,
    String accountableOwner,
    String slaTarget,
    double atRiskAt,
    String claimTtl,
    String timeoutBehaviour,
    Action timeoutDefault,
    List<Queue> escalationChain,
    List<Sticky> sticky,
    boolean rationaleRequired,
    int minimumReviewSeconds,
    String minimumReview,
    String clockWhileSentBack,
    String lateDecision,
    String aiRecommendation,
    String proofEvent,
    boolean shadow,
    Map<String, String> strength,
    Map<String, Object> trigger,
    Map<String, Object> halt,
    boolean blockDecisions) {

  public record ControlRef(String rowId, String name, String corpusRef, Integer revision) {}

  /**
   * Who may decide at the first queue.
   *
   * @param of N_OF_M only: the number of reviewers asked
   * @param splitVote what settles a TWO_PERSON or N_OF_M event whose votes disagree: DENY or ESCALATE
   */
  public record Routing(
      String queue,
      List<String> roles,
      List<String> groups,
      List<String> attestations,
      String reviewMode,
      int required,
      Integer of,
      String splitVote,
      List<String> independence) {}

  /** A queue of the escalation chain; {@code after} is how long an event waits there before moving on. */
  public record Queue(String queue, List<String> roles, String after) {}

  public static final String RUNS = "RUNS";
  public static final String PAUSED = "PAUSED";
  public static final String ENFORCE = "ENFORCE";
  public static final String WARN = "WARN";
  public static final String REFUSE = "REFUSE";

  public boolean haltRestart() {
    return pattern == Pattern.HALT_RESTART;
  }

  /** HITL when the work waits, HALT for halt-restart, HOTL otherwise. */
  public String mode() {
    return switch (blockingKind) {
      case "true" -> "HITL";
      case "system" -> "HALT";
      default -> "HOTL";
    };
  }

  /** Whether the pattern holds the work until the decision. */
  public boolean patternBlocks() {
    return "true".equals(blockingKind);
  }

  /**
   * The control's strength in an environment. An environment the control does not name gets
   * {@code prevent}: local and development runs show the control as it acts in production.
   */
  public String strengthIn(String environment) {
    var named = strength.get(environment);
    return named == null ? "prevent" : named;
  }

  public Duration deadline() {
    return slaTarget == null ? null : Durations.parse(slaTarget);
  }

  public Duration claimDuration() {
    return Durations.parse(claimTtl);
  }

  public int levels() {
    return escalationChain.size();
  }

  public String queueAt(int level) {
    return escalationChain.get(Math.min(level, escalationChain.size() - 1)).queue();
  }

  /** The roles that decide at a queue of the chain; the first queue takes the routing roles. */
  public List<String> rolesAt(int level) {
    if (level == 0 && !routing.roles().isEmpty()) return routing.roles();
    var queue = escalationChain.get(Math.min(level, escalationChain.size() - 1));
    return queue.roles().isEmpty() ? routing.roles() : queue.roles();
  }

  /** How long an event has at a queue: the queue's {@code after}, or the control's SLA target. */
  public Duration windowAt(int level) {
    if (level > 0 && level < escalationChain.size() && escalationChain.get(level).after() != null) {
      return Durations.parse(escalationChain.get(level).after());
    }
    return deadline();
  }

  /** The roles that may exercise a halt: halt_config.exerciser_personae, else the routing roles. */
  @SuppressWarnings("unchecked")
  public List<String> exercisers() {
    var personae = halt.get("exerciser_personae");
    return personae instanceof List<?> list && !list.isEmpty() ? (List<String>) list : routing.roles();
  }

  public String haltScope() {
    return String.valueOf(halt.getOrDefault("scope", "system"));
  }

  /** The service or capability a halt stops; a system-wide halt names {@code system}. */
  public String haltTarget() {
    var target = halt.get("target");
    return target == null ? "system" : String.valueOf(target);
  }

  public String haltAffectedCalls() {
    return String.valueOf(halt.getOrDefault("affected_calls", "all-runtime-operations"));
  }

  public String label(Action action) {
    var label = actionLabels.get(action);
    return label == null ? Settings.defaultLabel(action) : label;
  }
}
