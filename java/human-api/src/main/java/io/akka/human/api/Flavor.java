package io.akka.human.api;

/** The five human flavors of Verify (ADR-016): who decides and how tightly they are coupled. */
public enum Flavor {
  DOMAIN_DECISION("human-domain-decision"),
  SYSTEM_AUTHORIZATION("human-system-authorization"),
  MONITORING("human-monitoring"),
  FEEDBACK("human-feedback"),
  HALT("human-halt");

  private final String id;

  Flavor(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static Flavor of(String id) {
    for (var flavor : values()) {
      if (flavor.id.equals(id)) return flavor;
    }
    throw new IllegalArgumentException("not a human flavor: " + id);
  }
}
