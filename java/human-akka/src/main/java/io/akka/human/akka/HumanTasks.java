package io.akka.human.akka;

import akka.javasdk.agent.task.Task;
import io.akka.human.api.Decision;

/**
 * The task an autonomous agent's work waits on for a person. A service creates it unassigned, with
 * the tasks that need the decision depending on it, and raises the event with
 * {@code Resume.task(taskId)}. When the event settles, the library assigns the task to the decider and
 * completes it with the {@link Decision} if the subject proceeds, and fails it otherwise, which
 * cancels its dependents.
 */
public final class HumanTasks {

  public static final Task<Decision> DECISION = Task.name("Human decision")
      .description("A person decides an oversight event; the result is their decision.")
      .resultConformsTo(Decision.class);

  private HumanTasks() {}
}
