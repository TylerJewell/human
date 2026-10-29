package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Small helpers over Jackson trees; every reader is null-safe so scenario code can probe freely. */
final class Json {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  static ObjectNode object() {
    return MAPPER.createObjectNode();
  }

  static ArrayNode array(List<String> values) {
    ArrayNode array = MAPPER.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  /** The node's text, or null when it is missing or JSON null. */
  static String text(JsonNode node) {
    return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
  }

  static String text(JsonNode node, String fallback) {
    String text = text(node);
    return text == null ? fallback : text;
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(n -> {
        if (!n.isNull()) out.add(n.asText());
      });
    }
    return out;
  }

  static Instant instant(JsonNode node) {
    String text = text(node);
    if (text == null) return null;
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      return OffsetDateTime.parse(text).toInstant();
    }
  }

  static Optional<JsonNode> find(JsonNode array, Predicate<JsonNode> test) {
    if (array != null && array.isArray()) {
      for (JsonNode n : array) {
        if (test.test(n)) return Optional.of(n);
      }
    }
    return Optional.empty();
  }

  /** One line of at most 300 characters, for step details. */
  static String abbreviate(String text) {
    if (text == null) return "";
    String flat = text.replaceAll("\\s+", " ").trim();
    return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
  }
}
