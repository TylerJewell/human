package io.akka.human.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerdictDigestTest {

  /** The example's digest was computed by Verify's own tool (tools/verdicts/seal.py). */
  @Test
  void reproducesVerifysDigest() throws IOException {
    JsonNode example = Json.MAPPER.readTree(Path.of("../../spec/examples/Verdict.upheld.json").toFile());
    assertEquals(example.path("integrity").path("digest").asText(), Json.verdictDigest(example));

    ObjectNode edited = example.deepCopy();
    edited.put("conclusion", "breached");
    assertNotEquals(example.path("integrity").path("digest").asText(), Json.verdictDigest(edited));
  }
}
