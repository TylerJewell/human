package io.akka.human.akka;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.human.engine.HaltState;
import java.time.Instant;

/**
 * Whether a halt stops one target; the id is the target. Every command is idempotent, because both
 * the halt route and the outcome consumer apply a halt.
 */
@Component(id = "human-halt")
public class HaltEntity extends KeyValueEntity<HaltState> {

  /**
   * @param base the target as its control declares it, not halted
   * @param recordId the HaltExercised proof record
   */
  public record Exercise(HaltState base, String by, String reason, String eventId, String recordId, Instant at) {}

  /** The halt as it stands; {@code state} is null when the target was never halted. */
  public record Current(HaltState state) {
    public boolean halted() {
      return state != null && state.halted();
    }
  }

  /** Halts the target, unless it is halted already. */
  public Effect<Current> exercise(Exercise command) {
    if (halted()) return effects().reply(new Current(currentState()));
    var next = command.base().exercised(command.by(), command.reason(), command.eventId(), command.recordId(),
        command.at());
    return effects().updateState(next).thenReply(new Current(next));
  }

  /** A restart event is pending for the halt. */
  public Effect<Current> liftRaised(String eventId) {
    if (!halted()) return effects().reply(new Current(currentState()));
    var next = currentState().liftRaised(eventId);
    return effects().updateState(next).thenReply(new Current(next));
  }

  /** The restart event closed without lifting the halt. */
  public Effect<Current> liftClosed(String eventId) {
    if (!halted() || !eventId.equals(currentState().liftEventId())) return effects().reply(new Current(currentState()));
    var next = currentState().liftRaised(null);
    return effects().updateState(next).thenReply(new Current(next));
  }

  /** The restart was approved. */
  public Effect<Current> lift(String eventId) {
    if (!halted()) return effects().reply(new Current(currentState()));
    var next = currentState().lifted();
    return effects().updateState(next).thenReply(new Current(next));
  }

  public ReadOnlyEffect<Current> get() {
    return effects().reply(new Current(currentState()));
  }

  /** Lifts the halt without a restart decision; for the development reset only. */
  public Effect<Current> clear() {
    if (!halted()) return effects().reply(new Current(currentState()));
    var next = currentState().lifted();
    return effects().updateState(next).thenReply(new Current(next));
  }

  private boolean halted() {
    return currentState() != null && currentState().halted();
  }
}
