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
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A conversation in which the user asks for a person. The agent stops and the person on duty takes it
 * over, returns it to the agent, or closes it (pt-00011, hand-off).
 */
@Component(id = "handoff")
public class HandOffWorkflow extends Workflow<HandOffWorkflow.Conversation> {

  public static final String RESUMER = "handoff";
  /** pt-00011 has 5 minutes, then 5 more at head-of-operations. */
  static final Duration BACKSTOP = Duration.ofMinutes(20);

  /** {@code reason}: user-asked, crisis, step-failed or policy. */
  public record Start(String channel, String reason, List<String> userSaid) {}

  /** {@code status}: WAITING_FOR_PERSON, WITH_PERSON, WITH_AGENT, CLOSED or NO_ANSWER. */
  public record Conversation(String id, String channel, String reason, List<String> userSaid, String status,
      String eventId, String takenOverBy) {

    Conversation with(String nextStatus, String by) {
      return new Conversation(id, channel, reason, userSaid, nextStatus, eventId, by);
    }
  }

  private final Human human;
  private final String id;

  public HandOffWorkflow(Human human, WorkflowContext context) {
    this.human = human;
    this.id = context.workflowId();
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder().defaultStepTimeout(Duration.ofSeconds(20)).build();
  }

  public Effect<Done> start(Start start) {
    if (currentState() != null) return effects().reply(Done.done());
    return effects().updateState(new Conversation(id, start.channel(), start.reason(), start.userSaid(), "STARTED", null, null))
        .transitionTo(HandOffWorkflow::handOff).thenReply(Done.done());
  }

  private StepEffect handOff() {
    var c = currentState();
    var now = Instant.now().toString();
    var transcript = new java.util.ArrayList<Map<String, Object>>();
    for (var line : c.userSaid()) transcript.add(Map.of("at", now, "role", "user", "text", line));
    transcript.add(Map.of("at", now, "role", "agent", "text", "I will bring in a person now."));
    var raised = human.raise(Patterns.handOff("Take over this conversation?")
        .control("pt-00011")
        .subject(Subject.conversation("The user asked for a person on " + c.channel(), c.channel(), c.reason(),
                List.copyOf(transcript))
            .with("takenOverBy", null).withSession(id).withWorkflow(id).withRef(id))
        .trigger(Trigger.rule("workflow", "handoff." + c.reason(), "The conversation needs a person."))
        .dedupeKey("handoff-" + id)
        .effect(Action.DECLINE_SYSTEM, "You continue the conversation; the agent stops.")
        .effect(Action.REDIRECT, "The agent continues the conversation.")
        .resumes(Resume.workflow(RESUMER, id))
        .build()).toCompletableFuture().join();
    return stepEffects()
        .updateState(new Conversation(c.id(), c.channel(), c.reason(), c.userSaid(), "WAITING_FOR_PERSON", raised.eventId(), null))
        .thenPause(pauseSetting(BACKSTOP).timeoutHandler(HandOffWorkflow::noAnswer));
  }

  public Effect<Done> decided(Decision decision) {
    var c = currentState();
    if (c == null || !c.status().equals("WAITING_FOR_PERSON")) return effects().reply(Done.done());
    var by = decision.deciders().isEmpty() ? null : decision.deciders().getLast();
    var next = decision.action() == null ? "NO_ANSWER"
        : switch (decision.action()) {
          case DECLINE_SYSTEM -> "WITH_PERSON";
          case REDIRECT -> "WITH_AGENT";
          default -> "CLOSED";
        };
    return effects().updateState(c.with(next, by)).end().thenReply(Done.done());
  }

  public Effect<Done> noAnswer() {
    return effects().updateState(currentState().with("NO_ANSWER", null)).end().thenReply(Done.done());
  }

  public ReadOnlyEffect<Conversation> get() {
    if (currentState() == null) return effects().error("no such conversation");
    return effects().reply(currentState());
  }
}
