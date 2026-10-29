package io.akka.human.api.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a service supplies to the library, provided once through its setup class's dependency
 * provider. The library's components take it as a constructor parameter (the SDK ignores a setup
 * class inside a library, so the service's provider is the only way in).
 *
 * @param service the service's name, as registration and proof records give it
 * @param environment local, pre-production or production; selects each control's strength
 * @param resumers by target name, for {@code Resume.workflow}
 * @param handlers by target name for {@code Resume.handler}, and by pattern id or subject kind for
 *     events nothing waits on; the most specific match wins (target, then pattern, then kind)
 * @param haltScopes by halt target; a target with none is always safe to restart
 * @param ledger empty means proof records are kept only on the events
 * @param devRoutes serve /oversight/dev/* (conformance suite, playground); never in production
 */
public record HumanPlugins(
    String service,
    String environment,
    ControlCatalog catalog,
    OverseerDirectory overseers,
    Map<String, Resumer> resumers,
    Map<String, ApplyHandler> handlers,
    Map<String, HaltScope> haltScopes,
    AccessPolicy access,
    Optional<ProofLedger> ledger,
    HumanClock clock,
    boolean devRoutes) {

  public HumanPlugins {
    resumers = Map.copyOf(resumers);
    handlers = Map.copyOf(handlers);
    haltScopes = Map.copyOf(haltScopes);
  }

  public static Builder builder(String service, ControlCatalog catalog, OverseerDirectory overseers) {
    return new Builder(service, catalog, overseers);
  }

  public static final class Builder {
    private final String service;
    private final ControlCatalog catalog;
    private final OverseerDirectory overseers;
    private String environment = "local";
    private final Map<String, Resumer> resumers = new LinkedHashMap<>();
    private final Map<String, ApplyHandler> handlers = new LinkedHashMap<>();
    private final Map<String, HaltScope> haltScopes = new LinkedHashMap<>();
    private AccessPolicy access = AccessPolicy.ALLOW_ALL;
    private ProofLedger ledger;
    private HumanClock clock = HumanClock.SYSTEM;
    private boolean devRoutes;

    private Builder(String service, ControlCatalog catalog, OverseerDirectory overseers) {
      this.service = service;
      this.catalog = catalog;
      this.overseers = overseers;
    }

    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    public Builder resumer(String target, Resumer resumer) {
      resumers.put(target, resumer);
      return this;
    }

    public Builder handler(String key, ApplyHandler handler) {
      handlers.put(key, handler);
      return this;
    }

    public Builder haltScope(String target, HaltScope scope) {
      haltScopes.put(target, scope);
      return this;
    }

    public Builder access(AccessPolicy access) {
      this.access = access;
      return this;
    }

    public Builder ledger(ProofLedger ledger) {
      this.ledger = ledger;
      return this;
    }

    public Builder clock(HumanClock clock) {
      this.clock = clock;
      return this;
    }

    public Builder devRoutes(boolean on) {
      this.devRoutes = on;
      return this;
    }

    public HumanPlugins build() {
      return new HumanPlugins(service, environment, catalog, overseers, resumers, handlers, haltScopes, access,
          Optional.ofNullable(ledger), clock, devRoutes);
    }
  }
}
