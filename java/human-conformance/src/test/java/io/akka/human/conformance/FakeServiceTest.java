package io.akka.human.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the HTTP plumbing against an in-process service that answers the read and dev routes for a
 * service declaring no controls. It is not an implementation of the contract.
 */
class FakeServiceTest {
  private HttpServer server;
  private String url;
  private boolean devRoutes = true;
  private Duration offset = Duration.ZERO;
  private boolean offsetWasMoved;
  private final AtomicInteger resets = new AtomicInteger();
  private final AtomicInteger pendingReads = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::answer);
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void answer(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String query = exchange.getRequestURI().getQuery();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    switch (path) {
      case "/oversight/info" -> json(exchange, 200,
          "{\"service\":\"fake\",\"contract\":3,\"patterns\":[],\"devRoutes\":" + devRoutes + ",\"controls\":0}");
      case "/oversight/registry" -> json(exchange, 200,
          "{\"overseers\":[{\"subject\":\"amy@x\",\"roles\":[\"owner\"],\"qualified\":true}]}");
      case "/oversight/controls" -> json(exchange, 200, "{\"controls\":[]}");
      case "/oversight/dev/reset" -> {
        resets.incrementAndGet();
        json(exchange, 200, "{\"removed\":0}");
      }
      case "/oversight/dev/clock" -> {
        JsonNode request = Json.MAPPER.readTree(body);
        if (request.path("reset").asBoolean(false)) offset = Duration.ZERO;
        if (request.has("advance")) offset = offset.plus(Duration.parse(request.path("advance").asText()));
        if (!offset.isZero()) offsetWasMoved = true;
        json(exchange, 200, "{\"now\":\"" + Instant.now().plus(offset) + "\",\"offset\":\"" + offset + "\"}");
      }
      case "/oversight/events" -> {
        boolean pending = query != null && query.contains("state=pending");
        int total = pending && pendingReads.getAndIncrement() < 2 ? 1 : 0;
        json(exchange, 200, "{\"events\":[],\"total\":" + total + "}");
      }
      case "/oversight/halts" -> json(exchange, 200, "{\"halts\":[]}");
      case "/oversight/standing" -> json(exchange, 200, "{\"standing\":[]}");
      case "/oversight/verdicts" -> json(exchange, 200, "{\"verdicts\":[]}");
      case "/oversight/summary" -> json(exchange, 200, "{\"loop\":{\"in\":{\"pending\":0,\"overdue\":0},"
          + "\"on\":{\"pending\":0,\"overdue\":0},\"over\":{\"pending\":0,\"overdue\":0}},\"states\":{},"
          + "\"atRisk\":0,\"overdue\":0,\"halts\":[],\"standing\":{\"active\":0,\"expiring\":0},\"patterns\":{}}");
      case "/oversight/quality" -> json(exchange, 200,
          "{\"window\":\"P30D\",\"reviewTimes\":[],\"reviewers\":[],\"outcomes\":{}}");
      case "/oversight/proof" -> send(exchange, 200, "application/x-ndjson", "");
      case "/conflict" -> json(exchange, 409, "{\"error\":\"held by another\"}");
      default -> json(exchange, 404, "{\"error\":\"unknown\"}");
    }
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    send(exchange, status, "application/json", body);
  }

  private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private int main(PrintStream out, String... args) {
    return Main.run(args, out, out);
  }

  @Test
  void aServiceWithNoControlsPassesTheServiceChecksAndWritesTheReport(@TempDir Path dir) throws IOException {
    Path report = dir.resolve("report.json");
    ByteArrayOutputStream console = new ByteArrayOutputStream();

    int exit = main(new PrintStream(console, true, StandardCharsets.UTF_8), "--url", url, "--allow-reset", "--report",
        report.toString());

    String printed = console.toString(StandardCharsets.UTF_8);
    assertEquals(0, exit, printed);
    JsonNode json = Json.MAPPER.readTree(Files.readString(report));
    assertEquals(0, json.path("totals").path("failed").asInt());
    assertEquals(14, json.path("totals").path("skipped").asInt(), "one skipped entry per pattern without a control");
    assertTrue(printed.contains("the service declares no control for this pattern"), printed);
  }

  @Test
  void aRunLeavesTheClockAtRealTimeAndResetsTheServiceOnceMore() {
    ByteArrayOutputStream console = new ByteArrayOutputStream();

    int exit = main(new PrintStream(console, true, StandardCharsets.UTF_8), "--url", url, "--allow-reset");

    assertEquals(0, exit, console.toString(StandardCharsets.UTF_8));
    assertTrue(offsetWasMoved, "the dev-clock scenario moves the clock");
    assertEquals(Duration.ZERO, offset);
    assertTrue(console.toString(StandardCharsets.UTF_8).contains("cleanup"));
    assertTrue(resets.get() >= 1);
  }

  @Test
  void withoutAllowResetTheRunRefusesAndListsTheServiceItWouldReset() {
    ByteArrayOutputStream console = new ByteArrayOutputStream();

    int exit = main(new PrintStream(console, true, StandardCharsets.UTF_8), "--url", url);

    String printed = console.toString(StandardCharsets.UTF_8);
    assertEquals(2, exit);
    assertTrue(printed.contains("It would reset:") && printed.contains(url), printed);
    assertTrue(printed.contains("--allow-reset"), printed);
    assertEquals(0, resets.get(), "nothing was called on the service");
    assertEquals(Duration.ZERO, offset);
  }

  @Test
  void withAllowResetTheRunWarnsBeforeItStartsAndNamesTheService() {
    ByteArrayOutputStream console = new ByteArrayOutputStream();

    main(new PrintStream(console, true, StandardCharsets.UTF_8), "--url", url, "--allow-reset");

    String printed = console.toString(StandardCharsets.UTF_8);
    int warning = printed.indexOf("WARNING");
    assertTrue(warning >= 0 && printed.indexOf(url) >= 0, printed);
    assertTrue(warning < printed.indexOf("human-conformance against"), "the warning comes before the run is announced");
  }

  @Test
  void devRoutesOffFailsTheRunAtTheServiceInfo() {
    devRoutes = false;
    ByteArrayOutputStream console = new ByteArrayOutputStream();

    int exit = main(new PrintStream(console, true, StandardCharsets.UTF_8), "--url", url, "--allow-reset");

    assertEquals(1, exit);
    assertTrue(console.toString(StandardCharsets.UTF_8).contains("dev routes are on"));
  }

  @Test
  void aConflictMustNameTheStateAndAnUnexpectedStatusFailsTheStep() {
    Run run = new Run(new Http(url), new SchemaCheck(), new Run.DevClock());

    Run.Failure conflict = assertThrows(Run.Failure.class, () -> run.get("conflict", "/conflict", null));
    assertTrue(conflict.getMessage().contains("expected 200, got 409"), conflict.getMessage());
    Run.Failure shape = assertThrows(Run.Failure.class,
        () -> run.send("conflict", "GET", "/conflict", null, null, 409));
    assertTrue(shape.getMessage().contains("a 409 answer names the state"), shape.getMessage());
    assertEquals(Report.FAILED, run.steps().getLast().status());
  }

  @Test
  void awaitPollsUntilTheViewCatchesUp() {
    Run run = new Run(new Http(url), new SchemaCheck(), new Run.DevClock());

    JsonNode page = run.await("pending drains", "/oversight/events?state=pending", "EventsPage",
        p -> p.path("total").asInt() == 0, "no pending events");

    assertEquals(0, page.path("total").asInt());
    assertTrue(pendingReads.get() >= 3);
  }
}
