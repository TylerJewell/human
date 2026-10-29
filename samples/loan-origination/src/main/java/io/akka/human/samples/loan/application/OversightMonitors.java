package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;
import akka.javasdk.timer.TimerScheduler;
import akka.stream.Materializer;
import com.typesafe.config.Config;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanLibrary;
import io.akka.human.api.spi.HumanPlugins;

/**
 * Runs the monitors of S2, S3 and S4 every {@code loan-origination.monitors.interval} and sets the
 * timer for the next run; an interval of zero runs them only when asked ({@code POST /monitors/run}).
 */
@Component(id = "oversight-monitors")
public class OversightMonitors extends TimedAction {

  public static final String TIMER = "oversight-monitors";

  private final ComponentClient client;
  private final HumanClient human;
  private final HumanPlugins plugins;
  private final Materializer materializer;
  private final TimerScheduler timers;
  private final Config config;

  public OversightMonitors(ComponentClient client, HumanClient human, HumanPlugins plugins, Materializer materializer,
      TimerScheduler timers, Config config) {
    this.client = client;
    this.human = human;
    this.plugins = plugins;
    this.materializer = materializer;
    this.timers = timers;
    this.config = config;
  }

  public Effect evaluate() {
    monitors(client, human, plugins, materializer, config).evaluate();
    schedule(timers, client, config);
    return effects().done();
  }

  public static Monitors monitors(ComponentClient client, HumanClient human, HumanPlugins plugins,
      Materializer materializer, Config config) {
    return new Monitors(client, human, HumanLibrary.of(plugins), materializer,
        config.getInt("loan-origination.monitors.referral-minimum"));
  }

  public static void schedule(TimerScheduler timers, ComponentClient client, Config config) {
    var every = config.getDuration("loan-origination.monitors.interval");
    if (every.isZero()) return;
    timers.createSingleTimer(TIMER, every, client.forTimedAction().method(OversightMonitors::evaluate).deferred());
  }
}
