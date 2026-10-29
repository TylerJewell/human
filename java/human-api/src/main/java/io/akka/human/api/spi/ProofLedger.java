package io.akka.human.api.spi;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Where proof records go. Stands in for runtime request 10 (oversight records in the governance
 * ledger); the local implementation appends to an entity and exports NDJSON.
 */
public interface ProofLedger {

  /** Appending the same record id twice keeps one. */
  CompletionStage<Void> append(ProofRecord record);

  CompletionStage<List<ProofRecord>> since(Instant since);
}
