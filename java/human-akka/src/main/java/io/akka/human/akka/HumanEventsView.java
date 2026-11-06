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
import io.akka.human.engine.Verdicts;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The queue: one row per event, holding the whole event as JSON so lists, summaries and quality
 * figures read the view instead of every entity. The row applies each journal event with the same
 * code the entity uses, so it holds what the entity holds.
 *
 * <p>A row also carries the columns the queries filter and order on. A view query cannot take an
 * optional condition, so the equality filters (pattern, mode, control and the rest) are one array,
 * {@code keys}, and a query names a fixed number of keys the row must hold; an unused slot asks for
 * {@link #ANY}, which every row holds. The runtime cannot order the rows a query selects through
 * that array, so the queries answer the matching rows unordered and the caller orders and pages them.
 */
@Component(id = "human-events")
public class HumanEventsView extends View {

  /** The key every row holds: a slot that filters nothing asks for it. */
  public static final String ANY = "*";

  /** Later than any instant a window can start at: a pending event is always inside a window. */
  static final Instant OPEN = Instant.parse("9999-12-31T00:00:00Z");

  /**
   * {@code document} is the event as the entity holds it, serialised.
   *
   * @param keys {@link #ANY}, then {@code pending:}, {@code state:}, {@code pattern:}, {@code mode:},
   *     {@code flavor:}, {@code kind:}, {@code control:}, {@code queue:}, {@code shadow:}, {@code halt:} and
   *     {@code event:} each followed by the event's value
   * @param relevantFrom {@link #OPEN} while pending, else when it closed: a windowed read wants the
   *     rows from its window's start on
   * @param verdictAt when the event's verdict was reached; empty while it has none
   */
  public record Row(String id, boolean pending, String document, List<String> keys, Instant detectedAt,
      Instant relevantFrom, Optional<Instant> verdictAt) {

    public HumanEvent event() {
      try {
        return JsonSupport.getObjectMapper().readValue(document, HumanEvent.class);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      }
    }

    static Row of(HumanEvent e) {
      try {
        var verdict = Verdicts.of(e);
        return new Row(e.id(), e.pending(), JsonSupport.getObjectMapper().writeValueAsString(e), List.of(ANY,
            "pending:" + e.pending(), "state:" + e.state().name(), "pattern:" + e.settings().pattern().id(),
            "mode:" + e.settings().mode(), "flavor:" + e.settings().flavor(), "kind:" + e.subject().kind().id(),
            "control:" + e.settings().control().rowId(), "queue:" + e.queue(), "shadow:" + e.shadow(),
            "event:" + e.id(), "halt:" + e.settings().haltRestart()), e.detectedAt(),
            e.pending() ? OPEN : e.closedAt() != null ? e.closedAt() : e.detectedAt(),
            Optional.ofNullable(verdict).map(Verdicts.Verdict::at));
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

  /** The keys a query asks of a row, one per slot, and the lower bounds on its times. */
  public record Filter(String k1, String k2, String k3, String k4, String k5, String k6, String k7, String k8,
      Instant detectedSince, Instant relevantSince) {

    /** The number of keys a query can name; the slots left over ask for {@link HumanEventsView#ANY}. */
    public static final int SLOTS = 8;

    public static Filter of(List<String> keys, Instant detectedSince, Instant relevantSince) {
      if (keys.size() > SLOTS) throw new IllegalArgumentException("a query takes at most " + SLOTS + " keys");
      var k = new String[SLOTS];
      for (int i = 0; i < SLOTS; i++) k[i] = i < keys.size() ? keys.get(i) : ANY;
      return new Filter(k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], detectedSince, relevantSince);
    }
  }

  /** One key, and the verdict times a row may have, both ends inclusive. */
  public record VerdictFilter(String k1, Instant since, Instant until) {}

  private static final String MATCHING = """
      FROM human_events
      WHERE :k1 = ANY(keys) AND :k2 = ANY(keys) AND :k3 = ANY(keys) AND :k4 = ANY(keys)
        AND :k5 = ANY(keys) AND :k6 = ANY(keys) AND :k7 = ANY(keys) AND :k8 = ANY(keys)
        AND detectedAt >= :detectedSince AND relevantFrom >= :relevantSince
      """;

  @Query("SELECT * FROM human_events")
  public QueryStreamEffect<Row> all() {
    return queryStreamResult();
  }

  @Query("SELECT * FROM human_events WHERE pending = true")
  public QueryStreamEffect<Row> pending() {
    return queryStreamResult();
  }

  /** Every matching row, in no order. */
  @Query("SELECT * " + MATCHING)
  public QueryStreamEffect<Row> matching(Filter filter) {
    return queryStreamResult();
  }

  /** Every row holding the key whose verdict falls in the range, in no order. */
  @Query("SELECT * FROM human_events WHERE :k1 = ANY(keys) AND verdictAt >= :since AND verdictAt <= :until")
  public QueryStreamEffect<Row> verdicts(VerdictFilter filter) {
    return queryStreamResult();
  }
}
