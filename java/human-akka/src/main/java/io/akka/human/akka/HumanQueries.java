package io.akka.human.akka;

import akka.javasdk.client.ComponentClient;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import io.akka.human.engine.HumanEvent;
import java.time.Instant;
import java.util.List;

/**
 * Reads the events from the queue view. The view follows the journal, so an event raised a moment ago
 * may not be listed yet; read one event by id from its entity when that matters.
 */
public final class HumanQueries {

  private HumanQueries() {}

  /** A page of rows and the count of every row the query matches. */
  public record Paged(List<HumanEventsView.Row> rows, long total) {}

  public static List<HumanEvent> all(ComponentClient client, Materializer materializer) {
    return client.forView().stream(HumanEventsView::all).source().runWith(Sink.seq(), materializer)
        .toCompletableFuture().join().stream().map(HumanEventsView.Row::event).toList();
  }

  public static List<HumanEvent> pending(ComponentClient client, Materializer materializer) {
    return client.forView().stream(HumanEventsView::pending).source().runWith(Sink.seq(), materializer)
        .toCompletableFuture().join().stream().map(HumanEventsView.Row::event).toList();
  }

  /**
   * Every event holding all of {@code keys} (see {@link HumanEventsView.Row}), detected at or after
   * {@code detectedSince}, and either pending or closed at or after {@code relevantSince}.
   */
  public static List<HumanEvent> matching(ComponentClient client, Materializer materializer, List<String> keys,
      Instant detectedSince, Instant relevantSince) {
    var filter = HumanEventsView.Filter.of(keys, detectedSince, relevantSince, 0, 0);
    return client.forView().stream(HumanEventsView::matching).source(filter).runWith(Sink.seq(), materializer)
        .toCompletableFuture().join().stream().map(HumanEventsView.Row::event).toList();
  }

  /** The matching pending events, most urgent first. */
  public static Paged pendingPage(ComponentClient client, List<String> keys, Instant detectedSince, int offset,
      int limit) {
    var page = client.forView().method(HumanEventsView::pendingPage)
        .invoke(HumanEventsView.Filter.of(keys, detectedSince, Instant.EPOCH, offset, limit));
    return new Paged(page.entries(), page.total());
  }

  /** The matching closed events, latest closed first. */
  public static Paged closedPage(ComponentClient client, List<String> keys, Instant detectedSince, int offset,
      int limit) {
    var page = client.forView().method(HumanEventsView::closedPage)
        .invoke(HumanEventsView.Filter.of(keys, detectedSince, Instant.EPOCH, offset, limit));
    return new Paged(page.entries(), page.total());
  }

  /** The events whose verdict falls in {@code since}..{@code until}, latest first. */
  public static Paged verdictPage(ComponentClient client, String key, Instant since, Instant until, int offset,
      int limit) {
    var page = client.forView().method(HumanEventsView::verdictPage)
        .invoke(new HumanEventsView.VerdictFilter(key, since, until, offset, limit));
    return new Paged(page.entries(), page.total());
  }
}
