package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Checks on the service as a whole: what it says it serves, the read routes, the dev routes the suite relies on. */
final class ServiceScenarios {
  private ServiceScenarios() {}

  static JsonNode info(Run run) {
    JsonNode info = run.get("service info", "/oversight/info", "ServiceInfo");
    run.check("contract version 2", info.path("contract").asInt() == 2, "contract " + info.path("contract"));
    run.check("dev routes are on", info.path("devRoutes").asBoolean(false),
        "devRoutes is false; the suite raises events and moves time through the dev routes"
            + " (akka.human.dev-routes = true)");
    return info;
  }

  static Registry registry(Run run) {
    JsonNode registry = run.get("overseer registry", "/oversight/registry", "OverseerRegistry");
    run.check("the registry lists overseers", registry.path("overseers").size() > 0, "no overseers");
    return Registry.from(registry);
  }

  static List<Control> controls(Run run, JsonNode info) {
    JsonNode page = run.get("controls", "/oversight/controls", "controls[]:ControlSettings");
    List<Control> controls = new ArrayList<>();
    page.path("controls").forEach(n -> controls.add(new Control(n)));
    Set<String> served = new HashSet<>(Json.strings(info.path("patterns")));
    Set<String> declared = new HashSet<>();
    controls.forEach(c -> declared.add(c.pattern()));
    Set<String> withoutControl = new HashSet<>(served);
    withoutControl.removeAll(declared);
    run.check("every pattern the service serves has a control", withoutControl.isEmpty(),
        "info lists " + withoutControl + " but no control has them");
    Set<String> unlisted = new HashSet<>(declared);
    unlisted.removeAll(served);
    run.check("info lists the pattern of every control", unlisted.isEmpty(), "info.patterns omits " + unlisted);
    if (info.path("controls").isIntegralNumber()) {
      run.check("info counts the controls", info.path("controls").asInt() == controls.size(),
          "info says " + info.path("controls") + ", /oversight/controls lists " + controls.size());
    }
    return controls;
  }

  /** The reset the suite runs before every scenario leaves no pending event, halt or active standing decision. */
  static void reset(Run run) {
    Flows.reset(run);
    run.await("no halt remains", "/oversight/halts", "halts[]:Halt",
        page -> Json.find(page.path("halts"), h -> h.path("halted").asBoolean(false)).isEmpty(), "no halted entry");
    run.await("no standing decision remains active", "/oversight/standing", "standing[]:StandingDecision",
        page -> Json.find(page.path("standing"), s -> "ACTIVE".equals(s.path("state").asText())).isEmpty(),
        "no ACTIVE standing decision");
  }

  /** The dev clock resets to the service's time and moves forward by what it is asked. */
  static void clock(Run run) {
    Instant start = run.resetClock();
    Instant later = run.advance(Duration.ofMinutes(1));
    run.check("the clock moves forward by PT1M", !later.isBefore(start.plus(Duration.ofMinutes(1))),
        "from " + start + " to " + later);
  }

  static void summary(Run run, List<Control> controls) {
    run.get("summary", "/oversight/summary", "Summary");
    if (!controls.isEmpty()) {
      run.get("summary of one control", "/oversight/summary?control=" + Http.encode(controls.getFirst().id()), "Summary");
    }
  }

  static void quality(Run run) {
    run.get("quality over P30D", "/oversight/quality?window=P30D", "Quality");
  }

  /** The remaining read routes answer their types; resolved events come newest first; an unknown event is 404. */
  static void reads(Run run) {
    run.get("standing decisions", "/oversight/standing", "standing[]:StandingDecision");
    run.get("halts", "/oversight/halts", "halts[]:Halt");
    run.get("verdicts", "/oversight/verdicts?limit=50", "verdicts[]:Verdict");
    JsonNode resolved = run.get("resolved events", "/oversight/events?state=resolved&limit=50", "EventsPage");
    List<Instant> closedAt = new ArrayList<>();
    resolved.path("events").forEach(e -> {
      Instant at = Json.instant(e.path("closedAt"));
      if (at != null) closedAt.add(at);
    });
    boolean newestFirst = true;
    for (int i = 1; i < closedAt.size(); i++) {
      if (closedAt.get(i).isAfter(closedAt.get(i - 1))) newestFirst = false;
    }
    run.check("resolved events are ordered by closedAt, newest first", newestFirst, "closedAt " + closedAt);
    run.proofRecords("the proof export");
    run.send("an unknown event", "GET", Flows.eventPath("conformance-unknown-" + UUID.randomUUID()), null, null, 404);
  }
}
