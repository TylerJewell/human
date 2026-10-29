package io.akka.human.api;

import java.util.Collection;
import java.util.List;

/**
 * The person making a call, as the companion or an access policy stamps them. A rehearsal decision
 * takes effect; only its verdict's source differs.
 */
public record Reviewer(String subject, String name, List<String> roles, String identityProvider, boolean rehearsal) {

  public Reviewer {
    if (subject == null || subject.isBlank()) throw new IllegalArgumentException("a reviewer needs a subject");
    name = name == null ? subject : name;
    roles = roles == null ? List.of() : List.copyOf(roles);
    identityProvider = identityProvider == null ? "" : identityProvider;
  }

  public static Reviewer of(String subject, String... roles) {
    return new Reviewer(subject, subject, List.of(roles), "", false);
  }

  /** The service itself, for acts no person takes: timeouts, supersession, standing decisions. */
  public static Reviewer system(String why) {
    return new Reviewer("system:" + why, "system", List.of(), "service", false);
  }

  public boolean holdsAnyOf(Collection<String> wanted) {
    return roles.stream().anyMatch(wanted::contains);
  }
}
