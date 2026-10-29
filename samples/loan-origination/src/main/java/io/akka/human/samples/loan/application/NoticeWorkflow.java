package io.akka.human.samples.loan.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.samples.loan.domain.LoanApplication;
import io.akka.human.samples.loan.domain.NoticeWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The adverse-action notice of a declined application. The notice writer drafts it from the decline
 * reasons, the template for the first reason and the underwriter's rationale; a draft the
 * protected-characteristics guardrail blocks is held until an adverse-action reviewer releases it as
 * shown, amends it, or has the standard letter issued (custom-5a1e00000008). Nobody deciding within
 * the review window issues the standard letter. The workflow id is the application id.
 */
@Component(id = "notice")
public class NoticeWorkflow extends Workflow<NoticeWorkflow.Notice> {

  public static final String RESUMER = "notice";
  public static final String CONTROL = "custom-5a1e00000008";
  /** custom-5a1e00000008 has 4 hours. */
  static final Duration BACKSTOP = Duration.ofHours(6);

  public record Start(List<String> reasons, String rationale) {}

  /**
   * @param status DRAFTING, HELD, ISSUED or NO_ANSWER
   * @param draft the draft as a reviewer sees it, blocked sentences withheld
   */
  public record Notice(String applicationId, List<String> reasons, String rationale, String status, String template,
      String model, String draft, List<NoticeWriter.Block> blocks, String eventId, Decision decision) {}

  private final Human human;
  private final ComponentClient client;
  private final String id;

  public NoticeWorkflow(Human human, ComponentClient client, WorkflowContext context) {
    this.human = human;
    this.client = client;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(20)).build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    return effects().updateState(new Notice(id, start.reasons(), start.rationale(), "DRAFTING", null, null, null,
            List.of(), null, null))
        .transitionTo(NoticeWorkflow::draft).thenReply(Done.done());
  }

  private StepEffect draft() {
    var n = currentState();
    var writing = client.forKeyValueEntity(NoticeWritingEntity.ID).method(NoticeWritingEntity::get).invoke();
    var template = writing.template(n.reasons().getFirst());
    var draft = NoticeWriter.draft(writing.model(), template, n.reasons(), n.rationale());
    var drafted = new Notice(id, n.reasons(), n.rationale(), "DRAFTING", template, writing.model(), draft.text(),
        draft.blocks(), null, null);
    if (!draft.blocked()) {
      issue(drafted, draft.text(), "DRAFT");
      return stepEffects().updateState(with(drafted, "ISSUED", null, null)).thenEnd();
    }
    var app = client.forKeyValueEntity(id).method(ApplicationEntity::noticeBlocked).invoke();
    var categories = draft.blocks().stream().map(NoticeWriter.Block::category).distinct().toList();
    var raised = human.raise(Patterns.decisionReview("Release the held adverse-action notice for application " + id + "?")
        .control(CONTROL)
        .subject(Subject.output("Held adverse-action notice for application " + id, draft.text())
            .with("flag", Subject.fields("by", "protected-characteristics guardrail",
                "note", "The draft named " + String.join(", ", categories) + " (en); the sentences are withheld."))
            .with("decision", Subject.fields("ref", id, "summary", "Declined: " + String.join(", ", n.reasons()),
                "affects", app.applicant()))
            .with("model", writing.model())
            .withAgent(NoticeWriter.AGENT).withWorkflow(id).withRef(id))
        .check("The notice gives the reasons for the decline.", "subject")
        .check("The notice names no protected characteristic.", "subject")
        .trigger(new Trigger("guardrail", "protected-characteristics", "The guardrail blocked " + draft.blocks().size()
            + " sentence(s) of the draft.", List.of(), List.of(new Trigger.Condition("notice checks passed", false))))
        .proposedBy("agent:" + NoticeWriter.AGENT)
        .dedupeKey("notice-" + id)
        .effect(Action.APPROVE, "The notice is issued as shown, without the withheld sentences.")
        .effect(Action.EDIT, "Your corrected notice is issued.")
        .effect(Action.DENY, "The standard letter is issued instead.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    return stepEffects().updateState(with(drafted, "HELD", raised.eventId(), null))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(NoticeWorkflow::noAnswer));
  }

  public Effect<Done> decided(Decision decision) {
    var n = currentState();
    if (n == null || !n.status().equals("HELD") || !decision.eventId().equals(n.eventId())) {
      return effects().reply(Done.done());
    }
    return effects().updateState(with(n, "HELD", n.eventId(), decision)).transitionTo(NoticeWorkflow::release)
        .thenReply(Done.done());
  }

  private StepEffect release() {
    var n = currentState();
    var d = n.decision();
    if (d.action() == Action.APPROVE) {
      issue(n, n.draft(), "REVIEWED");
    } else if (d.action() == Action.EDIT) {
      issue(n, amended(d, n.draft()), "AMENDED");
    } else {
      issue(n, NoticeWriter.standardLetter(n.template(), n.reasons()), "STANDARD");
    }
    return stepEffects().updateState(with(n, "ISSUED", n.eventId(), d)).thenEnd();
  }

  public Effect<Done> noAnswer() {
    var n = currentState();
    return effects().updateState(with(n, "NO_ANSWER", n.eventId(), null)).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Notice> get() {
    if (currentState() == null) return effects().error("no notice for " + id);
    return effects().reply(currentState());
  }

  private void issue(Notice n, String text, String issuedAs) {
    client.forKeyValueEntity(id).method(ApplicationEntity::noticeIssued)
        .invoke(new LoanApplication.Notice(text, issuedAs, n.model(), Instant.now()));
  }

  private static Notice with(Notice n, String status, String eventId, Decision decision) {
    return new Notice(n.applicationId(), n.reasons(), n.rationale(), status, n.template(), n.model(), n.draft(), n.blocks(),
        eventId, decision);
  }

  /** EDIT replaces the text through the patch {@code [{op: replace, path: /output, value}]}. */
  @SuppressWarnings("unchecked")
  private static String amended(Decision d, String text) {
    if (!(d.edits().get("patch") instanceof List<?> patch)) return text;
    for (var op : patch) {
      var change = (Map<String, Object>) op;
      if ("/output".equals(change.get("path")) && change.get("value") != null) return String.valueOf(change.get("value"));
    }
    return text;
  }
}
