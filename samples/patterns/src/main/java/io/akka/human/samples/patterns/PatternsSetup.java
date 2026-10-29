package io.akka.human.samples.patterns;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timer.TimerScheduler;
import com.typesafe.config.Config;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanRegistration;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.spi.ApplyHandler;
import io.akka.human.api.spi.HaltScope;
import io.akka.human.api.spi.HumanClock;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.YamlCatalog;
import io.akka.human.engine.YamlOverseers;
import io.akka.human.local.DevClock;
import io.akka.human.local.HumanGuardrails;
import io.akka.human.local.LocalProofLedger;
import io.akka.human.samples.patterns.application.AttestationSchedule;
import io.akka.human.samples.patterns.application.HandOffWorkflow;
import io.akka.human.samples.patterns.application.NoticeWorkflow;
import io.akka.human.samples.patterns.application.PaymentWorkflow;
import io.akka.human.samples.patterns.application.QualityMonitor;
import io.akka.human.samples.patterns.application.RecordsEntity;
import io.akka.human.samples.patterns.application.RolloutWorkflow;
import io.akka.human.samples.patterns.application.SafeStateEntity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Wires the library into the service: the fourteen controls and the overseer registry from the
 * fixtures, a resumer per waiting workflow, an apply handler per raise-and-carry-on pattern, the halt
 * scope of the eligibility capability, the local proof ledger and the development clock. Registers
 * the service for the companion and installs the guardrails on startup.
 */
@Setup
public class PatternsSetup implements ServiceSetup {

  public static final String SERVICE = "patterns";
  public static final String ELIGIBILITY = "eligibility-assessment";

  private final ComponentClient client;
  private final TimerScheduler timers;
  private final Config config;
  private final HumanPlugins plugins;
  private final Human human;

  public PatternsSetup(ComponentClient client, TimerScheduler timers, Config config) {
    this.client = client;
    this.timers = timers;
    this.config = config;
    this.plugins = plugins(client, config.getString("human.environment"), config.getBoolean("human.dev-routes"),
        new DevClock());
    this.human = new HumanClient(client, plugins);
  }

  /** The plug-ins, with the clock given; the tests pass a clock they move. */
  public static HumanPlugins plugins(ComponentClient client, String environment, boolean devRoutes, HumanClock clock) {
    return HumanPlugins.builder(SERVICE, YamlCatalog.fromClasspath("fixtures/controls-patterns.yaml"),
            YamlOverseers.fromClasspath("fixtures/overseers.yaml"))
        .environment(environment)
        .clock(clock)
        .devRoutes(devRoutes)
        .ledger(new LocalProofLedger(client))
        .resumer(PaymentWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(PaymentWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .resumer(NoticeWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(NoticeWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .resumer(RolloutWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(RolloutWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .resumer(HandOffWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(HandOffWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .handler("waiver", record(client, "waiver", d -> d.proceeds() ? "granted" : "refused"))
        .handler("match", record(client, "match", d -> d.action() == null ? "undecided" : switch (d.action()) {
          case APPROVE -> "same";
          case EDIT -> "relabelled";
          default -> "different";
        }))
        .handler("flag-feedback", record(client, "eval-case", d -> d.proceeds() ? "accepted as an eval case" : "dismissed"))
        .handler("triage", record(client, "triage", d -> d.action() == null ? "unanswered" : switch (d.action()) {
          case APPROVE -> "resolved";
          case ESCALATE -> "reported";
          default -> "dismissed";
        }))
        .handler("attestation", record(client, "attestation", d -> d.action() == null ? "not attested" : switch (d.action()) {
          case APPROVE -> "attested";
          case EDIT -> "attested with findings";
          default -> "refused";
        }))
        .handler("oversight-quality", record(client, "oversight-quality", d -> d.action() == null ? "unanswered"
            : d.action().name().toLowerCase()))
        .handler("contest-appeal", record(client, "appeal", d -> d.action() == null ? "unanswered" : switch (d.action()) {
          case APPROVE -> "upheld";
          case EDIT -> "modified";
          case REVERSE -> "overturned";
          default -> d.action().name().toLowerCase();
        }))
        .handler("override", record(client, "override", d -> d.action() == null ? "none" : d.action().name().toLowerCase()))
        .haltScope(ELIGIBILITY, new HaltScope() {
          @Override
          public CompletionStage<Map<String, Boolean>> safeStateChecks(String target) {
            return client.forKeyValueEntity(ELIGIBILITY).method(SafeStateEntity::get).invokeAsync()
                .thenApply(state -> Map.of("backlog drained", state.backlogDrained()));
          }
        })
        .build();
  }

  /** An apply handler that writes what was decided to the sample's records, under the resume id. */
  private static ApplyHandler record(ComponentClient client, String kind, java.util.function.Function<Decision, String> outcome) {
    return (id, subject, decision) -> {
      var fields = new LinkedHashMap<String, String>();
      fields.put("event", decision.eventId());
      fields.put("summary", subject.summary());
      fields.put("deciders", String.join(", ", decision.deciders()));
      fields.put("rationale", decision.rationale());
      fields.put("conclusion", String.valueOf(decision.conclusion()));
      var line = outcome.apply(decision);
      return client.forKeyValueEntity(id).method(RecordsEntity::write)
          .invokeAsync(new RecordsEntity.Record(kind, id, line, fields, Instant.now()))
          .thenApply(written -> kind + " " + id + " " + line);
    };
  }

  @Override
  public void onStartup() {
    if (config.getBoolean("human.register")) HumanRegistration.register(SERVICE);
    HumanGuardrails.install(client, plugins);
    AttestationSchedule.schedule(timers, client, AttestationSchedule.nextQuarter(LocalDate.now(ZoneOffset.UTC)));
    timers.createSingleTimer(QualityMonitor.TIMER, Duration.ofHours(1),
        client.forTimedAction().method(QualityMonitor::check).deferred());
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
