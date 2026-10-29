package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** One overseer from {@code GET /oversight/registry}, or the suite's own agent when no person fits. */
record Person(String subject, String name, List<String> roles, String reportsTo, boolean qualified,
    List<Attestation> attestations, List<Authority> authority) {

  record Attestation(String id, boolean expired) {}

  /** A limit on what a person may decide: the controls ({@code *} for all) and the actions. */
  record Authority(List<String> controls, List<String> actions) {}

  /** Who the suite stamps as proposer when the registry has no person outside the control's roles. */
  static final Person AGENT = new Person("agent:human-conformance", "human-conformance", List.of(), null, false,
      List.of(), List.of());

  static Person from(JsonNode n) {
    List<Attestation> attestations = new ArrayList<>();
    n.path("attestations").forEach(a -> attestations.add(new Attestation(a.path("id").asText(), expired(a))));
    List<Authority> authority = new ArrayList<>();
    n.path("authority").forEach(a -> authority.add(
        new Authority(Json.strings(a.path("controls")), Json.strings(a.path("actions")))));
    return new Person(n.path("subject").asText(), Json.text(n.path("name"), n.path("subject").asText()),
        Json.strings(n.path("roles")), Json.text(n.path("reportsTo")), n.path("qualified").asBoolean(false),
        List.copyOf(attestations), List.copyOf(authority));
  }

  private static boolean expired(JsonNode attestation) {
    if (attestation.has("expired")) return attestation.path("expired").asBoolean(false);
    String expires = Json.text(attestation.path("expiresAt"));
    if (expires == null || expires.length() < 10) return false;
    try {
      return LocalDate.parse(expires.substring(0, 10)).isBefore(LocalDate.now(ZoneOffset.UTC));
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  boolean holdsAny(Collection<String> wanted) {
    return roles.stream().anyMatch(wanted::contains);
  }

  /** Holds an unexpired attestation for every id. */
  boolean attested(Collection<String> ids) {
    return ids.stream().allMatch(id -> attestations.stream().anyMatch(a -> a.id().equals(id) && !a.expired()));
  }

  /** No authority entries means no limit. */
  boolean authorised(String control, String action) {
    if (authority.isEmpty()) return true;
    return authority.stream().anyMatch(a ->
        (a.controls().contains("*") || a.controls().contains(control))
            && (a.actions().isEmpty() || a.actions().contains(action)));
  }

  /** The reviewer every write carries, stamped as the companion would, as a rehearsal. */
  ObjectNode stamp() {
    ObjectNode reviewer = Json.object();
    reviewer.put("subject", subject);
    reviewer.put("name", name);
    reviewer.set("roles", Json.array(roles));
    reviewer.put("identityProvider", "human-conformance");
    reviewer.put("rehearsal", true);
    return reviewer;
  }
}
