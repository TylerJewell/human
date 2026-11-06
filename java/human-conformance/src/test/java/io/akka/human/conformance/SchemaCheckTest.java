package io.akka.human.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
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
    assertEquals(List.of(), schema.validate("ServiceInfo", info(3)));
    assertFalse(schema.validate("ServiceInfo", info(2)).isEmpty());
  }

  @Test
  void assertsFormats() throws IOException {
    ObjectNode verdict = (ObjectNode) Json.MAPPER.readTree(Path.of("../../spec/examples/Verdict.upheld.json").toFile());
    assertEquals(List.of(), schema.validate("Verdict", verdict));
    verdict.put("observedAt", "yesterday");

    List<String> problems = schema.validate("Verdict", verdict);
    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.getFirst().contains("observedAt"), problems.toString());
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
