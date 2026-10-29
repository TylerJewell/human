package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The service's overseer registry and the rules the suite uses to pick people for a control. People are
 * considered in subject order, so a run against the same registry always picks the same people.
 */
final class Registry {
  /** Two people in one reporting line: {@code first} decided first, {@code line} is one of their managers. */
  record Line(Person first, Person line) {}

  private final List<Person> people;
  private final Map<String, String> owners;

  Registry(List<Person> people, Map<String, String> owners) {
    this.people = people.stream().sorted(Comparator.comparing(Person::subject)).toList();
    this.owners = Map.copyOf(owners);
  }

  static Registry from(JsonNode registry) {
    List<Person> people = new ArrayList<>();
    registry.path("overseers").forEach(n -> people.add(Person.from(n)));
    Map<String, String> owners = new HashMap<>();
    registry.path("owners").forEach(o -> owners.put(o.path("control").asText(), o.path("accountable").asText()));
    return new Registry(people, owners);
  }

  List<Person> people() {
    return people;
  }

  Optional<Person> find(String subject) {
    return people.stream().filter(p -> p.subject().equals(subject)).findFirst();
  }

  /** Qualified in the registry, holds one of the roles, and holds every attestation the control's routing names. */
  boolean qualified(Person p, Control c, Collection<String> roles) {
    return p.qualified() && p.holdsAny(roles) && p.attested(c.attestations());
  }

  /** People who may take {@code action} on a new event of the control, except those barred from it. */
  List<Person> deciders(Control c, Collection<String> barred, String action) {
    return qualifiedAt(c, c.roles(), barred).stream().filter(p -> p.authorised(c.id(), action)).toList();
  }

  /** Qualified people holding one of {@code roles}, the roles of some queue of the control. */
  List<Person> qualifiedAt(Control c, Collection<String> roles, Collection<String> barred) {
    return people.stream().filter(p -> qualified(p, c, roles) && !barred.contains(p.subject())).toList();
  }

  /** Someone holding none of the control's routing roles; a qualified one when there is one. */
  Optional<Person> withoutRoles(Control c) {
    List<Person> candidates = people.stream().filter(p -> !p.holdsAny(c.roles())).toList();
    return candidates.stream().filter(Person::qualified).findFirst().or(() -> candidates.stream().findFirst());
  }

  /**
   * Someone who is not qualified for the control: preferably a holder of one of its roles who lacks the
   * registry's qualification or an attestation, otherwise anyone the registry marks unqualified.
   */
  Optional<Person> unqualified(Control c) {
    return people.stream().filter(p -> p.holdsAny(c.roles()) && !qualified(p, c, c.roles())).findFirst()
        .or(() -> people.stream().filter(p -> !p.qualified()).findFirst());
  }

  /** People who may exercise the control's halt. */
  List<Person> exercisers(Control c) {
    return people.stream().filter(p -> qualified(p, c, c.exerciserRoles())).toList();
  }

  /** Who asks to restart a halt in the suite's restart flows: the last exerciser, leaving the others to vote. */
  Optional<Person> liftRequester(Control c) {
    List<Person> exercisers = exercisers(c);
    return exercisers.isEmpty() ? Optional.empty() : Optional.of(exercisers.getLast());
  }

  /** The accountable owner as the registry names it: the control's owner entry, else its settings. */
  String accountableName(Control c) {
    return Optional.ofNullable(owners.get(c.id())).orElse(c.accountableOwner());
  }

  /**
   * The person who answers for the control. The accountable owner may name a person or a role; for a role,
   * the first qualified holder, else the first holder.
   */
  Optional<Person> accountable(Control c) {
    String owner = accountableName(c);
    if (owner == null) return Optional.empty();
    return find(owner)
        .or(() -> people.stream().filter(p -> p.qualified() && p.roles().contains(owner)).findFirst())
        .or(() -> people.stream().filter(p -> p.roles().contains(owner)).findFirst());
  }

  boolean isAccountable(Person p, Control c) {
    String owner = accountableName(c);
    return owner != null && (p.subject().equals(owner) || p.roles().contains(owner));
  }

  /**
   * Someone with no part in the control: none of its roles, not its accountable owner, not excluded. A
   * qualified one is preferred, so a refusal is about their part in the event and not their qualification.
   */
  Optional<Person> outsider(Control c, Collection<String> excluded) {
    List<Person> candidates = people.stream()
        .filter(p -> !p.holdsAny(c.allRoles()) && !isAccountable(p, c) && !excluded.contains(p.subject()))
        .toList();
    return candidates.stream().filter(Person::qualified).findFirst().or(() -> candidates.stream().findFirst());
  }

  /** Who proposes in flows that need a proposer to act later: an outsider, else the suite's own agent. */
  Person proposer(Control c) {
    return outsider(c, List.of()).orElse(Person.AGENT);
  }

  /**
   * A first decider and a qualified decider above them, for the first-reviewer-line rule, which follows
   * {@code reportsTo} from the first decider up through their managers.
   */
  Optional<Line> reportingLine(Control c, String action) {
    List<Person> deciders = deciders(c, List.of(), action);
    for (Person first : people) {
      List<String> seen = new ArrayList<>(List.of(first.subject()));
      String manager = first.reportsTo();
      while (manager != null && !seen.contains(manager)) {
        seen.add(manager);
        String subject = manager;
        Optional<Person> decider = deciders.stream().filter(p -> p.subject().equals(subject)).findFirst();
        if (decider.isPresent()) return Optional.of(new Line(first, decider.get()));
        manager = find(manager).map(Person::reportsTo).orElse(null);
      }
    }
    return Optional.empty();
  }

  /** Everyone who matches none of the given people, for picking a fresh participant. */
  Stream<Person> others(Collection<String> excluded) {
    return people.stream().filter(p -> !excluded.contains(p.subject()));
  }
}
