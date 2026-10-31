package io.akka.human.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.akka.human.api.spi.HumanControl;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The library's YAML readers on a runtime classpath without snakeyaml, as a test-scoped snakeyaml leaves it. */
class YamlTest {

  private static final byte[] CATALOG = "controls: []\n".getBytes(StandardCharsets.UTF_8);

  @Test
  void theCatalogNamesSnakeyamlWhenItIsMissing() throws Exception {
    try (var loader = classpath(false)) {
      var refusal = assertThrows(InvocationTargetException.class, () -> loader.loadClass(YamlCatalog.class.getName())
          .getMethod("parse", byte[].class).invoke(null, (Object) CATALOG));
      assertNamesSnakeyaml(refusal.getCause());
    }
  }

  @Test
  void theOverseerRegistryNamesSnakeyamlWhenItIsMissing() throws Exception {
    try (var loader = classpath(false)) {
      var refusal = assertThrows(InvocationTargetException.class, () -> loader.loadClass(YamlOverseers.class.getName())
          .getMethod("parse", byte[].class).invoke(null, (Object) "overseers: []\n".getBytes(StandardCharsets.UTF_8)));
      assertNamesSnakeyaml(refusal.getCause());
    }
  }

  @Test
  void theVerifyRegistriesNameSnakeyamlWhenItIsMissing() throws Exception {
    try (var loader = classpath(false)) {
      var refusal = assertThrows(ExceptionInInitializerError.class, () -> Class.forName(Verify.class.getName(), true, loader));
      assertNamesSnakeyaml(refusal.getCause());
    }
  }

  @Test
  void theSameLoaderReadsYamlWithSnakeyaml() throws Exception {
    try (var loader = classpath(true)) {
      var catalog = loader.loadClass(YamlCatalog.class.getName()).getMethod("parse", byte[].class).invoke(null, (Object) CATALOG);
      assertEquals(YamlCatalog.class.getName(), catalog.getClass().getName());
    }
  }

  private static void assertNamesSnakeyaml(Throwable failure) {
    var refusal = assertInstanceOf(IllegalStateException.class, failure);
    assertTrue(refusal.getMessage().startsWith(Yaml.SNAKEYAML_MISSING), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("org/yaml/snakeyaml/"), refusal.getMessage());
    assertInstanceOf(NoClassDefFoundError.class, refusal.getCause());
  }

  /** The engine, the API and Jackson in a loader of their own, with or without snakeyaml beside them. */
  private static URLClassLoader classpath(boolean snakeyaml) {
    var jars = new java.util.ArrayList<URL>();
    for (var type : new Class<?>[] {YamlCatalog.class, HumanControl.class, JsonFactory.class, ObjectMapper.class,
        JsonProperty.class, YAMLFactory.class}) {
      jars.add(type.getProtectionDomain().getCodeSource().getLocation());
    }
    if (snakeyaml) jars.add(org.yaml.snakeyaml.LoaderOptions.class.getProtectionDomain().getCodeSource().getLocation());
    return new URLClassLoader(jars.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
  }
}
