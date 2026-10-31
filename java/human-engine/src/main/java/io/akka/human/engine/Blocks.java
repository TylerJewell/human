package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.Pattern;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Oversight-quality blocks. An oversight-quality event judges the control its subject's {@code ref}
 * names: the monitored control of {@link Figures#signals}. When its control's {@code block_decisions} is on and it
 * was routed at strength {@code prevent}, it blocks settling decisions on the judged control's events
 * ({@link Rules#decide}) until it is settled with Reset, Retrain or Reassign.
 *
 * <p>For each oversight-quality control and judged control, the latest such event decides. It holds the
 * block while it is pending, when it timed out or was abandoned (nobody reset anything), and when it was
 * settled with any other action; it releases it when settled with Reset, Retrain or Reassign, withdrawn
 * or killed. A newer event takes over from an older one, so settling the latest finding, or the event
 * that reopens a timed-out one, lifts the block.
 */
public final class Blocks {

  /** Reset, Retrain and Reassign. */
  private static final Set<Action> LIFTS = Set.of(Action.APPROVE, Action.REPLAN, Action.REDIRECT);

  private Blocks() {}

  /** Each blocked control, with the oversight-quality event that blocks it: the earliest raised, if several do. */
  public static Map<String, String> of(Collection<HumanEvent> events) {
    var latest = new HashMap<List<String>, HumanEvent>();
    var newest = Comparator.comparing(HumanEvent::detectedAt).thenComparing(HumanEvent::id);
    for (var e : events) {
      if (!canBlock(e)) continue;
      latest.merge(List.of(e.settings().control().rowId(), e.subject().ref()), e,
          (a, b) -> newest.compare(a, b) >= 0 ? a : b);
    }
    var blocking = new TreeMap<String, HumanEvent>();
    for (var e : latest.values()) {
      if (holds(e)) blocking.merge(e.subject().ref(), e, (a, b) -> newest.compare(a, b) <= 0 ? a : b);
    }
    var out = new TreeMap<String, String>();
    blocking.forEach((control, e) -> out.put(control, e.id()));
    return out;
  }

  /** The event that blocks settling decisions on {@code e}, or null; a closed event is never blocked. */
  public static String blocking(HumanEvent e, Map<String, String> blocks) {
    return e.pending() ? blocks.get(e.settings().control().rowId()) : null;
  }

  private static boolean canBlock(HumanEvent e) {
    var s = e.settings();
    return s.pattern() == Pattern.OVERSIGHT_QUALITY && s.blockDecisions() && !e.shadow() && "prevent".equals(e.strength())
        && e.subject().ref() != null && !e.subject().ref().equals(s.control().rowId());
  }

  private static boolean holds(HumanEvent e) {
    if (e.pending()) return true;
    return switch (e.state()) {
      case WITHDRAWN, KILLED -> false;
      case DECIDED, APPLIED -> !(HumanEvent.COMPLETED.equals(e.outcome().slaOutcome())
          && LIFTS.contains(e.outcome().action()));
      default -> true;
    };
  }
}
