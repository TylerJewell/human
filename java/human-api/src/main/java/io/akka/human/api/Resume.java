package io.akka.human.api;

/**
 * Who hears the final outcome, once.
 *
 * <ul>
 *   <li>{@code workflow}: the service's {@link io.akka.human.api.spi.Resumer} registered under
 *       {@code target} is called with the workflow id and the decision.
 *   <li>{@code task}: an autonomous agent's task that no agent is assigned to. On a decision that
 *       proceeds the library assigns it to the decider and completes it; otherwise it fails it, and the
 *       SDK cancels the tasks that depend on it.
 *   <li>{@code handler}: the service's {@link io.akka.human.api.spi.ApplyHandler} registered under
 *       {@code target} carries the decision out.
 *   <li>{@code none}: only the control's apply handler, if any.
 * </ul>
 */
public record Resume(String kind, String target, String id) {

  public static Resume workflow(String target, String workflowId) {
    return new Resume("workflow", target, workflowId);
  }

  public static Resume task(String taskId) {
    return new Resume("task", "", taskId);
  }

  public static Resume handler(String target, String id) {
    return new Resume("handler", target, id);
  }

  public static Resume none() {
    return new Resume("none", "", "");
  }
}
