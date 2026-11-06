package io.akka.human.samples.patterns.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Human;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * An adverse notice an agent drafts about a person, held until a case reviewer confirms, amends or
 * withholds it: decision review before effect (pt-00002).
 */
@Component(id = "notice")
public class NoticeWorkflow extends Workflow<NoticeWorkflow.Notice> {

  public static final String RESUMER = "notice";
  /** pt-00002 has 4 hours, then 4 more at head-of-operations. */
  static final Duration BACKSTOP = Duration.ofHours(10);

  public record Start(String applicant, String text, String decisionRef) {}

  /** {@code status}: AWAITING_REVIEW, SENT, WITHHELD or NO_ANSWER; {@code text} as sent, when sent. */
  public record Notice(String id, String applicant, String text, String decisionRef, String status, String eventId,
      String note) {

    Notice with(String nextStatus, String nextText, String nextNote) {
      return new Notice(id, applicant, nextText, decisionRef, nextStatus, eventId, nextNote);
    }
  }

  private final Human human;
  private final String id;

  public NoticeWorkflow(Human human, WorkflowContext context) {
    this.human = human;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(20)).build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    return effects().updateState(new Notice(id, start.applicant(), start.text(), start.decisionRef(), "DRAFTED", null, ""))
        .transitionTo(NoticeWorkflow::askReview).thenReply(Done.done());
  }

  private StepEffect askReview() {
    var n = currentState();
    var raised = human.raise(Patterns.decisionReview("Should this adverse notice go to " + n.applicant() + "?")
        .control("pt-00002")
        .subject(Subject.output("Adverse notice to " + n.applicant(), n.text())
            .with("decision", Subject.fields("ref", n.decisionRef(), "summary", "Benefit claim denied", "affects", n.applicant()))
            .withAgent("notices").withWorkflow(id).withRef(n.decisionRef()))
        .check("The notice gives the reason for the decision.", "subject")
        .check("The notice says how to appeal.", "subject")
        .trigger(Trigger.rule("workflow", "notice.adverse", "An adverse notice is ready to send."))
        .proposedBy("agent:notices")
        .dedupeKey("notice-" + id)
        .effect(Action.APPROVE, "The notice is sent as drafted.")
        .effect(Action.EDIT, "The amended notice is sent.")
        .effect(Action.DENY, "The notice is not sent.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    return stepEffects()
        .updateState(new Notice(n.id(), n.applicant(), n.text(), n.decisionRef(), "AWAITING_REVIEW", raised.eventId(), ""))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(NoticeWorkflow::noAnswer));
  }

  public Effect<Done> decided(Decision decision) {
    var n = currentState();
    if (n == null || !n.status().equals("AWAITING_REVIEW")) return effects().reply(Done.done());
    if (decision.action() == Action.APPROVE) {
      return effects().updateState(n.with("SENT", n.text(), decision.rationale())).end().thenReply(Done.done());
    }
    if (decision.action() == Action.EDIT) {
      return effects().updateState(n.with("SENT", amended(decision, n.text()), decision.rationale())).end().thenReply(Done.done());
    }
    return effects().updateState(n.with("WITHHELD", n.text(),
        decision.unanswered() ? "no decision: " + decision.slaOutcome() : decision.rationale())).end().thenReply(Done.done());
  }

  public Effect<Done> noAnswer() {
    return effects().updateState(currentState().with("NO_ANSWER", currentState().text(), "the backstop timeout passed"))
        .end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Notice> get() {
    if (currentState() == null) return effects().error("no such notice");
    return effects().reply(currentState());
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
