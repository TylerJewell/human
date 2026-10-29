package io.akka.human.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaCheckTest {
  private final SchemaCheck schema = new SchemaCheck();

  private static ObjectNode info(int contract) {
    ObjectNode info = Json.object();
    info.put("service", "patterns");
    info.put("contract", contract);
    info.set("patterns", Json.array(List.of("action-approval")));
    info.put("devRoutes", true);
    return info;
  }

  @Test
  void validatesAgainstTheTypeTheContractNames() {
    assertEquals(List.of(), schema.validate("ServiceInfo", info(2)));
    assertFalse(schema.validate("ServiceInfo", info(1)).isEmpty());
  }

  @Test
  void assertsFormats() {
    ObjectNode verdict = Json.object();
    verdict.put("id", "v1").put("eventId", "e1").put("conclusion", "upheld").put("mechanism", "human")
        .put("at", "yesterday");
    verdict.putObject("source").put("kind", "rehearsal");
    verdict.putObject("boundary").put("kind", "work-item").put("ref", "q · e1");

    List<String> problems = schema.validate("Verdict", verdict);
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.getFirst().contains("at"), problems.toString());
  }

  @Test
  void aListShapeValidatesEachItemOfTheField() {
    ObjectNode page = Json.object();
    page.putArray("verdicts").addObject().put("id", "v1");

    List<String> problems = schema.validateShape("verdicts[]:Verdict", page);
    assertFalse(problems.isEmpty());
    assertTrue(problems.getFirst().startsWith("verdicts[0]"), problems.toString());
    assertFalse(schema.validateShape("halts[]:Halt", Json.object()).isEmpty());
  }

  @Test
  void anUnknownTypeIsTheSuitesMistake() {
    assertThrows(IllegalArgumentException.class, () -> schema.validate("Nothing", Json.object()));
  }
}
