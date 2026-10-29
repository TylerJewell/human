package io.akka.human.local;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

/**
 * The approval event behind one tool call, by the call's key (session, tool and arguments), and
 * whether a retried call has used its one-time approval.
 */
@Component(id = "human-tool-approval")
public class ToolApprovalEntity extends KeyValueEntity<ToolApprovalEntity.Approval> {

  /** {@code eventId} is null until an event is raised for the call. */
  public record Approval(String eventId, boolean used) {}

  @Override
  public Approval emptyState() {
    return new Approval(null, false);
  }

  public ReadOnlyEffect<Approval> get() {
    return effects().reply(currentState());
  }

  /** The event now behind the call; a new event resets the use. */
  public Effect<Approval> track(String eventId) {
    if (eventId.equals(currentState().eventId())) return effects().reply(currentState());
    var next = new Approval(eventId, false);
    return effects().updateState(next).thenReply(next);
  }

  /** Uses the approval of {@code eventId}; answers whether it was still unused. */
  public Effect<Boolean> use(String eventId) {
    if (!eventId.equals(currentState().eventId()) || currentState().used()) return effects().reply(false);
    return effects().updateState(new Approval(eventId, true)).thenReply(true);
  }
}
