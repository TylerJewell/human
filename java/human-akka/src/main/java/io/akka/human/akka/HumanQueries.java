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
    var filter = HumanEventsView.Filter.of(keys, detectedSince, relevantSince);
    return client.forView().stream(HumanEventsView::matching).source(filter).runWith(Sink.seq(), materializer)
        .toCompletableFuture().join().stream().map(HumanEventsView.Row::event).toList();
  }

  /** Every event holding {@code key} whose verdict was reached in {@code since}..{@code until}, in no order. */
  public static List<HumanEvent> withVerdicts(ComponentClient client, Materializer materializer, String key,
      Instant since, Instant until) {
    var filter = new HumanEventsView.VerdictFilter(key, since, until);
    return client.forView().stream(HumanEventsView::verdicts).source(filter).runWith(Sink.seq(), materializer)
        .toCompletableFuture().join().stream().map(HumanEventsView.Row::event).toList();
  }
}
