package io.akka.human.samples.patterns.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;
import java.util.Map;

/**
 * What the sample's apply handlers carry out: a granted waiver, a match, an evaluation case, a triage
 * result, an attestation. One record per id, as the handler that wrote it last left it.
 */
@Component(id = "sample-record")
public class RecordsEntity extends KeyValueEntity<RecordsEntity.Record> {

  /** {@code kind} names what wrote it: waiver, match, eval-case, triage, attestation, ... */
  public record Record(String kind, String id, String outcome, Map<String, String> fields, Instant at) {}

  public Effect<Record> write(Record record) {
    return effects().updateState(record).thenReply(record);
  }

  /** The record, or null when nothing has been written under the id. */
  public record Found(Record record) {}

  public ReadOnlyEffect<Found> get() {
    return effects().reply(new Found(currentState()));
  }
}
