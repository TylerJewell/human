package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The context of one scenario: makes the calls, checks each answer's status and shape, and records every
 * call and check as a step. The first failed step ends the scenario.
 */
final class Run {
  /** A step failed; the scenario ends failed. */
  static final class Failure extends RuntimeException {
    Failure(String message) {
      super(message, null, false, false);
    }
  }

  /** The scenario cannot apply to this service; it ends skipped with the reason. */
  static final class Skip extends RuntimeException {
    Skip(String message) {
      super(message, null, false, false);
    }
  }

  /** How long a read waits for the service's views to catch up with a write. */
  static final Duration WAIT = Duration.ofSeconds(10);
  private static final long POLL_MILLIS = 150;

  /** The dev clock's time as the service last answered it; shared by every scenario of a run. */
  static final class DevClock {
    private Instant now = Instant.now();

    Instant now() {
      return now;
    }
  }

  private final Http http;
  private final SchemaCheck schema;
  private final DevClock clock;
  private final List<Report.Step> steps = new ArrayList<>();

  Run(Http http, SchemaCheck schema, DevClock clock) {
    this.http = http;
    this.schema = schema;
    this.clock = clock;
  }

  List<Report.Step> steps() {
    return List.copyOf(steps);
  }

  /**
   * Makes a call and expects one of the statuses. A 2xx answer must match {@code shape} (null skips the
   * check); a 4xx answer must be an {@code Error}, naming the event's state on 409 and listing errors on 422.
   */
  Http.Response send(String name, String method, String path, JsonNode body, String shape, int... expected) {
    String request = method + " " + path;
    Http.Response answer = call(name, request, method, path, body);
    if (Arrays.stream(expected).noneMatch(s -> s == answer.status())) {
      throw fail(name, request, answer.status(), "expected " + statuses(expected) + ", got " + answer.status()
          + ": " + Json.abbreviate(answer.body()));
    }
    List<String> problems = problems(answer, shape);
    if (!problems.isEmpty()) {
      throw fail(name, request, answer.status(), "the answer does not match "
          + (answer.status() >= 400 ? "Error" : shape) + ": " + summarise(problems));
    }
    steps.add(new Report.Step(name, Report.PASSED, request, answer.status(), null));
    return answer;
  }

  JsonNode get(String name, String path, String shape) {
    return send(name, "GET", path, null, shape, 200).json();
  }

  JsonNode post(String name, String path, JsonNode body, String shape, int... expected) {
    return send(name, "POST", path, body, shape, expected).json();
  }

  /** Repeats a GET until {@code until} holds, for reads served from views that follow writes. */
  JsonNode await(String name, String path, String shape, Predicate<JsonNode> until, String expectation) {
    String request = "GET " + path;
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (true) {
      Http.Response answer = call(name, request, "GET", path, null);
      if (answer.status() == 200 && answer.json() != null && until.test(answer.json())) {
        List<String> problems = problems(answer, shape);
        if (!problems.isEmpty()) {
          throw fail(name, request, 200, "the answer does not match " + shape + ": " + summarise(problems));
        }
        steps.add(new Report.Step(name, Report.PASSED, request, 200, null));
        return answer.json();
      }
      if (System.nanoTime() > deadline) {
        throw fail(name, request, answer.status(), "waited " + WAIT.toSeconds() + "s for " + expectation
            + "; last answer " + answer.status() + ": " + Json.abbreviate(answer.body()));
      }
      pause();
    }
  }

  /**
   * Waits until {@code GET /oversight/proof} holds a record matching {@code test}. The answer must be NDJSON;
   * every line must parse and the matching record must be a {@code ProofRecord}.
   */
  JsonNode awaitProof(String name, Predicate<JsonNode> test, String expectation) {
    return awaitProof(name, test, expectation, 0);
  }

  /** As {@link #awaitProof(String, Predicate, String)}, waiting for more than {@code before} matching records. */
  JsonNode awaitProof(String name, Predicate<JsonNode> test, String expectation, long before) {
    String path = "/oversight/proof";
    String request = "GET " + path;
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (true) {
      Http.Response answer = call(name, request, "GET", path, null);
      if (answer.status() != 200) {
        throw fail(name, request, answer.status(), "expected 200, got " + answer.status() + ": "
            + Json.abbreviate(answer.body()));
      }
      if (!answer.contentType().contains("application/x-ndjson")) {
        throw fail(name, request, 200, "expected Content-Type application/x-ndjson, got " + answer.contentType());
      }
      List<JsonNode> records = ndjson(name, request, answer.body());
      List<JsonNode> matches = records.stream().filter(test).toList();
      Optional<JsonNode> match = matches.size() > before ? Optional.of(matches.getLast()) : Optional.empty();
      if (match.isPresent()) {
        List<String> problems = schema.validate("ProofRecord", match.get());
        if (!problems.isEmpty()) {
          throw fail(name, request, 200, "the record does not match ProofRecord: " + summarise(problems));
        }
        steps.add(new Report.Step(name, Report.PASSED, request, 200, null));
        return match.get();
      }
      if (System.nanoTime() > deadline) {
        throw fail(name, request, 200, "waited " + WAIT.toSeconds() + "s for " + expectation + " among "
            + records.size() + " proof records");
      }
      pause();
    }
  }

  /** How many records of the proof export match {@code test}; recorded as a step. */
  long countProof(String name, Predicate<JsonNode> test) {
    String path = "/oversight/proof";
    String request = "GET " + path;
    Http.Response answer = call(name, request, "GET", path, null);
    if (answer.status() != 200) throw fail(name, request, answer.status(), "expected 200, got " + answer.status());
    long count = ndjson(name, request, answer.body()).stream().filter(test).count();
    steps.add(new Report.Step(name, Report.PASSED, request, 200, count + " matching records"));
    return count;
  }

  /** Every line of a proof export, each validated as a {@code ProofRecord}. */
  List<JsonNode> proofRecords(String name) {
    String path = "/oversight/proof";
    String request = "GET " + path;
    Http.Response answer = call(name, request, "GET", path, null);
    if (answer.status() != 200) {
      throw fail(name, request, answer.status(), "expected 200, got " + answer.status());
    }
    if (!answer.contentType().contains("application/x-ndjson")) {
      throw fail(name, request, 200, "expected Content-Type application/x-ndjson, got " + answer.contentType());
    }
    List<JsonNode> records = ndjson(name, request, answer.body());
    for (int i = 0; i < records.size(); i++) {
      List<String> problems = schema.validate("ProofRecord", records.get(i));
      if (!problems.isEmpty()) {
        throw fail(name, request, 200, "line " + (i + 1) + " does not match ProofRecord: " + summarise(problems));
      }
    }
    steps.add(new Report.Step(name, Report.PASSED, request, 200, records.size() + " records"));
    return records;
  }

  /** Records a passed check, or fails the scenario with {@code detail}. */
  void check(String name, boolean ok, String detail) {
    if (!ok) throw fail(name, null, null, detail);
    steps.add(new Report.Step(name, Report.PASSED, null, null, null));
  }

  /** Records a step that does not apply here, with the reason; the scenario goes on. */
  void skipStep(String name, String reason) {
    steps.add(new Report.Step(name, Report.SKIPPED, null, null, reason));
  }

  /** Records a passed step that carries information for the reader. */
  void note(String name, String detail) {
    steps.add(new Report.Step(name, Report.PASSED, null, null, detail));
  }

  Failure fail(String name, String request, Integer status, String detail) {
    steps.add(new Report.Step(name, Report.FAILED, request, status, detail));
    return new Failure(name + ": " + detail);
  }

  /** Records an error the suite did not expect, such as a lost connection, as the failed step. */
  Failure error(RuntimeException e) {
    return fail("unexpected error", null, null, e.toString());
  }

  Instant now() {
    return clock.now;
  }

  /** Sets the dev clock back to the service's real time. */
  Instant resetClock() {
    ObjectNode body = Json.object();
    body.put("reset", true);
    return clockAnswer(post("reset the dev clock", "/oversight/dev/clock", body, null, 200));
  }

  Instant advance(Duration by) {
    ObjectNode body = Json.object();
    body.put("advance", by.toString());
    return clockAnswer(post("advance the dev clock " + by, "/oversight/dev/clock", body, null, 200));
  }

  /** Moves the dev clock at least one second past {@code instant}. */
  Instant advancePast(Instant instant) {
    Duration by = Duration.between(clock.now, instant).withNanos(0).plusSeconds(2);
    return advance(by.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : by);
  }

  /** Runs every deadline due at the dev clock's time. */
  JsonNode tick() {
    JsonNode answer = post("run due deadlines", "/oversight/dev/tick", Json.object(), null, 200);
    check("tick answers expired and atRisk",
        answer != null && answer.path("expired").isIntegralNumber() && answer.path("atRisk").isIntegralNumber(),
        "answer " + answer);
    return answer;
  }

  private Instant clockAnswer(JsonNode answer) {
    Instant now = answer == null ? null : Json.instant(answer.path("now"));
    check("the dev clock answers now and offset", now != null && answer.has("offset"), "answer " + answer);
    clock.now = now;
    return now;
  }

  private Http.Response call(String name, String request, String method, String path, JsonNode body) {
    try {
      return "GET".equals(method) ? http.get(path) : http.post(path, body);
    } catch (UncheckedIOException e) {
      throw fail(name, request, null, "no answer from " + http.base() + ": " + e.getCause());
    }
  }

  private List<String> problems(Http.Response answer, String shape) {
    if (answer.status() >= 400) {
      if (answer.json() == null) return List.of("the body is not JSON");
      List<String> problems = new ArrayList<>(schema.validate("Error", answer.json()));
      if (answer.status() == 409 && !answer.json().hasNonNull("state")) problems.add("a 409 answer names the state");
      if (answer.status() == 422 && !answer.json().path("errors").isArray()) problems.add("a 422 answer lists errors");
      return problems;
    }
    if (shape == null) return List.of();
    if (answer.json() == null) return List.of("the body is not JSON");
    return schema.validateShape(shape, answer.json());
  }

  private List<JsonNode> ndjson(String name, String request, String body) {
    List<JsonNode> records = new ArrayList<>();
    String[] lines = body == null ? new String[0] : body.split("\\r?\\n");
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].isBlank()) continue;
      try {
        records.add(Json.MAPPER.readTree(lines[i]));
      } catch (IOException e) {
        throw fail(name, request, 200, "line " + (i + 1) + " is not JSON: " + Json.abbreviate(lines[i]));
      }
    }
    return records;
  }

  private static String statuses(int... expected) {
    return Arrays.stream(expected).mapToObj(Integer::toString).collect(Collectors.joining(" or "));
  }

  private static String summarise(List<String> problems) {
    String shown = problems.stream().limit(5).collect(Collectors.joining("; "));
    return problems.size() > 5 ? shown + "; and " + (problems.size() - 5) + " more" : shown;
  }

  private static void pause() {
    try {
      Thread.sleep(POLL_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }
}
