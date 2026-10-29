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
import io.akka.human.samples.loan.domain.ApplicantSupport;
import io.akka.human.samples.loan.domain.LoanApplication;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The support assistant's call of {@code extendDocumentDeadline} for more days than it may grant
 * alone: the call waits for the loan system owner's approval (custom-5a1e00000001), with the deadline
 * extension judge's recommendation beside it. Approve moves the deadline by the days asked, an edit
 * by the days the owner sets; deny, taking the case off the system, or a timeout leaves it. An
 * approval for the pattern settles later calls for the same number of days at once.
 */
@Component(id = "deadline-extension")
public class DeadlineExtensionWorkflow extends Workflow<DeadlineExtensionWorkflow.Request> {

  public static final String RESUMER = "deadline-extension";
  public static final String CONTROL = "custom-5a1e00000001";
  /** custom-5a1e00000001 has 30 minutes, and 30 more at the head of credit when escalated. */
  static final Duration BACKSTOP = Duration.ofHours(2);

  public record Start(String applicationId, int days, String reason, String sessionId) {}

  /** {@code status}: AWAITING_APPROVAL, APPLIED, REFUSED or NO_ANSWER. */
  public record Request(String id, String applicationId, int days, String reason, String sessionId, String status,
      String eventId, int daysApplied, Decision decision) {}

  private final Human human;
  private final ComponentClient client;
  private final String id;

  public DeadlineExtensionWorkflow(Human human, ComponentClient client, WorkflowContext context) {
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
    return effects().updateState(new Request(id, start.applicationId(), start.days(), start.reason(), start.sessionId(),
            "ASKING", null, 0, null))
        .transitionTo(DeadlineExtensionWorkflow::askApproval).thenReply(Done.done());
  }

  private StepEffect askApproval() {
    var r = currentState();
    var judge = ApplicantSupport.judge(r.days(), r.reason());
    var raised = human.raise(Patterns.actionApproval("Extend the documents deadline of application " + r.applicationId()
            + " by " + r.days() + " days?")
        .control(CONTROL)
        .subject(Subject.action("Extend the documents deadline of " + r.applicationId() + " by " + r.days() + " days",
                ApplicantSupport.TOOL, Subject.fields("applicationReference", r.applicationId(), "days", r.days(),
                    "reason", r.reason()))
            .with("authority", Subject.fields("missing", List.of("days > " + ApplicantSupport.DAYS_ALONE),
                "required", Subject.fields("role", "loan-system-owner")))
            .with("recentSteps", List.of(Subject.fields("at", Instant.now().toString(), "step", "The applicant asked: "
                + r.reason())))
            .withAgent(ApplicantSupport.AGENT).withSession(r.sessionId()).withWorkflow(id).withRef(r.applicationId()))
        .check("The applicant's reason explains the days asked for.", "subject")
        .trigger(new Trigger("tool-policy", ApplicantSupport.TOOL + ".days > " + ApplicantSupport.DAYS_ALONE,
            "The assistant asked for " + r.days() + " days; it may grant " + ApplicantSupport.DAYS_ALONE + " alone.",
            List.of(new Trigger.Value("days", r.days(), ApplicantSupport.DAYS_ALONE)),
            List.of(new Trigger.Condition("days > " + ApplicantSupport.DAYS_ALONE, true))))
        .proposedBy("agent:" + ApplicantSupport.AGENT)
        .matchKey(ApplicantSupport.TOOL + ":" + r.days())
        .aiRecommendation(ApplicantSupport.JUDGE, Action.valueOf(judge.action()), judge.score(), judge.rationale())
        .effect(Action.APPROVE, "The deadline moves by " + r.days() + " days.")
        .effect(Action.EDIT, "The deadline moves by the days you set.")
        .effect(Action.DENY, "The deadline stays.")
        .effect(Action.DECLINE_SYSTEM, "A member of staff takes the request over; the deadline stays.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    record(new LoanApplication.Extension(id, r.days(), "AWAITING_APPROVAL", raised.eventId(), 0, ""));
    return stepEffects().updateState(with(r, "AWAITING_APPROVAL", raised.eventId(), 0, null))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(DeadlineExtensionWorkflow::noAnswer));
  }

  public Effect<Done> decided(Decision decision) {
    var r = currentState();
    if (r == null || !r.status().equals("AWAITING_APPROVAL") || !decision.eventId().equals(r.eventId())) {
      return effects().reply(Done.done());
    }
    int days = decision.action() == Action.APPROVE ? r.days() : decision.action() == Action.EDIT ? editedDays(decision, r.days()) : 0;
    return effects().updateState(with(r, days > 0 ? "APPLIED" : "REFUSED", r.eventId(), days, decision))
        .transitionTo(DeadlineExtensionWorkflow::apply).thenReply(Done.done());
  }

  private StepEffect apply() {
    var r = currentState();
    var d = r.decision();
    var note = d.unanswered() ? "no decision: " + d.slaOutcome() : d.rationale();
    record(new LoanApplication.Extension(id, r.days(), r.status(), r.eventId(), r.daysApplied(), note));
    return stepEffects().thenEnd();
  }

  public Effect<Done> noAnswer() {
    var r = currentState();
    return effects().updateState(with(r, "NO_ANSWER", r.eventId(), 0, null)).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Request> get() {
    if (currentState() == null) return effects().error("no such request");
    return effects().reply(currentState());
  }

  private void record(LoanApplication.Extension extension) {
    client.forKeyValueEntity(currentState().applicationId()).method(ApplicationEntity::extension).invoke(extension);
  }

  private static Request with(Request r, String status, String eventId, int daysApplied, Decision decision) {
    return new Request(r.id(), r.applicationId(), r.days(), r.reason(), r.sessionId(), status, eventId, daysApplied, decision);
  }

  /** EDIT sets the days through the patch {@code [{op: replace, path: /arguments/days, value}]}. */
  @SuppressWarnings("unchecked")
  private static int editedDays(Decision d, int days) {
    if (!(d.edits().get("patch") instanceof List<?> patch)) return days;
    for (var op : patch) {
      var change = (Map<String, Object>) op;
      if ("/arguments/days".equals(change.get("path")) && change.get("value") instanceof Number value) return value.intValue();
    }
    return days;
  }
}
