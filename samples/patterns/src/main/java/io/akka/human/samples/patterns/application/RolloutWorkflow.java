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
 * A model rollout that two model-risk owners, neither its proposer nor its builder, must authorise
 * (pt-0003, change authorization). A reviewer may send it back; the proposer resubmits through
 * {@code POST /oversight/events/{id}/resubmit} and the review starts again.
 */
@Component(id = "rollout")
public class RolloutWorkflow extends Workflow<RolloutWorkflow.Rollout> {

  public static final String RESUMER = "rollout";
  /** pt-0003 has a day, then a day at head-of-operations. */
  static final Duration BACKSTOP = Duration.ofDays(3);

  public record Start(String model, String from, String to, String proposedBy, String builtBy, double trafficShare) {}

  /** {@code status}: AWAITING_AUTHORIZATION, ROLLED_OUT, REJECTED or NO_ANSWER. */
  public record Rollout(String id, String model, String from, String to, String proposedBy, String builtBy,
      double trafficShare, String status, String eventId, String note) {

    Rollout with(String nextStatus, String nextNote) {
      return new Rollout(id, model, from, to, proposedBy, builtBy, trafficShare, nextStatus, eventId, nextNote);
    }
  }

  private final Human human;
  private final String id;

  public RolloutWorkflow(Human human, WorkflowContext context) {
    this.human = human;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(20)).build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    return effects().updateState(new Rollout(id, start.model(), start.from(), start.to(), start.proposedBy(),
            start.builtBy(), start.trafficShare(), "PROPOSED", null, ""))
        .transitionTo(RolloutWorkflow::askAuthorization).thenReply(Done.done());
  }

  private StepEffect askAuthorization() {
    var r = currentState();
    var raised = human.raise(Patterns.changeAuthorization(
            "Roll out " + r.to() + " to " + Math.round(r.trafficShare() * 100) + "% of " + r.model() + " traffic?")
        .control("pt-0003")
        .subject(Subject.change("Roll out " + r.to() + " for " + r.model(), "model-registry", "rollout", id,
                Map.of("model", r.from()), Map.of("model", r.to()))
            .with("proposedBy", r.proposedBy())
            .with("builtBy", r.builtBy())
            .with("labels", Map.of("model", "Model"))
            .with("evidence", List.of(Map.of("kind", "offline-eval", "ref", "ev-" + id, "summary", "quality 0.91 to 0.90")))
            .with("blastRadius", Map.of("useCases", List.of(r.model()), "trafficShare", r.trafficShare()))
            .with("stage", "awaiting-approval")
            .withWorkflow(id).withRef(id))
        .check("The offline evaluation covers the use cases in the blast radius.", "evidence")
        .trigger(Trigger.rule("change-request", "rollout", r.proposedBy() + " proposed the rollout."))
        .proposedBy(r.proposedBy())
        .builtBy(r.builtBy())
        .dedupeKey("rollout-" + id)
        .effect(Action.APPROVE, "The rollout starts.")
        .effect(Action.SEND_BACK, "The proposer revises the rollout.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    return stepEffects()
        .updateState(new Rollout(r.id(), r.model(), r.from(), r.to(), r.proposedBy(), r.builtBy(), r.trafficShare(),
            "AWAITING_AUTHORIZATION", raised.eventId(), ""))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(RolloutWorkflow::noAnswer));
  }

  public Effect<Done> decided(Decision decision) {
    var r = currentState();
    if (r == null || !r.status().equals("AWAITING_AUTHORIZATION")) return effects().reply(Done.done());
    var note = decision.unanswered() ? "no decision: " + decision.slaOutcome()
        : decision.action() + " by " + String.join(" and ", decision.deciders());
    return effects().updateState(r.with(decision.proceeds() ? "ROLLED_OUT" : "REJECTED", note)).end().thenReply(Done.done());
  }

  public Effect<Done> noAnswer() {
    return effects().updateState(currentState().with("NO_ANSWER", "the backstop timeout passed")).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Rollout> get() {
    if (currentState() == null) return effects().error("no such rollout");
    return effects().reply(currentState());
  }
}
