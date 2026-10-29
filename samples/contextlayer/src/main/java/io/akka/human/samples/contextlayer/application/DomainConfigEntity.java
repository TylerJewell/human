package io.akka.human.samples.contextlayer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project's data domains and the join rules that let one question link two of them, in one entity
 * with the id {@link #ID}. Setting or removing a domain and revoking a join rule are an administrator's
 * decisions (custom-cl0042); a join rule is approved by someone other than whoever last changed either
 * domain (custom-cl0014).
 */
@Component(id = "context-domains")
public class DomainConfigEntity extends KeyValueEntity<DomainConfigEntity.Config> {

  public static final String ID = "domains";

  public record Domain(String name, List<String> entityTypes, List<String> readerRoles, String changedBy) {}

  /** {@code status}: APPROVED or REVOKED. */
  public record JoinRule(String a, String b, String status, String decidedBy) {}

  public record Config(Map<String, Domain> domains, List<JoinRule> joins) {
    public Config {
      domains = domains == null ? Map.of() : Map.copyOf(domains);
      joins = joins == null ? List.of() : List.copyOf(joins);
    }
  }

  public record Join(String a, String b, String by, boolean approved) {}

  @Override
  public Config emptyState() {
    return new Config(Map.of(), List.of());
  }

  public Effect<Config> set(Domain domain) {
    var domains = new LinkedHashMap<>(currentState().domains());
    domains.put(domain.name(), domain);
    return save(new Config(domains, currentState().joins()));
  }

  public Effect<Config> remove(String name) {
    var domains = new LinkedHashMap<>(currentState().domains());
    domains.remove(name);
    var joins = currentState().joins().stream().filter(j -> !j.a().equals(name) && !j.b().equals(name)).toList();
    return save(new Config(domains, joins));
  }

  /** Records a join rule approved, or revoked. */
  public Effect<Config> join(Join join) {
    if (join.approved() && (!currentState().domains().containsKey(join.a()) || !currentState().domains().containsKey(join.b()))) {
      return effects().error("both data domains have to exist");
    }
    var joins = new ArrayList<>(currentState().joins());
    joins.removeIf(j -> j.a().equals(join.a()) && j.b().equals(join.b()));
    joins.add(new JoinRule(join.a(), join.b(), join.approved() ? "APPROVED" : "REVOKED", join.by()));
    return save(new Config(currentState().domains(), joins));
  }

  public ReadOnlyEffect<Config> get() {
    return effects().reply(currentState());
  }

  private Effect<Config> save(Config next) {
    return effects().updateState(next).thenReply(next);
  }
}
