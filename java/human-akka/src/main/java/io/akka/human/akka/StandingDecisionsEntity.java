package io.akka.human.akka;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.human.engine.Refusal;
import io.akka.human.engine.StandingDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The service's standing decisions, in one entity with the id {@link #ID}: each raise looks for a
 * match, so they are read together, and people create them at human pace.
 */
@Component(id = "human-standing")
public class StandingDecisionsEntity extends KeyValueEntity<StandingDecisionsEntity.Standing> {

  public static final String ID = "standing";

  public record Standing(List<StandingDecision> decisions) {
    public Standing {
      decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }
  }

  public record Match(String control, String matchKey, String sessionId, Instant now) {}

  /** The matching decision, or null. */
  public record Found(StandingDecision decision) {}

  public record Use(String id, String eventId) {}

  public record Revoke(String id, String by, String reason, Instant now) {}

  public record Revoked(StandingDecision decision, Refusal refusal) {}

  @Override
  public Standing emptyState() {
    return new Standing(List.of());
  }

  /** Adding the same id again keeps the first. */
  public Effect<Found> add(StandingDecision decision) {
    var existing = find(decision.id());
    if (existing != null) return effects().reply(new Found(existing));
    var next = new ArrayList<>(currentState().decisions());
    next.add(decision);
    return effects().updateState(new Standing(next)).thenReply(new Found(decision));
  }

  /** The latest active decision that settles a raise of this control, key and session. */
  public ReadOnlyEffect<Found> match(Match query) {
    return effects().reply(new Found(currentState().decisions().stream()
        .filter(d -> d.matches(query.control(), query.matchKey(), query.sessionId(), query.now()))
        .reduce((first, second) -> second).orElse(null)));
  }

  public Effect<Found> use(Use command) {
    var existing = find(command.id());
    if (existing == null) return effects().reply(new Found(null));
    var next = existing.usedFor(command.eventId());
    return effects().updateState(replaced(next)).thenReply(new Found(next));
  }

  public Effect<Revoked> revoke(Revoke command) {
    var existing = find(command.id());
    if (existing == null) return effects().reply(new Revoked(null, Refusal.notFound("standing decision not found")));
    if (existing.revoked() != null) {
      return effects().reply(new Revoked(existing, Refusal.conflict("already revoked", "REVOKED")));
    }
    var next = existing.revokedBy(command.by(), command.now(), command.reason());
    return effects().updateState(replaced(next)).thenReply(new Revoked(next, null));
  }

  public ReadOnlyEffect<Standing> list() {
    return effects().reply(currentState());
  }

  public Effect<Integer> clear() {
    int removed = currentState().decisions().size();
    return effects().updateState(emptyState()).thenReply(removed);
  }

  private StandingDecision find(String id) {
    return currentState().decisions().stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
  }

  private Standing replaced(StandingDecision next) {
    return new Standing(currentState().decisions().stream().map(d -> d.id().equals(next.id()) ? next : d).toList());
  }
}
