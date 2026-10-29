package io.akka.human.samples.patterns.application;

import akka.javasdk.agent.autonomous.AgentDefinition;
import akka.javasdk.agent.autonomous.AutonomousAgent;
import akka.javasdk.agent.autonomous.capability.TaskAcceptance;
import akka.javasdk.agent.task.Task;
import akka.javasdk.annotations.Component;

/**
 * An autonomous agent whose work waits on a person through a task no agent is assigned to: the
 * approval task is created unassigned and the library completes or fails it with the decision. The
 * sample never assigns the agent work of its own; it is here so the service runs autonomous-agent
 * tasks at all.
 */
@Component(id = "payments-agent", description = "Pays approved supplier invoices and reports what was paid.")
public class PaymentsAgent extends AutonomousAgent {

  public static final Task<String> PAY = Task.name("Pay an invoice").description("Pay one approved supplier invoice.");

  @Override
  public AgentDefinition definition() {
    return define().capability(TaskAcceptance.of(PAY).maxIterationsPerTask(3));
  }
}
