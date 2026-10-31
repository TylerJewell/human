package io.akka.human.engine;

import io.akka.human.api.spi.ControlCatalog;
import io.akka.human.api.spi.HumanControl;
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
 * The digest is the SHA-256 of the file.
 */
public final class YamlCatalog implements ControlCatalog {

  private final List<HumanControl> controls;
  private final String digest;

  private YamlCatalog(List<HumanControl> controls, String digest) {
    this.controls = controls;
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
      var controls = ((List<Map<String, Object>>) root.getOrDefault("controls", List.of())).stream()
          .map(c -> new HumanControl(
              String.valueOf(c.get("id")),
              String.valueOf(c.get("name")),
              (String) c.get("corpus_ref"),
              String.valueOf(c.get("flavor")),
              c.get("revision") instanceof Number n ? n.intValue() : 1,
              (Map<String, Object>) c.getOrDefault("human_config", Map.of()),
              (Map<String, Object>) c.getOrDefault("halt_config", Map.of())))
          .toList();
      return new YamlCatalog(controls, "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml)));
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
}
