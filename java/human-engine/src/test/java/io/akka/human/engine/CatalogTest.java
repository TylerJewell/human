package io.akka.human.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.spi.SanitizerControl;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogTest {

  private static final String HUMAN = """
      controls:
        - id: pt-00001
          name: Payments over the limit need approval
          flavor: human-system-authorization
          human_config: { pattern: action-approval, subject_kind: action }
      """;

  private static final String SANITIZER = """
        - id: custom-0000mask
          name: Account numbers are masked for reviewers
          flavor: sanitizer-pii
          sanitizer_config: { data_classes: [financial], injection_point: before-human-display }
      """;

  @Test
  void aSanitizerRowIsASanitizerControlNotAHumanOne() {
    var catalog = YamlCatalog.parse((HUMAN + SANITIZER).getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of("pt-00001"), catalog.controls().stream().map(c -> c.id()).toList());
    assertEquals(List.of(new SanitizerControl("custom-0000mask", "Account numbers are masked for reviewers",
        SanitizerControl.BEFORE_HUMAN_DISPLAY, List.of("financial"))), catalog.sanitizers());
    assertTrue(catalog.masksHumanDisplay());
    assertFalse(YamlCatalog.parse(HUMAN.getBytes(StandardCharsets.UTF_8)).masksHumanDisplay());
  }

  @Test
  void maskingLeavesPeopleIdsAndSettingsAsTheyAre() {
    var event = Json.map(
        "id", "he-1",
        "subject", Json.map("kind", "action", "summary", "Pay 42", "ref", "pay-42",
            "detail", Map.of("arguments", Map.of("account", "42"))),
        "trigger", Json.map("detector", "rule-42", "reason", "Over 42"),
        "ask", Json.map("question", "Pay 42?"),
        "holder", Json.map("subject", "owner-42"));
    var masked = Json.masked(event, s -> s.replace("42", "**"));
    assertEquals(Json.map("kind", "action", "summary", "Pay **", "ref", "pay-42",
        "detail", Map.of("arguments", Map.of("account", "**"))), masked.get("subject"));
    assertEquals(Json.map("detector", "rule-42", "reason", "Over **"), masked.get("trigger"));
    assertEquals(Json.map("question", "Pay **?"), masked.get("ask"));
    assertEquals(event.get("holder"), masked.get("holder"));
    assertEquals("he-1", masked.get("id"));
  }
}
