package io.akka.human.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
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

  /**
   * {@code sha256:} and the hex SHA-256 of the RFC 8785 canonical JSON of {@code node} without its
   * {@code integrity} field: a verdict's {@code integrity.digest}.
   */
  static String verdictDigest(JsonNode node) {
    ObjectNode body = node.deepCopy();
    body.remove("integrity");
    StringBuilder out = new StringBuilder();
    canonical(body, out);
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Names sorted by UTF-16 code unit, as String order is; numbers as integers or plain decimals. */
  private static void canonical(JsonNode node, StringBuilder out) {
    if (node.isObject()) {
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(null);
      out.append('{');
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) out.append(',');
        string(names.get(i), out);
        out.append(':');
        canonical(node.get(names.get(i)), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(',');
        canonical(node.get(i), out);
      }
      out.append(']');
    } else if (node.isTextual()) {
      string(node.textValue(), out);
    } else if (node.isIntegralNumber()) {
      out.append(node.bigIntegerValue());
    } else if (node.isNumber()) {
      double d = node.doubleValue();
      out.append(d == Math.rint(d) ? String.valueOf((long) d) : BigDecimal.valueOf(d).toPlainString());
    } else {
      out.append(node);
    }
  }

  private static void string(String s, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  /** One line of at most 300 characters, for step details. */
  static String abbreviate(String text) {
    if (text == null) return "";
    String flat = text.replaceAll("\\s+", " ").trim();
    return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
  }
}
