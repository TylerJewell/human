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

/**
 * A declined applicant's contest (custom-27163ccc75f2). A senior underwriter reconsiders the decline;
 * the underwriter who declined, and anyone above them, may not. An overturn approves the application
 * (the decline stays in its history); an uphold sends the applicant a contest notice. The workflow id
 * is the application id.
 */
@Component(id = "contest")
public class ContestWorkflow extends Workflow<ContestWorkflow.Contest> {

  public static final String RESUMER = "contest";
  public static final String CONTROL = "custom-27163ccc75f2";
  /** custom-27163ccc75f2 has 14 days at contest review, then 7 at the head of credit. */
  static final Duration BACKSTOP = Duration.ofDays(22);

  public record Start(String statement) {}

  /**
   * @param status AWAITING_REVIEW, OVERTURNED, UPHELD, NO_DECISION or NO_ANSWER
   * @param notice the contest notice, when the decline is upheld
   */
  public record Contest(String applicationId, String statement, String status, String eventId, String notice,
      Decision decision) {}

  private final Human human;
  private final ComponentClient client;
  private final String id;

  public ContestWorkflow(Human human, ComponentClient client, WorkflowContext context) {
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
    return effects().updateState(new Contest(id, start.statement(), "FILED", null, null, null))
        .transitionTo(ContestWorkflow::askReview).thenReply(Done.done());
  }

  private StepEffect askReview() {
    var c = currentState();
    var app = client.forKeyValueEntity(id).method(ApplicationEntity::get).invoke();
    var declined = app.outcome();
    var original = Subject.fields("ref", declined.eventId(), "decision", "DENY",
        "summary", "Application " + id + " declined: " + String.join(", ", declined.reasons()),
        "decidedBy", declined.by(), "decidedAt", declined.at().toString());
    var raised = human.raise(Patterns.contestAppeal("Should the decline of application " + id + " stand?")
        .control(CONTROL)
        .subject(Subject.appeal("Contest of the decline of application " + id, original, c.statement())
            .with("appellant", Subject.fields("ref", "applicant:" + id, "name", app.applicant()))
            .with("right", "GDPR Art. 22(3): to contest the decision and obtain human intervention")
            .with("filedAt", Instant.now().toString())
            .withWorkflow(id).withRef(id))
        .trigger(new Trigger("person", null, c.statement(), List.of(), List.of()))
        .proposedBy("applicant:" + id)
        .firstReviewer(declined.by())
        .dedupeKey("contest-" + id)
        .effect(Action.APPROVE, "The decline stands; the applicant receives a contest notice.")
        .effect(Action.REVERSE, "The decline is overturned and the application approved.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    client.forKeyValueEntity(id).method(ApplicationEntity::note).invoke("Contest filed (" + raised.eventId() + ")");
    return stepEffects().updateState(new Contest(id, c.statement(), "AWAITING_REVIEW", raised.eventId(), null, null))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(ContestWorkflow::noAnswer));
  }

  public Effect<Done> decided(Decision decision) {
    var c = currentState();
    if (c == null || !c.status().equals("AWAITING_REVIEW") || !decision.eventId().equals(c.eventId())) {
      return effects().reply(Done.done());
    }
    return effects().updateState(new Contest(id, c.statement(), "DECIDED", c.eventId(), null, decision))
        .transitionTo(ContestWorkflow::apply).thenReply(Done.done());
  }

  private StepEffect apply() {
    var c = currentState();
    var d = c.decision();
    var entity = client.forKeyValueEntity(id);
    var by = d.deciders().isEmpty() ? "system" : d.deciders().getLast();
    String status;
    String notice = null;
    if (d.action() == Action.REVERSE) {
      entity.method(ApplicationEntity::decided).invoke(new LoanApplication.Outcome(LoanApplication.APPROVED, by,
          "Contest overturned the decline: " + d.rationale(), List.of(), d.eventId(), Instant.now()));
      status = "OVERTURNED";
    } else if (d.action() == Action.APPROVE) {
      notice = NoticeWriter.contestNotice(d.rationale());
      entity.method(ApplicationEntity::note).invoke("Contest upheld by " + by + "; contest notice issued");
      status = "UPHELD";
    } else {
      entity.method(ApplicationEntity::note).invoke("Contest closed without a decision: " + d.slaOutcome());
      status = "NO_DECISION";
    }
    return stepEffects().updateState(new Contest(id, c.statement(), status, c.eventId(), notice, d)).thenEnd();
  }

  public Effect<Done> noAnswer() {
    var c = currentState();
    return effects().updateState(new Contest(id, c.statement(), "NO_ANSWER", c.eventId(), null, null)).end()
        .thenReply(Done.done());
  }

  public ReadOnlyEffect<Contest> get() {
    if (currentState() == null) return effects().error("no contest for " + id);
    return effects().reply(currentState());
  }
}
