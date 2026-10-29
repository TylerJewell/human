package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs the scenarios against one service. The service checks come first and are prerequisites: the suite
 * discovers controls and people from them. Then, for each pattern, every scenario runs against the first of
 * the pattern's controls that makes it applicable, each starting from a reset service. Scenarios run one at
 * a time; they share the service and its dev clock.
 */
final class Suite {
  static final String SERVICE = "service";

  private final Options options;
  private final Http http;
  private final Consumer<Report.Result> progress;
  private final SchemaCheck schema = new SchemaCheck();
  private final Run.DevClock clock = new Run.DevClock();
  private final List<Report.Result> results = new ArrayList<>();

  Suite(Options options, Consumer<Report.Result> progress) {
    this.options = options;
    this.http = new Http(options.url());
    this.progress = progress;
  }

  Report run() {
    Instant started = Instant.now();
    AtomicReference<JsonNode> info = new AtomicReference<>();
    AtomicReference<Registry> registry = new AtomicReference<>();
    List<Control> controls = new ArrayList<>();
    boolean ready = service("info", false, run -> info.set(ServiceScenarios.info(run)))
        && service("registry", false, run -> registry.set(ServiceScenarios.registry(run)))
        && service("controls", false, run -> controls.addAll(ServiceScenarios.controls(run, info.get())))
        && service("reset", false, ServiceScenarios::reset)
        && service("dev-clock", false, ServiceScenarios::clock);
    if (ready) {
      for (String pattern : Patterns.ALL) {
        if (options.includes(pattern)) runPattern(pattern, registry.get(), controls);
      }
      service("summary", false, run -> ServiceScenarios.summary(run, controls));
      service("quality", false, ServiceScenarios::quality);
      service("reads", false, ServiceScenarios::reads);
    }
    return new Report(options.url(), info.get(), started, Instant.now(), List.copyOf(results));
  }

  private void runPattern(String pattern, Registry registry, List<Control> controls) {
    if (controls.stream().noneMatch(c -> pattern.equals(c.pattern()))) {
      record(new Report.Result(pattern, "*", null, Report.SKIPPED, "the service declares no control for this pattern",
          List.of(), 0));
      return;
    }
    for (Scenario scenario : PatternScenarios.all()) {
      if (scenario.onlyPattern() != null && !scenario.onlyPattern().equals(pattern)) continue;
      Choice choice = choose(scenario, pattern, registry, controls);
      if (choice.skipReason() != null) {
        record(new Report.Result(pattern, scenario.name(), choice.target().control().id(), Report.SKIPPED,
            choice.skipReason(), List.of(), 0));
      } else {
        Target target = choice.target();
        record(execute(pattern, scenario.name(), target.control().id(), true,
            run -> scenario.body().accept(run, target)));
      }
    }
  }

  /** The control a scenario runs against, or the reason none of the pattern's controls applies. */
  record Choice(Target target, String skipReason) {}

  /**
   * Picks, among the pattern's controls in the order the service declares them, the first that makes the
   * scenario applicable. When none does, the reason names each distinct reason with the controls it holds for.
   */
  static Choice choose(Scenario scenario, String pattern, Registry registry, List<Control> controls) {
    List<Control> candidates = controls.stream().filter(c -> pattern.equals(c.pattern())).toList();
    if (candidates.isEmpty()) throw new IllegalArgumentException("the service declares no " + pattern + " control");
    Map<String, List<String>> idsByReason = new LinkedHashMap<>();
    for (Control c : candidates) {
      Target target = new Target(c, registry, List.copyOf(controls));
      String reason = scenario.skipReason().apply(target);
      if (reason == null) return new Choice(target, null);
      idsByReason.computeIfAbsent(reason, r -> new ArrayList<>()).add(c.id());
    }
    Target first = new Target(candidates.getFirst(), registry, List.copyOf(controls));
    if (candidates.size() == 1) return new Choice(first, idsByReason.keySet().iterator().next());
    String reasons = idsByReason.entrySet().stream()
        .map(e -> e.getKey() + " (" + String.join(", ", e.getValue()) + ")")
        .collect(Collectors.joining("; "));
    return new Choice(first, "none of the " + candidates.size() + " " + pattern + " controls applies: " + reasons);
  }

  private boolean service(String name, boolean reset, Consumer<Run> body) {
    Report.Result result = execute(SERVICE, name, null, reset, body);
    record(result);
    return result.status().equals(Report.PASSED);
  }

  private Report.Result execute(String pattern, String name, String control, boolean reset, Consumer<Run> body) {
    Run run = new Run(http, schema, clock);
    long start = System.nanoTime();
    String status;
    String detail;
    try {
      if (reset) Flows.reset(run);
      body.accept(run);
      status = Report.PASSED;
      detail = skippedSteps(run.steps());
    } catch (Run.Failure failure) {
      status = Report.FAILED;
      detail = failure.getMessage();
    } catch (Run.Skip skip) {
      status = Report.SKIPPED;
      detail = skip.getMessage();
    } catch (RuntimeException e) {
      run.error(e);
      status = Report.FAILED;
      detail = "unexpected error: " + e;
    }
    return new Report.Result(pattern, name, control, status, detail, run.steps(), (System.nanoTime() - start) / 1_000_000);
  }

  private static String skippedSteps(List<Report.Step> steps) {
    List<String> skipped = steps.stream().filter(s -> s.status().equals(Report.SKIPPED))
        .map(s -> s.name() + " (" + s.detail() + ")").toList();
    return skipped.isEmpty() ? null : "steps skipped: " + skipped.stream().collect(Collectors.joining("; "));
  }

  private void record(Report.Result result) {
    results.add(result);
    progress.accept(result);
  }
}
