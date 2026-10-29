package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Records from different sources that an identity rule matched on one key: an email address, a name
 * and date of birth, a SKU. They are one thing only once a reviewer confirms the link (custom-cl0004).
 * The fingerprint names the records the cluster holds; a decision made on another set of records is
 * not applied.
 */
@Component(id = "context-identity")
public class IdentityEntity extends KeyValueEntity<IdentityEntity.Cluster> {

  /** One source record; {@code loadedBy} is who loaded it, who may not confirm the link. */
  public record Member(String source, String ref, String loadedBy, Map<String, String> fields) {}

  /** {@code status}: SINGLE (one record), PENDING (awaiting review), CONFIRMED or REJECTED. */
  public record Cluster(String id, String rule, List<Member> members, String fingerprint, String status,
      String reviewedBy, String eventId) {

    public Cluster {
      members = members == null ? List.of() : List.copyOf(members);
    }
  }

  public record Add(String rule, Member member) {}

  public record Review(boolean same, String by, String fingerprint) {}

  /** Adds a record; a record already there under the same source and ref is replaced. */
  public Effect<Cluster> add(Add add) {
    var members = new ArrayList<Member>(currentState() == null ? List.of() : currentState().members());
    members.removeIf(m -> m.source().equals(add.member().source()) && m.ref().equals(add.member().ref()));
    members.add(add.member());
    var next = new Cluster(commandContext().entityId(), add.rule(), members, fingerprint(members),
        members.size() > 1 ? "PENDING" : "SINGLE", null, currentState() == null ? null : currentState().eventId());
    return effects().updateState(next).thenReply(next);
  }

  public Effect<Cluster> reviewRaised(String eventId) {
    var c = currentState();
    var next = new Cluster(c.id(), c.rule(), c.members(), c.fingerprint(), c.status(), c.reviewedBy(), eventId);
    return effects().updateState(next).thenReply(next);
  }

  /** Confirms or rejects the link, only if the cluster still holds the records the reviewer saw. */
  public Effect<Cluster> review(Review review) {
    var c = currentState();
    if (c == null) return effects().error("no such cluster");
    if (!c.fingerprint().equals(review.fingerprint())) {
      return effects().error("the records changed after this was decided; it was not applied");
    }
    var next = new Cluster(c.id(), c.rule(), c.members(), c.fingerprint(), review.same() ? "CONFIRMED" : "REJECTED",
        review.by(), c.eventId());
    return effects().updateState(next).thenReply(next);
  }

  public ReadOnlyEffect<Cluster> get() {
    if (currentState() == null) return effects().error("no such cluster");
    return effects().reply(currentState());
  }

  static String fingerprint(List<Member> members) {
    var text = String.join("|", members.stream().map(m -> m.source() + "/" + m.ref()).sorted().toList());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
