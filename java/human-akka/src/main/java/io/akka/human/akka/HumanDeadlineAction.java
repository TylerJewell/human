package io.akka.human.akka;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import io.akka.human.api.spi.HumanPlugins;

/**
 * Where an event's deadline timer lands. The event works out what is due at the library's time, so a
 * timer that fires late, twice, or for an event already closed changes nothing it should not. A timer
 * that finds nothing due while a check is still ahead (it fired a moment early, or the development
 * clock was moved back) sets the next one itself, since no journal event will.
 */
@Component(id = "human-deadline-action")
public class HumanDeadlineAction extends TimedAction {

  private final ComponentClient client;
  private final TimerScheduler timers;
  private final HumanLibrary library;

  public HumanDeadlineAction(ComponentClient client, TimerScheduler timers, HumanPlugins plugins) {
    this.client = client;
    this.timers = timers;
    this.library = HumanLibrary.of(plugins);
  }

  public Effect check(String eventId) {
    var now = library.now();
    var ticked = client.forEventSourcedEntity(eventId).method(HumanEventEntity::tick).invoke(new HumanEventEntity.Tick(now));
    var event = ticked.event();
    if (event != null && !ticked.changed() && event.nextCheckAt() != null) {
      HumanDeadlines.schedule(timers, client,
          "human-deadline-" + eventId + "-" + event.nextCheckAt().toEpochMilli() + "-after-" + now.toEpochMilli(),
          eventId, event.nextCheckAt());
    }
    return effects().done();
  }
}
