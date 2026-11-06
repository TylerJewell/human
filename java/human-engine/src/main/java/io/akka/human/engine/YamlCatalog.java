package io.akka.human.engine;

import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
import io.akka.human.api.spi.SanitizerControl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A control catalog read from YAML in the shape of spec/fixtures/controls-patterns.yaml: a list of
 * controls, each with {@code id}, {@code name}, {@code corpus_ref}, {@code flavor}, an optional
 * {@code revision}, its {@code human_config} and, for halt-restart controls, its {@code halt_config}.
 * A row with a {@code sanitizer_config} is a sanitizer control, not a human one. The digest is the
 * SHA-256 of the file.
 */
public final class YamlCatalog implements ControlCatalog {

  private final List<HumanControl> controls;
  private final List<SanitizerControl> sanitizers;
  private final String digest;

  private YamlCatalog(List<HumanControl> controls, List<SanitizerControl> sanitizers, String digest) {
    this.controls = controls;
    this.sanitizers = sanitizers;
    this.digest = digest;
  }

  public static YamlCatalog fromClasspath(String resource) {
    try (InputStream in = YamlCatalog.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) throw new IllegalArgumentException("no control catalog on the classpath at " + resource);
      return parse(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static YamlCatalog parse(byte[] yaml) {
    try {
      var root = Yaml.read(yaml);
      var rows = (List<Map<String, Object>>) root.getOrDefault("controls", List.of());
      var sanitizers = rows.stream().filter(c -> c.get("sanitizer_config") instanceof Map).map(c -> {
        var config = (Map<String, Object>) c.get("sanitizer_config");
        return new SanitizerControl(String.valueOf(c.get("id")), String.valueOf(c.get("name")),
            (String) config.get("injection_point"), (List<String>) config.get("data_classes"));
      }).toList();
      var controls = rows.stream().filter(c -> !(c.get("sanitizer_config") instanceof Map))
          .map(c -> new HumanControl(
              String.valueOf(c.get("id")),
              String.valueOf(c.get("name")),
              (String) c.get("corpus_ref"),
              String.valueOf(c.get("flavor")),
              c.get("revision") instanceof Number n ? n.intValue() : 1,
              (Map<String, Object>) c.getOrDefault("human_config", Map.of()),
              (Map<String, Object>) c.getOrDefault("halt_config", Map.of())))
          .toList();
      return new YamlCatalog(controls, sanitizers, "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public List<HumanControl> controls() {
    return controls;
  }

  @Override
  public String digest() {
    return digest;
  }

  @Override
  public List<SanitizerControl> sanitizers() {
    return sanitizers;
  }
}
