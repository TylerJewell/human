package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;

/**
 * One monitor's trigger between evaluations, keyed by the monitor. An episode runs from the trigger
 * firing until it stops: the first reading raises the episode's event, later readings add occurrences
 * to it while it is pending, and once it is answered nothing is raised until the next episode.
 */
@Component(id = "oversight-monitor")
public class MonitorEntity extends KeyValueEntity<MonitorEntity.Monitor> {

  /**
   * @param eventId the event of the current or last episode
   * @param answer what was done with the last episode's event, once answered
   */
  public record Monitor(boolean firing, int episode, String eventId, double value, String answer, Instant at) {}

  public record Fired(int episode, String eventId, double value) {}

  @Override
  public Monitor emptyState() {
    return new Monitor(false, 0, null, 0, null, null);
  }

  public Effect<Monitor> fired(Fired f) {
    var next = new Monitor(true, f.episode(), f.eventId(), f.value(),
        f.episode() == currentState().episode() ? currentState().answer() : null, Instant.now());
    return effects().updateState(next).thenReply(next);
  }

  public Effect<Monitor> stopped(double value) {
    var s = currentState();
    var next = new Monitor(false, s.episode(), s.eventId(), value, s.answer(), Instant.now());
    return effects().updateState(next).thenReply(next);
  }

  public Effect<Monitor> answered(String answer) {
    var s = currentState();
    var next = new Monitor(s.firing(), s.episode(), s.eventId(), s.value(), answer, Instant.now());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Monitor> get() {
    return effects().reply(currentState());
  }
}
