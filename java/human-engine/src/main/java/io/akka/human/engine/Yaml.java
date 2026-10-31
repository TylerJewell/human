package io.akka.human.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * Reads the library's YAML: the Verify registries, a control catalog and an overseer registry. A
 * service that declares snakeyaml at test scope removes it from the runtime classpath, which would
 * otherwise surface as a bare {@link NoClassDefFoundError} at startup.
 */
final class Yaml {

  static final String SNAKEYAML_MISSING = "snakeyaml (org.yaml:snakeyaml) is not on the runtime classpath, so the "
      + "human library cannot read its YAML: the Verify registries, the control catalog and the overseer registry. "
      + "snakeyaml must be on the runtime classpath, not at test scope: remove <scope>test</scope> from the "
      + "service's snakeyaml dependency, or remove the dependency and let jackson-dataformat-yaml bring it.";

  private Yaml() {}

  static LinkedHashMap<String, Object> read(byte[] yaml) throws IOException {
    try {
      return new ObjectMapper(new YAMLFactory()).readValue(yaml, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (NoClassDefFoundError e) {
      throw new IllegalStateException(SNAKEYAML_MISSING + " Missing class: " + e.getMessage(), e);
    }
  }
}
