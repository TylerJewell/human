package io.akka.human.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * RFC 8785 canonical JSON of the values {@link Json} builds for a verdict: maps, lists, strings,
 * integers, booleans and null. A verdict's {@code integrity.digest} is the SHA-256 of these bytes.
 */
final class Canonical {

  private Canonical() {}

  /** {@code sha256:} and the lower-case hex SHA-256 of the canonical UTF-8 bytes. */
  static String digest(Object value) {
    try {
      var bytes = of(value).getBytes(StandardCharsets.UTF_8);
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String of(Object value) {
    var out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    switch (value) {
      case null -> out.append("null");
      case Map<?, ?> m -> {
        // String order is UTF-16 code unit order, which RFC 8785 sorts names by.
        var names = m.keySet().stream().map(String.class::cast).sorted().toList();
        out.append('{');
        for (int i = 0; i < names.size(); i++) {
          if (i > 0) out.append(',');
          string(names.get(i), out);
          out.append(':');
          write(m.get(names.get(i)), out);
        }
        out.append('}');
      }
      case List<?> l -> {
        out.append('[');
        for (int i = 0; i < l.size(); i++) {
          if (i > 0) out.append(',');
          write(l.get(i), out);
        }
        out.append(']');
      }
      case String s -> string(s, out);
      case Boolean b -> out.append(b);
      case Integer i -> out.append(i);
      case Long l -> out.append(l);
      default -> throw new IllegalArgumentException("no canonical form for " + value.getClass().getName());
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
}
