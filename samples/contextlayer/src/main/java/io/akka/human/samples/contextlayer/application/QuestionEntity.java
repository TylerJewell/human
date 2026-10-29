package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A question asked in words, drafted into a query and run only by a person (custom-cl0043): the model
 * never runs it. The draft here is deterministic, standing in for the drafting model, and running it
 * answers fixed rows, standing in for the graph.
 *
 * <p>{@code status}: DRAFTED, RAN or NOT_RUN.
 */
@Component(id = "context-question")
public class QuestionEntity extends KeyValueEntity<QuestionEntity.Question> {

  public record Question(String id, String text, String draft, String askedBy, String status, String ranBy,
      List<Map<String, String>> rows, String eventId) {}

  public record Ask(String text, String askedBy, String eventId) {}

  public record Ran(boolean run, String by) {}

  public Effect<Question> ask(Ask ask) {
    if (currentState() != null) return effects().reply(currentState());
    var q = new Question(commandContext().entityId(), ask.text(), draft(ask.text()), ask.askedBy(), "DRAFTED", null,
        List.of(), ask.eventId());
    return effects().updateState(q).thenReply(q);
  }

  /** The person's decision: run the draft, recording who ran it, or leave it unrun. */
  public Effect<Question> ran(Ran ran) {
    var q = currentState();
    if (q == null) return effects().error("no such question");
    if (!q.status().equals("DRAFTED")) return effects().reply(q);
    var next = ran.run()
        ? new Question(q.id(), q.text(), q.draft(), q.askedBy(), "RAN", ran.by(), rows(q.draft()), q.eventId())
        : new Question(q.id(), q.text(), q.draft(), q.askedBy(), "NOT_RUN", ran.by(), List.of(), q.eventId());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Question> get() {
    if (currentState() == null) return effects().error("no such question");
    return effects().reply(currentState());
  }

  /** The read-only query a question's words are drafted into. */
  public static String draft(String text) {
    var words = text.toLowerCase(Locale.ROOT);
    var type = words.contains("claim") ? "Claim" : words.contains("order") ? "Order" : "Customer";
    var filter = words.contains("open") ? " WHERE n.status = 'OPEN'" : words.contains("eu") ? " WHERE n.region = 'EU'" : "";
    return "MATCH (n:" + type + ")" + filter + " RETURN n.id, n.name LIMIT 25";
  }

  private static List<Map<String, String>> rows(String draft) {
    var type = draft.substring(draft.indexOf(':') + 1, draft.indexOf(')'));
    return List.of(Map.of("n.id", type.toLowerCase(Locale.ROOT) + "-1", "n.name", type + " one"),
        Map.of("n.id", type.toLowerCase(Locale.ROOT) + "-2", "n.name", type + " two"));
  }
}
