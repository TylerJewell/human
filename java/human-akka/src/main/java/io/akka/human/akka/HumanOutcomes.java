package io.akka.human.akka;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.human.api.Action;
import io.akka.human.api.Decision;
import io.akka.human.api.spi.HumanPlugins;
import io.akka.human.engine.HaltState;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.HumanEventEvent;
import io.akka.human.engine.Proofs;
import io.akka.human.engine.StandingDecision;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries each final outcome to whoever waits, once, and records the proof.
 *
 * <p>When an event closes, the outcome goes to its resume target: the service's {@code Resumer} for a
 * workflow, the autonomous-agent task for a task (completed when the subject proceeds, failed
 * otherwise), the {@code ApplyHandler} for a handler, or the handler registered for the pattern or
 * subject kind when nothing waits. Halts take effect, standing decisions are created or used, and the
 * event is marked applied with what was done, or with the error. A redelivered close finds the event
 * applied and does nothing; a failure before the event is marked is retried, so whoever waits must take
 * a repeated outcome without harm.
 *
 * <p>Once the event is marked, the proof records go to the {@code ProofLedger} plug-in, carrying the
 * final verdict. Record ids are derived from the event, so a redelivery appends nothing new.
 */
@Component(id = "human-outcomes")
@Consume.FromEventSourcedEntity(HumanEventEntity.class)
public class HumanOutcomes extends Consumer {

  private static final Logger log = LoggerFactory.getLogger(HumanOutcomes.class);

  private final ComponentClient client;
  private final HumanLibrary library;

  public HumanOutcomes(ComponentClient client, HumanPlugins plugins) {
    this.client = client;
    this.library = HumanLibrary.of(plugins);
  }

  public Effect onEvent(HumanEventEvent event) {
    var id = messageContext().eventSubject().orElseThrow();
    return switch (event) {
      case HumanEventEvent.Decided e -> deliver(id);
      case HumanEventEvent.StandingApplied e -> deliver(id);
      case HumanEventEvent.Closed e -> deliver(id);
      case HumanEventEvent.Applied e -> record(id);
      case HumanEventEvent.ApplyFailed e -> record(id);
      default -> effects().ignore();
    };
  }

  private Effect deliver(String id) {
    var e = event(id);
    if (e == null || e.pending() || e.outcome() == null || e.outcome().delivered()) return effects().ignore();
    var entity = client.forEventSourcedEntity(id);
    try {
      var note = carryOut(e);
      entity.method(HumanEventEntity::markApplied).invoke(new HumanEventEntity.Report(note, library.now()));
    } catch (RuntimeException ex) {
      var cause = ex.getCause() != null ? ex.getCause() : ex;
      log.warn("The outcome of {} could not be applied: {}", id, cause.toString());
      entity.method(HumanEventEntity::markApplyFailed)
          .invoke(new HumanEventEntity.Report(String.valueOf(cause.getMessage()), library.now()));
    }
    return effects().done();
  }

  /** Does what the outcome asks and answers a line saying what was done. */
  private String carryOut(HumanEvent e) {
    var plugins = library.plugins();
    var notes = new ArrayList<String>();
    var decision = HumanClient.decision(e);
    if (e.settings().haltRestart()) notes.add(haltEffect(e));
    var o = e.outcome();
    if (o.standingDecisionId() != null) {
      var standing = client.forKeyValueEntity(StandingDecisionsEntity.ID);
      if (o.byStanding()) {
        standing.method(StandingDecisionsEntity::use).invoke(new StandingDecisionsEntity.Use(o.standingDecisionId(), e.id()));
      } else {
        standing.method(StandingDecisionsEntity::add).invoke(StandingDecision.from(e));
        notes.add("standing decision " + o.standingDecisionId() + " created");
      }
    }
    var resume = e.resumes();
    switch (resume.kind()) {
      case "workflow" -> {
        var resumer = plugins.resumers().get(resume.target());
        if (resumer == null) throw new IllegalStateException("no resumer is registered for " + resume.target());
        resumer.resume(resume.id(), decision).toCompletableFuture().join();
        notes.add("resumed " + resume.target() + " " + resume.id());
      }
      case "task" -> notes.add(settleTask(resume.id(), decision));
      case "handler" -> {
        var handler = plugins.handlers().get(resume.target());
        if (handler == null) throw new IllegalStateException("no apply handler is registered for " + resume.target());
        notes.add(handler.apply(resume.id(), e.subject(), decision).toCompletableFuture().join());
      }
      default -> {
        var handler = plugins.handlers().get(e.settings().pattern().id());
        if (handler == null) handler = plugins.handlers().get(e.subject().kind().id());
        if (handler != null) notes.add(handler.apply(e.id(), e.subject(), decision).toCompletableFuture().join());
      }
    }
    notes.removeIf(n -> n == null || n.isBlank());
    return notes.isEmpty() ? "recorded" : String.join("; ", notes);
  }

  /**
   * The autonomous-agent task bridge: the decider is assigned the task, which completes with the
   * decision when the subject proceeds and fails otherwise, cancelling the tasks that depend on it.
   */
  private String settleTask(String taskId, Decision decision) {
    var task = client.forTask(taskId);
    task.assign(decision.deciders().isEmpty() ? "system" : decision.deciders().getLast());
    if (decision.proceeds()) {
      task.complete(HumanTasks.DECISION, decision);
      return "task " + taskId + " completed";
    }
    task.fail(decision.unanswered() ? "no decision: " + decision.slaOutcome() : decision.action() + ": " + decision.rationale());
    return "task " + taskId + " failed";
  }

  private String haltEffect(HumanEvent e) {
    var settings = e.settings();
    var target = e.haltTarget();
    var scope = library.haltScope(target, settings);
    var halt = client.forKeyValueEntity(target);
    var action = e.outcome().action();
    if (!e.lift()) {
      if (action != Action.HALT) return "";
      halt.method(HaltEntity::exercise).invoke(new HaltEntity.Exercise(HaltState.notHalted(settings, target),
          e.deciders().isEmpty() ? HumanEvent.SYSTEM : e.deciders().getFirst(), e.outcome().rationale(), e.id(),
          Proofs.haltRecordId(e.id()), e.closedAt()));
      scope.halted(target, e.outcome().rationale()).toCompletableFuture().join();
      return target + " is halted";
    }
    if (action == Action.APPROVE) {
      halt.method(HaltEntity::lift).invoke(e.id());
      scope.lifted(target).toCompletableFuture().join();
      return "the halt on " + target + " was lifted";
    }
    halt.method(HaltEntity::liftClosed).invoke(e.id());
    return target + " stays halted";
  }

  private Effect record(String id) {
    var ledger = library.plugins().ledger();
    if (ledger.isEmpty()) return effects().ignore();
    var e = event(id);
    if (e == null || e.outcome() == null) return effects().ignore();
    var at = e.outcome().appliedAt() != null ? e.outcome().appliedAt() : library.now();
    var decision = Proofs.decision(e, at);
    if (decision != null) ledger.get().append(decision).toCompletableFuture().join();
    var action = e.outcome().action();
    if (e.settings().haltRestart() && !e.lift() && action == Action.HALT) {
      ledger.get().append(Proofs.haltExercised(e, e.closedAt())).toCompletableFuture().join();
    }
    if (e.settings().haltRestart() && e.lift() && action == Action.APPROVE) {
      var haltEvent = String.valueOf(e.subject().detail().get("haltEventId"));
      ledger.get().append(Proofs.haltLifted(e, Proofs.haltRecordId(haltEvent), at)).toCompletableFuture().join();
    }
    return effects().done();
  }

  private HumanEvent event(String id) {
    return client.forEventSourcedEntity(id).method(HumanEventEntity::get).invoke().event();
  }
}
