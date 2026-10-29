package io.akka.human.akka;

import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.akka.human.engine.HumanEvent;
import io.akka.human.engine.HumanEventEvent;
import java.io.UncheckedIOException;

/**
 * The queue: one row per event, holding the whole event as JSON so lists, summaries and quality
 * figures read the view instead of every entity. The row applies each journal event with the same
 * code the entity uses, so it holds what the entity holds.
 */
@Component(id = "human-events")
public class HumanEventsView extends View {

  /** {@code document} is the event as the entity holds it, serialised. */
  public record Row(String id, boolean pending, String document) {

    public HumanEvent event() {
      try {
        return JsonSupport.getObjectMapper().readValue(document, HumanEvent.class);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      }
    }

    static Row of(HumanEvent e) {
      try {
        return new Row(e.id(), e.pending(),
            JsonSupport.getObjectMapper().writeValueAsString(e));
      } catch (JsonProcessingException ex) {
        throw new UncheckedIOException(ex);
      }
    }
  }

  @Consume.FromEventSourcedEntity(HumanEventEntity.class)
  public static class Updater extends TableUpdater<Row> {
    public Effect<Row> onEvent(HumanEventEvent event) {
      if (event instanceof HumanEventEvent.Raised raised) return effects().updateRow(Row.of(raised.event()));
      if (rowState() == null) return effects().ignore();
      return effects().updateRow(Row.of(rowState().event().apply(event)));
    }
  }

  @Query("SELECT * FROM human_events")
  public QueryStreamEffect<Row> all() {
    return queryStreamResult();
  }

  @Query("SELECT * FROM human_events WHERE pending = true")
  public QueryStreamEffect<Row> pending() {
    return queryStreamResult();
  }
}
