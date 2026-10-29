package io.akka.human.samples.contextlayer;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.human.akka.HumanClient;
import io.akka.human.akka.HumanRegistration;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.Subject;
import io.akka.human.api.spi.ApplyHandler;
import io.akka.human.api.spi.HumanClock;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.YamlCatalog;
import io.akka.human.engine.YamlOverseers;
import io.akka.human.local.DevClock;
import io.akka.human.local.LocalProofLedger;
import io.akka.human.samples.contextlayer.application.ActionLedgerEntity;
import io.akka.human.samples.contextlayer.application.ActionWorkflow;
import io.akka.human.samples.contextlayer.application.AssistantEntity;
import io.akka.human.samples.contextlayer.application.CaseEntity;
import io.akka.human.samples.contextlayer.application.ConnectorPackEntity;
import io.akka.human.samples.contextlayer.application.ControlClaimsEntity;
import io.akka.human.samples.contextlayer.application.DomainConfigEntity;
import io.akka.human.samples.contextlayer.application.IdentityEntity;
import io.akka.human.samples.contextlayer.application.QuestionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Puts contextlayer's seventeen human controls on the library. The bundle and the overseer registry
 * come from {@code governance/}; each domain change a decision allows is carried out by an apply handler
 * registered under the name the raise resumes, and the action run is resumed through its workflow.
 */
@Setup
public class ContextLayerSetup implements ServiceSetup {

  public static final String SERVICE = "contextlayer";
  /** The halt target that stops every playbook; a single playbook is halted under its own id. */
  public static final String ALL_PLAYBOOKS = "playbooks";

  public static final String ACTION_APPROVAL = "custom-cl0003";
  public static final String LINK_REVIEW = "custom-cl0004";
  public static final String RELEASE_APPROVAL = "custom-cl0005";
  public static final String STAGE_CHANGE = "custom-cl0040";
  public static final String CONNECTOR_ACTIVATION = "custom-cl0006";
  public static final String CONNECTOR_DEACTIVATION = "custom-cl0041";
  public static final String HALT = "custom-cl0011";
  public static final String RESTART = "custom-cl0012";
  public static final String DOMAIN_JOIN = "custom-cl0014";
  public static final String DOMAIN_ADMINISTRATION = "custom-cl0042";
  public static final String RUN_QUESTION = "custom-cl0043";
  public static final String UNKNOWN_OUTCOME = "custom-cl0044";
  public static final String FAILED_STEP = "custom-cl0045";
  public static final String CASE_RESOLUTION = "custom-cl0046";
  public static final String CONTROL_WAIVER = "custom-cl0047";
  public static final String CONTROL_ACCEPTANCE = "custom-cl0048";
  public static final String EVIDENCE_ASSERTION = "custom-cl0049";

  public static final String LINK_HANDLER = "link";
  public static final String UNKNOWN_OUTCOME_HANDLER = "unknown-outcome";
  public static final String RELEASE_HANDLER = "release";
  public static final String STAGE_HANDLER = "stage";
  public static final String CONNECTOR_HANDLER = "connector";
  public static final String DOMAIN_HANDLER = "domain";
  public static final String JOIN_HANDLER = "join";
  public static final String QUESTION_HANDLER = "question";
  public static final String FAILED_STEP_HANDLER = "failed-step";
  public static final String CASE_HANDLER = "case";
  public static final String WAIVER_HANDLER = "waiver";
  public static final String ACCEPTANCE_HANDLER = "acceptance";
  public static final String ASSERTION_HANDLER = "assertion";

  private final Config config;
  private final HumanPlugins plugins;
  private final HumanClient human;

  public ContextLayerSetup(ComponentClient client, Config config) {
    this.config = config;
    this.plugins = plugins(client, config.getString("human.environment"), config.getBoolean("human.dev-routes"),
        new DevClock());
    this.human = new HumanClient(client, plugins);
  }

  public static HumanPlugins plugins(ComponentClient client, String environment, boolean devRoutes, HumanClock clock) {
    return HumanPlugins.builder(SERVICE, YamlCatalog.fromClasspath("governance/controls.yaml"),
            YamlOverseers.fromClasspath("governance/overseers.yaml"))
        .environment(environment)
        .clock(clock)
        .devRoutes(devRoutes)
        .ledger(new LocalProofLedger(client))
        .resumer(ActionWorkflow.RESUMER, (id, decision) ->
            client.forWorkflow(id).method(ActionWorkflow::decided).invokeAsync(decision).thenApply(done -> null))
        .handler(LINK_HANDLER, decided((id, subject, d) -> client.forKeyValueEntity(id).method(IdentityEntity::review)
            .invokeAsync(new IdentityEntity.Review(d.action() == Action.APPROVE, decider(d), text(subject, "fingerprint")))
            .thenApply(c -> "link " + (d.action() == Action.APPROVE ? "confirmed" : "rejected"))))
        .handler(UNKNOWN_OUTCOME_HANDLER, decided((id, subject, d) -> client.forKeyValueEntity(id)
            .method(ActionLedgerEntity::resolveUnknown)
            .invokeAsync(new ActionLedgerEntity.Resolve(d.action() == Action.APPROVE, decider(d)))
            .thenApply(a -> d.action() == Action.APPROVE ? "recorded as having taken effect"
                : "recorded as not having taken effect")))
        .handler(RELEASE_HANDLER, decided((id, subject, d) -> client.forKeyValueEntity(before(id))
            .method(AssistantEntity::releaseDecided).invokeAsync(new AssistantEntity.Decided(after(id), d.proceeds(), decider(d)))
            .thenApply(a -> d.proceeds() ? "release approved" : "release not approved")))
        .handler(STAGE_HANDLER, decided((id, subject, d) -> d.proceeds()
            ? client.forKeyValueEntity(before(id)).method(AssistantEntity::stageDecided)
                .invokeAsync(new AssistantEntity.Decided(after(id), true, decider(d)))
                .thenApply(a -> "stage changed to " + a.stage())
            : done("the stage is unchanged")))
        .handler(CONNECTOR_HANDLER, decided((id, subject, d) -> {
          if (!d.proceeds()) return done("the connector is unchanged");
          var after = detail(subject, "after");
          return client.forKeyValueEntity(text(subject, "system")).method(ConnectorPackEntity::setMode)
              .invokeAsync(new ConnectorPackEntity.SetMode(after.get("mode"), after.get("hash"), decider(d), d.rationale()))
              .thenApply(p -> "connector set to " + p.mode());
        }))
        .handler(DOMAIN_HANDLER, decided((id, subject, d) -> {
          if (!d.proceeds()) return done("the data domains are unchanged");
          var after = detail(subject, "after");
          var domains = client.forKeyValueEntity(DomainConfigEntity.ID);
          return switch (text(subject, "change")) {
            case "set-domain" -> domains.method(DomainConfigEntity::set).invokeAsync(new DomainConfigEntity.Domain(
                    after.get("name"), split(after.get("entityTypes")), split(after.get("readerRoles")), after.get("changedBy")))
                .thenApply(c -> "domain " + after.get("name") + " set");
            case "remove-domain" -> domains.method(DomainConfigEntity::remove).invokeAsync(after.get("name"))
                .thenApply(c -> "domain " + after.get("name") + " removed");
            default -> domains.method(DomainConfigEntity::join)
                .invokeAsync(new DomainConfigEntity.Join(after.get("a"), after.get("b"), decider(d), false))
                .thenApply(c -> "join rule revoked");
          };
        }))
        .handler(JOIN_HANDLER, decided((id, subject, d) -> {
          if (!d.proceeds()) return done("the join rule is not approved");
          var after = detail(subject, "after");
          return client.forKeyValueEntity(DomainConfigEntity.ID).method(DomainConfigEntity::join)
              .invokeAsync(new DomainConfigEntity.Join(after.get("a"), after.get("b"), decider(d), true))
              .thenApply(c -> "join rule approved");
        }))
        .handler(QUESTION_HANDLER, decided((id, subject, d) -> client.forKeyValueEntity(id).method(QuestionEntity::ran)
            .invokeAsync(new QuestionEntity.Ran(d.proceeds(), decider(d)))
            .thenApply(q -> d.proceeds() ? "run by " + q.ranBy() + ": " + q.rows().size() + " rows" : "not run")))
        .handler(FAILED_STEP_HANDLER, decided((id, subject, d) -> done(d.action() == Action.APPROVE
            ? "acknowledged by " + decider(d) : "reported by " + decider(d))))
        .handler(CASE_HANDLER, decided((id, subject, d) -> client.forKeyValueEntity(id).method(CaseEntity::close)
            .invokeAsync(new CaseEntity.Close(d.action() == Action.APPROVE, decider(d), d.rationale()))
            .thenApply(c -> "case " + c.status().toLowerCase())))
        .handler(WAIVER_HANDLER, decided((id, subject, d) -> d.proceeds()
            ? client.forKeyValueEntity(id).method(ControlClaimsEntity::waive).invokeAsync(new ControlClaimsEntity.Waiver(
                    text(subject, "justification"), Instant.parse(text(subject, "until")), text(subject, "requestedBy"),
                    decider(d), Instant.now()))
                .thenApply(c -> "waived until " + text(subject, "until"))
            : done("not waived")))
        .handler(ACCEPTANCE_HANDLER, decided((id, subject, d) -> {
          if (!d.proceeds()) return done("not accepted");
          var after = detail(subject, "after");
          return client.forKeyValueEntity(id).method(ControlClaimsEntity::accept).invokeAsync(new ControlClaimsEntity.Acceptance(
                  after.get("digest"), d.rationale(), text(subject, "proposedBy"), decider(d), Instant.now()))
              .thenApply(c -> "accepted under catalog " + after.get("digest"));
        }))
        .handler(ASSERTION_HANDLER, decided((id, subject, d) -> {
          if (!d.proceeds()) return done("not asserted");
          var after = detail(subject, "after");
          return client.forKeyValueEntity(id).method(ControlClaimsEntity::addAssertion)
              .invokeAsync(new ControlClaimsEntity.Assertion(0, split(after.get("records")), split(after.get("documents")),
                  d.rationale(), text(subject, "proposedBy"), decider(d), Instant.now()))
              .thenApply(c -> "asserted as revision " + c.assertions().size());
        }))
        .build();
  }

  /** A handler that records an event closed without a decision and carries out the rest. */
  private static ApplyHandler decided(ApplyHandler handler) {
    return (id, subject, decision) -> decision.action() == null
        ? done("no decision (" + decision.slaOutcome() + "); nothing changed")
        : handler.apply(id, subject, decision);
  }

  private static CompletionStage<String> done(String note) {
    return CompletableFuture.completedFuture(note);
  }

  private static String decider(Decision d) {
    return d.deciders().isEmpty() ? "system" : d.deciders().getLast();
  }

  private static String text(Subject subject, String field) {
    var value = subject.detail().get(field);
    return value == null ? null : String.valueOf(value);
  }

  /** A change subject's {@code before} or {@code after}, as text. */
  @SuppressWarnings("unchecked")
  private static Map<String, String> detail(Subject subject, String field) {
    var value = subject.detail().get(field);
    if (!(value instanceof Map<?, ?> map)) return Map.of();
    var out = new java.util.LinkedHashMap<String, String>();
    ((Map<String, Object>) map).forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
    return out;
  }

  private static List<String> split(String commaList) {
    return commaList == null || commaList.isBlank() ? List.of() : List.of(commaList.split(","));
  }

  /** The part of a {@code <owner>/<key>} resume id before the slash. */
  private static String before(String id) {
    return id.substring(0, id.indexOf('/'));
  }

  private static String after(String id) {
    return id.substring(id.indexOf('/') + 1);
  }

  @Override
  public void onStartup() {
    if (config.getBoolean("human.register")) HumanRegistration.register(SERVICE);
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
