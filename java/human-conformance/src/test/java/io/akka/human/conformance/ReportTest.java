package io.akka.human.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTest {
  private static final Report.Step OK = new Report.Step("claim (amy@x)", Report.PASSED, "POST /oversight/events/e1/claim", 200, null);
  private static final Report.Step SKIPPED_STEP = new Report.Step("a second qualified reviewer claims", Report.SKIPPED, null, null, "one reviewer");
  private static final Report.Step BROKEN = new Report.Step("decide", Report.FAILED, "POST /oversight/events/e1/decision", 200, "expected 422, got 200");

  private static Report report(Report.Result... results) {
    JsonNode info = Json.object().put("service", "patterns").put("contract", 2);
    return new Report("http://localhost:9164", info, Instant.parse("2026-10-29T14:00:00Z"),
        Instant.parse("2026-10-29T14:01:00Z"), List.of(results));
  }

  @Test
  void totalsCountEachStatusAndOnlyAFailureFailsTheRun() {
    Report passing = report(
        new Report.Result("action-approval", "claim", "pt-0001", Report.PASSED, null, List.of(OK, SKIPPED_STEP), 5),
        new Report.Result("action-approval", "send-back", "pt-0001", Report.SKIPPED, "no SEND_BACK", List.of(), 0));
    Report failing = report(
        new Report.Result("action-approval", "decide", "pt-0001", Report.FAILED, "decide: expected 422", List.of(OK, BROKEN), 7));

    assertEquals(new Report.Totals(1, 0, 1), passing.totals());
    assertTrue(passing.passed());
    assertFalse(failing.passed());
  }

  @Test
  void theJsonCarriesEveryScenarioWithItsStepsAndTheTotals() {
    Report r = report(
        new Report.Result("action-approval", "decide", "pt-0001", Report.FAILED, "decide: expected 422", List.of(OK, BROKEN), 7));

    JsonNode json = r.toJson();
    assertEquals("patterns", json.path("service").asText());
    assertEquals(1, json.path("totals").path("failed").asInt());
    assertEquals(1, json.path("totals").path("total").asInt());
    JsonNode scenario = json.path("scenarios").get(0);
    assertEquals("action-approval", scenario.path("pattern").asText());
    assertEquals("decide", scenario.path("scenario").asText());
    assertEquals("failed", scenario.path("status").asText());
    assertEquals("decide: expected 422", scenario.path("detail").asText());
    assertEquals(2, scenario.path("steps").size());
    assertEquals("POST /oversight/events/e1/decision", scenario.path("steps").get(1).path("request").asText());
    assertFalse(scenario.path("steps").get(0).has("detail"));
  }

  @Test
  void consoleLinesNameSkippedStepsAndFailuresAndTheSummaryListsFailedSteps() {
    Report.Result passed = new Report.Result("p", "claim", "c", Report.PASSED, null, List.of(OK, SKIPPED_STEP), 1);
    Report.Result failed = new Report.Result("p", "decide", "c", Report.FAILED, "decide: expected 422", List.of(BROKEN), 1);

    assertTrue(Report.line(passed).contains("(1 step skipped)"));
    assertTrue(Report.line(failed).endsWith("decide: expected 422"));
    String summary = report(passed, failed).summary();
    assertTrue(summary.contains("p / decide"));
    assertTrue(summary.contains("[POST /oversight/events/e1/decision]: expected 422, got 200"));
    assertTrue(summary.contains("passed 1, failed 1, skipped 0 (2 scenarios)"));
  }
}
