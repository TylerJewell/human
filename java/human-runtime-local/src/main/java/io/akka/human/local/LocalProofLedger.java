package io.akka.human.local;

import akka.javasdk.client.ComponentClient;
import io.akka.human.api.spi.ProofLedger;
import io.akka.human.api.spi.ProofRecord;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * {@link ProofLedger} over the local ledger entity. A service passes it to its {@code HumanPlugins};
 * {@code GET /oversight/proof} exports what it holds as NDJSON.
 */
public final class LocalProofLedger implements ProofLedger {

  private final ComponentClient client;

  public LocalProofLedger(ComponentClient client) {
    this.client = client;
  }

  @Override
  public CompletionStage<Void> append(ProofRecord record) {
    return client.forEventSourcedEntity(ProofLedgerEntity.ID).method(ProofLedgerEntity::append).invokeAsync(record)
        .thenApply(appended -> null);
  }

  @Override
  public CompletionStage<List<ProofRecord>> since(Instant since) {
    return client.forEventSourcedEntity(ProofLedgerEntity.ID).method(ProofLedgerEntity::since).invokeAsync(since)
        .thenApply(ProofLedgerEntity.Records::records);
  }
}
