package io.akka.human.api;

import java.util.Set;

/**
 * The names an event's {@code related[].relation} can carry (spec/CONTRACT.md, "Related events"). The
 * library sets {@link #REOPENS} and {@link #REOPENED_AS}; a service sets the rest. A name of its own
 * starts with {@value #CUSTOM_PREFIX}.
 */
public final class Relations {

  /** On the new event of a reopening: the closed event it reopens. */
  public static final String REOPENS = "reopens";

  /** On a closed event that was reopened: the new event the reopening raised. */
  public static final String REOPENED_AS = "reopened-as";

  /** On an event raised because the one it names closed without a decision. */
  public static final String RERAISED_AFTER = "reraised-after";

  /** On an event raised again for an item whose earlier event, the one it names, held it. */
  public static final String HELD_AGAIN_AFTER = "held-again-after";

  /** On an event raised on the proposal of a decision: the event holding that decision. */
  public static final String PROPOSED_BY = "proposed-by";

  /** The prefix of a relation name a service defines itself. */
  public static final String CUSTOM_PREFIX = "x-";

  public static final Set<String> KNOWN = Set.of(REOPENS, REOPENED_AS, RERAISED_AFTER, HELD_AGAIN_AFTER, PROPOSED_BY);

  private Relations() {}

  /** Whether {@code relation} is a known name or a custom one: {@value #CUSTOM_PREFIX} and lower-case words. */
  public static boolean valid(String relation) {
    return relation != null && (KNOWN.contains(relation) || relation.matches("x-[a-z0-9]+(-[a-z0-9]+)*"));
  }
}
