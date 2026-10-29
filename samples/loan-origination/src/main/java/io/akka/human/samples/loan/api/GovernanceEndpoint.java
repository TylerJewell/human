package io.akka.human.samples.loan.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import akka.stream.Materializer;
import com.typesafe.config.Config;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.Action;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.samples.loan.LoanSetup;
import io.akka.human.samples.loan.application.ApplyHandlers;
import io.akka.human.samples.loan.application.MonitorEntity;
import io.akka.human.samples.loan.application.Monitors;
import io.akka.human.samples.loan.application.NoticeWritingEntity;
import io.akka.human.samples.loan.application.OversightMonitors;
import io.akka.human.samples.loan.domain.NoticeWriter;
import java.util.List;
import java.util.UUID;

/**
 * Changes to how notices are written, which wait for authorisation: the notice writer's model (S5)
 * and a decline letter template (0ff1ce0001). And the monitors of S2 to S4, run on request.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class GovernanceEndpoint extends AbstractHttpEndpoint {

  public static final String MODEL_CHANGE = "custom-5a1e00000005";
  public static final String TEMPLATE_REVIEW = "0ff1ce0001";
  static final String NOTICE_WRITER = "agent:" + NoticeWriter.AGENT;

  /** @param evaluation what the offline evaluation of the new model found, if any */
  public record ModelChange(String to, String proposedBy, String builtBy, String evaluation) {}

  /** @param reason the decline reason the template is for; {@code proposedBy} defaults to the notice writer */
  public record TemplateSuggestion(String reason, String text, String proposedBy) {}

  public record Raised(String eventId, String state) {}

  public record Readings(List<Monitors.Reading> readings) {}

  private final ComponentClient client;
  private final HumanClient human;
  private final HumanPlugins plugins;
  private final Materializer materializer;
  private final Config config;

  public GovernanceEndpoint(ComponentClient client, HumanClient human, HumanPlugins plugins, Materializer materializer,
      Config config) {
    this.client = client;
    this.human = human;
    this.plugins = plugins;
    this.materializer = materializer;
    this.config = config;
  }

  @Get("/notice-writer")
  public NoticeWritingEntity.Writing writing() {
    return client.forKeyValueEntity(NoticeWritingEntity.ID).method(NoticeWritingEntity::get).invoke();
  }

  /** S5: a change of the notice writer's model, which two people who neither proposed nor built it approve. */
  @Post("/notice-writer/model-changes")
  public Raised changeModel(ModelChange r) {
    if (blank(r.to()) || blank(r.proposedBy())) throw HttpException.badRequest("to and proposedBy are required");
    var current = writing().model();
    var changeId = "change-" + UUID.randomUUID().toString().substring(0, 8);
    var raised = human.raise(Patterns.changeAuthorization("Switch the notice writer from " + current + " to " + r.to() + "?")
        .control(MODEL_CHANGE)
        .subject(Subject.change("Notice writer model " + current + " to " + r.to(), LoanSetup.SERVICE, "model", changeId,
                Subject.fields("model", current), Subject.fields("model", r.to()))
            .with("proposedBy", r.proposedBy())
            .with("builtBy", r.builtBy())
            .with("labels", Subject.fields("model", "Model"))
            .with("evidence", blank(r.evaluation()) ? List.of()
                : List.of(Subject.fields("kind", "offline-eval", "ref", "eval-" + changeId, "summary", r.evaluation())))
            .with("blastRadius", Subject.fields("useCases", List.of("adverse-action-notices"), "trafficShare", 1.0))
            .with("stage", "awaiting-approval")
            .withAgent(NoticeWriter.AGENT).withRef(changeId))
        .check("The evaluation covers adverse-action notices.", "evidence")
        .trigger(Trigger.rule("change-request", "notice-writer rollout", r.proposedBy() + " proposed the change."))
        .proposedBy(r.proposedBy())
        .builtBy(r.builtBy())
        .effect(Action.APPROVE, "The notice writer switches to " + r.to() + " for every new notice.")
        .effect(Action.DENY, "The notice writer keeps " + current + ".")
        .effect(Action.SEND_BACK, "The proposer revises the change.")
        .resumes(Resume.handler(ApplyHandlers.WRITER_MODEL, changeId))
        .build()).toCompletableFuture().join();
    return new Raised(raised.eventId(), raised.state());
  }

  /** A decline letter template, authorised by underwriting counsel before it is used. */
  @Post("/notice-writer/templates")
  public Raised suggestTemplate(TemplateSuggestion r) {
    if (blank(r.reason()) || blank(r.text())) throw HttpException.badRequest("reason and text are required");
    var proposer = blank(r.proposedBy()) ? NOTICE_WRITER : r.proposedBy();
    var ref = "tpl-" + r.reason().toLowerCase().replace('_', '-');
    var raised = human.raise(Patterns.changeAuthorization("Use this decline letter template for " + r.reason() + "?")
        .control(TEMPLATE_REVIEW)
        .subject(Subject.change("Decline letter template for " + r.reason(), LoanSetup.SERVICE, "template", ref,
                Subject.fields("template", writing().template(r.reason())), Subject.fields("template", r.text()))
            .with("proposedBy", proposer)
            .with("builtBy", NOTICE_WRITER)
            .with("labels", Subject.fields("template", "Template"))
            .with("blastRadius", Subject.fields("useCases", List.of("adverse-action-notices")))
            .with("stage", "awaiting-approval")
            .withAgent(NoticeWriter.AGENT).withRef(ref))
        .check("The new wording says the same as the old, more plainly.", "subject")
        .trigger(Trigger.rule("change-request", "template suggestion", proposer + " suggested a template for "
            + r.reason() + " declines."))
        .proposedBy(proposer)
        .builtBy(NOTICE_WRITER)
        .effect(Action.APPROVE, "The template is used for every " + r.reason() + " notice.")
        .effect(Action.DENY, "The current template stays.")
        .effect(Action.SEND_BACK, "The template returns to its proposer with what to change.")
        .resumes(Resume.handler(ApplyHandlers.TEMPLATE, r.reason()))
        .build()).toCompletableFuture().join();
    return new Raised(raised.eventId(), raised.state());
  }

  /** Runs the monitors of S2, S3 and S4 now and answers each reading. */
  @Post("/monitors/run")
  public Readings runMonitors() {
    return new Readings(OversightMonitors.monitors(client, human, plugins, materializer, config).evaluate());
  }

  /** A monitor's trigger and what was done with its last event; S3's keys add the watched control. */
  @Get("/monitors/{key}")
  public MonitorEntity.Monitor monitor(String key) {
    return client.forKeyValueEntity(key).method(MonitorEntity::get).invoke();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
