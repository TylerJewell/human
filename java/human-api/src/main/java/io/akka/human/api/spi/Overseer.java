package io.akka.human.api.spi;

import io.akka.human.api.Action;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** A person in the overseer registry (Verify overseer-registry.schema.yaml). */
public record Overseer(
    String subject,
    String name,
    List<String> roles,
    List<String> groups,
    String reportsTo,
    List<Authority> authority,
    List<Attestation> attestations,
    boolean qualified,
    String qualifiedReason) {

  /** Decisions allowed on controls; "*" in controls means every control the roles route to. */
  public record Authority(List<String> controls, Set<Action> actions) {}

  public record Attestation(String id, LocalDate completedAt, LocalDate expiresAt) {
    public boolean validOn(LocalDate day) {
      return expiresAt == null || !day.isAfter(expiresAt);
    }
  }

  public Overseer {
    roles = roles == null ? List.of() : List.copyOf(roles);
    groups = groups == null ? List.of() : List.copyOf(groups);
    authority = authority == null ? List.of() : List.copyOf(authority);
    attestations = attestations == null ? List.of() : List.copyOf(attestations);
  }
}
