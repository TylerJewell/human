package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What people have stated about one control of the bundle; the id is the control's row id. A waiver
 * sets the control aside until a date (custom-cl0047); an acceptance holds for the catalog digest it was
 * given under and goes stale when the catalog changes (custom-cl0048); each evidence assertion is a new
 * revision (custom-cl0049).
 */
@Component(id = "context-control-claims")
public class ControlClaimsEntity extends KeyValueEntity<ControlClaimsEntity.Claims> {

  public record Waiver(String reason, Instant until, String requestedBy, String grantedBy, Instant grantedAt) {}

  public record Acceptance(String digest, String rationale, String requestedBy, String acceptedBy, Instant at) {}

  public record Assertion(int revision, List<String> records, List<String> documents, String rationale,
      String requestedBy, String assertedBy, Instant at) {}

  public record Claims(String rowId, Waiver waiver, Acceptance acceptance, List<Assertion> assertions) {
    public Claims {
      assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }
  }

  @Override
  public Claims emptyState() {
    return new Claims(null, null, null, List.of());
  }

  public Effect<Claims> waive(Waiver waiver) {
    var c = currentState();
    return save(new Claims(commandContext().entityId(), waiver, c.acceptance(), c.assertions()));
  }

  public Effect<Claims> accept(Acceptance acceptance) {
    var c = currentState();
    return save(new Claims(commandContext().entityId(), c.waiver(), acceptance, c.assertions()));
  }

  /** Adds the next revision of the evidence assertion. */
  public Effect<Claims> addAssertion(Assertion assertion) {
    var c = currentState();
    var assertions = new ArrayList<>(c.assertions());
    assertions.add(new Assertion(assertions.size() + 1, assertion.records(), assertion.documents(), assertion.rationale(),
        assertion.requestedBy(), assertion.assertedBy(), assertion.at()));
    return save(new Claims(commandContext().entityId(), c.waiver(), c.acceptance(), assertions));
  }

  public ReadOnlyEffect<Claims> get() {
    return effects().reply(currentState());
  }

  private Effect<Claims> save(Claims next) {
    return effects().updateState(next).thenReply(next);
  }
}
