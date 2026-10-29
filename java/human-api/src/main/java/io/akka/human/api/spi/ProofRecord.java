package io.akka.human.api.spi;

import java.time.Instant;
import java.util.Map;

/**
 * An audit record as Verify's taxonomy names it: HumanOversightDecisionRecordedV2, HaltExercised or
 * HaltLifted, with its snake_case payload (specs/corpus/aud-taxonomy.yaml).
 */
public record ProofRecord(String type, String recordId, Instant recordedAt, String service, Map<String, Object> payload) {

  public static final String DECISION = "HumanOversightDecisionRecordedV2";
  public static final String HALT_EXERCISED = "HaltExercised";
  public static final String HALT_LIFTED = "HaltLifted";
}
