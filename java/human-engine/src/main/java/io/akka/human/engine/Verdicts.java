package io.akka.human.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What a closed event concludes about the oversight, not about the AI's action: a reviewer who denies
 * an unsafe action has made the control work. The rules are the contract's "Settling and verdicts";
 * {@link Json#verdict} writes the verdict as Verify's verdict schema records it.
 */
public final class Verdicts {

  public static final String UPHELD = "upheld";
  public static final String BREACHED = "breached";
  public static final String INCONCLUSIVE = "inconclusive";
  public static final String ERROR = "error";

  /**
   * A human verdict.
   *
   * @param reasons the codes behind a conclusion other than upheld: late, below-minimum, fail-open,
   *     missed-deadline, killed, not-applied
   * @param source runtime, simulated or rehearsal; only runtime counts as evidence
   * @param at when the outcome was applied, or when the event closed
   */
  public record Verdict(String id, String eventId, String conclusion, List<String> reasons, String source, Instant at) {}

  private Verdicts() {}

  /** The verdict of a closed event; null while it is pending. */
  public static Verdict of(HumanEvent e) {
    if (e.pending() || e.outcome() == null) return null;
    var o = e.outcome();
    var reasons = new ArrayList<String>();
    String conclusion;
    if (HumanEvent.KILLED.equals(o.slaOutcome())) {
      conclusion = ERROR;
      reasons.add("killed");
    } else if (o.applyError() != null) {
      conclusion = ERROR;
      reasons.add("not-applied");
    } else if (HumanEvent.ABANDONED.equals(o.slaOutcome())) {
      conclusion = INCONCLUSIVE;
    } else if (HumanEvent.TIMED_OUT.equals(o.slaOutcome())) {
      if ("RESUME_WITH_DEFAULT".equals(o.timeoutBehaviour()) && o.action() != null && o.action().proceeds()) {
        conclusion = BREACHED;
        reasons.add("fail-open");
      } else if ("ESCALATE".equals(o.timeoutBehaviour())) {
        conclusion = BREACHED;
        reasons.add("missed-deadline");
      } else {
        conclusion = INCONCLUSIVE;
      }
    } else if (o.byStanding()) {
      conclusion = UPHELD;
    } else {
      var round = e.roundVotes();
      if (round.stream().anyMatch(HumanEvent.Vote::late)) reasons.add("late");
      if (round.stream().anyMatch(HumanEvent.Vote::belowMinimum)) reasons.add("below-minimum");
      conclusion = reasons.isEmpty() ? UPHELD : BREACHED;
    }
    var at = o.appliedAt() != null ? o.appliedAt() : e.closedAt();
    return new Verdict("vd-" + e.id(), e.id(), conclusion, List.copyOf(reasons), source(e), at);
  }

  /** simulated for a simulated event, rehearsal when a settling vote was rehearsed, runtime otherwise. */
  public static String source(HumanEvent e) {
    if (e.simulated()) return "simulated";
    if (rehearsedBy(e) != null) return "rehearsal";
    return "runtime";
  }

  /** Who cast the rehearsed vote that settled the event, or null. */
  static String rehearsedBy(HumanEvent e) {
    if (e.settlingVote() == null) return null;
    return e.roundVotes().stream().filter(v -> v.reviewer().rehearsal()).map(v -> v.reviewer().subject()).findFirst()
        .orElse(null);
  }
}
