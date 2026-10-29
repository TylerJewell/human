package io.akka.human.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OptionsTest {
  @Test
  void parsesTheUrlPatternsAndReport() {
    Options o = Options.parse(new String[] {"--url", "http://localhost:9164", "--pattern",
        "action-approval, halt-restart", "--report=out/report.json"});

    assertEquals("http://localhost:9164", o.url());
    assertEquals(Set.of("action-approval", "halt-restart"), o.patterns());
    assertEquals(Path.of("out/report.json"), o.report());
    assertTrue(o.includes("halt-restart"));
    assertFalse(o.includes("triage"));
  }

  @Test
  void withoutAPatternFilterEveryPatternIsIncluded() {
    assertTrue(Options.parse(new String[] {"--url=http://localhost:9164"}).includes("triage"));
  }

  @Test
  void refusesAnUnknownPatternAMissingUrlAndAMissingValue() {
    assertThrows(IllegalArgumentException.class,
        () -> Options.parse(new String[] {"--url", "http://x", "--pattern", "approval"}));
    assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--pattern", "triage"}));
    assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--url"}));
    assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--url", "localhost:9164"}));
  }
}
