package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates answers against the types of the bundled {@code oversight.schema.json} (JSON Schema 2020-12,
 * formats asserted). A shape is a {@code $defs} name such as {@code HumanEvent}, or {@code field[]:Type}
 * for an object whose {@code field} is an array of that type, such as {@code verdicts[]:Verdict}.
 */
final class SchemaCheck {
  static final String SCHEMA_ID = "https://akka.io/schemas/human/oversight/v2";
  private static final String RESOURCE = "oversight.schema.json";

  private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
      builder -> builder.schemaMappers(m -> m.mapPrefix(SCHEMA_ID, "classpath:" + RESOURCE)));
  private final SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
  private final JsonNode definitions;
  private final Map<String, JsonSchema> byType = new ConcurrentHashMap<>();

  SchemaCheck() {
    try (InputStream in = SchemaCheck.class.getResourceAsStream("/" + RESOURCE)) {
      if (in == null) throw new IllegalStateException(RESOURCE + " is not on the classpath");
      definitions = Json.MAPPER.readTree(in).path("$defs");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<String> validateShape(String shape, JsonNode node) {
    int split = shape.indexOf("[]:");
    if (split < 0) return validate(shape, node);
    String field = shape.substring(0, split);
    String type = shape.substring(split + 3);
    JsonNode items = node.get(field);
    if (items == null || !items.isArray()) return List.of("$." + field + ": expected an array of " + type);
    List<String> problems = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      for (String problem : validate(type, items.get(i))) problems.add(field + "[" + i + "] " + problem);
    }
    return problems;
  }

  List<String> validate(String type, JsonNode node) {
    if (!definitions.has(type)) throw new IllegalArgumentException("the contract names no type " + type);
    JsonSchema schema = byType.computeIfAbsent(type,
        t -> factory.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/" + t), config));
    return schema.validate(node).stream().map(ValidationMessage::getMessage).sorted().toList();
  }
}
