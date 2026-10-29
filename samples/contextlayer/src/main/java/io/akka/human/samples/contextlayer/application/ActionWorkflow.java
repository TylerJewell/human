package io.akka.human.samples.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.samples.contextlayer.ContextLayerSetup;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Takes one action a playbook asked for from request to outcome: decide by risk and stage, wait for a
 * person who did not request it when the risk is MEDIUM or HIGH (custom-cl0003), call the target once,
 * record the result. The call is never retried: a call that fails without saying whether it took
 * effect leaves the outcome unknown, for a reviewer to resolve (custom-cl0044). The id is the action
 * key, the same as the ledger entry's.
 */
@Component(id = "context-action-run")
public class ActionWorkflow extends Workflow<ActionWorkflow.Run> {

  public static final String RESUMER = "action-run";
  /** custom-cl0003 has 24 hours and 24 more at the admins; the library always answers before this. */
  static final Duration BACKSTOP = Duration.ofHours(50);

  /** {@code risk}: LOW, MEDIUM, HIGH or NEVER. */
  public record Start(String assistantId, String actionType, String payload, String risk, String requestedBy) {}

  public record Run(String key, String assistantId, String actionType, String payload, String risk, String requestedBy,
      String phase, String approvalEventId) {

    Run withPhase(String next) {
      return new Run(key, assistantId, actionType, payload, risk, requestedBy, next, approvalEventId);
    }
  }

  private final ComponentClient client;
  private final HumanClient human;
  private final String key;

  public ActionWorkflow(ComponentClient client, HumanClient human, WorkflowContext context) {
    this.client = client;
    this.human = human;
    this.key = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(Duration.ofSeconds(20))
        .stepRecovery(ActionWorkflow::execute, RecoverStrategy.maxRetries(0).failoverTo(ActionWorkflow::unknownOutcome))
        .build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    var run = new Run(key, start.assistantId(), start.actionType(), start.payload() == null ? "" : start.payload(),
        start.risk(), start.requestedBy(), "REQUESTED", null);
    ledger().method(ActionLedgerEntity::request).invoke(new ActionLedgerEntity.Action(key, run.assistantId(),
        run.actionType(), run.payload(), run.risk(), run.requestedBy(), "REQUESTED", "", null, null, null, null, null));
    return effects().updateState(run).transitionTo(ActionWorkflow::decide).thenReply(Done.done());
  }

  private StepEffect decide() {
    var run = currentState();
    var refusal = refusal(run);
    if (refusal != null) {
      record("DENIED", refusal, null, null);
      return stepEffects().updateState(run.withPhase("DENIED")).thenEnd();
    }
    if (run.risk().equals("LOW")) return stepEffects().updateState(run.withPhase("APPROVED")).thenTransitionTo(ActionWorkflow::execute);
    var raised = human.raise(Patterns.actionApproval("Should playbook " + run.assistantId() + " be allowed to run "
            + run.actionType() + "?")
        .control(ContextLayerSetup.ACTION_APPROVAL)
        .subject(Subject.action("Run " + run.actionType() + " for playbook " + run.assistantId(), run.actionType(),
                Map.of("payload", run.payload(), "risk", run.risk()))
            .withAgent(run.assistantId()).withWorkflow(key).withRef(key))
        .check("The action is what the playbook meant to do.", "subject")
        .check("The payload is within what the playbook may change.", "subject")
        .trigger(Trigger.rule("workflow", "risk=" + run.risk(), "A " + run.risk() + " risk action waits for a person."))
        .proposedBy(run.requestedBy())
        .dedupeKey("approve-" + key)
        .effect(Action.APPROVE, "The action is sent to the target system once.")
        .effect(Action.DENY, "The action is not sent.")
        .resumes(Resume.workflow(RESUMER, key))
        .build()).toCompletableFuture().join();
    record("AWAITING_APPROVAL", "waiting for an approver", null, raised.eventId());
    return stepEffects()
        .updateState(new Run(run.key(), run.assistantId(), run.actionType(), run.payload(), run.risk(), run.requestedBy(),
            "AWAITING_APPROVAL", raised.eventId()))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(ActionWorkflow::noAnswer));
  }

  /** The library's one call with the approval's outcome; a repeated call finds the run moved on. */
  public Effect<Done> decided(Decision decision) {
    var run = currentState();
    if (run == null || !run.phase().equals("AWAITING_APPROVAL") || !decision.eventId().equals(run.approvalEventId())) {
      return effects().reply(Done.done());
    }
    var by = decision.deciders().isEmpty() ? null : decision.deciders().getLast();
    if (!decision.proceeds()) {
      record("REJECTED", decision.unanswered() || decision.timedOut() ? "nobody approved it in time"
          : "rejected: " + decision.rationale(), by, null);
      return effects().updateState(run.withPhase("REJECTED")).end().thenReply(Done.done());
    }
    if (human.halted(run.assistantId()).toCompletableFuture().join() || human.halted(ContextLayerSetup.ALL_PLAYBOOKS)
        .toCompletableFuture().join()) {
      record("REJECTED", "approved, but not run: the playbook is halted", by, null);
      return effects().updateState(run.withPhase("REJECTED")).end().thenReply(Done.done());
    }
    record("APPROVED", "approved by " + by, by, null);
    return effects().updateState(run.withPhase("APPROVED")).transitionTo(ActionWorkflow::execute).thenReply(Done.done());
  }

  /**
   * Calls the target system once. The stand-in target fails when the payload says {@code fail}, and
   * does not answer when it says {@code timeout}, which leaves the outcome unknown.
   */
  private StepEffect execute() {
    var run = currentState();
    if (run.payload().contains("timeout")) throw new IllegalStateException("the target system did not answer");
    if (run.payload().contains("fail")) {
      record("FAILED", "the target system refused the call", null, null);
      return stepEffects().updateState(run.withPhase("FAILED")).thenEnd();
    }
    record("SUCCEEDED", "sent once", null, null);
    return stepEffects().updateState(run.withPhase("SUCCEEDED")).thenEnd();
  }

  /** Raises the question of whether the call took effect, for a reviewer who did not request it. */
  private StepEffect unknownOutcome() {
    var run = currentState();
    var raised = human.raise(Patterns.outcomeReconciliation("Did " + run.actionType() + " of playbook " + run.assistantId()
            + " take effect?")
        .control(ContextLayerSetup.UNKNOWN_OUTCOME)
        .subject(Subject.action("Unknown outcome: " + run.actionType() + " (" + key + ")", run.actionType(),
                Map.of("payload", run.payload()))
            .with("sentAt", Instant.now().toString())
            .with("requestedBy", run.requestedBy())
            .with("lastKnown", "The call was sent; the target system did not answer.")
            .withAgent(run.assistantId()).withWorkflow(key).withRef(key))
        .check("I looked at the target system itself, not at this service's records.", "subject")
        .trigger(Trigger.rule("workflow", "outcome-unknown", "The call did not complete, so whether it took effect is unknown."))
        .proposedBy(run.requestedBy())
        .dedupeKey("unknown-" + key)
        .effect(Action.APPROVE, "Recorded as having taken effect.")
        .effect(Action.DENY, "Recorded as not having taken effect.")
        .resumes(Resume.handler(ContextLayerSetup.UNKNOWN_OUTCOME_HANDLER, key))
        .build()).toCompletableFuture().join();
    record("OUTCOME_UNKNOWN", "the target system did not answer; a reviewer resolves it", null, null);
    ledger().method(ActionLedgerEntity::update)
        .invoke(new ActionLedgerEntity.Update(null, null, null, null, raised.eventId()));
    return stepEffects().updateState(run.withPhase("OUTCOME_UNKNOWN")).thenEnd();
  }

  public Effect<Done> noAnswer() {
    record("REJECTED", "the backstop timeout passed", null, null);
    return effects().updateState(currentState().withPhase("REJECTED")).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Run> get() {
    if (currentState() == null) return effects().error("no such action");
    return effects().reply(currentState());
  }

  /** Why the action may not run at all: halted, a draft playbook, or never allowed. */
  private String refusal(Run run) {
    if (run.risk().equals("NEVER")) return "this action is never run by a playbook";
    if (human.halted(run.assistantId()).toCompletableFuture().join()
        || human.halted(ContextLayerSetup.ALL_PLAYBOOKS).toCompletableFuture().join()) {
      return "the playbook is halted";
    }
    var stage = client.forKeyValueEntity(run.assistantId()).method(AssistantEntity::get).invoke().stage();
    if (stage.equals(AssistantEntity.DRAFT)) return "a DRAFT playbook runs no actions";
    return null;
  }

  private void record(String status, String detail, String decidedBy, String approvalEventId) {
    ledger().method(ActionLedgerEntity::update)
        .invoke(new ActionLedgerEntity.Update(status, detail, decidedBy, approvalEventId, null));
  }

  private akka.javasdk.client.KeyValueEntityClient ledger() {
    return client.forKeyValueEntity(key);
  }
}
