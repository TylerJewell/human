package io.akka.human.samples.patterns.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import akka.stream.Materializer;
import io.akka.human.akka.HumanLibrary;
import io.akka.human.akka.HumanQueries;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.Figures;
import java.time.Duration;
import java.util.List;

/**
 * Reads the oversight-quality signals every hour and raises an oversight-quality event for each figure
 * that crosses its limit (pt-0014: more than 20% of a control's decisions made under its minimum review
 * time). The control's accountable owner answers it; while it is pending a repeated finding adds an
 * occurrence.
 */
@Component(id = "quality-monitor")
public class QualityMonitor extends TimedAction {

  public static final String TIMER = "oversight-quality";
  static final Duration EVERY = Duration.ofHours(1);

  private final Human human;
  private final HumanPlugins plugins;
  private final ComponentClient client;
  private final TimerScheduler timers;
  private final Materializer materializer;

  public QualityMonitor(Human human, HumanPlugins plugins, ComponentClient client, TimerScheduler timers,
      Materializer materializer) {
    this.human = human;
    this.plugins = plugins;
    this.client = client;
    this.timers = timers;
    this.materializer = materializer;
  }

  public Effect check() {
    var library = HumanLibrary.of(plugins);
    var signals = Figures.signals(HumanQueries.all(client, materializer), library.controls(), library.now());
    for (var signal : signals) {
      if (!signal.breached()) continue;
      human.raise(Patterns.oversightQuality("The " + signal.signal() + " of " + signal.control() + " is "
              + signal.value() + ". Reset, retrain or reassign?")
          .control(signal.qualityControl())
          .subject(Subject.behaviour(signal.signal() + " on " + signal.control() + " is " + signal.value(),
              signal.signal(), signal.value(), signal.limit(), signal.comparison()).withRef(signal.control()))
          .trigger(new Trigger("monitor", signal.signal() + " " + signal.comparison() + " " + signal.limit(),
              "The oversight of " + signal.control() + " is not working as it should.",
              List.of(new Trigger.Value(signal.signal(), signal.value(), signal.limit())), List.of()))
          .dedupeKey("oq:" + signal.qualityControl() + ":" + signal.control())
          .build()).toCompletableFuture().join();
    }
    timers.createSingleTimer(TIMER, EVERY, client.forTimedAction().method(QualityMonitor::check).deferred());
    return effects().done();
  }
}
