package io.akka.human.samples.loan;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import com.typesafe.config.Config;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanRegistration;
import io.akka.human.api.Human;
import io.akka.human.api.spi.HumanClock;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.YamlCatalog;
import io.akka.human.engine.YamlOverseers;
import io.akka.human.local.DevClock;
import io.akka.human.local.LocalProofLedger;
import io.akka.human.samples.loan.application.ApplyHandlers;
import io.akka.human.samples.loan.application.ContestWorkflow;
import io.akka.human.samples.loan.application.DeadlineExtensionWorkflow;
import io.akka.human.samples.loan.application.EligibilityHaltScope;
import io.akka.human.samples.loan.application.Monitors;
import io.akka.human.samples.loan.application.NoticeWorkflow;
import io.akka.human.samples.loan.application.OversightMonitors;
import io.akka.human.samples.loan.application.UnderwritingWorkflow;
import io.akka.human.samples.loan.domain.EligibilityAssessor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires the library into the service: the controls and overseer registry of {@code governance/}, a
 * resumer per waiting workflow, the apply handlers of the monitors, flags, templates and model
 * changes, the halt scope of the eligibility assessment, the local proof ledger and the development
 * clock. Registers the service for the companion and starts the monitors on startup.
 */
@Setup
public class LoanSetup implements ServiceSetup {

  public static final String SERVICE = "loan-origination";

  private final ComponentClient client;
  private final TimerScheduler timers;
  private final Config config;
  private final HumanPlugins plugins;
  private final HumanClient human;

  public LoanSetup(ComponentClient client, TimerScheduler timers, Config config) {
    this.client = client;
    this.timers = timers;
    this.config = config;
    this.plugins = plugins(client, config, new DevClock());
    this.human = new HumanClient(client, plugins);
  }

  static HumanPlugins plugins(ComponentClient client, Config config, HumanClock clock) {
    var built = new AtomicReference<HumanPlugins>();
    var plugins = HumanPlugins.builder(SERVICE, YamlCatalog.fromClasspath("governance/controls.yaml"),
            YamlOverseers.fromClasspath("governance/overseers.yaml"))
        .environment(config.getString("human.environment"))
        .clock(clock)
        .devRoutes(config.getBoolean("human.dev-routes"))
        .ledger(new LocalProofLedger(client))
        .resumer(UnderwritingWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(UnderwritingWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .resumer(NoticeWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(NoticeWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .resumer(ContestWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(ContestWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .resumer(DeadlineExtensionWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(DeadlineExtensionWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .handler(Monitors.HANDLER, ApplyHandlers.monitors(client, () -> new HumanClient(client, built.get())))
        .handler("flag-feedback", ApplyHandlers.flags(client))
        .handler(ApplyHandlers.TEMPLATE, ApplyHandlers.templates(client))
        .handler(ApplyHandlers.WRITER_MODEL, ApplyHandlers.writerModel(client))
        .haltScope(EligibilityAssessor.CAPABILITY,
            new EligibilityHaltScope(client, config.getInt("loan-origination.halt.max-referral-backlog")))
        .build();
    built.set(plugins);
    return plugins;
  }

  @Override
  public void onStartup() {
    if (config.getBoolean("human.register")) HumanRegistration.register(SERVICE);
    OversightMonitors.schedule(timers, client, config);
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> T getDependency(Class<T> type) {
        if (type == HumanPlugins.class) return (T) plugins;
        if (type == Human.class || type == HumanClient.class) return (T) human;
        throw new IllegalArgumentException("no dependency of type " + type.getName());
      }
    };
  }
}
