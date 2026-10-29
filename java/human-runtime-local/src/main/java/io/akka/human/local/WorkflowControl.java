package io.akka.human.local;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import io.akka.human.api.Reviewer;
import io.akka.human.api.spi.HumanClock;
import java.util.List;

/**
 * Suspends, resumes and terminates a workflow through the SDK's workflow client, and records who did
 * it in {@link WorkflowInterventionsEntity}. The reason passed to the SDK names the person too, so the
 * workflow's own record shows it.
 */
public final class WorkflowControl {

  private final ComponentClient client;
  private final HumanClock clock;

  public WorkflowControl(ComponentClient client, HumanClock clock) {
    this.client = client;
    this.clock = clock;
  }

  public void suspend(Class<? extends Workflow<?>> type, String workflowId, Reviewer by, String reason) {
    client.forWorkflow(workflowId).suspend(type, reason + " (" + by.subject() + ")");
    record(workflowId, "suspend", by, reason);
  }

  public void resume(Class<? extends Workflow<?>> type, String workflowId, Reviewer by, String reason) {
    client.forWorkflow(workflowId).resume(type);
    record(workflowId, "resume", by, reason);
  }

  public void terminate(Class<? extends Workflow<?>> type, String workflowId, Reviewer by, String reason) {
    client.forWorkflow(workflowId).terminate(type, reason + " (" + by.subject() + ")");
    record(workflowId, "terminate", by, reason);
  }

  public List<WorkflowInterventionsEntity.Intervention> history(String workflowId) {
    return client.forEventSourcedEntity(workflowId).method(WorkflowInterventionsEntity::history).invoke().interventions();
  }

  private void record(String workflowId, String kind, Reviewer by, String reason) {
    client.forEventSourcedEntity(workflowId).method(WorkflowInterventionsEntity::record)
        .invoke(new WorkflowInterventionsEntity.Intervention(kind, by.subject(), reason, clock.now()));
  }
}
