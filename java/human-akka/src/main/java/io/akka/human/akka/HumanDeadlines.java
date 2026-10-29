package io.akka.human.akka;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import akka.javasdk.timer.TimerScheduler;
import io.akka.human.engine.HumanEventEvent;
import java.time.Duration;
import java.time.Instant;

/**
 * Keeps a timer at each pending event's next clock check: a claim lapsing, the window turning at risk,
 * or the window ending. After every change it reads the event and sets a timer for its next check, so
 * deadlines act from the journal with nothing waiting on them. Each timer is named after its event and
 * check time: a redelivered change sets nothing twice, and a timer for a time the event no longer has
 * finds nothing due when it fires.
 */
@Component(id = "human-deadlines")
@Consume.FromEventSourcedEntity(HumanEventEntity.class)
public class HumanDeadlines extends Consumer {

  private final ComponentClient client;
  private final TimerScheduler timers;

  public HumanDeadlines(ComponentClient client, TimerScheduler timers) {
    this.client = client;
    this.timers = timers;
  }

  public Effect onEvent(HumanEventEvent event) {
    var id = messageContext().eventSubject().orElseThrow();
    var current = client.forEventSourcedEntity(id).method(HumanEventEntity::get).invoke().event();
    var next = current == null ? null : current.nextCheckAt();
    if (next != null) schedule(timers, client, "human-deadline-" + id + "-" + next.toEpochMilli(), id, next);
    return effects().done();
  }

  /**
   * A timer that fires at {@code at} in real time. Timers run on real time even when the development
   * clock has been moved ahead: what the moved clock makes due is acted on by
   * {@code POST /oversight/dev/tick}, so a decision after a moved deadline can still be tried.
   */
  static void schedule(TimerScheduler timers, ComponentClient client, String name, String eventId, Instant at) {
    var delay = Duration.between(Instant.now(), at);
    timers.createSingleTimer(name, delay.isNegative() ? Duration.ZERO : delay,
        client.forTimedAction().method(HumanDeadlineAction::check).deferred(eventId));
  }
}
