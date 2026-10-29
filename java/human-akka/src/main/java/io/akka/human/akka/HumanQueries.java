package io.akka.human.akka;

import akka.javasdk.client.ComponentClient;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import io.akka.human.engine.HumanEvent;
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
}
