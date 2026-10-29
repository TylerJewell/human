package io.akka.human.local;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Who suspended, resumed or terminated a workflow, and why; the id is the workflow's. Stands in for
 * runtime request 11 (a person recorded on suspend, resume and terminate): the SDK's workflow client
 * persists a reason on suspend and terminate but no person, and nothing on resume.
 */
@Component(id = "human-workflow-interventions")
public class WorkflowInterventionsEntity
    extends EventSourcedEntity<WorkflowInterventionsEntity.History, WorkflowInterventionsEntity.InterventionEvent> {

  /** {@code kind} is suspend, resume or terminate. */
  public record Intervention(String kind, String by, String reason, Instant at) {}

  public record History(List<Intervention> interventions) {
    public History {
      interventions = interventions == null ? List.of() : List.copyOf(interventions);
    }
  }

  public sealed interface InterventionEvent {}

  @TypeName("workflow-intervened")
  public record Intervened(Intervention intervention) implements InterventionEvent {}

  @Override
  public History emptyState() {
    return new History(List.of());
  }

  public Effect<History> record(Intervention intervention) {
    return effects().persist(new Intervened(intervention)).thenReply(state -> state);
  }

  public ReadOnlyEffect<History> history() {
    return effects().reply(currentState());
  }

  @Override
  public History applyEvent(InterventionEvent event) {
    var next = new ArrayList<>(currentState().interventions());
    next.add(((Intervened) event).intervention());
    return new History(next);
  }
}
