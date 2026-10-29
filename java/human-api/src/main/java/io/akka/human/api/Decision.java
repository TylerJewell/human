package io.akka.human.api;

import java.util.List;
import java.util.Map;

/**
 * The final outcome of an event, delivered once to whoever it resumes.
 *
 * @param action the settling decision; null when the event closed without one (abandoned, withdrawn,
 *     killed, or timed out with no default)
 * @param edits EDIT: the reviewer's change, as {@code summary} and a JSON {@code patch}
 * @param replacement OVERRIDE: the output that replaces the system's
 * @param slaOutcome COMPLETED, TIMED_OUT, ESCALATED, ABANDONED or KILLED
 * @param conclusion the verdict: upheld, breached, inconclusive or error
 * @param standingDecisionId set when a standing decision settled the event, or when this decision
 *     created one
 */
public record Decision(
    String eventId,
    String control,
    Pattern pattern,
    Action action,
    Map<String, Object> edits,
    String replacement,
    String rationale,
    List<String> deciders,
    Sticky sticky,
    String slaOutcome,
    String conclusion,
    String standingDecisionId) {

  public Decision {
    edits = edits == null ? Map.of() : Map.copyOf(edits);
    deciders = deciders == null ? List.of() : List.copyOf(deciders);
    rationale = rationale == null ? "" : rationale;
  }

  /** The subject goes ahead, as presented or as edited. */
  public boolean proceeds() {
    return action != null && action.proceeds();
  }

  public boolean timedOut() {
    return "TIMED_OUT".equals(slaOutcome);
  }

  /** Closed without a person's judgment: no action recorded. */
  public boolean unanswered() {
    return action == null;
  }
}
