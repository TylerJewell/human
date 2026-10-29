package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Builders for the registries and control settings the unit tests reason about. */
final class Fixtures {
  private Fixtures() {}

  static ObjectNode person(String subject, boolean qualified, String... roles) {
    ObjectNode p = Json.object();
    p.put("subject", subject);
    p.put("name", subject);
    p.set("roles", Json.array(List.of(roles)));
    p.put("qualified", qualified);
    ObjectNode attestation = p.putArray("attestations").addObject();
    attestation.put("id", "oversight-training");
    attestation.put("completedAt", "2026-01-01");
    attestation.put("expired", false);
    return p;
  }

  static ObjectNode expired(ObjectNode person) {
    ((ObjectNode) person.path("attestations").get(0)).put("expired", true);
    return person;
  }

  static ObjectNode authority(ObjectNode person, String control, String... actions) {
    ObjectNode entry = person.putArray("authority").addObject();
    entry.set("controls", Json.array(List.of(control)));
    entry.set("actions", Json.array(List.of(actions)));
    return person;
  }

  static ObjectNode reportsTo(ObjectNode person, String manager) {
    person.put("reportsTo", manager);
    return person;
  }

  static Registry registry(ObjectNode... people) {
    ObjectNode registry = Json.object();
    ArrayNode overseers = registry.putArray("overseers");
    for (ObjectNode p : people) overseers.add(p);
    return Registry.from(registry);
  }

  /** A resolved control with one queue, SINGLE review and the shared defaults. */
  static ObjectNode control(String id, String pattern, List<String> roles, String... actions) {
    ObjectNode c = Json.object();
    c.putObject("control").put("rowId", id).put("name", "Control " + id);
    c.put("pattern", pattern);
    c.put("flavor", "human-domain-decision");
    c.put("subjectKind", "action");
    c.set("allowedActions", Json.array(List.of(actions)));
    ObjectNode labels = c.putObject("actionLabels");
    for (String a : actions) labels.put(a, a.toLowerCase());
    ObjectNode routing = c.putObject("routing");
    routing.put("queue", "q-" + id);
    routing.set("roles", Json.array(roles));
    routing.putArray("attestations");
    routing.put("reviewMode", "SINGLE");
    routing.put("required", 1);
    routing.set("independence", Json.array(List.of("proposer")));
    c.put("slaTarget", "PT30M");
    c.put("timeoutBehaviour", "RESUME_WITH_DEFAULT");
    c.put("timeoutDefault", "DENY");
    c.set("sticky", Json.array(List.of("APPROVE_ONCE")));
    c.put("rationaleRequired", true);
    return c;
  }

  static ObjectNode chain(ObjectNode control, String... queueRolePairs) {
    ArrayNode chain = control.putArray("escalationChain");
    for (int i = 0; i < queueRolePairs.length; i += 2) {
      ObjectNode level = chain.addObject();
      level.put("queue", queueRolePairs[i]);
      level.set("roles", Json.array(List.of(queueRolePairs[i + 1])));
    }
    return control;
  }

  static ObjectNode twoPerson(ObjectNode control) {
    ((ObjectNode) control.path("routing")).put("reviewMode", "TWO_PERSON").put("required", 2);
    return control;
  }

  static Target target(JsonNode control, Registry registry) {
    Control c = new Control(control);
    return new Target(c, registry, List.of(c));
  }
}
