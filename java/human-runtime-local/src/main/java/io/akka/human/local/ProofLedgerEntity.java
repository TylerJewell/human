package io.akka.human.local;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.human.api.spi.ProofRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The local proof ledger, standing in for runtime request 10 (oversight records in the governance
 * ledger): one append-only journal, id {@link #ID}, of the records Verify's audit taxonomy names.
 * Appending a record id that is already there keeps the first.
 */
@Component(id = "human-proof-ledger")
public class ProofLedgerEntity extends EventSourcedEntity<ProofLedgerEntity.Ledger, ProofLedgerEntity.LedgerEvent> {

  public static final String ID = "proof";

  public record Ledger(List<ProofRecord> records) {
    public Ledger {
      records = records == null ? List.of() : List.copyOf(records);
    }
  }

  public sealed interface LedgerEvent {}

  @TypeName("proof-appended")
  public record Appended(ProofRecord record) implements LedgerEvent {}

  public record Records(List<ProofRecord> records) {}

  @Override
  public Ledger emptyState() {
    return new Ledger(List.of());
  }

  public Effect<Boolean> append(ProofRecord record) {
    if (currentState().records().stream().anyMatch(r -> r.recordId().equals(record.recordId()))) {
      return effects().reply(false);
    }
    return effects().persist(new Appended(record)).thenReply(state -> true);
  }

  /** Records at or after {@code since}, oldest first. */
  public ReadOnlyEffect<Records> since(Instant since) {
    return effects().reply(new Records(currentState().records().stream()
        .filter(r -> !r.recordedAt().isBefore(since))
        .sorted(Comparator.comparing(ProofRecord::recordedAt)).toList()));
  }

  @Override
  public Ledger applyEvent(LedgerEvent event) {
    var next = new ArrayList<>(currentState().records());
    next.add(((Appended) event).record());
    return new Ledger(next);
  }
}
