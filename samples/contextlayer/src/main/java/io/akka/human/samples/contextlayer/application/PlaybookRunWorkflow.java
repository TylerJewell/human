package io.akka.human.samples.contextlayer.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.human.akka.HumanClient;
import io.akka.human.api.Patterns;
import io.akka.human.api.Resume;
import io.akka.human.api.Subject;
import io.akka.human.api.Trigger;
import io.akka.human.samples.contextlayer.ContextLayerSetup;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One run of a playbook's steps. A step is retried twice; a step that still fails is handed to an
 * operator (custom-cl0045) and the run goes on without waiting for them. The stand-in steps fail when
 * the run names them in {@code failing}.
 */
@Component(id = "context-playbook-run")
public class PlaybookRunWorkflow extends Workflow<PlaybookRunWorkflow.Run> {

  public record Start(String assistantId, List<String> steps, List<String> failing, String startedBy) {}

  /** {@code status}: RUNNING, DONE or HALTED; {@code handOffs} the events raised for failed steps. */
  public record Run(String id, String assistantId, List<String> steps, List<String> failing, int next, String status,
      List<String> log, List<String> handOffs) {

    Run after(String line, String handOff) {
      var lines = new ArrayList<>(log);
      lines.add(line);
      var events = new ArrayList<>(handOffs);
      if (handOff != null) events.add(handOff);
      return new Run(id, assistantId, steps, failing, next + 1, status, lines, events);
    }

    Run ended(String nextStatus, String line) {
      var lines = new ArrayList<>(log);
      lines.add(line);
      return new Run(id, assistantId, steps, failing, next, nextStatus, lines, handOffs);
    }
  }

  private final HumanClient human;
  private final String id;

  public PlaybookRunWorkflow(HumanClient human, WorkflowContext context) {
    this.human = human;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(Duration.ofSeconds(20))
        .stepRecovery(PlaybookRunWorkflow::step, RecoverStrategy.maxRetries(2).failoverTo(PlaybookRunWorkflow::handOff))
        .build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    var run = new Run(id, start.assistantId(), start.steps(), start.failing() == null ? List.of() : start.failing(), 0,
        "RUNNING", List.of(), List.of());
    return effects().updateState(run).transitionTo(PlaybookRunWorkflow::step).thenReply(Done.done());
  }

  private StepEffect step() {
    var run = currentState();
    if (run.next() >= run.steps().size()) return stepEffects().updateState(run.ended("DONE", "finished")).thenEnd();
    if (human.halted(run.assistantId()).toCompletableFuture().join()
        || human.halted(ContextLayerSetup.ALL_PLAYBOOKS).toCompletableFuture().join()) {
      return stepEffects().updateState(run.ended("HALTED", "stopped: the playbook is halted")).thenEnd();
    }
    var name = run.steps().get(run.next());
    if (run.failing().contains(name)) throw new IllegalStateException("step " + name + " failed");
    return stepEffects().updateState(run.after(name + " done", null)).thenTransitionTo(PlaybookRunWorkflow::step);
  }

  /** The step failed after its retries: an operator is told, and the run moves on. */
  private StepEffect handOff() {
    var run = currentState();
    var name = run.steps().get(run.next());
    var raised = human.raise(Patterns.triage("Step " + name + " of playbook " + run.assistantId()
            + " failed after its retries. Acknowledge it?")
        .control(ContextLayerSetup.FAILED_STEP)
        .subject(Subject.action("Failed step " + name + " in run " + id, name,
                Map.of("run", id, "playbook", run.assistantId(), "attempts", 3))
            .withAgent(run.assistantId()).withWorkflow(id).withRef(id + "/" + name))
        .trigger(Trigger.rule("workflow", "step.failed after retries", "The step failed three times."))
        .dedupeKey("failed-step-" + id + "-" + name)
        .resumes(Resume.handler(ContextLayerSetup.FAILED_STEP_HANDLER, id + "/" + name))
        .build()).toCompletableFuture().join();
    return stepEffects().updateState(run.after(name + " failed; handed to an operator", raised.eventId()))
        .thenTransitionTo(PlaybookRunWorkflow::step);
  }

  public ReadOnlyEffect<Run> get() {
    if (currentState() == null) return effects().error("no such run");
    return effects().reply(currentState());
  }
}
