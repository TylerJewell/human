package io.akka.human.engine;

import io.akka.human.api.Action;
import io.akka.human.api.spi.Overseer;
import io.akka.human.api.spi.OverseerDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** An overseer directory read from a Verify overseer registry (overseer-registry.schema.yaml). */
public final class YamlOverseers implements OverseerDirectory {

  private final List<Overseer> overseers;

  private YamlOverseers(List<Overseer> overseers) {
    this.overseers = overseers;
  }

  public static YamlOverseers fromClasspath(String resource) {
    try (InputStream in = YamlOverseers.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) throw new IllegalArgumentException("no overseer registry on the classpath at " + resource);
      return parse(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static YamlOverseers parse(byte[] yaml) {
    try {
      var root = Yaml.read(yaml);
      var people = ((List<Map<String, Object>>) root.getOrDefault("overseers", List.of())).stream().map(o -> new Overseer(
          String.valueOf(o.get("subject")),
          (String) o.get("name"),
          (List<String>) o.get("roles"),
          (List<String>) o.get("groups"),
          (String) o.get("reports_to"),
          ((List<Map<String, Object>>) o.getOrDefault("authority", List.of())).stream().map(a -> new Overseer.Authority(
              (List<String>) a.get("controls"),
              a.get("actions") == null ? null : ((List<String>) a.get("actions")).stream().map(Action::valueOf)
                  .collect(Collectors.toUnmodifiableSet()))).toList(),
          ((List<Map<String, Object>>) o.getOrDefault("attestations", List.of())).stream().map(a -> new Overseer.Attestation(
              String.valueOf(a.get("id")),
              LocalDate.parse(String.valueOf(a.get("completed_at"))),
              a.get("expires_at") == null ? null : LocalDate.parse(String.valueOf(a.get("expires_at"))))).toList(),
          Boolean.TRUE.equals(o.get("qualified")),
          (String) o.get("qualified_reason"))).toList();
      return new YamlOverseers(people);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public List<Overseer> overseers() {
    return overseers;
  }
}
