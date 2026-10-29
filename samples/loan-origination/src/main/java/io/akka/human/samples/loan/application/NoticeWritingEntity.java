package io.akka.human.samples.loan.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.human.samples.loan.domain.NoticeWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How adverse-action notices are written: the notice writer's model, changed only by an authorised
 * model change (custom-5a1e00000005), and the letter template per decline reason, changed only by an
 * authorised template review (0ff1ce0001). One instance, {@link #ID}.
 */
@Component(id = "notice-writing")
public class NoticeWritingEntity extends KeyValueEntity<NoticeWritingEntity.Writing> {

  public static final String ID = "notice-writer";

  /** @param eventId the event that authorised it; null for the first version */
  public record Template(String text, int version, String eventId) {}

  public record Writing(String model, String modelEventId, Map<String, Template> templates) {
    public String template(String reason) {
      var t = templates.get(reason);
      return t == null ? NoticeWriter.DEFAULT_TEMPLATE : t.text();
    }
  }

  public record AdoptModel(String model, String eventId) {}

  public record AdoptTemplate(String reason, String text, String eventId) {}

  @Override
  public Writing emptyState() {
    return new Writing(NoticeWriter.FIRST_MODEL, null, Map.of());
  }

  public Effect<Writing> adoptModel(AdoptModel command) {
    if (command.eventId().equals(currentState().modelEventId())) return effects().reply(currentState());
    var next = new Writing(command.model(), command.eventId(), currentState().templates());
    return effects().updateState(next).thenReply(next);
  }

  public Effect<Writing> adoptTemplate(AdoptTemplate command) {
    var current = currentState().templates().get(command.reason());
    if (current != null && command.eventId().equals(current.eventId())) return effects().reply(currentState());
    var templates = new LinkedHashMap<>(currentState().templates());
    templates.put(command.reason(), new Template(command.text(), current == null ? 2 : current.version() + 1,
        command.eventId()));
    var next = new Writing(currentState().model(), currentState().modelEventId(), Map.copyOf(templates));
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Writing> get() {
    return effects().reply(currentState());
  }
}
