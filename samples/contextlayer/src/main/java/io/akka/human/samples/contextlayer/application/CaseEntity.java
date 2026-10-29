package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;
import java.util.Map;

/**
 * A case a watcher opened when a reading crossed its threshold. A reviewer resolves it (APPROVE) or
 * dismisses it (DENY), with a reason (custom-cl0046).
 *
 * <p>{@code status}: OPEN, RESOLVED or DISMISSED; {@code headline} holds the fields a case list shows.
 */
@Component(id = "context-case")
public class CaseEntity extends KeyValueEntity<CaseEntity.Case> {

  public record Case(String id, String watcherId, String title, Map<String, String> headline, String status,
      Instant openedAt, int readings, String closedBy, String reason, String eventId) {}

  public record Close(boolean resolved, String by, String reason) {}

  public record Reading(Map<String, String> headline) {}

  public Effect<Case> open(Case opened) {
    if (currentState() != null) return effects().reply(currentState());
    return effects().updateState(opened).thenReply(opened);
  }

  /** Another reading past the threshold while the case is open. */
  public Effect<Case> reading(Reading reading) {
    var c = currentState();
    if (c == null) return effects().error("no such case");
    var next = new Case(c.id(), c.watcherId(), c.title(), reading.headline(), c.status(), c.openedAt(), c.readings() + 1,
        c.closedBy(), c.reason(), c.eventId());
    return effects().updateState(next).thenReply(next);
  }

  public Effect<Case> close(Close close) {
    var c = currentState();
    if (c == null) return effects().error("no such case");
    if (!c.status().equals("OPEN")) return effects().reply(c);
    var next = new Case(c.id(), c.watcherId(), c.title(), c.headline(), close.resolved() ? "RESOLVED" : "DISMISSED",
        c.openedAt(), c.readings(), close.by(), close.reason(), c.eventId());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Case> get() {
    if (currentState() == null) return effects().error("no such case");
    return effects().reply(currentState());
  }
}
