package io.akka.human.akka;

import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * Writes the registration file the companion discovers services by:
 * {@code $AKKA_HUMAN_HOME/services/<service>.json}, by default under {@code ~/.akka/human}. A service
 * calls it from its setup's {@code onStartup}. The file stands in for runtime request 15 (a local API
 * for pending decisions); the companion calls {@code /oversight/info} to tell a running service from a
 * stale file.
 */
public final class HumanRegistration {

  private HumanRegistration() {}

  /**
   * Registers the service at the HTTP port its application.conf names for local runs
   * ({@code akka.javasdk.dev-mode.http-port}, 9000 when it names none). The configuration a component
   * is given leaves out the akka namespace, so the file is read here.
   */
  public static Path register(String service) {
    var config = ConfigFactory.load();
    var port = config.hasPath("akka.javasdk.dev-mode.http-port") ? config.getInt("akka.javasdk.dev-mode.http-port") : 9000;
    return register(service, "http://localhost:" + port, home(), Instant.now());
  }

  public static Path register(String service, String url, Path home, Instant startedAt) {
    var file = home.resolve("services").resolve(service + ".json");
    var entry = new LinkedHashMap<String, Object>();
    entry.put("service", service);
    entry.put("url", url);
    entry.put("oversight", "/oversight");
    entry.put("contract", 2);
    entry.put("startedAt", startedAt.toString());
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, Wire.write(entry));
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code $AKKA_HUMAN_HOME}, or {@code ~/.akka/human}. */
  public static Path home() {
    var configured = System.getenv("AKKA_HUMAN_HOME");
    return configured == null || configured.isBlank()
        ? Path.of(System.getProperty("user.home"), ".akka", "human")
        : Path.of(configured);
  }
}
