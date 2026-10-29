package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;

/** The outcome of one run: every scenario with its steps, the totals, and the console lines. */
record Report(String url, JsonNode info, Instant startedAt, Instant finishedAt, List<Report.Result> results) {
  static final String PASSED = "passed";
  static final String FAILED = "failed";
  static final String SKIPPED = "skipped";

  /** One step of a scenario: an HTTP call with its expectation, or a check on what came back. */
  record Step(String name, String status, String request, Integer http, String detail) {}

  record Result(String pattern, String scenario, String control, String status, String detail, List<Step> steps,
      long durationMs) {}

  record Totals(int passed, int failed, int skipped) {
    int total() {
      return passed + failed + skipped;
    }
  }

  Totals totals() {
    return new Totals(count(PASSED), count(FAILED), count(SKIPPED));
  }

  /** True when every scenario that applied passed. */
  boolean passed() {
    return count(FAILED) == 0;
  }

  private int count(String status) {
    return (int) results.stream().filter(r -> r.status().equals(status)).count();
  }

  ObjectNode toJson() {
    ObjectNode root = Json.object();
    root.put("url", url);
    if (info != null) {
      root.put("service", Json.text(info.path("service")));
      root.put("library", Json.text(info.path("library")));
      root.put("contract", info.path("contract").asInt());
    }
    root.put("startedAt", startedAt.toString());
    root.put("finishedAt", finishedAt.toString());
    Totals totals = totals();
    ObjectNode t = root.putObject("totals");
    t.put("passed", totals.passed());
    t.put("failed", totals.failed());
    t.put("skipped", totals.skipped());
    t.put("total", totals.total());
    ArrayNode scenarios = root.putArray("scenarios");
    for (Result r : results) {
      ObjectNode s = scenarios.addObject();
      s.put("pattern", r.pattern());
      s.put("scenario", r.scenario());
      s.put("control", r.control());
      s.put("status", r.status());
      s.put("detail", r.detail());
      s.put("durationMs", r.durationMs());
      ArrayNode steps = s.putArray("steps");
      for (Step step : r.steps()) {
        ObjectNode n = steps.addObject();
        n.put("name", step.name());
        n.put("status", step.status());
        if (step.request() != null) n.put("request", step.request());
        if (step.http() != null) n.put("http", step.http());
        if (step.detail() != null) n.put("detail", step.detail());
      }
    }
    return root;
  }

  /** One console line per scenario: status, name, and why it failed or was skipped. */
  static String line(Result r) {
    String line = String.format("  %-8s %-18s", r.status(), r.scenario());
    if (r.status().equals(PASSED)) {
      long skipped = r.steps().stream().filter(s -> s.status().equals(SKIPPED)).count();
      return skipped == 0 ? line.stripTrailing() : line + " (" + skipped + " step" + (skipped == 1 ? "" : "s") + " skipped)";
    }
    return r.detail() == null ? line.stripTrailing() : line + " " + r.detail();
  }

  /** Every failed step with its request and detail, then the totals. */
  String summary() {
    StringBuilder out = new StringBuilder();
    List<Result> failed = results.stream().filter(r -> r.status().equals(FAILED)).toList();
    if (!failed.isEmpty()) {
      out.append("\nFailures\n");
      for (Result r : failed) {
        out.append("  ").append(r.pattern()).append(" / ").append(r.scenario()).append("\n");
        r.steps().stream().filter(s -> s.status().equals(FAILED)).forEach(s -> out.append("    ").append(s.name())
            .append(s.request() == null ? "" : " [" + s.request() + "]").append(": ").append(s.detail()).append("\n"));
      }
    }
    Totals totals = totals();
    out.append(String.format("%npassed %d, failed %d, skipped %d (%d scenarios)%n",
        totals.passed(), totals.failed(), totals.skipped(), totals.total()));
    return out.toString();
  }
}
